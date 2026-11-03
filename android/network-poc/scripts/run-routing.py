#!/usr/bin/env python3
"""Protocol-labelled, owned Android routing assertions; no shell URI expansion."""
import argparse
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys
from urllib.parse import parse_qsl, urlencode, urlsplit, urlunsplit

PACKAGE = 'com.forcemind.chatgpt.networkpreview.ci'
EXPECTED_TESTS = 4
TRANSFER_CASES = [
    ('core-stop-download', 'download', 'core-stop'),
    ('recovered-download', 'download', 'explicit-new-success'),
    ('core-stop-native-upload', 'native-upload', 'core-stop'),
    ('recovered-native-upload', 'native-upload', 'explicit-new-success'),
    ('core-stop-web-upload', 'web-upload', 'core-stop'),
    ('recovered-web-upload', 'web-upload', 'explicit-new-success'),
    ('cancel-download', 'download', 'cancel'),
    ('after-cancel-download', 'download', 'explicit-new-success'),
    ('close-download', 'download', 'close'),
    ('new-instance-download', 'download', 'explicit-new-success'),
    ('close-native-upload', 'native-upload', 'close'),
    ('after-close-native-upload', 'native-upload', 'explicit-new-success'),
]
HEALTH_REQUESTS = [
    {'path': '/stability/core-health-before/echo', 'method': 'GET', 'route': 'A'},
    {'path': '/stability/core-health/page', 'method': 'GET', 'route': 'A'},
    {'path': '/stability/core-health-reconnected-native/echo', 'method': 'GET', 'route': 'A'},
    {'path': '/stability/core-health-reconnected-web/upload', 'method': 'POST', 'route': 'A'},
    {'path': '/stability/core-health-direct/echo', 'method': 'GET', 'route': 'direct'},
]


def verify_instrumentation(returncode, stdout):
    counts = re.findall(r'(?m)^OK \((\d+) tests\)\s*$', stdout)
    if (returncode != 0 or counts != [str(EXPECTED_TESTS)]
            or re.search(r'FAILURES!!!|INSTRUMENTATION_FAILED|shortMsg=', stdout)):
        raise RuntimeError('Routing suite did not pass all four required instrumentation tests')


def redact_output(value, arguments):
    result = value
    for key, uri in arguments.items():
        if key.startswith(('node', 'invalidNode')):
            result = result.replace(uri, 'vless://[REDACTED]')
            username = urlsplit(uri).username
            if username:
                result = result.replace(username, '[REDACTED-ID]')
    return re.sub(r'vless://[^\s]+', 'vless://[REDACTED]', result)


