"""CI-only native transport isolation. No app, browser, screenshot or personal data."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import time

assert os.environ.get('GITHUB_ACTIONS') == 'true'
root = Path('android/app/build/adb-evidence')
root.mkdir(parents=True, exist_ok=True)
records = []

def command(label, args, timeout=25):
    started = time.monotonic()
    result = subprocess.run(args, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout)
    (root / (label + '.stdout')).write_bytes(result.stdout)
    (root / (label + '.stderr')).write_bytes(result.stderr)
    entry = {'label': label, 'arguments': args, 'returnCode': result.returncode,
             'elapsedSeconds': time.monotonic() - started, 'stdoutBytes': len(result.stdout),
             'stdoutSHA256': hashlib.sha256(result.stdout).hexdigest()}
    records.append(entry)
    (root / 'commands.json').write_text(json.dumps(records, indent=2) + '\n')
    if result.returncode:
        with (root / 'first-error.json').open('x') as output:
            json.dump(entry, output, indent=2)
        raise RuntimeError('Native command failed: ' + label)
    return result.stdout

assert command('emulator-identity', ['adb', 'shell', 'getprop', 'ro.kernel.qemu']).strip() == b'1'
command('adb-version', ['adb', 'version'])
long_connection = None
try:
    for phase in ('shell', 'root', 'parallel'):
        if phase == 'root':
            command('root-transition', ['adb', 'root'])
            command('root-device-ready', ['adb', 'wait-for-device'], timeout=60)
            assert command('actual-root-uid', ['adb', 'shell', 'id', '-u']).strip() == b'0'
        if phase == 'parallel':
            long_connection = subprocess.Popen(['adb', 'shell', 'sleep', '600'],
                stdout=(root / 'parallel-shell.stdout').open('wb'),
                stderr=(root / 'parallel-shell.stderr').open('wb'))
        for index in range(40):
            if long_connection is not None:
                assert long_connection.poll() is None, 'Parallel transport exited unexpectedly'
            native = 'dumpsys activity processes' if phase == 'shell' else 'su 2000 dumpsys activity processes'
            data = command(phase + '-' + str(index).zfill(2), ['adb', 'shell', native])
            assert b'ACTIVITY MANAGER' in data, 'Incomplete diagnostic service output'
    (root / 'result.json').write_text(json.dumps({'complete': True, 'commands': len(records),
        'sourceSHA': os.environ['GITHUB_SHA'], 'appStarted': False, 'browserStarted': False}) + '\n')
except BaseException as error:
    target = root / 'first-error.json'
    if not target.exists():
        details = {'type': type(error).__name__, 'message': str(error)}
        if isinstance(error, subprocess.TimeoutExpired):
            (root / 'timeout.stdout').write_bytes(error.stdout or b'')
            (root / 'timeout.stderr').write_bytes(error.stderr or b'')
            details['command'] = error.cmd
        target.write_text(json.dumps(details, indent=2) + '\n')
    raise
finally:
    if long_connection is not None:
        long_connection.terminate()
        long_connection.wait(timeout=10)
    for name, args in [('guest-logcat', ['adb', 'logcat', '-d', '-v', 'threadtime']),
                       ('guest-kernel', ['adb', 'shell', 'dmesg'])]:
        with (root / (name + '.txt')).open('wb') as output:
            try:
                subprocess.run(args, stdout=output, stderr=subprocess.STDOUT, timeout=15)
            except subprocess.TimeoutExpired:
                output.write(b'Post-failure diagnostic timed out; original error retained.\n')
