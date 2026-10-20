#!/usr/bin/env python3
"""Sign a CI release APK locally. Never print or upload the signing password."""
import hashlib
import os
from pathlib import Path
import struct
import subprocess
import sys
import zipfile

root = Path(__file__).resolve().parents[1]
if len(sys.argv) != 2:
    raise SystemExit("Usage: python3 scripts/sign-apk.py /path/to/app-release-unsigned.apk")
source = Path(sys.argv[1]).resolve()
sdk = Path(os.environ.get("ANDROID_HOME", os.environ.get("ANDROID_SDK_ROOT", "")))
jar = Path(os.environ.get("APKSIGNER_JAR", str(sdk / "build-tools/35.0.0/lib/apksigner.jar")))
java = str(Path(os.environ["JAVA_HOME"]) / "bin/java") if "JAVA_HOME" in os.environ else "java"
if not jar.is_file() or not source.is_file():
    raise SystemExit("Missing APK or apksigner.jar; set ANDROID_HOME or APKSIGNER_JAR.")
# Gradle aligns its unsigned package. Refuse an unaligned input instead of silently publishing it.
with zipfile.ZipFile(source) as archive, source.open("rb") as raw:
    for entry in archive.infolist():
        if entry.compress_type != zipfile.ZIP_STORED or entry.is_dir():
            continue
        raw.seek(entry.header_offset + 26)
        name_size, extra_size = struct.unpack("<HH", raw.read(4))
        offset = entry.header_offset + 30 + name_size + extra_size
        alignment = 16384 if entry.filename.startswith("lib/") and entry.filename.endswith(".so") else 4
        if offset % alignment:
            raise SystemExit("APK requires zipalign before signing: " + entry.filename)
properties = dict(line.split("=", 1) for line in (root / "keystore.properties").read_text().splitlines() if "=" in line and not line.startswith("#"))
version = "3.0.0-alpha.1"
output = root / "release" / ("ChatGPT-Profiles-" + version + "-android.apk")
output.parent.mkdir(exist_ok=True)
key = (root / properties["storeFile"]).resolve()
subprocess.run([java, "-jar", str(jar), "sign", "--ks", str(key), "--ks-type", "PKCS12", "--ks-key-alias", properties["keyAlias"],
                "--ks-pass", "stdin", "--out", str(output), str(source)], input=(properties["storePassword"] + "\n").encode(), check=True)
subprocess.run([java, "-jar", str(jar), "verify", "--verbose", "--print-certs", str(output)], check=True)
print(output)
print("SHA-256:", hashlib.sha256(output.read_bytes()).hexdigest())