def verify_core_health(evidence, security, origin):
    assert evidence['complete'] is True
    assert evidence['schema'] == 1
    assert evidence['test'] == 'unexpectedCoreStopFailsClosedAndRequiresExplicitRecovery'
    assert evidence['security'] == security and evidence['origin'] == origin
    assert 'vless://' not in json.dumps(evidence).lower(), 'Node credentials must not enter health evidence'
    before = evidence['before']
    assert before['state'] == 'CONNECTED' and before['coreRunning'] is True
    assert before['webNetworkBlocked'] is False and '已连接' in before['visibleStatus']
    for failed in [evidence['automaticFailure'], evidence['background']['failure']]:
        assert failed['state'] == 'FAILED' and failed['coreRunning'] is False
        assert failed['webNetworkBlocked'] is True
        assert '已连接' not in failed['visibleStatus'] and '失败' in failed['visibleStatus']
        assert '手动' in failed['error'] and '直连' in failed['error']
        assert '重新连接' in failed['error'] or '重连' in failed['error']
    assert evidence['externalStopAtMillis'] <= evidence['failureDetectedAtMillis'] <= evidence['failedRequestsStartedAtMillis']
    assert evidence['detectionMillis'] == evidence['failureDetectedAtMillis'] - evidence['externalStopAtMillis']
    assert 0 <= evidence['detectionMillis'] <= 40000
    assert evidence['nativeFailure'] == 'IllegalStateException' and evidence['webFailure'] == 'error'
    assert evidence['activeTransfersAfterFailure'] == 0
    assert evidence['reconnected']['state'] == 'CONNECTED'
    assert evidence['reconnected']['coreRunning'] is True and evidence['reconnected']['webNetworkBlocked'] is False
    assert evidence['direct']['state'] == 'DIRECT'
    assert evidence['direct']['coreRunning'] is False and evidence['direct']['webNetworkBlocked'] is False
    background = evidence['background']
    assert background['homeCommand'] == 'input keyevent KEYCODE_HOME'
    assert background['returnCommand'] == 'am start -W -n ' + PACKAGE + '/com.forcemind.chatgpt.networkpreview.MainActivity'
    assert background['stoppedLifecycle'] == 'CREATED' and background['resumedLifecycle'] == 'RESUMED'
    assert background['sameActivity'] is True and background['elapsedMillis'] >= 700
    documents = evidence['documents']
    original = documents['before']
    assert original['identity'] and original['draft'] == 'core-health-unsent-draft'
    assert original['fetchStarts'] == 0 and original['submissions'] == 0
    for phase, fetches in [('afterAutomaticFailure', 0), ('afterBlockedRequests', 1),
                           ('afterExplicitReconnect', 1), ('afterHomeResumeFailure', 2), ('afterExplicitDirect', 2)]:
        current = documents[phase]
        assert all(current[key] == original[key] for key in ('url', 'identity', 'draft', 'submissions'))
        assert current['fetchStarts'] == fetches
    closed = evidence['closed']
    assert closed['state'] == 'CLOSED' and closed['generation'] == closed['expectedGeneration']
    assert closed['observedMillis'] >= 4500 and closed['samples'] >= 2
    assert closed['callbackInvocations'] == 0 and closed['coreRunning'] is False
    assert evidence['expectedRequests'] == HEALTH_REQUESTS
    return evidence


def verify_health_requests(raw, origin):
    events = []
    port = urlsplit(origin).port
    for line in raw.splitlines():
        try:
            event = json.loads(line)
        except json.JSONDecodeError:
            continue
        if (event.get('event') == 'owned-origin-access' and event.get('port') == port
                and event.get('path', '').startswith('/stability/core-health')):
            events.append(event)
    observations = []
    for expected in HEALTH_REQUESTS:
        found = [event for event in events if all(event.get(key) == value for key, value in expected.items())]
        assert len(found) == 1, 'Missing or repeated explicit health recovery request: ' + expected['path']
        event = found[0]
        assert event['status'] == 200 and event['responseBytesWritten'] == event['contentLength'] > 0
        observations.append({**expected, 'count': len(found), 'status': event['status']})
    assert len(events) == len(HEALTH_REQUESTS), 'Unexpected, direct-fallback or replayed health request reached the fixture'
    return observations


