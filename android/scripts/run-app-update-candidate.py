#!/usr/bin/env python3
"""Exercise the shared updater and confirm replacement through the emulator's package installer."""
import argparse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import hashlib
import json
import os
from pathlib import Path
import re
import socket
import subprocess
import threading
import time
import uuid
from xml.etree import ElementTree

import regular_environment_upgrade as common

PACKAGE = common.PACKAGE
VERIFY_PACKAGE = PACKAGE + ".updatecheck"
VERIFY_RUNNER = VERIFY_PACKAGE + "/androidx.test.runner.AndroidJUnitRunner"
PORT = 18777
NODE_PORT = 18778
PROXY_SOURCE = '127.0.0.2'
ORIGIN = f'http://127.0.0.1:{PORT}'
DATA = '/data/user/0/' + PACKAGE
EXPECTED = DATA + '/files/regular-environment-upgrade-expected.json'
CORRUPT = b'Owned CI fixture, intentionally not an Android APK.'
NO_DOWNLOAD = {'current', 'wrong-certificate'}
MODES = NO_DOWNLOAD | {'slow', 'bad-digest', 'not-an-apk', 'wrong-version', 'wrong-package', 'valid'}
SYSTEM_INSTALLERS = {
    'com.android.packageinstaller', 'com.google.android.packageinstaller',
    'com.android.permissioncontroller', 'com.google.android.permissioncontroller',
}


class Fixture(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, target, test, signer, source_name, target_name, source_code=3000037, target_code=3000038):
        super().__init__(('127.0.0.1', PORT), Handler)
        self.target = target
        self.test = test
        self.test_size = test.stat().st_size
        self.test_sha = common._sha(test)
        self.size = target.stat().st_size
        self.sha = common._sha(target)
        self.signer = signer
        self.source_name = source_name
        self.target_name = target_name
        self.source_code = source_code
        self.target_code = target_code
        self.mode = 'current'
        self.requests = []
        self.authorized = False
        self.valid_downloads = 0

    def metadata(self, mode):
        return {
            'schema': 1, 'packageName': PACKAGE,
            'versionName': self.source_name if mode == 'current' else self.target_name,
            'versionCode': {'current': self.source_code, 'wrong-version': self.target_code + 1}.get(mode, self.target_code),
            'size': {'not-an-apk': len(CORRUPT), 'wrong-package': self.test_size}.get(mode, self.size),
            'sha256': {'bad-digest': '0' * 64, 'not-an-apk': hashlib.sha256(CORRUPT).hexdigest(),
                       'wrong-package': self.test_sha}.get(mode, self.sha),
            'certificateSHA256': '0' * 64 if mode == 'wrong-certificate' else self.signer,
            'abi': 'x86_64', 'url': ORIGIN + '/update.apk',
        }


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def reply(self, status, size, content_type='application/octet-stream'):
        self.send_response(status)
        self.send_header('Content-Type', content_type)
        self.send_header('Content-Length', str(size))
        self.end_headers()

    def do_GET(self):
        if self.path.startswith('/control/'):
            action = self.path.removeprefix('/control/')
            if action == 'stats':
                stats = {'feed': sum(row['path']=='/android.json' for row in self.server.requests),
                         'apk': sum(row['path']=='/update.apk' for row in self.server.requests)}
                body = json.dumps(stats).encode()
                self.reply(200, len(body), 'application/json')
                self.wfile.write(body)
                return
            if action in MODES or action == 'resume':
                self.server.mode = action
            elif action == 'authorize' and not self.server.authorized:
                common._shell('appops', 'set', PACKAGE, 'REQUEST_INSTALL_PACKAGES', 'allow')
                self.server.authorized = True
            else:
                self.reply(404, 0)
                return
            self.reply(204, 0)
            return

        if self.path == '/probe':
            through_line = self.client_address[0] == PROXY_SOURCE
            self.server.requests.append({'path': self.path, 'throughSelectedLine': through_line})
            self.reply(204 if through_line else 503, 0)
            return

        mode = self.server.mode
        credentials = any(self.headers.get(name) is not None for name in ('Cookie', 'Cookie2', 'Authorization'))
        through_line = self.client_address[0] == PROXY_SOURCE
        self.server.requests.append({'path': self.path, 'mode': mode, 'credentialHeader': credentials,
                                     'throughSelectedLine': through_line})
        if credentials or not through_line:
            self.reply(503, 0)
            return
        try:
            if self.path == '/android.json':
                data = json.dumps(self.server.metadata(mode)).encode()
                self.reply(200, len(data), 'application/json')
                self.wfile.write(data)
            elif self.path == '/update.apk' and mode == 'not-an-apk':
                self.reply(200, len(CORRUPT))
                self.wfile.write(CORRUPT)
            elif self.path == '/update.apk':
                file = self.server.test if mode == 'wrong-package' else self.server.target
                self.reply(200, file.stat().st_size)
                with file.open('rb') as apk:
                    if mode == 'valid':
                        self.server.valid_downloads += 1
                        if self.server.valid_downloads == 1:
                            # Close the first response early. Retry must use the same offer.
                            self.wfile.write(apk.read(256 * 1024))
                            return
                    while block := apk.read(256 * 1024):
                        self.wfile.write(block)
                        if mode == 'slow':
                            time.sleep(0.18)
            else:
                self.reply(404, 0)
        except (BrokenPipeError, ConnectionResetError):
            # The real client cancels its own request in this mode.
            if mode != 'slow':
                raise


