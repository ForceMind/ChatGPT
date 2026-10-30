#!/usr/bin/env python3
"""Scoped CI stability driver: owned nodes, real elapsed time, no screenshots."""
import argparse
from collections import Counter
import json
import os
from pathlib import Path
import re
import shlex
import socket
import ssl
import subprocess
import time
from urllib.parse import urlsplit, parse_qsl
import urllib.request

APP = 'com.forcemind.chatgpt.networkpreview.ci'


def command(args, **kwargs):
    return subprocess.check_output(args, text=True, timeout=30, **kwargs).strip()


def shell(args):
    return command(['adb', 'shell', ' '.join(shlex.quote(str(x)) for x in args)])


def read_device(name):
    result = subprocess.run(['adb', 'shell', f'run-as {APP} cat files/{name}'], text=True,
                            capture_output=True, timeout=15)
    return json.loads(result.stdout) if result.returncode == 0 else None


def acknowledge(phase):
    assert phase in ('background-start', 'background-end', 'outage-start', 'outage-end', 'complete')
    shell(['run-as', APP, 'sh', '-c', f"printf %s {phase} > files/stability-ack.tmp && mv files/stability-ack.tmp files/stability-ack"])


def verify_requests(expected, log):
    counts = Counter()
    for line in log.splitlines():
        try:
            record = json.loads(line)
        except json.JSONDecodeError:
            continue
        if record.get('event') != 'owned-origin-access' or not record.get('path', '').startswith('/stability/'):
            continue
        if record.get('error') or record.get('status') != 200 or record.get('responseBytesWritten') != record.get('contentLength'):
            raise AssertionError('Incomplete stability origin request: ' + str(record))
        counts[(record['path'], record['method'], record['route'])] += 1
    wanted = Counter((item['path'], item['method'], item['route']) for item in expected)
    if counts != wanted:
        raise AssertionError(f'Origin request counts differ: observed={counts}, expected={wanted}')
    assert all(count == 1 for count in counts.values()), 'Operation replay or document reload'
    assert all(key[2] in ('A', 'B') for key in counts), 'Unexpected direct request'
    return [{'path': path, 'method': method, 'route': route, 'count': count}
            for (path, method, route), count in sorted(counts.items())]


