"""Keep the arm64 updater review package separate from temporary CI fixtures."""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import zipfile

assert os.environ.get('GITHUB_ACTIONS') == 'true'
assert os.environ.get('GITHUB_REPOSITORY') == 'ForceMind/ChatGPT'
assert os.environ.get('GITHUB_REF') == 'refs/heads/dev'
root = Path('android/app/build')
version = json.loads((root / 'app-update-version.json').read_text())
assert version['versionCode'] == 3000037 and version['sourceSHA'] == os.environ['GITHUB_SHA']
stamp = version['versionName']
assert re.fullmatch(r'\d{4}\.\d{2}\.\d{2}-\d{4}', stamp)
source = root / 'outputs/apk/release/app-release-unsigned.apk'
tools = Path(os.environ['ANDROID_HOME']) / 'build-tools/35.0.0'
badging = subprocess.check_output([str(tools / 'aapt2'), 'dump', 'badging', str(source)], text=True)
assert "name='com.forcemind.chatgpt.profiles' versionCode='3000037' versionName='" + stamp + "'" in badging
assert "native-code: 'arm64-v8a'" in badging and "sdkVersion:'28'" in badging
assert 'application-debuggable' not in badging
manifest = subprocess.check_output([str(tools / 'aapt'), 'dump', 'xmltree', str(source), 'AndroidManifest.xml'], text=True)
for name in ('AppUpdateActivity', 'AppUpdateService', 'AppUpdateInstallReceiver', 'REQUEST_INSTALL_PACKAGES'):
    assert name in manifest
for name in ('android:testOnly', 'EnvironmentAcceptanceActivity', 'app_update_test_security'):
    assert name not in manifest
with zipfile.ZipFile(source) as apk:
    dex = [name for name in apk.namelist() if name.endswith('.dex')]
    assert dex and all(b'http://127.0.0.1:18777' not in apk.read(name) for name in dex)
assert subprocess.run([str(tools / 'apksigner'), 'verify', str(source)], capture_output=True).returncode != 0
subprocess.run([str(tools / 'zipalign'), '-c', '-P', '16', '4', str(source)], check=True)
out = root / 'app-update-review'
name = 'ChatGPT-Studio-' + stamp + '-android-arm64-UNSIGNED-REVIEW.apk'
assert not (out / name).exists()
shutil.copyfile(source, out / name)
with (out / name).open('rb') as stream:
    digest = hashlib.file_digest(stream, 'sha256').hexdigest()
receipt = {'schema': 1, 'versionMetadata': version, 'package': 'com.forcemind.chatgpt.profiles',
           'file': name, 'bytes': (out / name).stat().st_size, 'sha256': digest, 'signing': 'UNSIGNED_REVIEW_ONLY',
           'abi': 'arm64-v8a', 'r8': True, 'alignment16KiB': True, 'ciEndpointIncluded': False}
(out / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
print(json.dumps(receipt))
