"""Keep the arm64 updater review package separate from temporary CI fixtures."""
import argparse
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
ref = os.environ.get('GITHUB_REF', '')
assert ref == 'refs/heads/dev' or (
    ref.startswith('refs/heads/release/android-')
    and os.environ.get('STUDIO_APP_UPDATE_AUTO') == 'true'
    and os.environ.get('STUDIO_APP_UPDATE_ACCEPTANCE') != 'true'
)
root = Path('android/app/build')
parser = argparse.ArgumentParser()
parser.add_argument('--target', action='store_true')
args = parser.parse_args()
private = os.environ.get('STUDIO_APP_UPDATE_ACCEPTANCE') == 'true'
assert not args.target or private
version = json.loads((root / ('app-update-human-target.json' if args.target else 'app-update-version.json')).read_text())
auto = os.environ.get('STUDIO_APP_UPDATE_AUTO') == 'true'
code = (3000040 if args.target else 3000039) if private else 3000044 if auto else 3000037
assert version['versionCode'] == code and version['sourceSHA'] == os.environ['GITHUB_SHA']
stamp = version['versionName']
assert re.fullmatch(r'\d{4}\.\d{2}\.\d{2}-\d{4}', stamp)
source = root / 'outputs/apk/release/app-release-unsigned.apk'
tools = Path(os.environ['ANDROID_HOME']) / 'build-tools/35.0.0'
badging = subprocess.check_output([str(tools / 'aapt2'), 'dump', 'badging', str(source)], text=True)
assert f"name='com.forcemind.chatgpt.profiles' versionCode='{code}' versionName='{stamp}'" in badging
print(badging)
minimum = re.findall(r"^(?:minSdkVersion|sdkVersion):'(\d+)'$", badging, re.MULTILINE)
assert minimum == ['28'], f'Expected API 28 minimum, got {minimum}'
assert 'application-debuggable' not in badging
manifest = subprocess.check_output([str(tools / 'aapt'), 'dump', 'xmltree', str(source), 'AndroidManifest.xml'], text=True)
for name in ('AppUpdateActivity', 'AppUpdateService', 'AppUpdateInstallReceiver', 'REQUEST_INSTALL_PACKAGES'):
    assert name in manifest
assert ('AppUpdateCheckJob' in manifest) == auto
assert 'com.journeyapps.barcodescanner.CaptureActivity' in manifest
for name in ('android:testOnly', 'EnvironmentAcceptanceActivity', 'app_update_test_security'):
    assert name not in manifest
with zipfile.ZipFile(source) as apk:
    libs = [name for name in apk.namelist() if name.startswith('lib/') and name.endswith('.so')]
    assert libs and all(name.startswith('lib/arm64-v8a/') for name in libs), 'Expected ARM64-only native libraries'
    assert any(name.endswith('/libxul.so') for name in libs), 'Missing Gecko native engine'
    dex = [name for name in apk.namelist() if name.endswith('.dex')]
    assert dex and all(b'http://127.0.0.1:18777' not in apk.read(name) for name in dex)
    if not private:
        assert all(b'studio-android-acceptance.pages.dev' not in apk.read(name) for name in dex)
assert subprocess.run([str(tools / 'apksigner'), 'verify', str(source)], capture_output=True).returncode != 0
subprocess.run([str(tools / 'zipalign'), '-c', '-P', '16', '4', str(source)], check=True)
out = root / 'app-update-review'
if args.target:
    out = out / 'target'
    out.mkdir()
name = 'ChatGPT-Studio-' + stamp + '-android-arm64-UNSIGNED-REVIEW.apk'
assert not (out / name).exists()
shutil.copyfile(source, out / name)
with (out / name).open('rb') as stream:
    digest = hashlib.file_digest(stream, 'sha256').hexdigest()
receipt = {'schema': 1, 'versionMetadata': version, 'package': 'com.forcemind.chatgpt.profiles',
           'file': name, 'bytes': (out / name).stat().st_size, 'sha256': digest, 'signing': 'UNSIGNED_REVIEW_ONLY',
           'abi': 'arm64-v8a', 'r8': True, 'alignment16KiB': True, 'ciEndpointIncluded': False, 'privateSourceEnabled': private}
(out / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
print(json.dumps(receipt))
