#!/usr/bin/env python3
"""Authenticate cloud-built inputs before local original-certificate signing."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import zipfile

from regular_version import load_version, assert_apk_version

assert os.environ.get('GITHUB_ACTIONS') == 'true'
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--phase', choices=('human-x86', 'test'), required=True)
args = parser.parse_args()
build = Path('android/app/build')
output = build / 'original-signature-input'
output.mkdir(parents=True, exist_ok=True)
tools = Path(os.environ['ANDROID_HOME']) / 'build-tools/35.0.0'
metadata = load_version()
assert metadata['sourceSHA'] == os.environ['GITHUB_SHA']


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def run(*argv):
    return subprocess.check_output([str(tools / argv[0]), *map(str, argv[1:])], text=True)


if args.phase == 'human-x86':
    source = build / 'outputs/apk/release/app-release-unsigned.apk'
    config = (build / 'generated/source/buildConfig/release/com/forcemind/chatgpt/profiles/BuildConfig.java').read_text()
    assert 'ENVIRONMENT_CLIENT = true' in config
    for flag in ('ENVIRONMENT_TEST', 'ENVIRONMENT_NETWORK_TEST', 'NETWORK_TEST'):
        assert flag + ' = false' in config
    badging = run('aapt2', 'dump', 'badging', source)
    manifest = run('aapt2', 'dump', 'xmltree', source, '--file', 'AndroidManifest.xml')
    assert "name='com.forcemind.chatgpt.profiles'" in badging and "application-label:'ChatGPT Studio'" in badging
    assert 'application-debuggable' not in badging and 'android:testOnly' not in manifest
    assert_apk_version(badging, metadata, config)
    assert subprocess.run([str(tools/'apksigner'),'verify',str(source)],capture_output=True).returncode != 0
    human = json.loads((build/'regular-review/receipt.json').read_text())
    arm = build / 'regular-review' / human['file']
    assert sha(arm) == human['sha256'] and human['versionMetadata'] == metadata
    with zipfile.ZipFile(source) as x86, zipfile.ZipFile(arm) as a64:
        dex = sorted(n for n in x86.namelist() if n.endswith('.dex'))
        assert dex and dex == sorted(n for n in a64.namelist() if n.endswith('.dex'))
        assert all(x86.read(n) == a64.read(n) for n in dex), 'Human Java code differs by architecture'
        native = [n for n in x86.namelist() if n.endswith('.so')]
        assert native and all(n.startswith('lib/x86_64/') for n in native)
    run('zipalign','-c','-P','16','4',source)
    name = 'human-x86-unsigned.apk'
    extra = {'abi':'x86_64','sameDexAsHumanArm64':True,'arm64UnsignedSHA256':human['sha256'],
             'debuggable':False,'testOnly':False,'ciControlsIncluded':False,'ciTrustIncluded':False}
else:
    source = build / 'outputs/apk/androidTest/release/app-release-androidTest.apk'
    badging = run('aapt2','dump','badging',source)
    manifest = run('aapt','dump','xmltree',source,'AndroidManifest.xml')
    assert "name='com.forcemind.chatgpt.profiles.test'" in badging
    assert manifest.count('E: instrumentation ') == 1
    assert any('android:targetPackage' in row and '"com.forcemind.chatgpt.profiles"' in row for row in manifest.splitlines())
    certificates = re.findall(r'certificate SHA-256 digest: ([a-f0-9]{64})',run('apksigner','verify','--print-certs',source))
    expected = os.environ['STUDIO_REGULAR_CI_CERT_SHA256']
    assert expected not in ('8b6eef928fded91f291ad954e20976b2cc6011b55e8cbec0152695fe1f05cf19',
                            'bd7b9c924795ec21d27c73f280b5fc71b83ffea950e36e05967b77f092056ac0')
    assert certificates == [expected]
    with zipfile.ZipFile(source) as archive:
        dex = b''.join(archive.read(n) for n in archive.namelist() if n.endswith('.dex'))
        assert b'OriginalSignatureUpgradeData' in dex and b'OriginalSignatureUpgradeVerifyTest' in dex
        for forbidden in (b'WorkbenchTest',b'BrowserChromeNavigationTest',b'NetworkIntegrationTest',b'RegularDualEngineSwitchTest'):
            assert forbidden not in dex, 'Unexpected privileged test code'
    name = 'original-signature-test.apk'
    extra = {'role':'guarded-original-certificate-instrumentation','personalDeviceRefusalConfigured':True,
             'temporarySigningCertificateSHA256':expected,
             'guards':['qemu','api28or35','user0','originalCertificate','exactInstalledAPK','nonDebugNonTestOnlyTarget']}
destination = output / name
assert not destination.exists()
shutil.copyfile(source, destination)
receipt = {'schema':1,'role':args.phase,'file':name,'bytes':destination.stat().st_size,'sha256':sha(destination),
           'versionMetadata':metadata,'sourceSHA':metadata['sourceSHA'],'originalKeyUsedInCloud':False,
           'originalSignatureUpgradeVerified':False,**extra}
(output/(args.phase+'-receipt.json')).write_text(json.dumps(receipt,indent=2)+'\n')
(output/'android-version.json').write_text(json.dumps(metadata,indent=2)+'\n')
print(json.dumps(receipt))
