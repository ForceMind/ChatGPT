"""Overlap original dumpsys with CI-only test APK lifecycle; never touch personal packages."""
import concurrent.futures
import json
import os
from pathlib import Path
import subprocess
import threading
import time
from sampling_evidence import capture_failure

assert os.environ.get('GITHUB_ACTIONS') == 'true'
root = Path('android/app/build/resource-evidence/package-churn')
root.mkdir(parents=True, exist_ok=True)
records, lock, start = [], threading.Lock(), threading.Barrier(2)
package = 'com.forcemind.chatgpt.profiles.debug.test'
apk = Path('android/app/build/performance-inputs/baseline-test.apk')
assert apk.is_file()

def command(name, args):
    at = time.monotonic()
    try:
        result = subprocess.run(args, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=30)
        (root / (name + '.stdout')).write_bytes(result.stdout)
        (root / (name + '.stderr')).write_bytes(result.stderr)
        with lock:
            records.append({'name': name, 'arguments': args, 'returnCode': result.returncode,
                            'start': at, 'end': time.monotonic(), 'stdoutBytes': len(result.stdout)})
            (root / 'commands.json').write_text(json.dumps(records, indent=2) + '\n')
        if result.returncode:
            raise subprocess.CalledProcessError(result.returncode, args, result.stdout, result.stderr)
        return result.stdout
    except BaseException as error:
        capture_failure(root, error)
        raise

assert command('qemu', ['adb', 'shell', 'getprop', 'ro.kernel.qemu']).strip() == b'1'
assert command('uid', ['adb', 'shell', 'id', '-u']).strip() == b'0'
assert b'package:' in command('test-package', ['adb', 'shell', 'pm', 'path', package])

def lifecycle():
    start.wait(timeout=10)
    for i in range(5):
        assert command('uninstall-%02d' % i, ['adb', 'uninstall', package]).strip() == b'Success'
        assert b'Success' in command('install-%02d' % i, ['adb', 'install', '-r', '-t', str(apk)])

def dumps():
    start.wait(timeout=10)
    for i in range(40):
        assert b'ACTIVITY MANAGER' in command('dump-%02d' % i,
            ['adb', 'shell', 'su 2000 dumpsys activity processes'])

try:
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        tasks = [pool.submit(lifecycle), pool.submit(dumps)]
        for task in tasks:
            task.result()
    changes = [r for r in records if r['name'].startswith(('install-', 'uninstall-'))]
    reads = [r for r in records if r['name'].startswith('dump-')]
    overlaps = sum(any(r['start'] < c['end'] and r['end'] > c['start'] for c in changes) for r in reads)
    assert len(changes) == 10 and len(reads) == 40 and overlaps > 0
    (root / 'result.json').write_text(json.dumps({'complete': True, 'sourceSHA': os.environ['GITHUB_SHA'],
        'testPackage': package, 'lifecycleCommands': len(changes), 'dumps': len(reads),
        'overlappingDumps': overlaps, 'browserStartedByThisScript': False}) + '\n')
except BaseException as error:
    capture_failure(root, error)
    raise
finally:
    with (root / 'guest-logcat.txt').open('wb') as output:
        try:
            subprocess.run(['adb', 'logcat', '-d', '-v', 'threadtime'], stdout=output,
                           stderr=subprocess.STDOUT, timeout=15)
        except subprocess.TimeoutExpired:
            output.write(b'Post-failure log collection timed out.\n')