class OwnedOutage:
    """Reject only this job's two synthetic listener ports; never alter default policy."""
    def __init__(self, ports, output):
        assert len(ports) == 2 and set(ports) in ({19443, 19444}, {20443, 20444})
        self.ports, self.output = ports, output
        self.chain = f'CI_STAB_{os.getpid()}'
        self.created = False
        self.hooks = []

    def iptables(self, *args):
        return command(['sudo', '-n', 'iptables', '-w', '5', *args])

    def start(self):
        self.iptables('-N', self.chain); self.created = True
        self.iptables('-A', self.chain, '-p', 'tcp', '-m', 'multiport', '--dports',
                      ','.join(map(str, self.ports)), '-j', 'REJECT', '--reject-with', 'tcp-reset')
        for hook in ('INPUT', 'OUTPUT'):
            self.iptables('-I', hook, '1', '-j', self.chain); self.hooks.append(hook)
        for port in self.ports:
            try:
                with socket.create_connection(('127.0.0.1', port), timeout=2):
                    raise AssertionError('Owned port was not blocked')
            except OSError:
                pass
        (self.output / 'outage-rules.txt').write_text(self.iptables('-S', self.chain) + '\n')

    def stop(self):
        if not self.created:
            return
        counters = self.iptables('-L', self.chain, '-v', '-n', '-x')
        (self.output / 'outage-counters.txt').write_text(counters + '\n')
        for hook in list(self.hooks):
            self.iptables('-D', hook, '-j', self.chain); self.hooks.remove(hook)
        self.iptables('-F', self.chain); self.iptables('-X', self.chain); self.created = False
        for port in self.ports:
            with socket.create_connection(('127.0.0.1', port), timeout=3):
                pass


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--fixture', required=True)
    parser.add_argument('--transport', choices=['tls', 'reality'], required=True)
    parser.add_argument('--output', required=True)
    args = parser.parse_args()
    assert os.environ.get('GITHUB_ACTIONS') == 'true'
    assert shell(['getprop', 'ro.kernel.qemu']) == '1'
    assert shell(['getprop', 'ro.build.version.sdk']) in ('28', '35')
    fixture_path = Path(args.fixture)
    fixture = json.loads(fixture_path.read_text())
    assert json.loads(fixture_path.with_name('ready.json').read_text())['ready']
    assert fixture['externalRequests'] is False
    nodes = fixture['transports'][args.transport]
    ports = []
    for key in ('nodeA', 'nodeB'):
        uri = urlsplit(nodes[key]); ports.append(uri.port)
        assert uri.hostname == '10.0.2.2' and uri.username == '00000000-0000-4000-8000-000000000001'
        assert dict(parse_qsl(uri.query))['security'] == args.transport
    output = Path(args.output); output.mkdir(parents=True, exist_ok=True)
    outage = OwnedOutage(ports, output)
    origin = 'https://network-fixture.test:18480'
    test_args = {'class': 'com.forcemind.chatgpt.networkpreview.NetworkStabilityInstrumentedTest',
                 'fixtureUrl': origin, 'expectedSecurity': args.transport,
                 'nodeA': nodes['nodeA'], 'nodeB': nodes['nodeB'], 'backgroundSeconds': '1800'}
    invocation = ['am', 'instrument', '-w', '-r']
    for key, value in test_args.items():
        invocation += ['-e', key, value]
    invocation += [APP + '.test/androidx.test.runner.AndroidJUnitRunner']
    log_file = output / 'instrumentation.txt'
    seen = set(); background_start = None; background_duration = None
    background_samples = []; pid = None; next_sample = 0; latest = None
    context = ssl.create_default_context(cafile=str(fixture_path.with_name('ca.pem')))
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPSHandler(context=context))
    try:
        with log_file.open('w') as stream:
            process = subprocess.Popen(['adb', 'shell', ' '.join(shlex.quote(x) for x in invocation)],
                                       stdout=stream, stderr=subprocess.STDOUT, text=True)
            deadline = time.monotonic() + 2400
            try:
                while process.poll() is None:
                    assert time.monotonic() < deadline, 'Stability instrumentation timed out'
                    latest = read_device('stability-phase.json')
                    if latest:
                        phase = latest['phase']
                        if phase not in seen:
                            print('STABILITY_PHASE ' + phase, flush=True)
                            if phase == 'background-start':
                                background_start = time.monotonic(); pid = latest['pid']
                            elif phase == 'background-end':
                                assert background_start is not None
                                background_duration = time.monotonic() - background_start
                                assert background_duration >= 1800, 'Host observed less than 30 minutes'
                                assert latest['pid'] == pid
                            elif phase == 'outage-start':
                                outage.start()
                                # Owned HTTPS origin stays healthy while node ports are cut.
                                with opener.open('https://127.0.0.1:18480/echo', timeout=5) as response:
                                    assert response.status == 200
                                (output / 'outage-origin-control.json').write_text(json.dumps({'healthy': True, 'onlyNodePortsBlocked': ports}))
                            elif phase == 'outage-end':
                                outage.stop()
                            elif phase == 'complete':
                                assert background_duration is not None
                            else:
                                raise AssertionError('Unknown phase ' + phase)
                            acknowledge(phase); seen.add(phase)
                        if phase == 'background-start' and time.monotonic() >= next_sample:
                            alive = shell(['pidof', APP])
                            assert str(pid) in alive.split(), 'App process changed in background'
                            dump_args = ['dumpsys', 'activity', 'activities']
                            if shell(['id', '-u']) == '0':
                                dump_args = ['su', '2000', *dump_args]
                            dump = shell(dump_args)
                            resumed = [line.strip() for line in dump.splitlines() if re.search(r'(?:mResumedActivity|topResumedActivity)', line)]
                            assert resumed, 'Cannot verify background activity'
                            assert not any(APP in line for line in resumed), 'CI app unexpectedly resumed'
                            background_samples.append({'hostMonotonic': time.monotonic(), 'pid': pid, 'resumed': resumed})
                            (output / 'background-samples.json').write_text(json.dumps(background_samples, indent=2))
                            print('BACKGROUND_SECONDS ' + str(round(time.monotonic() - background_start)), flush=True)
                            next_sample = time.monotonic() + 60
                    time.sleep(5)
                assert process.returncode == 0
            finally:
                if process.poll() is None:
                    process.terminate()
                    try:
                        process.wait(timeout=10)
                    except subprocess.TimeoutExpired:
                        process.kill(); process.wait(timeout=5)
        result = read_device('stability-result.json')
        assert result and result['complete'] and result['security'] == args.transport
        assert result['tabCount'] == 3 and result['backgroundMillis'] >= 1800000
        assert seen == {'background-start', 'background-end', 'outage-start', 'outage-end', 'complete'}
        assert len(background_samples) >= 29
        text = log_file.read_text()
        assert re.search(r'OK \(1 test\)', text) and not re.search(r'FAILURES!!!|INSTRUMENTATION_FAILED|shortMsg=', text), text
        # Server access lines are emitted after complete response flush. Permit
        # the final line to drain; there is no new request while checking counts.
        time.sleep(1)
        fixture_log = Path(os.environ['RUNNER_TEMP']) / 'network-fixture.log'
        counts = verify_requests(result['expectedRequests'], fixture_log.read_text())
        result.update({'transport': args.transport, 'sourceSha': os.environ['GITHUB_SHA'],
                       'api': int(shell(['getprop', 'ro.build.version.sdk'])),
                       'hostBackgroundSeconds': background_duration, 'backgroundSamples': background_samples,
                       'originRequests': counts, 'externalRequests': False, 'screenshots': False,
                       'certificateSha256': fixture['certificateSha256'],
                       'limits': ['Three test-harness WebViews share one isolated CI app space; not three account spaces.',
                                  'Stopped Activity with a live instrumented process; no OS process kill, Doze, or OEM proof.',
                                  'Unexpected core binding stop; native process crash is not simulated.',
                                  'No resource benchmark or real ChatGPT acceptance in this batch.']})
        (output / 'stability.json').write_text(json.dumps(result, indent=2) + '\n')
        print('NETWORK_STABILITY_COMPLETE ' + args.transport, flush=True)
    finally:
        try:
            outage.stop()
        finally:
            for name in ('stability-result.json', 'stability-progress.json', 'stability-phase.json'):
                value = read_device(name)
                if value:
                    (output / name).write_text(json.dumps(value, indent=2) + '\n')


if __name__ == '__main__':
    main()
