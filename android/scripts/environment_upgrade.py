"""Fixed Beta.5 -> Beta.6 preservation proof on fresh, owned GitHub Actions emulators.

Importing this module does not invoke adb, create evidence, or alter a device.
All browser documents in this phase are owned inline .invalid fixtures.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import traceback

PACKAGE = 'com.forcemind.chatgpt.profiles.networkbeta'
TEST_PACKAGE = PACKAGE + '.test'
RUNNER = TEST_PACKAGE + '/androidx.test.runner.AndroidJUnitRunner'
INPUT = Path('android/app/build/environment-client-input')
OLD = Path('android/app/build/environment-client-baseline')
OLD_CI = Path('android/app/build/environment-client-ci-baseline')
EVIDENCE = Path('android/app/build/environment-client-evidence')
OLD_CODE = 3000029
NEW_CODE = 3000030
EXPECTED_FILE = '/data/user/0/' + PACKAGE + '/files/environment-upgrade-expected.json'


def _run(args, timeout=120, check=True):
    result = subprocess.run(args, text=True, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, timeout=timeout)
    if check and result.returncode:
        raise RuntimeError(f'{args} exited {result.returncode}:\n{result.stdout}')
    return result


def _shell(*args):
    return _run(['adb', 'shell', *args]).stdout


def _sha(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest()


def _json(path):
    return json.loads(path.read_text(encoding='utf-8'))


def _write_json(path, value):
    path.write_text(json.dumps(value, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')


def _guard():
    assert os.environ.get('GITHUB_ACTIONS') == 'true', 'CI emulator only'
    assert _shell('getprop', 'ro.kernel.qemu').strip() == '1', 'Refuse physical devices'
    api = int(_shell('getprop', 'ro.build.version.sdk').strip())
    assert api in (28, 35), f'Unexpected emulator API {api}'
    assert _shell('am', 'get-current-user').strip() == '0', 'Owner user required'
    return api


def _tools():
    sdk = Path(os.environ.get('ANDROID_HOME') or os.environ['ANDROID_SDK_ROOT'])
    candidates = [path for path in (sdk / 'build-tools').iterdir()
                  if re.fullmatch(r'\d+\.\d+\.\d+', path.name)
                  and (path / 'apksigner').is_file() and (path / 'aapt').is_file()]
    assert candidates, 'Android build-tools with apksigner and aapt required'
    return max(candidates, key=lambda path: tuple(map(int, path.name.split('.'))))


def _inspect_apk(apk, tools, signer, package, code=None):
    assert apk.is_file(), f'APK missing: {apk}'
    output = _run([str(tools / 'apksigner'), 'verify', '--verbose', '--print-certs', str(apk)]).stdout
    assert re.findall(r'certificate SHA-256 digest: ([a-f0-9]+)', output) == [signer], str(apk)
    badging = _run([str(tools / 'aapt'), 'dump', 'badging', str(apk)]).stdout
    (EVIDENCE / (apk.stem + '-badging.txt')).write_text(badging,encoding='utf-8')
    match = re.search(r"(?m)^package: name='([^']+)'", badging)
    assert match and match[1] == package, f'Unexpected package: {apk}'
    version = re.search(r"versionCode='(\d+)'",badging)
    name = re.search(r"versionName='([^']*)'",badging)
    if code is not None:
        assert version and int(version[1]) == code and name, f'Unexpected application versionCode: {apk}'
    else:
        # AGP9 instrumentation APKs legitimately omit application version fields.
        # Authenticate the exact runner/target instead; never relax the actual app version checks.
        assert package == TEST_PACKAGE
        xml = _run([str(tools / 'aapt'),'dump','xmltree',str(apk),'AndroidManifest.xml']).stdout
        (EVIDENCE / (apk.stem + '-manifest.txt')).write_text(xml,encoding='utf-8')
        assert xml.count('E: instrumentation ') == 1
        assert any('android:targetPackage' in line and '"'+PACKAGE+'"' in line for line in xml.splitlines())
        assert any('android:name' in line and '"androidx.test.runner.AndroidJUnitRunner"' in line for line in xml.splitlines())
    return {'file': apk.name, 'sha256': _sha(apk), 'package': match[1],
            'versionCode': int(version[1]) if version else None, 'versionName': name[1] if name else None, 'signerSHA256': signer}


def _installed(expected_hash, label):
    paths = _shell('pm', 'path', PACKAGE).strip().splitlines()
    assert len(paths) == 1 and re.fullmatch(r'package:/data/app/[^\r\n]+/base.apk', paths[0]), paths
    temporary = EVIDENCE / ('.installed-' + label + '.apk')
    try:
        _run(['adb', 'pull', paths[0][8:], str(temporary)])
        actual = _sha(temporary)
        assert actual == expected_hash, f'Installed APK mismatch in {label}: {actual}'
        return actual
    finally:
        if temporary.exists():
            temporary.unlink()


def _root_and_block():
    transition = _run(['adb', 'root'], check=False)
    _write_json(EVIDENCE / 'root-transition.json',
                {'exitCode': transition.returncode, 'stdout': transition.stdout})
    if transition.returncode:
        assert re.search(r'\bclosed\b', transition.stdout, re.IGNORECASE), transition.stdout
    _run(['adb', 'wait-for-device'])
    assert _shell('id', '-u').strip() == '0', 'adb root did not produce uid 0'
    for tool in ('iptables', 'ip6tables'):
        _shell(tool, '-I', 'OUTPUT', '1', '-j', 'REJECT')
        _shell(tool, '-C', 'OUTPUT', '-j', 'REJECT')
    (EVIDENCE / 'native-egress.txt').write_text(
        _shell('iptables-save') + _shell('ip6tables-save'), encoding='utf-8')


def _phase(label, api, scope, signer):
    old_phase = label in ('seed', 'verify-old')
    cls = 'EnvironmentUpgradeSeedTest' if old_phase else 'EnvironmentUpgradeVerifyTest'
    command = ['adb', 'shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
               'com.forcemind.chatgpt.profiles.' + cls,
               '-e', 'phase', label, '-e', 'expectedApi', str(api),
               '-e', 'baselineScope', scope, '-e', 'signer', signer, RUNNER]
    try:
        result = _run(command, timeout=300, check=False)
    except subprocess.TimeoutExpired as error:
        partial = error.stdout or ''
        if isinstance(partial, bytes):
            partial = partial.decode('utf-8', errors='replace')
        (EVIDENCE / (label + '.txt')).write_text(partial, encoding='utf-8')
        raise
    text = result.stdout
    (EVIDENCE / (label + '.txt')).write_text(text, encoding='utf-8')
    print(text, flush=True)
    assert result.returncode == 0, f'Instrumentation exited {result.returncode}: {label}'
    assert re.findall(r'(?m)^OK \((\d+) test\)\s*$', text) == ['1'], label
    assert not re.search(r'FAILED|FAILURES!!!|shortMsg=', text), label
    entries = re.findall(r'(?m)^INSTRUMENTATION_RESULT: environmentUpgradeProof=(\{[^\r\n]+\})\s*$', text)
    assert len(entries) == 1, f'Missing or duplicate structured proof: {label}'
    proof = json.loads(entries[0])
    assert proof['schema'] == 1 and proof['complete'] is True
    assert proof['phase'] == label and proof['api'] == api
    assert proof['fromVersionCode'] == OLD_CODE and proof['toVersionCode'] == NEW_CODE
    assert proof['versionCode'] == (OLD_CODE if old_phase else NEW_CODE)
    assert proof['baselineScope'] == scope and proof['signerSHA256'] == signer
    assert proof['encryptedRecoveryRecords'] == 2
    for flag in ('encryptedPayloadPreserved', 'assetPreserved', 'disabledNetworkConfigurationPreserved',
                 'preferencesPreserved', 'persistedRegistryVerified', 'httpOnlySecureCookiePreserved',
                 'localStoragePreserved', 'inlineOfflineDocumentOnly'):
        assert proof[flag] is True, (label, flag)
    for flag in ('geckoOpened', 'appActivityLaunched', 'screenshots'):
        assert proof[flag] is False, (label, flag)
    assert proof['httpRequests'] == 0
    assert proof['legacyEngineChecked'] is (not old_phase)
    if label == 'verify-after-environment':
        assert proof['afterEnvironmentUse'] is True and proof['legacyAccountPreserved'] is True
        assert proof['verifiedLegacyAccounts'] == 1 and proof['verifiedLegacyTabs'] == 2
    else:
        assert proof['accounts'] == 1 and proof['tabs'] == 2
    return proof


def _expected():
    value = json.loads(_shell('cat', EXPECTED_FILE))
    assert value['schema'] == 1 and value['fromVersionCode'] == OLD_CODE and value['toVersionCode'] == NEW_CODE
    assert len(value['recoveries']) == 2 and 'legacyAccount' in value
    return value


def _record_failure():
    original = traceback.format_exc()
    try:
        if os.environ.get('GITHUB_ACTIONS') == 'true':
            EVIDENCE.mkdir(parents=True, exist_ok=True)
            path = EVIDENCE / 'first-error.txt'
            if not path.exists():
                path.write_text(original, encoding='utf-8')
    except Exception:
        print('Unable to retain first-error evidence:\n' + traceback.format_exc(), file=sys.stderr)
        print(original, file=sys.stderr)


def _logcat(label):
    try:
        result = _run(['adb', 'logcat', '-d'], timeout=20, check=False)
        (EVIDENCE / (label + '-logcat.txt')).write_text(result.stdout, encoding='utf-8')
        if result.returncode:
            _write_json(EVIDENCE / (label + '-logcat-error.json'), {'exitCode': result.returncode})
    except Exception:
        error = traceback.format_exc()
        try:
            (EVIDENCE / (label + '-logcat-error.txt')).write_text(error, encoding='utf-8')
        except Exception:
            print('Unable to retain logcat evidence:\n' + error, file=sys.stderr)


def run_upgrade(target='human'):
    """Install immutable baseline, seed/cold-read, cover-install candidate, and verify twice."""
    guarded = False
    try:
        assert target in ('human', 'synthetic'), 'Unknown upgrade target'
        api = _guard()
        guarded = True
        EVIDENCE.mkdir(parents=True, exist_ok=True)
        assert not _shell('pm', 'list', 'packages', PACKAGE).strip(), 'Fresh emulator package must be absent'
        receipt = _json(INPUT / 'receipt.json')
        assert receipt['package'] == PACKAGE and receipt['versionCode'] == NEW_CODE
        assert Path(receipt['file']).name == receipt['file'], 'Receipt APK must be a basename'
        human = INPUT / receipt['file']
        assert re.fullmatch(r'[a-f0-9]{64}', receipt['sha256']) and _sha(human) == receipt['sha256']
        previous = _json(Path('docs/releases/android-environment-beta5-baseline.json'))
        baseline = previous['receipt']
        assert baseline['package'] == PACKAGE and baseline['versionCode'] == OLD_CODE
        signer = _json(Path('docs/signing/android-network-beta.json'))['certificateSHA256']
        assert re.fullmatch(r'[a-f0-9]{64}', signer) and baseline['signerSHA256'] == signer
        assert receipt['signerSHA256'] == signer
        if api == 35:
            assert Path(baseline['file']).name == baseline['file']
            old = OLD / baseline['file']
            old_hash = baseline['sha256']
            scope = 'original-delivered-arm64'
        else:
            old = OLD_CI / 'human-x86.apk'
            records = [proof for proof in previous['deviceProofs'] if proof['api'] == 28]
            assert len(records) == 1
            old_hash = records[0]['candidateInstalledSHA256']
            scope = 'ci-x86-baseline'
        assert _sha(old) == old_hash, 'Immutable baseline bytes differ from release evidence'
        candidate = INPUT / ('synthetic-x86.apk' if target == 'synthetic'
                             else receipt['file'] if api == 35 else 'human-x86.apk')
        test = INPUT / 'upgrade-test.apk'
        test_receipt = None
        override = INPUT / 'api35-test-receipt.json'
        if api == 35 and override.is_file():
            assert target == 'synthetic', 'The additional API35 test is scoped to the synthetic client'
            test_receipt = _json(override)
            assert test_receipt['file'] == 'api35-upgrade-test.apk'
            assert test_receipt['sourceSHA'] == os.environ['GITHUB_SHA']
            assert test_receipt['applicationSourceSHA'] == receipt['sourceSHA']
            assert test_receipt['targetPackage'] == PACKAGE and test_receipt['targetVersionCode'] == NEW_CODE
            assert test_receipt['signerSHA256'] == signer
            test = INPUT / test_receipt['file']
            assert _sha(test) == test_receipt['sha256']
        tools = _tools()
        old_apk = _inspect_apk(old, tools, signer, PACKAGE, OLD_CODE)
        candidate_apk = _inspect_apk(candidate, tools, signer, PACKAGE, NEW_CODE)
        human_apk = _inspect_apk(human, tools, signer, PACKAGE, NEW_CODE)
        test_apk = _inspect_apk(test, tools, signer, TEST_PACKAGE)
        _root_and_block()
        _run(['adb', 'install', str(old)])
        old_installed = _installed(old_hash, 'baseline')
        _run(['adb', 'install', '-t', str(test)])
        seed = _phase('seed', api, scope, signer)
        _shell('am', 'force-stop', PACKAGE)
        old_cold = _phase('verify-old', api, scope, signer)
        _shell('am', 'force-stop', PACKAGE)
        _run(['adb', 'install', '-r', str(candidate)])
        candidate_installed = _installed(candidate_apk['sha256'], 'candidate')
        upgraded = _phase('verify-new', api, scope, signer)
        _shell('am', 'force-stop', PACKAGE)
        upgraded_cold = _phase('verify-new-cold', api, scope, signer)
        expected = _expected()
        for phase in (seed, old_cold, upgraded, upgraded_cold):
            assert phase['uid'] == expected['uid']
            assert phase['storageId'] == expected['storageId']
            for key in ('workspaceSHA256', 'payloadSHA256', 'assetSHA256', 'assetIndexSHA256',
                        'networkCipherSHA256', 'preferencesSHA256'):
                assert phase[key] == expected[key], key
            assert phase['expectedSHA256'] == seed['expectedSHA256']
            assert phase['recoveryRecords'] == expected['recoveries']
        proof = {'schema': 1, 'complete': True, 'target': target, 'sourceSHA': os.environ.get('GITHUB_SHA'),
                 'api': api, 'baselineScope': scope, 'fromVersionCode': OLD_CODE, 'toVersionCode': NEW_CODE,
                 'candidateScope': 'synthetic-x86-ci' if target == 'synthetic' else
                                   'original-human-arm64' if api == 35 else 'human-x86-ci-input',
                 'originalHumanCandidateBytesInstalled': target == 'human' and api == 35,
                 'baselineInstalledSHA256': old_installed, 'candidateInstalledSHA256': candidate_installed,
                 'humanCandidateSHA256': receipt['sha256'], 'signerSHA256': signer, 'uid': expected['uid'],
                 'baselineAPK': old_apk, 'candidateAPK': candidate_apk, 'humanAPK': human_apk,
                 'testAPK': test_apk, 'seed': seed, 'oldCold': old_cold,
                 'upgraded': upgraded, 'upgradedCold': upgraded_cold,
                 'uninstallOrClearBetweenVersions': False, 'allEmulatorEgressRejected': True,
                 'ownedInlineWebViewOnly': True, 'geckoOpened': False, 'screenshots': False,
                 'realWebsitesAccessed': False}
        filename = 'verified-' + target + '.json'
        if test_receipt is not None:
            proof['api35AcceptanceTestSource'] = test_receipt
        _write_json(EVIDENCE / filename, proof)
        _write_json(EVIDENCE / 'native-context.json', {'schema': 1, 'proofFile': filename, 'target': target})
        _write_json(EVIDENCE / 'upgrade-expected.json', expected)
        return proof
    except Exception:
        _record_failure()
        raise
    finally:
        if guarded:
            _logcat('upgrade-' + target)


def verify_after_environment():
    """Read the preserved old subset after environment use; never reseed or stop an Activity/service."""
    guarded = False
    try:
        api = _guard()
        guarded = True
        EVIDENCE.mkdir(parents=True, exist_ok=True)
        assert _shell('id', '-u').strip() == '0', 'Prior rooted native setup required'
        context = _json(EVIDENCE / 'native-context.json')
        assert context['schema'] == 1 and context['target'] in ('human', 'synthetic')
        filename = 'verified-' + context['target'] + '.json'
        assert context['proofFile'] == filename
        baseline = _json(EVIDENCE / filename)
        assert baseline['complete'] is True and baseline['api'] == api
        signer = _json(Path('docs/signing/android-network-beta.json'))['certificateSHA256']
        assert baseline['signerSHA256'] == signer
        installed = _installed(baseline['candidateInstalledSHA256'], 'after-environment')
        expected = _expected()
        assert expected == _json(EVIDENCE / 'upgrade-expected.json'), 'Original fixture expectations changed'
        phase = _phase('verify-after-environment', api, baseline['baselineScope'], signer)
        assert phase['uid'] == baseline['uid'] == expected['uid']
        assert phase['storageId'] == expected['storageId']
        assert phase['expectedSHA256'] == baseline['seed']['expectedSHA256']
        assert phase['baselineWorkspaceSHA256'] == expected['workspaceSHA256']
        assert phase['recoveryRecords'] == expected['recoveries']
        for key in ('payloadSHA256', 'assetSHA256', 'assetIndexSHA256', 'networkCipherSHA256', 'preferencesSHA256'):
            assert phase[key] == expected[key], key
        proof = {'schema': 1, 'complete': True, 'api': api, 'target': context['target'],
                 'candidateInstalledSHA256': installed, 'afterEnvironmentUse': True,
                 'legacyAccountPreserved': True, 'native': phase,
                 'reseeded': False, 'uninstallOrClear': False, 'activityOrServiceStoppedByHelper': False,
                 'geckoOpened': False, 'screenshots': False}
        _write_json(EVIDENCE / 'native-after-environment.json', proof)
        return proof
    except Exception:
        _record_failure()
        raise
    finally:
        if guarded:
            _logcat('after-environment')


def verify_browser_chrome():
    """Native legacy UI on the installed synthetic target; all page requests are intercepted."""
    try:
        api = _guard()
        context = _json(EVIDENCE / 'native-context.json')
        assert context['target'] == 'synthetic'
        previous = _json(EVIDENCE / 'verified-synthetic.json')
        assert previous['complete'] and previous['api'] == api
        _installed(previous['candidateInstalledSHA256'], 'legacy-chrome')
        command = ['adb', 'shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
                   'com.forcemind.chatgpt.profiles.BrowserChromeNavigationTest',
                   '-e', 'expectedApi', str(api), '-e', 'baselineScope', previous['baselineScope'],
                   '-e', 'signer', previous['signerSHA256'], RUNNER]
        result = _run(command, timeout=300, check=False)
        (EVIDENCE / 'browser-chrome-legacy.txt').write_text(result.stdout, encoding='utf-8')
        print(result.stdout, flush=True)
        assert result.returncode == 0
        assert re.findall(r'(?m)^OK \((\d+) test\)\s*$', result.stdout) == ['1']
        assert not re.search(r'FAILED|FAILURES!!!|shortMsg=', result.stdout)
        records = re.findall(r'(?m)^INSTRUMENTATION_RESULT: browserChromeProof=(\{[^\r\n]+\})\s*$', result.stdout)
        assert len(records) == 1
        proof = json.loads(records[0])
        assert proof['schema'] == 1 and proof['complete'] and proof['api'] == api and proof['engine'] == 'webview'
        for flag in ('nativeHeaderTitleSwitch', 'switchNoReload', 'disabledEmptyHistory', 'backForward',
                     'normalAndForceRefresh', 'draftCookieStoragePreserved', 'allRequestsIntercepted'):
            assert proof[flag], flag
        assert proof['unexpectedRequests'] == 0 and not proof['screenshots'] and not proof['realServicesAccessed']
        _write_json(EVIDENCE / 'browser-chrome-legacy.json', proof)
        return proof
    except Exception:
        _record_failure()
        raise
    finally:
        try:
            for name in ('browser-chrome-native-tree.txt', 'browser-chrome-document.json', 'browser-chrome-registry-barrier.json'):
                tree = _run(['adb', 'shell', 'cat', '/data/user/0/' + PACKAGE + '/files/' + name], timeout=15, check=False)
                if tree.returncode == 0:
                    (EVIDENCE / name).write_text(tree.stdout, encoding='utf-8')
        except Exception:
            (EVIDENCE / 'browser-chrome-tree-read-error.txt').write_text(traceback.format_exc(), encoding='utf-8')
        _logcat('legacy-chrome')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--target', choices=('human', 'synthetic'), default='human')
    arguments = parser.parse_args()
    print(json.dumps(run_upgrade(arguments.target), indent=2), flush=True)