def verify_transfer_fault(evidence, transport, origin):
    assert evidence['schema'] == 1 and evidence['complete'] is True
    assert evidence['transport'] == transport and evidence['origin'] == origin
    assert evidence['controlOrigin'] == 'http://10.0.2.2:18080'
    assert evidence['controlPlane'] == 'androidTest-only direct owned control; not production fallback'
    assert 'vless://' not in json.dumps(evidence).lower()
    prefix = transport + '-' + urlsplit(origin).scheme + '-'
    phases = evidence['phases']
    assert len(phases) == len(TRANSFER_CASES)
    assert [phase['opid'] for phase in phases] == [prefix + case[0] for case in TRANSFER_CASES]
    original = evidence['originalDocument']
    assert original['identity'] and original['draft'] == 'transfer-fault-draft' and original['submissions'] == 0
    finals = {item['opid']: item for item in evidence['finalOperations']}
    assert len(finals) == len(evidence['finalOperations']) == len(TRANSFER_CASES)
    expected_callbacks = {}
    for phase, (_, kind, action) in zip(phases, TRANSFER_CASES):
        assert phase['kind'] == kind and phase['action'] == action
        assert phase['activeTransfers'] == 0
        assert phase['routeState'] == ('FAILED' if action == 'core-stop' else 'CONNECTED')
        assert phase['coreRunning'] is (action != 'core-stop')
        assert phase['document'] == original
        for field in ['started', 'terminal']:
            server = phase[field]
            assert server['opid'] == phase['opid'] and server['transport'] == transport
            assert server['origin'] == urlsplit(origin).scheme and server['route'] == 'A'
            assert server['kind'] == ('download' if kind == 'download' else 'upload')
            assert server['started'] is True and server['opCount'] == server['attemptCount'] == 1
            assert server['commitCount'] == (0 if kind == 'download' else 1)
            assert server['timedOut'] is False
        assert phase['started']['released'] is False
        assert phase['terminal']['released'] is True
        terminal = phase['terminal']
        assert terminal['completed'] or terminal['writeError'] or terminal['readError']
        final = finals[phase['opid']]
        assert final['opCount'] == final['attemptCount'] == 1
        assert final['commitCount'] == terminal['commitCount']
        assert final['released'] is True and final['timedOut'] is False
        if kind == 'download':
            assert phase['started']['prefix'] is True and phase['started']['responseBytesWritten'] == 4096
            assert phase['started']['totalBytes'] == 262144
            assert phase['callbackCount'] == 1
            success = action == 'explicit-new-success'
            assert phase['successCount'] == int(success)
            assert phase['baselineFiles'] == phase['afterFiles']
            assert len(set(phase['baselineFiles'])) == len(phase['baselineFiles'])
            assert all(name.startswith('download-') for name in phase['baselineFiles'])
            if success:
                assert phase['deliveredBytes'] == 262144
            else:
                assert 4096 <= phase['partialBytes'] < 262144 and phase['error']
                assert phase['terminationMillis'] >= 0
                if action in ('cancel', 'close'): assert phase['terminationMillis'] <= 10000
            expected_callbacks[phase['opid']] = int(success)
        else:
            assert phase['started']['bodyComplete'] is True
            assert phase['started']['requestBytesRead'] == phase['started']['totalBytes'] > 0
            assert phase['started']['responseBytesWritten'] == 0
            assert phase['outcomeUncertain'] is (action != 'explicit-new-success')
            if kind == 'native-upload':
                assert phase['completionCount'] == 1
                assert phase['successCount'] == int(action == 'explicit-new-success')
                if action == 'explicit-new-success': assert phase['responseBytes'] == len(b'network-poc-upload-committed\n')
                else: assert '结果不明' in phase['error'] and phase['responseBytes'] == 0
                if action == 'close': assert 0 <= phase['terminationMillis'] <= 10000
            else:
                assert phase['clientBefore'] == {'state': 'pending', 'completions': 0}
                assert phase['client']['completions'] == 1
                assert phase['client']['state'] == ('error' if action == 'core-stop' else 'success')
                if action == 'explicit-new-success':
                    assert phase['client']['route'] == 'A' and phase['client']['body'] == 'network-poc-upload-committed\n'
                else: assert phase['client']['error']
    rejected = evidence['closedRejections']
    assert len(rejected) == 2
    assert rejected[0]['opid'] == prefix + 'closed-rejected-download'
    assert rejected[0]['callbackCount'] == 1 and rejected[0]['successCount'] == 0
    assert rejected[1]['opid'] == prefix + 'closed-rejected-upload' and rejected[1]['rejectedBeforeTransfer'] is True
    for item in rejected:
        assert item['serverAbsent'] is True
        assert any(control['opid'] == item['opid'] and control['status'] == 404 for control in evidence['controlRequests'])
    expected_callbacks[rejected[0]['opid']] = 0
    callbacks = evidence['finalDownloadCallbacks']
    assert len(callbacks) == len(expected_callbacks) == len({item['opid'] for item in callbacks})
    for item in callbacks:
        assert item['callbackCount'] == 1 and item['successCount'] == expected_callbacks[item['opid']]
    assert all(control['ownedDirectControl'] is True and control['status'] in (200, 404) for control in evidence['controlRequests'])
    for phase in phases:
        for method in ['GET', 'POST']:
            assert any(control['opid'] == phase['opid'] and control['method'] == method and control['status'] == 200 for control in evidence['controlRequests'])
    assert evidence['finalDocument'] == original and evidence['activeTransfers'] == 0
    return evidence


