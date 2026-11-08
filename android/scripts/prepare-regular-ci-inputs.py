"""Describe temporary-key compatibility inputs, not production-signed installers."""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess

from regular_environment_upgrade import _non_signing_entries
from regular_version import load_version

assert os.environ.get('GITHUB_ACTIONS') == 'true'
root = Path('android/app/build')
inputs = root / 'regular-environment-input'
inputs.mkdir(parents=True, exist_ok=True)
tools = Path(os.environ['ANDROID_HOME']) / 'build-tools/35.0.0'
metadata = load_version()
assert metadata['sourceSHA'] == os.environ['GITHUB_SHA']


def sha(path):
    value = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            value.update(chunk)
    return value.hexdigest()


def signer(path):
    text = subprocess.check_output([str(tools / 'apksigner'), 'verify', '--verbose', '--print-certs', str(path)], text=True)
    values = re.findall(r'certificate SHA-256 digest: ([a-f0-9]+)', text)
    assert len(values) == 1
    return values[0]


original = inputs / 'alpha22-public.apk'
baseline = inputs / 'alpha22-ci-resigned.apk'
assert original.stat().st_size == 7376521 and sha(original) == '39a06004608162884c5d2a11277eee840f5311438376bd7541adc638b1e55e53'
assert signer(original) == '8b6eef928fded91f291ad954e20976b2cc6011b55e8cbec0152695fe1f05cf19'
ci_signer = signer(baseline)
assert ci_signer not in ('8b6eef928fded91f291ad954e20976b2cc6011b55e8cbec0152695fe1f05cf19',
                         'bd7b9c924795ec21d27c73f280b5fc71b83ffea950e36e05967b77f092056ac0')
assert _non_signing_entries(original) == _non_signing_entries(baseline)
candidate = inputs / 'regular-ci-x86.apk'
test = inputs / 'upgrade-test.apk'
shutil.copyfile(root / 'outputs/apk/release/app-release.apk', candidate)
shutil.copyfile(root / 'outputs/apk/androidTest/release/app-release-androidTest.apk', test)
assert signer(candidate) == signer(test) == ci_signer
badging = subprocess.check_output([str(tools / 'aapt2'), 'dump', 'badging', str(candidate)], text=True)
assert "name='com.forcemind.chatgpt.profiles'" in badging
assert "versionCode='3000035'" in badging and "versionName='" + metadata['versionName'] + "'" in badging
source = (root / 'generated/source/buildConfig/release/com/forcemind/chatgpt/profiles/BuildConfig.java').read_text()
assert 'ENVIRONMENT_CLIENT = true' in source and 'ENVIRONMENT_TEST = true' in source
assert 'NETWORK_TEST = false' in source and 'ENVIRONMENT_NETWORK_TEST = false' in source
compiled_manifest = subprocess.check_output([str(tools/'aapt2'),'dump','xmltree',str(candidate),'--file','AndroidManifest.xml'],text=True)
receipt = {'schema': 1, 'file': candidate.name, 'sha256': sha(candidate), 'bytes': candidate.stat().st_size,
           'package': 'com.forcemind.chatgpt.profiles', 'sourceSHA': metadata['sourceSHA'],
           'versionCode': metadata['versionCode'], 'versionName': metadata['versionName'],
           'versionMetadata': metadata, 'signerSHA256': ci_signer, 'ciOnly': True,
           'productionSignatureVerified': False, 'temporarySignatureNotForPersonalDevices': True,
           'workbenchFixture': '.UploadProbeActivity' in compiled_manifest}
baseline_receipt = {'schema': 1, 'file': baseline.name, 'sha256': sha(baseline), 'bytes': baseline.stat().st_size,
                    'originalFile': original.name, 'originalSHA256': sha(original),
                    'signerSHA256': ci_signer, 'signatureOnlyResigned': True,
                    'nonSigningEntriesUnchanged': True, 'oldSourceRebuilt': False,
                    'productionSignatureVerifiedOnInstalledBaseline': False}
(inputs / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
(inputs / 'baseline-receipt.json').write_text(json.dumps(baseline_receipt, indent=2) + '\n')
(inputs / 'test-receipt.json').write_text(json.dumps({
    'schema':1,'file':test.name,'bytes':test.stat().st_size,'sha256':sha(test),
    'package':'com.forcemind.chatgpt.profiles.test','signerSHA256':ci_signer,'sourceSHA':metadata['sourceSHA'],
    'ciOnly':True,'productionSignatureVerified':False},indent=2)+'\n')
with open(os.environ['GITHUB_ENV'], 'a') as env:
    env.write('STUDIO_REGULAR_CI_CERT_SHA256=' + ci_signer + '\n')
print(json.dumps({'ciOnly': True, 'versionName': metadata['versionName'], 'signerSHA256': ci_signer,
                  'originalPayloadPreserved': True, 'productionSignatureVerified': False}))
