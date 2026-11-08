"""Original public alpha.22 -> regular candidate, on fresh owned CI emulators only.

The old application is never rebuilt. Only APK signing metadata may differ from
its pinned public bytes. This proves temporary-CI-signature compatibility, not
compatibility with the production signing certificate. Import has no side effects.
"""
import json
import os
from pathlib import Path
import re
import traceback
import zipfile
import environment_upgrade as common

PACKAGE = 'com.forcemind.chatgpt.profiles'
TEST_PACKAGE = PACKAGE + '.test'
RUNNER = TEST_PACKAGE + '/androidx.test.runner.AndroidJUnitRunner'
INPUT = Path('android/app/build/regular-environment-input')
EVIDENCE = Path('android/app/build/regular-environment-evidence')
OLD_CODE, NEW_CODE = 3000022, 3000035
OLD_HASH = '39a06004608162884c5d2a11277eee840f5311438376bd7541adc638b1e55e53'
OLD_BYTES = 7376521
PRODUCTION_SIGNER = '8b6eef928fded91f291ad954e20976b2cc6011b55e8cbec0152695fe1f05cf19'
BETA_SIGNER = 'bd7b9c924795ec21d27c73f280b5fc71b83ffea950e36e05967b77f092056ac0'
SCOPE = 'original-public-alpha22-signature-only-resigned'
EXPECTED_FILE = '/data/user/0/' + PACKAGE + '/files/regular-environment-upgrade-expected.json'
_run, _shell, _sha, _json, _write_json = common._run, common._shell, common._sha, common._json, common._write_json
_guard, _tools = common._guard, common._tools


def _ci_signer():
    signer = os.environ.get('STUDIO_REGULAR_CI_CERT_SHA256', '')
    assert re.fullmatch(r'[a-f0-9]{64}', signer), 'Explicit temporary CI certificate digest required'
    assert signer not in (PRODUCTION_SIGNER, BETA_SIGNER), 'Never use a human release key for synthetic upgrades'
    return signer


def _local_file(name):
    assert isinstance(name, str) and Path(name).name == name and name not in ('', '.', '..')
    path = INPUT / name
    assert path.is_file(), f'Missing regular CI input: {name}'
    return path


def _inspect_apk(path, tools, signer, package, code=None, test_only=False):
    certs = _run([str(tools / 'apksigner'), 'verify', '--verbose', '--print-certs', str(path)]).stdout
    assert re.findall(r'certificate SHA-256 digest: ([a-f0-9]+)', certs) == [signer], str(path)
    badging = _run([str(tools / 'aapt'), 'dump', 'badging', str(path)]).stdout
    manifest = _run([str(tools / 'aapt'), 'dump', 'xmltree', str(path), 'AndroidManifest.xml']).stdout
    (EVIDENCE / (path.stem + '-badging.txt')).write_text(badging, encoding='utf-8')
    (EVIDENCE / (path.stem + '-manifest.txt')).write_text(manifest, encoding='utf-8')
    match = re.search(r"(?m)^package: name='([^']+)'", badging)
    assert match and match[1] == package
    version = re.search(r"versionCode='(\d+)'", badging)
    if code is not None:
        assert version and int(version[1]) == code
    else:
        assert package == TEST_PACKAGE and manifest.count('E: instrumentation ') == 1
        assert any('android:targetPackage' in row and '"' + PACKAGE + '"' in row for row in manifest.splitlines())
        assert any('android:name' in row and '"androidx.test.runner.AndroidJUnitRunner"' in row for row in manifest.splitlines())
    if test_only:
        assert any('android:testOnly' in row and re.search(r'=\(type 0x12\)0xffffffff\b', row) for row in manifest.splitlines()), 'CI candidate must declare testOnly=true'
    return {'file': path.name, 'sha256': _sha(path), 'package': package,
            'versionCode': int(version[1]) if version else None, 'signerSHA256': signer}


def _non_signing_entries(path):
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        assert len(names) == len(set(names)), 'Duplicate APK ZIP entries'
        def signing(name):
            return re.fullmatch(r'META-INF/(?:MANIFEST\.MF|[^/]+\.(?:SF|RSA|DSA|EC))', name, re.IGNORECASE) is not None
        return {name: archive.read(name) for name in names if not signing(name)}


