"""Owned CI only: API28 compatibility boundary, immutable delivered baseline and fast visible-window exit."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import traceback
import zipfile

PACKAGE = 'com.forcemind.chatgpt.profiles.networkbeta'
ROOT = Path('android/app/build/beta-continuation-evidence')
INPUT = Path('android/app/build/beta-continuation-input')
BASELINE = Path('android/app/build/beta-continuation-baseline')
CI_BASELINE = Path('android/app/build/beta-continuation-ci-baseline')
ROOT.mkdir(parents=True, exist_ok=True)


def run(args, timeout=120, check=True):
    value = subprocess.run(args, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=timeout)
    if check and value.returncode:
        raise RuntimeError(f'{args} exited {value.returncode}:\n{value.stdout}')
    return value


def shell(*args):
    return run(['adb', 'shell', *args]).stdout


def sha(file):
    return hashlib.sha256(file.read_bytes()).hexdigest()


def installed(expected):
    paths = shell('pm', 'path', PACKAGE).strip().splitlines()
    assert len(paths) == 1 and re.fullmatch(r'package:/data/app/[^\r\n]+/base.apk', paths[0]), paths
    dest = ROOT / 'installed.apk'
    run(['adb', 'pull', paths[0][8:], str(dest)])
    actual = sha(dest)
    dest.unlink()
    assert actual == expected, 'Installed APK differs from original input'
    return actual


def main():
    assert os.environ.get('GITHUB_ACTIONS') == 'true'
    assert shell('getprop', 'ro.kernel.qemu').strip() == '1'
    api = int(shell('getprop', 'ro.build.version.sdk').strip())
    assert api in (28, 35)
    assert not shell('pm', 'list', 'packages', PACKAGE).strip(), 'Fresh emulator only'
    metadata = {name: shell('getprop', name).strip() for name in (
        'ro.build.version.sdk', 'ro.build.fingerprint', 'ro.product.cpu.abilist', 'ro.dalvik.vm.native.bridge')}
    (ROOT / 'environment.json').write_text(json.dumps(metadata, indent=2) + '\n')
    old_receipt = json.loads(Path('docs/releases/android-network-fixed-beta.2.json').read_text())['receipt']
    original = BASELINE / old_receipt['file']
    assert sha(original) == old_receipt['sha256'] and original.stat().st_size == old_receipt['bytes']
    new_receipt = json.loads((INPUT / 'receipt.json').read_text())
    assert new_receipt['versionCode'] == 3000027 and new_receipt['versionName'] == '3.0.0-alpha.24-beta.3'
    human = INPUT / new_receipt['file']
    assert sha(human) == new_receipt['sha256']
    signer = json.loads(Path('docs/signing/android-network-beta.json').read_text())['certificateSHA256']
    tools = Path(os.environ['ANDROID_HOME']) / 'build-tools/35.0.0'
    for apk in (original, human, INPUT / 'candidate-x86.apk', INPUT / 'browser-test.apk'):
        output = run([str(tools / 'apksigner'), 'verify', '--verbose', '--print-certs', str(apk)]).stdout
        assert re.findall(r'certificate SHA-256 digest: ([a-f0-9]+)', output) == [signer]
    for apk in (human, INPUT / 'candidate-x86.apk'):
        with zipfile.ZipFile(apk) as archive:
            assert not any(b'BetaBrowserUpgradeTest' in archive.read(n) for n in archive.namelist() if n.endswith('.dex'))
    # No target or renderer traffic can leave this fresh emulator; only inline resources are used.
    root_transition = run(['adb', 'root'], check=False)
    (ROOT / 'root-transition.json').write_text(json.dumps({'exitCode': root_transition.returncode, 'output': root_transition.stdout}, indent=2) + '\n')
    if root_transition.returncode and 'closed' not in root_transition.stdout:
        raise RuntimeError('Root setup failed: ' + root_transition.stdout)
    run(['adb', 'wait-for-device'])
    assert shell('id', '-u').strip() == '0'
    for binary in ('iptables', 'ip6tables'):
        shell(binary, '-I', 'OUTPUT', '1', '-j', 'REJECT')
        shell(binary, '-C', 'OUTPUT', '-j', 'REJECT')
    (ROOT / 'egress.txt').write_text(shell('iptables-save') + shell('ip6tables-save'))
    attempt = run(['adb', 'install', str(original)], check=False)
    (ROOT / 'original-arm64-install.txt').write_text(attempt.stdout)
    raw_accepted = attempt.returncode == 0
    if raw_accepted:
        assert 'arm64-v8a' in metadata['ro.product.cpu.abilist'].split(',')
        baseline, candidate, baseline_code = original, human, 3000026
        baseline_scope = 'original-delivered-arm64'
    else:
        assert api == 28 and 'INSTALL_FAILED_NO_MATCHING_ABIS' in attempt.stdout, attempt.stdout
        assert not shell('pm', 'list', 'packages', PACKAGE).strip()
        baseline = CI_BASELINE / 'candidate-x86_64.apk'
        candidate, baseline_code = INPUT / 'candidate-x86.apk', 3000025
        baseline_scope = 'ci-x86-baseline'
        # Exact previously built API28 input, never rebuilt/repacked to impersonate the human APK.
        cert = run([str(tools / 'apksigner'), 'verify', '--print-certs', str(baseline)]).stdout
        assert re.findall(r'certificate SHA-256 digest: ([a-f0-9]+)', cert) == [signer]
        run(['adb', 'install', str(baseline)])
    before_hash = installed(sha(baseline))
    run(['adb', 'install', '-t', str(INPUT / 'browser-test.apk')])

    def phase(name, quick=False):
        shell('am', 'force-stop', PACKAGE)
        method = 'visibleProfileQuickExitPersistsWithoutPeriodicTimer' if quick else 'originalDeliveredBetaBrowserStateSurvivesUpgrade'
        output = run(['adb', 'shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
                      'com.forcemind.chatgpt.profiles.BetaBrowserUpgradeTest#' + method,
                      '-e', 'phase', name, '-e', 'signer', signer, '-e', 'expectedApi', str(api),
                      '-e', 'baselineCode', str(baseline_code), '-e', 'candidateCode', '3000027',
                      '-e', 'baselineScope', baseline_scope,
                      PACKAGE + '.test/androidx.test.runner.AndroidJUnitRunner'], timeout=240).stdout
        (ROOT / (name + '.txt')).write_text(output); print(output, flush=True)
        assert re.findall(r'(?m)^OK \((\d+) test\)\s*$', output) == ['1']
        assert not re.search(r'FAILURES!!!|INSTRUMENTATION_FAILED|shortMsg=', output)
        label = 'betaQuickExitProof' if quick else 'betaBrowserUpgradeProof'
        match = re.search(r'(?m)^INSTRUMENTATION_RESULT: ' + label + r'=(\{[^\n]+\})\s*$', output)
        assert match, 'Structured phase proof missing'
        proof = json.loads(match[1]); assert proof['complete'] and proof['phase'] == name and proof['signerSHA256'] == signer
        if quick:
            assert proof['existingProfilesAndNativeDataPreserved'] and proof['noManualChromiumPrefWrites']
            assert proof['httpRequests'] == 0 and proof['inlineDocuments'] == 6 and not proof['screenshots']
            if name == 'quick-seed':
                assert 0 <= proof['visibleToHomeMillis'] < 5000 and 0 <= proof['homeToRegistryMillis'] < 2000
        else:
            for key, n in [('accounts', 3), ('tabs', 4), ('persistentProfiles', 3), ('httpOnlySecureCookies', 3),
                           ('localStorageProfiles', 3), ('indexedDBProfiles', 3), ('encryptedRecoveryRecords', 4)]:
                assert proof[key] == n
            assert proof['persistedRegistryVerified'] and proof['interceptedRequests'] == 0 and proof['inlineDocumentCallbacks'] == 5
            assert proof['inlineSyntheticPagesOnly'] and not proof['appActivityLaunched']
        return proof

    seeded, old = phase('seed'), phase('baseline-cold')
    assert seeded['versionCode'] == old['versionCode'] == baseline_code
    shell('am', 'force-stop', PACKAGE)
    run(['adb', 'install', '-r', str(candidate)])
    after_hash = installed(sha(candidate))
    upgraded, cold = phase('upgraded'), phase('upgraded-cold')
    assert upgraded['versionCode'] == cold['versionCode'] == 3000027
    for key in ('uid', 'signerSHA256', 'provider', 'workspaceSHA256', 'expectedSHA256'):
        assert len({p[key] for p in (seeded, old, upgraded, cold)}) == 1
    quick, quick_cold = phase('quick-seed', True), phase('quick-cold', True)
    assert quick['uid'] == quick_cold['uid'] == old['uid'] and quick['profile'] == quick_cold['profile']
    report = {'complete': True, 'api': api, 'sourceSHA': os.environ['GITHUB_SHA'], 'environment': metadata,
              'originalDeliveredBaselineAccepted': raw_accepted, 'baselineScope': baseline_scope,
              'baselineRun': 41986549978 if raw_accepted else 41959244766,
              'baselineArtifact': 13397180670 if raw_accepted else 13384831553,
              'baselineInstalledSHA256': before_hash, 'candidateInstalledSHA256': after_hash,
              'browserPhases': [seeded, old, upgraded, cold], 'quickExit': [quick, quick_cold],
              'uninstallOrClearBetweenVersions': False, 'allEmulatorEgressRejected': True,
              'realChatGPT': False, 'screenshots': False, 'personalData': False,
              'scope': 'API28 may use previously built same-signer CI x86 input; that is not ARM64 production upgrade acceptance'}
    (ROOT / 'verified.json').write_text(json.dumps(report, indent=2) + '\n')


if __name__ == '__main__':
    try:
        main()
    except Exception:
        (ROOT / 'first-error.txt').write_text(traceback.format_exc()); raise
    finally:
        try: (ROOT / 'logcat.txt').write_text(run(['adb', 'logcat', '-d'], timeout=15).stdout)
        except Exception: (ROOT / 'logcat-error.txt').write_text(traceback.format_exc())
