"""Check and copy the unsigned original-package review APK, in Actions only."""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import struct
import subprocess
import zipfile

from regular_version import load_version, assert_apk_version

assert os.environ.get('GITHUB_ACTIONS') == 'true'
root = Path('android/app/build')
apk = root / 'outputs/apk/release/app-release-unsigned.apk'
tools = Path(os.environ['ANDROID_HOME']) / 'build-tools/35.0.0'
source = (root / 'generated/source/buildConfig/release/com/forcemind/chatgpt/profiles/BuildConfig.java').read_text()
metadata = load_version()
assert metadata['sourceSHA'] == os.environ['GITHUB_SHA']
assert 'ENVIRONMENT_CLIENT = true' in source
for flag in ('ENVIRONMENT_TEST', 'ENVIRONMENT_NETWORK_TEST', 'NETWORK_TEST'):
    assert flag + ' = false' in source, flag
for field in ('ENVIRONMENT_HOME_URL', 'NETWORK_HOME_URL', 'NETWORK_PROBE_URL'):
    assert field + ' = ""' in source, field
badging = subprocess.check_output([str(tools / 'aapt2'), 'dump', 'badging', str(apk)], text=True)
manifest = subprocess.check_output([str(tools / 'aapt2'), 'dump', 'xmltree', str(apk), '--file', 'AndroidManifest.xml'], text=True)
resources = subprocess.check_output([str(tools / 'aapt2'), 'dump', 'resources', str(apk)], text=True)
assert "name='com.forcemind.chatgpt.profiles'" in badging and "application-label:'ChatGPT Studio'" in badging
assert 'application-debuggable' not in badging and 'android:testOnly' not in manifest
assert_apk_version(badging, metadata, source)
assert re.search(r"(?:minSdkVersion|sdkVersion):'28'", badging)
assert 'EnvironmentAcceptanceActivity' not in manifest and 'studio_environment_network_security' not in resources
result = subprocess.run([str(tools / 'apksigner'), 'verify', str(apk)], capture_output=True)
assert result.returncode != 0, 'Human review must remain unsigned, without key fallback'
with zipfile.ZipFile(apk) as archive:
    names = archive.namelist()
    assert len(names) == len(set(names))
    assert not any(re.fullmatch(r'META-INF/[^/]+\.(SF|RSA|DSA|EC)', name, re.I) for name in names)
    extension = json.loads(archive.read('assets/studio-environment/manifest.json'))
    assert set(extension['content_scripts'][0]['matches']) == {'https://chatgpt.com/*', 'https://chat.openai.com/*'}
    forbidden = (b'EnvironmentAcceptanceActivity', b'EnvironmentUpgradeSeedTest', b'RegularEnvironmentUpgradeTest',
                 b'EnvironmentNetworkFilesProvider', b'EnvironmentNetworkControl', b'EnvironmentLocalExportProbe',
                 b'EnvironmentHandoffProbe', b'GeneratedNetworkTrust', b'environment-fixture.test', b'network-fixture.test')
    for name in names:
        assert 'studio_environment_network_ci_ca' not in name and 'studio_environment_network_security' not in name
        if name.endswith('.dex'):
            body = archive.read(name)
            assert all(value not in body for value in forbidden), 'CI data/controller leaked into human APK'
    native = [name for name in names if name.endswith('.so')]
    assert native and any(name.endswith('/libxul.so') for name in native)
    for name in native:
        assert '/arm64-v8a/' in name
        body = archive.read(name)
        assert body[:6] == b'\x7fELF\x02\x01'
        offset = struct.unpack_from('<Q', body, 32)[0]
        size, count = struct.unpack_from('<HH', body, 54)
        for index in range(count):
            at = offset + index * size
            if struct.unpack_from('<I', body, at)[0] == 1:
                file_at, address = struct.unpack_from('<QQ', body, at + 8)
                assert struct.unpack_from('<Q', body, at + 48)[0] >= 16384 and (address - file_at) % 16384 == 0
subprocess.run([str(tools / 'zipalign'), '-c', '-P', '16', '4', str(apk)], check=True, capture_output=True)
folder = root / 'regular-review'
folder.mkdir(parents=True, exist_ok=False)
name = 'ChatGPT-Studio-' + metadata['versionName'] + '-android-arm64-UNSIGNED-REVIEW.apk'
shutil.copyfile(apk, folder / name)
receipt = {'schema': 1, 'sourceSHA': metadata['sourceSHA'], 'package': 'com.forcemind.chatgpt.profiles',
           'versionName': metadata['versionName'], 'versionCode': metadata['versionCode'], 'versionMetadata': metadata,
           'file': name, 'bytes': apk.stat().st_size, 'sha256': hashlib.sha256(apk.read_bytes()).hexdigest(),
           'signing': 'UNSIGNED_REVIEW_ONLY', 'productionSignatureVerified': False,
           'productionCertificateExpectedSHA256': '8b6eef928fded91f291ad954e20976b2cc6011b55e8cbec0152695fe1f05cf19',
           'debuggable': False, 'testOnly': False, 'environmentClient': True, 'ciControlsIncluded': False,
           'ciTrustIncluded': False, 'abi': ['arm64-v8a'], 'minAndroid': 9, 'alignment16KiB': True,
           'personalDeviceInstalled': False, 'publicRelease': False}
(folder / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
(folder / 'android-version.json').write_text(json.dumps(metadata, indent=2) + '\n')
(folder / 'SHA256SUMS.txt').write_text(receipt['sha256'] + '  ' + name + '\n')
shutil.copyfile('LICENSE', folder / 'LICENSE')
print(json.dumps(receipt))
