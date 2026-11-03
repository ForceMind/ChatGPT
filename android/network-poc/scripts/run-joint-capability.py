#!/usr/bin/env python3
"""CI emulator-only dynamic capability gate. Never substitute it for joint acceptance."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess

PACKAGE = 'com.forcemind.chatgpt.networkpreview.ci'


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', required=True)
    args = parser.parse_args()
    assert os.environ.get('GITHUB_ACTIONS') == 'true'
    assert subprocess.check_output(['adb', 'shell', 'getprop', 'ro.kernel.qemu'], text=True).strip() == '1'
    output = Path(args.output)
    output.mkdir(parents=True, exist_ok=True)
    (output/'attempt.json').write_text(json.dumps({'sourceSHA': os.environ.get('GITHUB_SHA'),
        'transport': os.environ.get('NETWORK_TEST_TRANSPORT'), 'fullJointAcceptance': False,
        'scope': 'one strict dynamic capability test before original routing suite'}, indent=2))
    provider = subprocess.run(['adb', 'shell', 'dumpsys', 'webviewupdate'], text=True, capture_output=True, timeout=15)
    (output/'provider.txt').write_text(provider.stdout + provider.stderr)
    subprocess.run(['adb', 'shell', 'run-as', PACKAGE, 'rm', '-f', 'files/joint-capability.json',
                    'files/joint-capability.json.tmp'], check=True, capture_output=True, timeout=15)
    result = subprocess.run(['adb', 'shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
        'com.forcemind.chatgpt.networkpreview.JointCapabilityInstrumentedTest',
        PACKAGE + '.test/androidx.test.runner.AndroidJUnitRunner'],
        text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=90)
    (output/'instrumentation.txt').write_text(result.stdout)
    print(result.stdout)
    readback = subprocess.run(['adb', 'exec-out', 'run-as', PACKAGE, 'cat', 'files/joint-capability.json'],
                              text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=15)
    if readback.returncode == 0:
        (output/'joint-capability.json').write_text(readback.stdout)
    else:
        (output/'readback-error.txt').write_text(readback.stderr)
    counts = re.findall(r'(?m)^OK \((\d+) test\)\s*$', result.stdout)
    if (result.returncode or counts != ['1']
            or re.search(r'FAILURES!!!|INSTRUMENTATION_FAILED|shortMsg=', result.stdout)):
        raise RuntimeError('Joint capability gate failed; full joint acceptance has not run')
    assert readback.returncode == 0, 'Missing actual capability evidence'
    evidence = json.loads(readback.stdout)
    assert evidence['schema'] == 1 and evidence['complete'] is True
    assert evidence['capabilityGateOnly'] is True and evidence['fullJointAcceptance'] is False
    assert evidence['multiProfile'] is True and evidence['proxyOverride'] is True
    assert evidence['systemProxyRead'] is True and evidence['provider'] and evidence['providerVersion']
    assert evidence['api'] in (28, 35)
    print('NETWORK_JOINT_CAPABILITY_VERIFIED ' + json.dumps(evidence))


if __name__ == '__main__':
    main()