def _signature_only_baseline(original, resigned, tools, signer):
    assert original.stat().st_size == OLD_BYTES and _sha(original) == OLD_HASH, 'Original public APK bytes mismatch'
    original_info = _inspect_apk(original, tools, PRODUCTION_SIGNER, PACKAGE, OLD_CODE)
    resigned_info = _inspect_apk(resigned, tools, signer, PACKAGE, OLD_CODE)
    before, after = _non_signing_entries(original), _non_signing_entries(resigned)
    assert before and before == after, 'Resigning changed non-signing APK entries; rebuilding/repacking app contents is forbidden'
    assert 'AndroidManifest.xml' in before and 'classes.dex' in before
    proof = {'schema': 1, 'complete': True, 'original': original_info, 'resigned': resigned_info,
             'originalBytes': OLD_BYTES, 'nonSigningEntryCount': len(before),
             'nonSigningEntriesUnchanged': True, 'signatureOnlyResigned': True,
             'oldSourceRebuilt': False, 'originalPackageManifestResourcesUnchanged': True,
             'productionSignatureVerifiedOnOriginalOnly': True, 'temporaryCISignatureOnInstalledBaseline': True}
    _write_json(EVIDENCE / 'signature-only-baseline.json', proof)
    return resigned_info, proof


def _installed(expected_hash, label):
    paths = _shell('pm', 'path', PACKAGE).strip().splitlines()
    assert len(paths) == 1 and re.fullmatch(r'package:/data/app/[^\r\n]+/base.apk', paths[0]), paths
    temporary = EVIDENCE / ('.installed-' + label + '.apk')
    try:
        _run(['adb', 'pull', paths[0][8:], str(temporary)])
        actual = _sha(temporary)
        assert actual == expected_hash, f'Installed {label} APK differs from authenticated input'
        return actual
    finally:
        temporary.unlink(missing_ok=True)


def _root_and_block():
    result = _run(['adb', 'root'], check=False)
    _write_json(EVIDENCE / 'root-transition.json', {'exitCode': result.returncode, 'stdout': result.stdout})
    if result.returncode:
        assert re.search(r'\bclosed\b', result.stdout, re.IGNORECASE), result.stdout
    _run(['adb', 'wait-for-device'])
    assert _shell('id', '-u').strip() == '0'
    for tool in ('iptables', 'ip6tables'):
        _shell(tool, '-I', 'OUTPUT', '1', '-j', 'REJECT')
        _shell(tool, '-C', 'OUTPUT', '-j', 'REJECT')
    (EVIDENCE / 'native-egress.txt').write_text(_shell('iptables-save') + _shell('ip6tables-save'), encoding='utf-8')


def _phase(label, api, signer, evidence_name=None):
    old = label in ('seed', 'verify-old')
    cls = 'RegularEnvironmentUpgradeTest' if old else 'RegularEnvironmentUpgradeVerifyTest'
    result = _run(['adb', 'shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
                   'com.forcemind.chatgpt.profiles.' + cls, '-e', 'phase', label,
                   '-e', 'expectedApi', str(api), '-e', 'baselineScope', SCOPE, '-e', 'signer', signer, RUNNER], timeout=300, check=False)
    (EVIDENCE / ((evidence_name or label) + '.txt')).write_text(result.stdout, encoding='utf-8')
    print(result.stdout, flush=True)
    assert result.returncode == 0 and re.findall(r'(?m)^OK \((\d+) test\)\s*$', result.stdout) == ['1']
    assert not re.search(r'FAILED|FAILURES!!!|shortMsg=', result.stdout)
    records = re.findall(r'(?m)^INSTRUMENTATION_RESULT: regularEnvironmentUpgradeProof=(\{[^\r\n]+\})\s*$', result.stdout)
    assert len(records) == 1
    proof = json.loads(records[0])
    assert proof['schema'] == 1 and proof['complete'] is True and proof['phase'] == label and proof['api'] == api
    assert proof['fromVersionCode'] == OLD_CODE and proof['toVersionCode'] == NEW_CODE
    assert proof['versionCode'] == (OLD_CODE if old else NEW_CODE)
    assert proof['baselineScope'] == SCOPE and proof['signerSHA256'] == signer
    assert proof['encryptedRecoveryRecords'] == 2 and proof['verifiedLegacyAccounts'] == 1 and proof['verifiedLegacyTabs'] == 2
    for flag in ('encryptedPayloadPreserved', 'assetPreserved', 'preferencesPreserved', 'persistedRegistryVerified',
                 'httpOnlySecureCookiePreserved', 'localStoragePreserved', 'indexedDbPreserved',
                 'inlineOfflineDocumentOnly', 'sameTemporaryCISignature'):
        assert proof[flag] is True, flag
    assert proof['appActivityLaunched'] is False
    for flag in ('productionSignatureVerified', 'screenshots', 'geckoOpened'):
        assert proof[flag] is False, flag
    assert proof['fixtureHttpRequests'] == 0
    assert proof['afterEnvironmentUse'] is (label == 'verify-after-environment')
    assert proof['legacyAccountPreserved'] is (not old)
    return proof


