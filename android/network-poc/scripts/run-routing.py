#!/usr/bin/env python3
"""Protocol-labelled, owned Android routing assertions; no shell URI expansion."""
import argparse
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
from urllib.parse import parse_qsl, urlencode, urlsplit, urlunsplit


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--fixture', required=True)
    parser.add_argument('--transport', choices=['raw', 'tls', 'reality'], required=True)
    parser.add_argument('--origin', choices=['http', 'https'], default='https')
    parser.add_argument('--output', required=True)
    args = parser.parse_args()
    assert os.environ.get('GITHUB_ACTIONS') == 'true'
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
    result = subprocess.run(['adb', 'shell', ' '.join(shlex.quote(x) for x in command)],
                            text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=420)
    (output/f'instrumentation-{args.transport}-{args.origin}.txt').write_text(result.stdout)
    print(result.stdout)
    if result.returncode or not re.search(r'OK \(2 tests\)', result.stdout) or re.search(r'FAILURES!!!|INSTRUMENTATION_FAILED|shortMsg=', result.stdout):
        raise RuntimeError('Protocol routing suite failed: ' + args.transport + '/' + args.origin)
    summary = {'complete': True, 'transport': args.transport, 'security': expected,
               'origin': origin, 'directOrigin': direct, 'certificateSha256': fixture['certificateSha256'],
               'negativeIdentityCases': negatives, 'tests': 2, 'externalRequests': False}
    (output/f'routing-{args.transport}-{args.origin}.json').write_text(json.dumps(summary, indent=2))
    print('ROUTING_PROTOCOL_RESULT ' + json.dumps(summary))


if __name__ == '__main__':
    main()
