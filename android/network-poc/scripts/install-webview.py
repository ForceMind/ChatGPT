#!/usr/bin/env python3
"""Update only the throwaway AOSP API28 emulator, never a physical device."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import time


def adb(*args, timeout=90, check=True):
    result = subprocess.run(['adb', *args], text=True, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, timeout=timeout)
    if check and result.returncode:
        raise RuntimeError(result.stdout)
    return result


def shell(*args, **kwargs):
    return adb('shell', ' '.join(shlex.quote(str(a)) for a in args), **kwargs)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--provider', required=True)
    parser.add_argument('--output', required=True)
    args = parser.parse_args()
    if os.environ.get('GITHUB_ACTIONS') != 'true' or shell('getprop', 'ro.kernel.qemu').stdout.strip() != '1':
        raise SystemExit('WebView replacement is restricted to an isolated CI emulator')
    if shell('getprop', 'ro.build.version.sdk').stdout.strip() != '28':
        raise SystemExit('Only the fresh API28 compatibility image may be updated')
    if shell('getprop', 'ro.build.type').stdout.strip() not in ('userdebug', 'eng'):
        raise SystemExit('Debug emulator required for an official AOSP test provider')
    provider, output = Path(args.provider), Path(args.output)
    output.mkdir(parents=True, exist_ok=True)
    meta = json.loads((provider/'provider.json').read_text())
    apk = provider/'webview.apk'
    assert meta['package'] == 'com.android.webview' and hashlib.sha256(apk.read_bytes()).hexdigest() == meta['sha256']
    before = shell('dumpsys', 'webviewupdate').stdout
    (output/'webview-before.txt').write_text(before)
    attempt = adb('install', '-r', '-t', str(apk), check=False)
    (output/'webview-install.txt').write_text(attempt.stdout)
    if attempt.returncode:
        if 'INSTALL_FAILED_UPDATE_INCOMPATIBLE' not in attempt.stdout:
            raise RuntimeError(attempt.stdout)
        # Official Chromium development procedure: remove only the old provider
        # APK from the disposable writable system image when certificates differ.
        adb('root'); adb('wait-for-device')
        assert shell('id', '-u').stdout.strip() == '0'
        paths = shell('pm', 'path', meta['package']).stdout.splitlines()
        assert paths and len(paths) == 1, paths
        path = paths[0].removeprefix('package:').strip()
        assert re.fullmatch(r'/system/app/[^/]+/[^/]+\.apk', path), path
        remount = adb('remount').stdout
        (output/'webview-remount.txt').write_text(remount)
        if 'remount succeeded' not in remount.lower():
            raise RuntimeError('Disposable system image is not writable: ' + remount)
        shell('rm', path)
        adb('reboot'); adb('wait-for-device', timeout=180)
        deadline = time.monotonic() + 180
        while shell('getprop', 'sys.boot_completed').stdout.strip() != '1':
            if time.monotonic() > deadline:
                raise RuntimeError('Emulator did not restart after provider replacement')
            time.sleep(2)
        adb('install', '-t', str(apk))
    selection = shell('cmd', 'webviewupdate', 'set-webview-implementation', meta['package']).stdout
    (output/'webview-selection.txt').write_text(selection)
    after = shell('dumpsys', 'webviewupdate').stdout
    (output/'webview-after.txt').write_text(after)
    expected = 'Current WebView package (name, version): (' + meta['package'] + ', ' + meta['version'] + ')'
    if expected not in after:
        raise RuntimeError('Installed provider is not the actual selected WebView: ' + after)
    (output/'provider.json').write_text(json.dumps(meta, indent=2))
    print('Selected official AOSP WebView:', meta['package'], meta['version'])


if __name__ == '__main__':
    main()