def verify_transfer_requests(evidence, raw):
    prefix = evidence['transport'] + '-' + urlsplit(evidence['origin']).scheme + '-'
    events = []
    for line in raw.splitlines():
        try: event = json.loads(line)
        except json.JSONDecodeError: continue
        if isinstance(event, dict) and event.get('event') == 'owned-transfer-summary' and event.get('opid', '').startswith(prefix):
            events.append(event)
    assert len(events) == len(TRANSFER_CASES)
    for final in evidence['finalOperations']:
        matched = [event for event in events if event['opid'] == final['opid']]
        assert len(matched) == 1, 'Missing/duplicate transfer terminal event'
        event = matched[0]
        for key in ['transport', 'origin', 'kind', 'route', 'opCount', 'attemptCount', 'commitCount', 'requestBytesRead', 'released', 'timedOut']:
            assert event[key] == final[key], 'Server/client transfer evidence differs: ' + key
        assert event['opCount'] == 1 and event['route'] == 'A' and event['timedOut'] is False
    return events


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--fixture', required=True)
    parser.add_argument('--transport', choices=['raw', 'tls', 'reality'], required=True)
    parser.add_argument('--origin', choices=['http', 'https'], default='https')
    parser.add_argument('--output', required=True)
    args = parser.parse_args()
    assert os.environ.get('GITHUB_ACTIONS') == 'true'
    subprocess.run([sys.executable, '-m', 'unittest', 'discover', '-s', str(Path(__file__).with_name('tests')),
                    '-p', 'test_routing_driver.py'], check=True)
    assert subprocess.check_output(['adb', 'shell', 'getprop', 'ro.kernel.qemu'], text=True).strip() == '1'
    path = Path(args.fixture)
    fixture = json.loads(path.read_text())
    assert json.loads(path.with_name('ready.json').read_text())['ready']
    node = fixture['transports'][args.transport]
    expected = 'none' if args.transport == 'raw' else args.transport
    assert node['expectedSecurity'] == expected
    for name in ['nodeA', 'nodeB']:
        parsed = urlsplit(node[name])
        assert parsed.scheme == 'vless' and parsed.hostname == '10.0.2.2'
        assert dict(parse_qsl(parsed.query))['security'] == expected
    origin = f'{args.origin}://network-fixture.test:' + str(fixture['originPort'] if args.origin == 'http' else fixture['httpsOriginPort'])
    direct = origin.replace('network-fixture.test', '10.0.2.2')
    arguments = {'class': 'com.forcemind.chatgpt.networkpreview.NetworkPocInstrumentedTest',
                 'requireWebViewProxy': 'true', 'expectedSecurity': expected,
                 'nodeA': node['nodeA'], 'nodeB': node['nodeB'],
                 'fixtureUrl': origin, 'directFixtureUrl': direct}
    negatives = []
    if args.transport == 'tls':
        parsed = urlsplit(node['nodeA'])
        query = dict(parse_qsl(parsed.query)); query['sni'] = 'wrong.network-fixture.test'
        arguments['invalidNode'] = urlunsplit(parsed._replace(query=urlencode(query)))
        arguments['invalidNode2'] = fixture['tlsTest']['untrustedAuthorityNodeA']
        negatives = ['trusted CA but wrong hostname', 'untrusted certificate authority']
    if args.transport == 'reality':
        arguments['invalidNode'] = fixture['realityTest']['wrongPublicKeyNodeA']
        arguments['invalidNode2'] = fixture['realityTest']['wrongShortIdNodeA']
        negatives = ['wrong REALITY public key', 'wrong REALITY short ID']
    command = ['am', 'instrument', '-w', '-r']
    for key, value in arguments.items():
        command.extend(['-e', key, value])
    command.append('com.forcemind.chatgpt.networkpreview.ci.test/androidx.test.runner.AndroidJUnitRunner')
    output = Path(args.output); output.mkdir(parents=True, exist_ok=True)
    subprocess.run(['adb', 'shell', 'run-as', PACKAGE, 'rm', '-f', 'files/core-health-result.json',
                    'files/core-health-result.json.tmp', 'files/transfer-fault-result.json',
                    'files/transfer-fault-result.json.tmp'], check=True, capture_output=True)
    try:
        result = subprocess.run(['adb', 'shell', ' '.join(shlex.quote(x) for x in command)],
                                text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=420)
    except subprocess.TimeoutExpired as failure:
        raw = failure.stdout or ''
        if isinstance(raw, bytes): raw = raw.decode(errors='replace')
        (output/f'instrumentation-{args.transport}-{args.origin}.txt').write_text(redact_output(raw, arguments))
        raise RuntimeError('Protocol routing suite timed out: ' + args.transport + '/' + args.origin) from None
    safe_stdout = redact_output(result.stdout, arguments)
    (output/f'instrumentation-{args.transport}-{args.origin}.txt').write_text(safe_stdout)
    print(safe_stdout)
    transfer_result = subprocess.run(['adb', 'exec-out', 'run-as', PACKAGE, 'cat', 'files/transfer-fault-result.json'],
                                     text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=15)
    if transfer_result.returncode == 0:
        (output/f'transfer-fault-{args.transport}-{args.origin}.json').write_text(redact_output(transfer_result.stdout, arguments))
    verify_instrumentation(result.returncode, safe_stdout)
    evidence = json.loads(subprocess.check_output(['adb', 'exec-out', 'run-as', PACKAGE, 'cat',
                                                  'files/core-health-result.json'], text=True, timeout=15))
    verify_core_health(evidence, expected, origin)
    requests = verify_health_requests((Path(os.environ['RUNNER_TEMP'])/'network-fixture.log').read_text(), origin)
    if transfer_result.returncode != 0:
        raise RuntimeError('Missing newly executed transfer fault evidence')
    transfer = json.loads(transfer_result.stdout)
    verify_transfer_fault(transfer, args.transport, origin)
    transfer_requests = verify_transfer_requests(transfer, (Path(os.environ['RUNNER_TEMP'])/'network-fixture.log').read_text())
    (output/f'core-health-{args.transport}-{args.origin}.json').write_text(json.dumps(evidence, indent=2))
    summary = {'complete': True, 'transport': args.transport, 'security': expected,
               'origin': origin, 'directOrigin': direct, 'certificateSha256': fixture['certificateSha256'],
               'negativeIdentityCases': negatives, 'tests': EXPECTED_TESTS, 'externalRequests': False,
               'coreHealth': {'instrumentation': evidence, 'fixtureRequests': requests,
                              'schedulingBoundary': 'CLOSED/core/callback observed for at least 4.5s; scheduler invocation counts were not measured'},
               'inFlightTransfers': {'instrumentation': transfer, 'fixtureRequests': transfer_requests,
                                     'controlPlane': 'androidTest-only owned direct HTTP, never a production fallback',
                                     'browserScope': 'Synthetic File/FormData only; not real ChatGPT'}}
    (output/f'routing-{args.transport}-{args.origin}.json').write_text(json.dumps(summary, indent=2))
    print('ROUTING_PROTOCOL_RESULT ' + json.dumps(summary))


if __name__ == '__main__':
    main()