def _expected():
    value = json.loads(_shell('cat', EXPECTED_FILE))
    assert value['schema'] == 1 and value['fromVersionCode'] == OLD_CODE and value['toVersionCode'] == NEW_CODE
    assert len(value['recoveries']) == 2 and 'legacyAccount' in value
    return value


def _record_failure():
    if os.environ.get('GITHUB_ACTIONS') == 'true':
        EVIDENCE.mkdir(parents=True, exist_ok=True)
        path = EVIDENCE / 'first-error.txt'
        if not path.exists():
            path.write_text(traceback.format_exc(), encoding='utf-8')


def _logcat(label):
    try:
        result = _run(['adb', 'logcat', '-d'], timeout=20, check=False)
        (EVIDENCE / (label + '-logcat.txt')).write_text(result.stdout, encoding='utf-8')
    except Exception:
        (EVIDENCE / (label + '-logcat-error.txt')).write_text(traceback.format_exc(), encoding='utf-8')


def _preserved(phase, expected, seed, subset=False):
    assert phase['uid'] == expected['uid'] and phase['storageId'] == expected['storageId']
    assert phase['expectedSHA256'] == seed['expectedSHA256']
    assert phase['recoveryRecords'] == expected['recoveries']
    for key in ('payloadSHA256', 'assetSHA256', 'assetIndexSHA256', 'preferencesSHA256'):
        assert phase[key] == expected[key], key
    assert phase['baselineWorkspaceSHA256'] == expected['workspaceSHA256']
    if not subset:
        assert phase['workspaceSHA256'] == expected['workspaceSHA256']


