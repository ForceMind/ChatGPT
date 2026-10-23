#!/usr/bin/env python3
"""Capture real system app details on a disposable emulator, never a phone."""
import json
import re
import subprocess
import sys
import time
from pathlib import Path
from xml.etree import ElementTree as ET

out = Path(sys.argv[1])
out.mkdir(parents=True, exist_ok=True)
devices = subprocess.check_output(["adb", "devices"], text=True).splitlines()[1:]
serials = [line.split()[0] for line in devices if line.endswith("\tdevice")]
if len(serials) != 1 or not serials[0].startswith("emulator-"):
    raise SystemExit("This capture requires exactly one disposable emulator")
serial = serials[0]


def adb(*args, binary=False):
    return subprocess.check_output(["adb", "-s", serial, *args], text=not binary, timeout=15)


gradle = (Path(__file__).resolve().parents[1] / "app/build.gradle").read_text()
version = re.search(r"versionName\s+['\"]([^'\"]+)", gradle).group(1) + "-dev"
package = "com.forcemind.chatgpt.profiles.debug"
metadata = adb("shell", "dumpsys", "package", package)
if "versionName=" + version not in metadata:
    (out / "icon-package-metadata.txt").write_text(metadata)
    raise SystemExit("Unexpected installed package version")
# Fresh CI images can still be showing Settings' FallbackHome after boot.
# These setup flags affect only the explicitly guarded disposable emulator.
adb("shell", "settings", "put", "global", "device_provisioned", "1")
adb("shell", "settings", "put", "secure", "user_setup_complete", "1")
adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
adb("shell", "wm", "dismiss-keyguard")
adb("shell", "input", "keyevent", "82")
adb("shell", "am", "start", "-W", "-a", "android.settings.APPLICATION_DETAILS_SETTINGS", "-d", "package:" + package)
deadline = time.monotonic() + 75
observations = []
ready = False
launcher_dialogs_closed = 0
while time.monotonic() < deadline:
    adb("shell", "uiautomator", "dump", "--compressed", "/sdcard/profiles-icon-settings.xml")
    xml = adb("shell", "cat", "/sdcard/profiles-icon-settings.xml")
    (out / "android-app-icon-settings.xml").write_text(xml)
    nodes = list(ET.fromstring(xml).iter("node"))
    labels = [node.get("text", "") for node in nodes]
    # The emulator's Pixel Launcher can ANR even while Settings is ready behind
    # it. Close only this exact unrelated launcher dialog, never an App ANR.
    launcher_anr = any(n.get("resource-id") == "android:id/alertTitle" and
                       n.get("text") == "Pixel Launcher isn't responding" for n in nodes)
    if launcher_anr and launcher_dialogs_closed < 1:
        close = next((n for n in nodes if n.get("resource-id") == "android:id/aerr_close"), None)
        bounds = re.findall(r"\d+", close.get("bounds", "")) if close is not None else []
        if len(bounds) == 4:
            x1, y1, x2, y2 = map(int, bounds)
            adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
            launcher_dialogs_closed += 1
            observations.append({"closedUnrelatedPixelLauncherAnr": True})
            time.sleep(2)
            continue
    ready = (any(node.get("package") == "com.android.settings" for node in nodes)
             and "ChatGPT Profiles" in labels
             and any(word in " ".join(labels).lower() for word in ["uninstall", "force stop", "卸载", "强行停止"]))
    observations.append({"appDetailsReady": ready})
    if ready:
        (out / "android-app-icon-settings.png").write_bytes(adb("exec-out", "screencap", "-p", binary=True))
        break
    time.sleep(3)
if not ready:
    (out / "icon-settings-unready.png").write_bytes(adb("exec-out", "screencap", "-p", binary=True))
(out / "icon-settings-result.json").write_text(json.dumps({
    "ready": ready, "package": package, "installedVersion": version,
    "unrelatedPixelLauncherDialogsClosed": launcher_dialogs_closed,
    "versionSource": "Android package metadata", "observations": observations,
    "scope": "Actual Settings app details; emulator only, no webpage/account interaction"
}, indent=2) + "\n")
if not ready:
    raise SystemExit("System app details never became ready; diagnostic image is not acceptance evidence")
