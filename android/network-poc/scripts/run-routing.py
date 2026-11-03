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
EXPECTED_TESTS = 3
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
        raise RuntimeError('Routing suite did not pass all three required instrumentation tests')


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
                    'files/core-health-result.json.tmp'], check=True, capture_output=True)
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
    verify_instrumentation(result.returncode, safe_stdout)
    evidence = json.loads(subprocess.check_output(['adb', 'exec-out', 'run-as', PACKAGE, 'cat',
                                                  'files/core-health-result.json'], text=True, timeout=15))
    verify_core_health(evidence, expected, origin)
    requests = verify_health_requests((Path(os.environ['RUNNER_TEMP'])/'network-fixture.log').read_text(), origin)
    (output/f'core-health-{args.transport}-{args.origin}.json').write_text(json.dumps(evidence, indent=2))
    summary = {'complete': True, 'transport': args.transport, 'security': expected,
               'origin': origin, 'directOrigin': direct, 'certificateSha256': fixture['certificateSha256'],
               'negativeIdentityCases': negatives, 'tests': EXPECTED_TESTS, 'externalRequests': False,
               'coreHealth': {'instrumentation': evidence, 'fixtureRequests': requests,
                              'schedulingBoundary': 'CLOSED/core/callback observed for at least 4.5s; scheduler invocation counts were not measured'}}
    (output/f'routing-{args.transport}-{args.origin}.json').write_text(json.dumps(summary, indent=2))
    print('ROUTING_PROTOCOL_RESULT ' + json.dumps(summary))


if __name__ == '__main__':
    main()
