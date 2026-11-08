"""Receipt for the separately marked CI target; never distribute it as a human APK."""
import hashlib, json, os, re, subprocess, zipfile
from pathlib import Path
from environment_ci_target import load_version, assert_apk_version, PACKAGE, REGULAR, expected_signer, native

assert os.environ.get('GITHUB_ACTIONS') == 'true'
root = Path('android/app/build')
inputs = root / ('regular-network-input' if REGULAR else 'environment-client-input')
apk = inputs / 'gecko-network-x86.apk'
variant = 'release' if REGULAR else 'networkBeta'
source = (root / ('generated/source/buildConfig/' + variant + '/com/forcemind/chatgpt/profiles/BuildConfig.java')).read_text()
for flag in ('ENVIRONMENT_CLIENT', 'ENVIRONMENT_TEST', 'ENVIRONMENT_NETWORK_TEST', 'NETWORK_TEST'):
    assert flag + ' = true' in source, flag
tools = Path(os.environ['ANDROID_HOME']) / 'build-tools/35.0.0'
badging = subprocess.check_output([str(tools/'aapt2'), 'dump', 'badging', str(apk)], text=True)
assert "name='" + PACKAGE + "'" in badging
version_metadata=load_version()
assert version_metadata['sourceSHA']==os.environ['GITHUB_SHA']
assert_apk_version(badging,version_metadata,source)
cert = subprocess.check_output([str(tools/'apksigner'), 'verify', '--print-certs', str(apk)], text=True)
signer = re.search(r'certificate SHA-256 digest: ([0-9a-fA-F]+)', cert)[1].lower()
assert signer == expected_signer()
resources = subprocess.check_output([str(tools/'aapt2'), 'dump', 'resources', str(apk)], text=True)
compiled_manifest = subprocess.check_output([str(tools/'aapt2'), 'dump', 'xmltree', str(apk), '--file', 'AndroidManifest.xml'], text=True)
assert 'raw/studio_environment_network_ci_ca' in resources, 'Owned CA resource missing from the compiled resource table'
assert 'xml/studio_environment_network_security' in resources, 'Owned trust policy missing from the compiled resource table'
assert 'android:networkSecurityConfig' in compiled_manifest, 'Owned trust policy is not attached to the application'
if REGULAR:
    # Use the same typed aapt verifier as the regular upgrade gate, rather than
    # depending on aapt2's presentation of boolean attributes.
    native.EVIDENCE = inputs / 'inspection'
    native.EVIDENCE.mkdir(parents=True, exist_ok=True)
    (native.EVIDENCE / 'aapt2-manifest.txt').write_text(compiled_manifest)
    native._inspect_apk(apk, tools, signer, PACKAGE, version_metadata['versionCode'], test_only=True)
with zipfile.ZipFile(apk) as archive:
    manifest = json.loads(archive.read('assets/studio-environment/manifest.json'))
    assert 'https://network-fixture.test/*' in manifest['content_scripts'][0]['matches']
    # AGP resource optimization can shorten ZIP paths. Verify the resource table
    # and the exact owned CA bytes instead of assuming the source filename survives.
    owned_ca = Path('android/network-poc/secure-fixture/ca.pem').read_bytes()
    assert any(archive.read(name) == owned_ca for name in archive.namelist() if name.startswith('res/')), 'Owned CA bytes missing from APK'
    dex = b''.join(archive.read(name) for name in archive.namelist() if name.endswith('.dex'))
    assert b'EnvironmentNetworkFilesProvider' in dex and b'GeneratedNetworkTrust' in dex
receipt = {'schema':1,'file':apk.name,'bytes':apk.stat().st_size,
           'sha256':hashlib.sha256(apk.read_bytes()).hexdigest(),'sourceSHA':os.environ['GITHUB_SHA'],
           'package':PACKAGE,'versionCode':version_metadata['versionCode'],
           'versionName':version_metadata['versionName'],'versionMetadata':version_metadata,'signerSHA256':signer,'ciOnly':True,'productionSignatureVerified':False}
if REGULAR:
    receipt['legacyWebviewFixture'] = '.NetworkDocumentsProvider' in compiled_manifest
    if receipt['legacyWebviewFixture']:
        test = inputs / 'network-test.apk'
        assert test.is_file(), 'The regular legacy tests must be compiled with this fixture'
        native._inspect_apk(test, tools, signer, PACKAGE + '.test')
        with zipfile.ZipFile(test) as archive:
            assert any(b'NetworkIntegrationTest' in archive.read(name) for name in archive.namelist() if name.endswith('.dex'))
        (inputs / 'network-test-receipt.json').write_text(json.dumps({
            'schema':1,'file':test.name,'bytes':test.stat().st_size,'sha256':hashlib.sha256(test.read_bytes()).hexdigest(),
            'package':PACKAGE+'.test','signerSHA256':signer,'sourceSHA':version_metadata['sourceSHA'],
            'ciOnly':True,'productionSignatureVerified':False},indent=2)+'\n')
(apk.parent/'gecko-network-receipt.json').write_text(json.dumps(receipt, indent=2)+'\n')
print(json.dumps(receipt))