def block_egress():
    common._root_and_block()
    for binary in ('iptables', 'ip6tables'):
        common._shell(binary, '-I', 'OUTPUT', '1', '-o', 'lo', '-j', 'ACCEPT')
        common._shell(binary, '-C', 'OUTPUT', '-o', 'lo', '-j', 'ACCEPT')
    rules = common._shell('iptables-save') + common._shell('ip6tables-save')
    (common.EVIDENCE / 'native-egress.txt').write_text(rules)
    for port in (PORT, NODE_PORT):
        common._run(['adb', 'reverse', f'tcp:{port}', f'tcp:{port}'])


def start_node(core, identity):
    config = common.EVIDENCE / 'owned-route.json'
    common._write_json(config, {
        'log': {'loglevel': 'warning'},
        'inbounds': [{'listen': '127.0.0.1', 'port': NODE_PORT, 'protocol': 'vless',
                      'settings': {'clients': [{'id': identity}], 'decryption': 'none'},
                      'streamSettings': {'network': 'raw', 'security': 'none'}}],
        'outbounds': [{'tag': 'blocked', 'protocol': 'blackhole'},
                      {'tag': 'fixture', 'protocol': 'freedom', 'sendThrough': PROXY_SOURCE,
                       'settings': {'finalRules': [{'action': 'allow', 'ip': ['127.0.0.1/32'],
                                                    'port': str(PORT)}]}}],
        'routing': {'rules': [{'type': 'field', 'ip': ['127.0.0.1/32'],
                               'port': str(PORT), 'outboundTag': 'fixture'}]},
    })
    with (common.EVIDENCE / 'owned-route.log').open('w') as output:
        process = subprocess.Popen([str(core.resolve()), 'run', '-config', str(config)],
                                   stdout=output, stderr=subprocess.STDOUT)
    try:
        until = time.monotonic() + 10
        while time.monotonic() < until:
            assert process.poll() is None, 'Owned VLESS node exited during startup'
            try:
                with socket.create_connection(('127.0.0.1', NODE_PORT), timeout=0.5):
                    return process
            except OSError:
                time.sleep(0.1)
        raise AssertionError('Owned VLESS node did not start')
    except BaseException:
        process.kill()
        process.wait()
        raise


def signer_of(apk):
    result = common._run([str(common._tools() / 'apksigner'), 'verify', '--print-certs', str(apk)]).stdout
    signers = re.findall(r'certificate SHA-256 digest: ([a-f0-9]{64})', result)
    assert len(signers) == 1
    return signers[0]


def instrument(class_name, signer, label, timeout, extras=(), runner=common.RUNNER):
    result = common._run(['adb', 'shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
                          PACKAGE + '.' + class_name, '-e', 'signer', signer, *extras, runner],
                         timeout=timeout, check=False)
    (common.EVIDENCE / (label + '-instrument.txt')).write_text(result.stdout)
    print(result.stdout, flush=True)
    assert result.returncode == 0 and re.findall(r'(?m)^OK \((\d+) test\)\s*$', result.stdout) == ['1']
    assert not re.search(r'FAILURES!!!|INSTRUMENTATION_FAILED|shortMsg=|AssumptionViolated', result.stdout)
    return result.stdout