def run_upgrade(target='synthetic'):
    guarded = False
    try:
        assert target == 'synthetic', 'Regular upgrade accepts only explicit temporary-key CI fixtures'
        api = _guard()
        guarded = True
        signer = _ci_signer()
        EVIDENCE.mkdir(parents=True, exist_ok=True)
        assert not _shell('pm', 'list', 'packages', PACKAGE).strip(), 'Fresh emulator package must be absent'
        assert not _shell('pm', 'list', 'packages', TEST_PACKAGE).strip(), 'Fresh emulator test package must be absent'
        receipt = _json(INPUT / 'receipt.json')
        baseline_receipt = _json(INPUT / 'baseline-receipt.json')
        assert receipt['package'] == PACKAGE and receipt['versionCode'] == NEW_CODE and receipt['signerSHA256'] == signer
        assert receipt['sourceSHA'] == os.environ['GITHUB_SHA']
        candidate = _local_file(receipt['file'])
        assert _sha(candidate) == receipt['sha256']
        original = _local_file(baseline_receipt['originalFile'])
        baseline = _local_file(baseline_receipt['file'])
        assert baseline_receipt['originalSHA256'] == OLD_HASH and baseline_receipt['signerSHA256'] == signer
        assert baseline_receipt['signatureOnlyResigned'] is True and baseline_receipt['nonSigningEntriesUnchanged'] is True
        assert _sha(baseline) == baseline_receipt['sha256']
        tools = _tools()
        old_info, resign = _signature_only_baseline(original, baseline, tools, signer)
        candidate_info = _inspect_apk(candidate, tools, signer, PACKAGE, NEW_CODE, test_only=True)
        test = _local_file('upgrade-test.apk')
        test_info = _inspect_apk(test, tools, signer, TEST_PACKAGE)
        _root_and_block()
        _run(['adb', 'install', str(baseline)])
        old_installed = _installed(old_info['sha256'], 'baseline')
        _run(['adb', 'install', '-t', str(test)])
        seed = _phase('seed', api, signer)
        _shell('am', 'force-stop', PACKAGE)
        old_cold = _phase('verify-old', api, signer)
        _shell('am', 'force-stop', PACKAGE)
        _run(['adb', 'install', '-r', '-t', str(candidate)])
        new_installed = _installed(candidate_info['sha256'], 'candidate')
        upgraded = _phase('verify-new', api, signer)
        _shell('am', 'force-stop', PACKAGE)
        upgraded_cold = _phase('verify-new-cold', api, signer)
        expected = _expected()
        for phase in (seed, old_cold, upgraded, upgraded_cold):
            _preserved(phase, expected, seed)
        proof = {'schema': 1, 'complete': True, 'target': 'synthetic', 'sourceSHA': os.environ['GITHUB_SHA'],
                 'api': api, 'baselineScope': SCOPE, 'fromVersionCode': OLD_CODE, 'toVersionCode': NEW_CODE,
                 'signerSHA256': signer, 'uid': expected['uid'], 'freshPackageAbsent': True,
                 'baselineInstalledSHA256': old_installed, 'candidateInstalledSHA256': new_installed,
                 'baselineAPK': old_info, 'candidateAPK': candidate_info, 'testAPK': test_info,
                 'signatureOnlyBaseline': resign, 'seed': seed, 'oldCold': old_cold,
                 'upgraded': upgraded, 'upgradedCold': upgraded_cold,
                 'sameTemporaryCISignature': True, 'productionSignatureVerified': False,
                 'humanUnsignedCandidateInstalled': False, 'uninstallOrClearBetweenVersions': False,
                 'allEmulatorEgressRejected': True, 'realWebsitesAccessed': False, 'screenshots': False}
        _write_json(EVIDENCE / 'verified-synthetic.json', proof)
        _write_json(EVIDENCE / 'native-context.json', {'schema': 1, 'proofFile': 'verified-synthetic.json', 'target': 'synthetic'})
        _write_json(EVIDENCE / 'upgrade-expected.json', expected)
        return proof
    except Exception:
        _record_failure()
        raise
    finally:
        if guarded:
            _logcat('upgrade-synthetic')


def verify_after_environment(evidence_prefix=''):
    try:
        api = _guard()
        signer = _ci_signer()
        assert _shell('id', '-u').strip() == '0'
        context = _json(EVIDENCE / 'native-context.json')
        assert context == {'schema': 1, 'proofFile': 'verified-synthetic.json', 'target': 'synthetic'}
        baseline = _json(EVIDENCE / context['proofFile'])
        assert baseline['complete'] is True and baseline['api'] == api
        assert baseline['sourceSHA'] == os.environ['GITHUB_SHA'] and baseline['signerSHA256'] == signer
        installed = _installed(baseline['candidateInstalledSHA256'], evidence_prefix + 'after-environment')
        expected = _expected()
        assert expected == _json(EVIDENCE / 'upgrade-expected.json'), 'Original expectations changed'
        phase = _phase('verify-after-environment', api, signer, evidence_prefix + 'verify-after-environment')
        _preserved(phase, expected, baseline['seed'], subset=True)
        proof = {'schema': 1, 'complete': True, 'api': api, 'target': 'synthetic',
                 'candidateInstalledSHA256': installed, 'afterEnvironmentUse': True, 'legacyAccountPreserved': True,
                 'native': phase, 'sameTemporaryCISignature': True, 'productionSignatureVerified': False,
                 'reseeded': False, 'uninstallOrClear': False, 'activityOrServiceStoppedByHelper': False,
                 'screenshots': False}
        _write_json(EVIDENCE / (evidence_prefix + 'native-after-environment.json'), proof)
        return proof
    except Exception:
        _record_failure()
        raise


if __name__ == '__main__':
    run_upgrade()
