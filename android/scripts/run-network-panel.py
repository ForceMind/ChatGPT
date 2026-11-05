"""Fresh CI emulators, fixed Beta.3 -> Beta.4, native network controls only. No browser navigation."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import traceback
import zipfile

PACKAGE = 'com.forcemind.chatgpt.profiles.networkbeta'
ROOT = Path('android/app/build/network-panel-evidence')
INPUT = Path('android/app/build/network-panel-input')
OLD = Path('android/app/build/network-panel-baseline')
OLD_CI = Path('android/app/build/network-panel-ci-baseline')
ROOT.mkdir(parents=True, exist_ok=True)


def run(args, timeout=120, check=True):
    r = subprocess.run(args, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=timeout)
    if check and r.returncode:
        raise RuntimeError(f'{args} exited {r.returncode}:\n{r.stdout}')
    return r


def shell(*args):
    return run(['adb', 'shell', *args]).stdout


def sha(file):
    return hashlib.sha256(file.read_bytes()).hexdigest()


def installed(expected):
    paths = shell('pm', 'path', PACKAGE).strip().splitlines()
    assert len(paths) == 1 and re.fullmatch(r'package:/data/app/[^\r\n]+/base.apk', paths[0])
    copy = ROOT / 'installed.apk'
    run(['adb', 'pull', paths[0][8:], str(copy)])
    actual = sha(copy); copy.unlink()
    assert actual == expected
    return actual


def main():
    assert os.environ.get('GITHUB_ACTIONS') == 'true'
    assert shell('getprop', 'ro.kernel.qemu').strip() == '1'
    api = int(shell('getprop', 'ro.build.version.sdk').strip()); assert api in (28, 35)
    assert not shell('pm', 'list', 'packages', PACKAGE).strip()
    receipt = json.loads((INPUT / 'receipt.json').read_text())
    assert receipt['versionName'] == '3.0.0-alpha.24-beta.4' and receipt['versionCode'] == 3000028
    before = json.loads(Path('docs/releases/android-network-fixed-beta.3.json').read_text())
    if api == 35:
        old = OLD / before['receipt']['file']; old_hash = before['receipt']['sha256']
        new = INPUT / receipt['file']; scope = 'original-delivered-arm64'
    else:
        old = OLD_CI / 'candidate-x86.apk'
        old_hash = next(p['candidateInstalledSHA256'] for p in before['deviceProofs'] if p['api'] == 28)
        new = INPUT / 'candidate-x86.apk'; scope = 'previously-built-x86-ci-input'
    assert sha(old) == old_hash
    assert sha(INPUT / receipt['file']) == receipt['sha256']
    tools = Path(os.environ['ANDROID_HOME']) / 'build-tools/35.0.0'
    signer = json.loads(Path('docs/signing/android-network-beta.json').read_text())['certificateSHA256']
    for apk in (old, new, INPUT / 'panel-test.apk'):
        text = run([str(tools / 'apksigner'), 'verify', '--verbose', '--print-certs', str(apk)]).stdout
        assert re.findall(r'certificate SHA-256 digest: ([a-f0-9]+)', text) == [signer]
    for apk in (new, INPUT / receipt['file']):
        with zipfile.ZipFile(apk) as z:
            for name in z.namelist():
                if name.endswith('.dex'):
                    body = z.read(name)
                    assert b'NetworkPanelTest' not in body and b'BetaPanelSeedTest' not in body
    transition = run(['adb', 'root'], check=False)
    (ROOT / 'root-transition.json').write_text(json.dumps({'exitCode': transition.returncode, 'output': transition.stdout}) + '\n')
    if transition.returncode: assert 'closed' in transition.stdout
    run(['adb', 'wait-for-device']); assert shell('id', '-u').strip() == '0'
    for tool in ('iptables', 'ip6tables'):
        shell(tool, '-I', 'OUTPUT', '1', '-j', 'REJECT'); shell(tool, '-C', 'OUTPUT', '-j', 'REJECT')
    (ROOT / 'egress.txt').write_text(shell('iptables-save') + shell('ip6tables-save'))
    run(['adb', 'install', str(old)]); old_installed = installed(old_hash)
    run(['adb', 'install', '-t', str(INPUT / 'panel-test.apk')])

    def phase(cls, label):
        text = run(['adb', 'shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
                    'com.forcemind.chatgpt.profiles.' + cls, '-e', 'signer', signer,
                    PACKAGE + '.test/androidx.test.runner.AndroidJUnitRunner'], timeout=240).stdout
        (ROOT / (label + '.txt')).write_text(text); print(text, flush=True)
        assert re.findall(r'(?m)^OK \((\d+) test\)\s*$', text) == ['1']
        assert not re.search(r'FAILURES!!!|INSTRUMENTATION_FAILED|shortMsg=', text)
        match = re.search(r'(?m)^INSTRUMENTATION_RESULT: ' + label + r'=(\{[^\n]+\})\s*$', text)
        assert match
        proof = json.loads(match[1]); assert proof['complete']
        return proof

    seed = phase('BetaPanelSeedTest', 'panelSeedProof')
    shell('am', 'force-stop', PACKAGE)
    run(['adb', 'install', '-r', str(new)]); new_installed = installed(sha(new))
    proof = phase('NetworkPanelTest', 'networkPanelProof')
    assert seed['uid'] == proof['uid'] and seed['signer'] == proof['signerSHA256'] == signer
    for flag in ('samePanelAcrossOperations', 'nativeAddEditDelete', 'liveFailureAndRetry', 'dismissAndOwnerCleanup', 'existingWorkspaceAndEncryptedDraftPreserved'):
        assert proof[flag]
    for flag in ('proxyCoreStarted', 'webViewDocumentsLoaded', 'screenshots', 'realChatGPT'): assert not proof[flag]
    (ROOT / 'verified.json').write_text(json.dumps({'complete': True, 'sourceSHA': os.environ['GITHUB_SHA'],
        'api': api, 'baselineScope': scope, 'baselineRun': 41993581770,
        'baselineInstalledSHA256': old_installed, 'candidateInstalledSHA256': new_installed,
        'seed': seed, 'panel': proof, 'uninstallOrClearBetweenVersions': False,
        'allEmulatorEgressRejected': True, 'routingBackendTestedThisBatch': False,
        'webViewCookiesTestedThisBatch': False}, indent=2) + '\n')


if __name__ == '__main__':
    try: main()
    except Exception:
        (ROOT / 'first-error.txt').write_text(traceback.format_exc()); raise
    finally:
        try: (ROOT / 'logcat.txt').write_text(run(['adb', 'logcat', '-d'], timeout=15).stdout)
        except Exception: (ROOT / 'logcat-error.txt').write_text(traceback.format_exc())
