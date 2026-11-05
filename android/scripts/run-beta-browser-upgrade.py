"""Original delivered ARM64 APK -> fixed-signer Beta.2, only owned offline browser fixtures in CI."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import traceback
import zipfile

PACKAGE = 'com.forcemind.chatgpt.profiles.networkbeta'
ROOT = Path('android/app/build/beta-browser-evidence')
INPUTS = Path('android/app/build/beta-browser-inputs')
OLD = Path('android/app/build/beta-browser-baseline')
ROOT.mkdir(parents=True, exist_ok=True)


def command(args, timeout=120):
    result = subprocess.run(args, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=timeout)
    if result.returncode:
        raise RuntimeError(f'Command failed ({result.returncode}): {args}\n{result.stdout}')
    return result.stdout


def shell(*args):
    return command(['adb', 'shell', *args])


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def installed_digest(expected):
    paths = shell('pm', 'path', PACKAGE).strip().splitlines()
    assert len(paths) == 1 and re.fullmatch(r'package:/data/app/[^\r\n]+/base.apk', paths[0]), paths
    installed = ROOT / 'installed-base.apk'
    command(['adb', 'pull', paths[0].removeprefix('package:'), str(installed)])
    actual = digest(installed)
    installed.unlink()
    assert actual == expected, 'Installed APK differs from immutable input'
    return actual


def main():
    assert os.environ.get('GITHUB_ACTIONS') == 'true'
    assert shell('getprop', 'ro.kernel.qemu').strip() == '1'
    assert shell('getprop', 'ro.build.version.sdk').strip() == '35'
    assert shell('pm', 'list', 'packages', PACKAGE).strip() == '', 'Refuse existing Beta data'
    assert 'arm64-v8a' in shell('getprop', 'ro.product.cpu.abilist').strip().split(','), 'Original ARM APK requires a compatible emulator image'
    metadata = {key: shell('getprop', key).strip() for key in (
        'ro.build.fingerprint', 'ro.product.cpu.abilist', 'ro.dalvik.vm.native.bridge', 'ro.build.version.sdk')}
    (ROOT / 'environment.json').write_text(json.dumps(metadata, indent=2) + '\n')
    baseline = json.loads(Path('docs/releases/android-network-fixed-beta.1.json').read_text())['receipt']
    old_apk = OLD / baseline['file']
    assert digest(old_apk) == baseline['sha256'] and old_apk.stat().st_size == baseline['bytes']
    candidate = json.loads((INPUTS / 'receipt.json').read_text())
    new_apk = INPUTS / candidate['file']
    assert digest(new_apk) == candidate['sha256']
    expected_signer = json.loads(Path('docs/signing/android-network-beta.json').read_text())['certificateSHA256']
    tools = Path(os.environ['ANDROID_HOME']) / 'build-tools/35.0.0'
    for apk in (old_apk, new_apk, INPUTS / 'browser-test.apk'):
        signing = command([str(tools / 'apksigner'), 'verify', '--verbose', '--print-certs', str(apk)])
        assert 'Number of signers: 1' in signing
        assert re.findall(r'certificate SHA-256 digest: ([a-f0-9]+)', signing) == [expected_signer]
    for apk in (old_apk, new_apk):
        with zipfile.ZipFile(apk) as archive:
            assert not any(b'BetaBrowserUpgradeTest' in archive.read(name) for name in archive.namelist() if name.endswith('.dex'))
    assert candidate['versionCode'] == 3000026 and candidate['package'] == baseline['package'] == PACKAGE
    # Unmodified human APKs keep INTERNET. Block all emulator egress before installing/running either.
    command(['adb', 'root'])
    command(['adb', 'wait-for-device'])
    assert shell('id', '-u').strip() == '0'
    shell('iptables', '-I', 'OUTPUT', '1', '-j', 'REJECT')
    shell('ip6tables', '-I', 'OUTPUT', '1', '-j', 'REJECT')
    shell('iptables', '-C', 'OUTPUT', '-j', 'REJECT')
    shell('ip6tables', '-C', 'OUTPUT', '-j', 'REJECT')
    (ROOT / 'egress-denied.txt').write_text(shell('iptables-save') + '\n' + shell('ip6tables-save'))
    command(['adb', 'install', str(old_apk)])
    old_installed = installed_digest(baseline['sha256'])
    command(['adb', 'install', '-t', str(INPUTS / 'browser-test.apk')])

    def phase(name):
        shell('am', 'force-stop', PACKAGE)
        output = command(['adb', 'shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
                          'com.forcemind.chatgpt.profiles.BetaBrowserUpgradeTest#originalDeliveredBetaBrowserStateSurvivesUpgrade', '-e', 'phase', name,
                          '-e', 'signer', expected_signer, PACKAGE + '.test/androidx.test.runner.AndroidJUnitRunner'], timeout=240)
        (ROOT / (name + '.txt')).write_text(output)
        print(output, flush=True)
        assert re.findall(r'(?m)^OK \((\d+) test\)\s*$', output) == ['1']
        assert not re.search(r'FAILURES!!!|INSTRUMENTATION_FAILED|shortMsg=', output)
        match = re.search(r'(?m)^INSTRUMENTATION_RESULT: betaBrowserUpgradeProof=(\{[^\n]+\})\s*$', output)
        assert match, 'Missing structured browser upgrade proof'
        proof = json.loads(match[1])
        assert proof['complete'] and proof['phase'] == name and proof['signerSHA256'] == expected_signer
        for key, count in [('accounts', 3), ('tabs', 4), ('persistentProfiles', 3), ('httpOnlySecureCookies', 3),
                           ('localStorageProfiles', 3), ('indexedDBProfiles', 3), ('encryptedRecoveryRecords', 4)]:
            assert proof[key] == count, (key, proof)
        assert proof['interceptedRequests'] == 0 and proof['inlineDocumentCallbacks'] == 5
        assert proof['inlineSyntheticPagesOnly'] and not proof['appActivityLaunched']
        assert proof['persistedRegistryVerified']
        return proof

    seeded = phase('seed')
    before = phase('baseline-cold')
    assert seeded['versionCode'] == before['versionCode'] == 3000025
    shell('am', 'force-stop', PACKAGE)
    command(['adb', 'install', '-r', str(new_apk)])
    new_installed = installed_digest(candidate['sha256'])
    after = phase('upgraded')
    cold = phase('upgraded-cold')
    assert after['versionCode'] == cold['versionCode'] == 3000026
    for key in ('uid', 'signerSHA256', 'provider', 'workspaceSHA256', 'expectedSHA256'):
        assert len({proof[key] for proof in (seeded, before, after, cold)}) == 1, key
    result = {'complete': True, 'sourceSHA': os.environ['GITHUB_SHA'], 'baselineRun': 41959244766,
              'baselineArtifact': 13384976249, 'originalDeliveredBaseline': True,
              'baselineInstalledSHA256': old_installed, 'candidateInstalledSHA256': new_installed,
              'before': before, 'after': after, 'cold': cold, 'environment': metadata,
              'uninstallOrClearBetweenVersions': False, 'allEmulatorEgressRejected': True,
              'webViewCookiesExercised': True, 'realChatGPT': False, 'screenshots': False,
              'api28BrowserAcceptance': False, 'physicalArmDeviceAcceptance': False,
              'runtimeScope': 'API35 x86_64 Google APIs image advertising ARM64 compatibility; exact original ARM64 APK, not a rebuilt old version'}
    (ROOT / 'verified.json').write_text(json.dumps(result, indent=2) + '\n')


if __name__ == '__main__':
    try:
        main()
    except Exception:
        (ROOT / 'first-error.txt').write_text(traceback.format_exc())
        raise
    finally:
        # The emulator action terminates the guest before later workflow steps run.
        try:
            (ROOT / 'logcat.txt').write_text(command(['adb', 'logcat', '-d'], timeout=15))
        except Exception:
            (ROOT / 'logcat-error.txt').write_text(traceback.format_exc())