def installed_uid():
    output = common._shell('pm', 'list', 'packages', '-U', PACKAGE)
    return int(re.search(r'(?m)^package:' + re.escape(PACKAGE) + r' uid:(\d+)\s*$', output)[1])


def files_before(golden):
    asset_directory = f"assets/{golden['accountId']}"
    files = ['workspace.json', 'regular-environment-upgrade-expected.json', 'regular-upgrade-payload.bin',
             f'{asset_directory}/index.json', f"{asset_directory}/{golden['assetId']}.bin",
             *[row['path'] for row in golden['recoveries']]]
    result = {}
    for name in files:
        digest = common._shell('sha256sum', DATA + '/files/' + name).split()[0]
        assert re.fullmatch(r'[a-f0-9]{64}', digest), name
        result[name] = digest
    return result


def confirm_system_install():
    deadline = time.monotonic() + 45
    latest = ''
    while time.monotonic() < deadline:
        dumped = common._run(['adb', 'shell', 'uiautomator', 'dump', '/data/local/tmp/update-window.xml'],
                             timeout=15, check=False)
        if dumped.returncode == 0 and 'dumped to' in dumped.stdout:
            latest = common._shell('cat', '/data/local/tmp/update-window.xml')
            nodes = list(ElementTree.fromstring(latest).iter('node'))
            for node in nodes:
                if (node.get('package') in SYSTEM_INSTALLERS and node.get('clickable') == 'true'
                        and node.get('enabled') == 'true' and node.get('text', '').casefold() in ('install', 'update')):
                    (common.EVIDENCE / 'system-confirmation.xml').write_text(latest)
                    x0, y0, x1, y1 = map(int, re.findall(r'\d+', node.get('bounds', '')))
                    common._shell('input', 'tap', str((x0 + x1) // 2), str((y0 + y1) // 2))
                    return
        time.sleep(0.5)
    (common.EVIDENCE / 'last-window.xml').write_text(latest)
    raise AssertionError('System-owned package installation confirmation was not actionable')


def recovery_installed_digest(expected):
    paths = common._shell('pm', 'path', PACKAGE).strip().splitlines()
    assert len(paths) == 1 and re.fullmatch(r'package:/data/app/[A-Za-z0-9_~=/.-]+/base\.apk', paths[0])
    digest = common._shell('sha256sum', paths[0][8:]).split()[0]
    assert digest == expected
    return digest


def interrupt_active_download(signer, node):
    marker = DATA + '/files/app-update-exit-proof.txt'
    command = ['adb', 'shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
               PACKAGE + '.AppUpdateFlowTest#startDownloadBeforeExit', '-e', 'signer', signer,
               '-e', 'ownedUpdateNode', node, common.RUNNER]
    with (common.EVIDENCE / 'partial-instrument.txt').open('w') as output:
        probe = subprocess.Popen(command, stdout=output, stderr=subprocess.STDOUT)
        try:
            end = time.monotonic() + 85
            while time.monotonic() < end:
                assert probe.poll() is None, 'Fixture ended before the host interrupted the live download'
                result = common._run(['adb', 'shell', 'cat', marker], check=False)
                proof = re.fullmatch(r'(\d+):(\d+)', result.stdout.strip()) if result.returncode == 0 else None
                if proof:
                    pid, partial = proof.groups()
                    assert pid in common._shell('pidof', PACKAGE).split()
                    assert int(partial) > 0
                    common._shell('am', 'force-stop', PACKAGE)
                    probe.wait(timeout=15)
                    return pid, int(partial)
                time.sleep(0.25)
            raise AssertionError('No evidence of a live, partial download')
        finally:
            if probe.poll() is None:
                probe.terminate()
                probe.wait(timeout=10)


def verify_recovery(fixture, signer, node, api, source_info, target_info, old_uid):
    partial = DATA + '/files/app-update/download.part.apk'
    ready = DATA + '/files/app-update/ready.apk'
    prefs = DATA + '/shared_prefs/app-update-v1.xml'
    pids = []

    def phase(method, expected):
        log = instrument('AppUpdateFlowTest#' + method, signer, expected, 150)
        proof = re.findall(r'(?m)^INSTRUMENTATION_RESULT: appUpdateRecoveryStage=([^:\r\n]+):(\d+)\s*$', log)
        assert len(proof) == 1
        stage, pid = proof[0]
        assert stage == expected and pid not in pids
        pids.append(pid)

    def force_stop():
        common._shell('am', 'force-stop', PACKAGE)
        assert not common._run(['adb', 'shell', 'pidof', PACKAGE], check=False).stdout.strip()

    def digest(path):
        return common._shell('sha256sum', path).split()[0]

    fixture.mode = 'slow'
    pid, observed_partial = interrupt_active_download(signer, node)
    pids.append(pid)
    assert not common._run(['adb', 'shell', 'pidof', PACKAGE], check=False).stdout.strip()
    partial_bytes = int(common._shell('stat', '-c', '%s', partial).strip())
    assert 0 < observed_partial <= partial_bytes < fixture.size
    assert common._run(['adb', 'shell', 'test', '-e', ready], check=False).returncode != 0
    settings = ElementTree.fromstring(common._shell('cat', prefs))
    assert any(item.get('name') == 'downloading' and item.get('value') == 'true' for item in settings)

    fixture.mode = 'resume'
    phase('retryAfterDownloadExit', 'ready')
    force_stop()
    assert digest(ready) == target_info['sha256']
    assert common._run(['adb', 'shell', 'test', '-e', partial], check=False).returncode != 0
    common._shell('appops', 'set', PACKAGE, 'REQUEST_INSTALL_PACKAGES', 'allow')
    phase('installAfterReadyExitAndRetryAfterCancel', 'reopened-retry-confirmation')
    assert digest(ready) == target_info['sha256']
    assert old_uid == installed_uid()
    recovery_installed_digest(source_info['sha256'])
    assert 'versionCode=3000037 ' in common._shell('dumpsys', 'package', PACKAGE)
    requests = [row for row in fixture.requests if 'mode' in row]
    assert [(row['path'], row['mode']) for row in requests] == [
        ('/android.json', 'slow'), ('/update.apk', 'slow'), ('/update.apk', 'resume')]
    assert all(row['throughSelectedLine'] and not row['credentialHeader'] for row in requests)
    confirm_system_install()
    deadline = time.monotonic() + 90
    while time.monotonic() < deadline:
        if 'versionCode=3000038 ' in common._shell('dumpsys', 'package', PACKAGE):
            break
        time.sleep(0.5)
    else:
        raise AssertionError('Retried system confirmation did not install the verified APK')
    installed = recovery_installed_digest(target_info['sha256'])
    assert old_uid == installed_uid()
    common._write_json(common.EVIDENCE / 'recovery-report.json', {
        'complete': True, 'api': api, 'sourceSHA': os.environ['GITHUB_SHA'], 'processIds': pids,
        'interruptedBytes': partial_bytes, 'downloadRetriedWithoutNewManifest': True,
        'verifiedApkReusedAfterRestart': True, 'systemCancelPrompts': 1,
        'originalVersionRetainedAfterCancel': True, 'installedAfterRetrySHA256': installed, 'uidPreserved': True,
        'requests': requests, 'syntheticOnly': True, 'screenshots': False,
    })


def verify_auto(fixture, signer, node, api, source_info, target_info, old_uid, private_source):
    if private_source:
        result = instrument('AppUpdateFlowTest#configurePrivateUpdateSource', signer, 'private-source-control', 120)
        assert 'INSTRUMENTATION_RESULT: privateUpdateSourceProof=enabled,official,closed' in result
        common._shell('am', 'force-stop', PACKAGE)
    log = instrument('AppUpdateFlowTest#automaticNoonAndExplicitDownload', signer, 'auto-check', 360,
                     extras=('-e', 'ownedUpdateNode', node))
    steps, = re.findall(r'(?m)^INSTRUMENTATION_RESULT: appUpdateAutoProof=([^\r\n]+)$', log)
    assert set(steps.split(',')) == {'startup', 'engine-dedupe', 'noon-job', 'foreground-notice',
                                     'missed-slot', 'manual-check', 'explicit-download', 'fixed-tab-entry',
                                        'full-title', 'live-tab-draft', 'gecko-tab-list'}
    requests = [row for row in fixture.requests if 'mode' in row]
    assert [(row['path'], row['mode']) for row in requests] == [
        ('/android.json', 'current'), ('/android.json', 'resume'), ('/android.json', 'resume'),
        ('/android.json', 'resume'), ('/update.apk', 'resume')]
    assert all(row['throughSelectedLine'] and not row['credentialHeader'] for row in requests)
    ready = common._shell('sha256sum', DATA + '/files/app-update/ready.apk').split()[0]
    assert ready == target_info['sha256']
    assert old_uid == installed_uid()
    recovery_installed_digest(source_info['sha256'])
    common._write_json(common.EVIDENCE / 'auto-report.json', {
        'complete': True, 'api': api, 'sourceSHA': os.environ['GITHUB_SHA'],
        'source': source_info, 'steps': steps.split(','), 'requests': requests,
        'screenshots': False, 'syntheticOnly': True, 'privateSourceControl': private_source,
    })


def run(args):
    api = common._guard()
    assert api == args.api
    devices = common._run(['adb', 'devices']).stdout.strip().splitlines()[1:]
    assert len(devices) == 1 and re.fullmatch(r'emulator-\d+\s+device', devices[0])
    assert not common._shell('pm', 'list', 'packages', PACKAGE).strip()
    assert not common._shell('pm', 'list', 'packages', common.TEST_PACKAGE).strip()
    assert not common._shell('pm', 'list', 'packages', VERIFY_PACKAGE).strip()
    block_egress()
    source, target, test, verifier = (args.input / name for name in
                                      ('source.apk', 'target.apk', 'source-test.apk', 'installed-test.apk'))
    signer = signer_of(source)
    assert signer not in (common.PRODUCTION_SIGNER, common.BETA_SIGNER)
    tools = common._tools()
    source_code = 3000039 if args.device_acceptance else 3000042 if args.auto_only else 3000037
    target_code = source_code + 1
    source_info = common._inspect_apk(source, tools, signer, PACKAGE, source_code, test_only=True)
    target_info = common._inspect_apk(target, tools, signer, PACKAGE, target_code)
    assert 'android:testOnly' not in (common.EVIDENCE / 'target-manifest.txt').read_text()
    common._inspect_apk(test, tools, signer, common.TEST_PACKAGE)
    if not args.recovery_only and not args.auto_only:
        common._inspect_apk(verifier, tools, signer, VERIFY_PACKAGE, 1)
        manifest = (common.EVIDENCE / 'installed-test-manifest.txt').read_text()
        assert manifest.count('E: instrumentation ') == 1
        assert any('android:targetPackage' in line and '"' + PACKAGE + '"' in line for line in manifest.splitlines())
        assert any('android:name' in line and '"androidx.test.runner.AndroidJUnitRunner"' in line
                   for line in manifest.splitlines())
    common._run(['adb', 'install', '-t', str(source)])
    common._run(['adb', 'install', '-t', str(test)])
    if args.recovery_only or args.auto_only:
        recovery_installed_digest(source_info['sha256'])
    else:
        common._installed(source_info['sha256'], 'updater-source')
    old_uid = installed_uid()
    common._shell('appops', 'set', PACKAGE, 'REQUEST_INSTALL_PACKAGES', 'deny')

    identity = str(uuid.uuid4())
    node = f'vless://{identity}@127.0.0.1:{NODE_PORT}'
    with Fixture(target, test, signer, source_info['versionName'], target_info['versionName'], source_code, target_code) as fixture:
        worker = threading.Thread(target=fixture.serve_forever, daemon=True)
        worker.start()
        process = None
        try:
            process = start_node(args.input / 'xray', identity)
            if args.auto_only:
                verify_auto(fixture, signer, node, api, source_info, target_info, old_uid, args.device_acceptance)
                return
            if args.recovery_only:
                verify_recovery(fixture, signer, node, api, source_info, target_info, old_uid)
                return
            result = instrument('AppUpdateFlowTest#downloadAndRequestSystemConfirmation', signer, 'source', 480,
                                extras=('-e', 'ownedUpdateNode', node))
            matches = re.findall(r'(?m)^INSTRUMENTATION_RESULT: appUpdateSourceProof=([^\r\n]+)$', result)
            assert len(matches) == 1
            steps = set(matches[0].split(','))
            assert steps == {'both-entries', 'progress', 'background', 'route-lease', 'cancel', 'bad-packages',
                             'retry', 'permission', 'live-tabs', 'draft', 'system-confirmation'}
            assert fixture.authorized
            assert any(row['path'] == '/probe' for row in fixture.requests)
            assert all(row['throughSelectedLine'] for row in fixture.requests)
            update_requests = [row for row in fixture.requests if 'mode' in row]
            assert not any(row['credentialHeader'] for row in update_requests)
            for mode in MODES:
                assert sum(row['mode'] == mode and row['path'] == '/android.json' for row in update_requests) == 1
                downloads = 2 if mode == 'valid' else int(mode not in NO_DOWNLOAD)
                assert sum(row['mode'] == mode and row['path'] == '/update.apk' for row in update_requests) == downloads
            golden = json.loads(common._shell('cat', EXPECTED))
            original = files_before(golden)
            for row in golden['recoveries']:
                assert original[row['path']] == row['sha256']
            assert original[f"assets/{golden['accountId']}/{golden['assetId']}.bin"] == golden['assetSHA256']
            assert original['regular-upgrade-payload.bin'] == golden['payloadSHA256']
            assert old_uid == golden['uid'] == installed_uid()
            assert 'versionCode=3000037 ' in common._shell('dumpsys', 'package', PACKAGE)
            confirm_system_install()
            deadline = time.monotonic() + 90
            while time.monotonic() < deadline:
                if 'versionCode=3000038 ' in common._shell('dumpsys', 'package', PACKAGE):
                    break
                time.sleep(0.5)
            else:
                raise AssertionError('System confirmation did not replace the app')
            installed_sha = common._installed(target_info['sha256'], 'updater-installed-target')
            assert old_uid == installed_uid()
            assert original == files_before(golden), 'Owned workspace/recovery/asset bytes changed during replacement'
            common._write_json(common.EVIDENCE / 'system-install-proof.json', {
                'api': api, 'uid': old_uid, 'installedSHA256': installed_sha, 'versionCode': 3000038,
                'preservedFileSHA256': original,
            })
            common._shell('am', 'force-stop', PACKAGE)
            common._run(['adb', 'install', str(verifier)])
            instrument('AppUpdateInstalledTest#readLoginAfterInstall', signer, 'installed', 180, runner=VERIFY_RUNNER)
            assert original == files_before(golden), 'Owned data changed after cold browser read'
            common._write_json(common.EVIDENCE / 'report.json', {
                'complete': True, 'api': api, 'sourceSHA': os.environ['GITHUB_SHA'],
                'source': source_info, 'target': target_info, 'temporarySigner': signer, 'uid': old_uid,
                'preservedFileSHA256': original, 'coldCookieLocalStorageIndexedDb': True,
                'coldDecryptedRecoveryAndPayload': True,
                'systemConfirmationTapped': True, 'steps': sorted(steps), 'requests': fixture.requests,
            })
        finally:
            if process is not None and process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()
            fixture.shutdown()
            common._write_json(common.EVIDENCE / 'fixture-requests.json', fixture.requests)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--api', type=int, choices=(28, 35), required=True)
    parser.add_argument('--device-acceptance', action='store_true')
    modes = parser.add_mutually_exclusive_group()
    modes.add_argument('--recovery-only', action='store_true')
    modes.add_argument('--auto-only', action='store_true')
    args = parser.parse_args()
    assert os.environ.get('GITHUB_ACTIONS') == 'true'
    common.EVIDENCE = args.output
    args.output.mkdir(parents=True, exist_ok=False)
    try:
        run(args)
    except Exception:
        common._record_failure()
        raise
    finally:
        common._logcat('app-update')
