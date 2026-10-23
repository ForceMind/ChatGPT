#!/usr/bin/env python3
"""Read-only anonymous homepage capture on one fresh CI emulator, never a phone."""
from pathlib import Path
import json
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

out = Path(sys.argv[1]); out.mkdir(parents=True, exist_ok=True)
devices = subprocess.check_output(['adb', 'devices'], text=True).splitlines()[1:]
connected = [line.split()[0] for line in devices if line.strip().endswith('\tdevice')]
if len(connected) != 1 or not connected[0].startswith('emulator-'):
    raise SystemExit('Homepage capture requires exactly one isolated CI emulator.')
serial = connected[0]
package = 'com.forcemind.chatgpt.profiles.debug'

def adb(*args, timeout=20):
    return subprocess.check_output(['adb', '-s', serial, *args], timeout=timeout)

observations = []
ready = False
reason = ''
try:
    # Explicit Activity launch only. No WebView URL overrides, page scripts,
    # verification clicks, credentials or chat messages are used.
    adb('shell', 'am', 'start', '-n', package + '/com.forcemind.chatgpt.profiles.MainActivity')
    deadline = time.monotonic() + 180
    while time.monotonic() < deadline:
        try:
            shot = adb('exec-out', 'screencap', '-p')
            if not shot.startswith(b'\x89PNG\r\n\x1a\n'):
                raise RuntimeError('Invalid screenshot payload')
            (out / 'android-home-candidate.png').write_bytes(shot)
            adb('shell', 'uiautomator', 'dump', '--compressed', '/sdcard/profiles-home.xml')
            raw = adb('exec-out', 'cat', '/sdcard/profiles-home.xml')
            tree = ET.fromstring(raw)
            (out / 'android-home.xml').write_bytes(raw)
            nodes = list(tree.iter('node'))
            labels = ' '.join(n.get('text', '') + ' ' + n.get('content-desc', '') for n in nodes)
            challenge = bool(re.search(r'Just a moment|Verifying|Checking your browser|verify you are human|验证您是真人|正在验证', labels, re.I))
            login = bool(re.search(r'Log in|登录|登入', labels, re.I))
            composer = any(n.get('class') == 'android.widget.EditText' for n in nodes) or bool(re.search(r'Ask anything|Message ChatGPT|询问任何|发送消息|向 ChatGPT', labels, re.I))
            native = any(n.get('content-desc', '').startswith('账号与操作菜单') for n in nodes)
            failed = bool(re.search(r"isn't responding|keeps stopping|网页暂时无法|ERR_[A-Z_]+", labels, re.I))
            item = {'elapsedSeconds':round(180 - max(0, deadline - time.monotonic()), 1), 'nativeApp':native, 'chatgpt':'ChatGPT' in labels, 'login':login, 'composer':composer, 'challenge':challenge, 'errorPage':failed}
            observations.append(item); print(json.dumps(item), flush=True)
            if native and 'ChatGPT' in labels and login and composer and not challenge and not failed:
                time.sleep(2)
                (out / 'android-homepage.png').write_bytes(adb('exec-out', 'screencap', '-p'))
                ready = True
                break
        except (subprocess.SubprocessError, ET.ParseError, RuntimeError) as error:
            reason = type(error).__name__ + ': ' + str(error)[:250]
            observations.append({'error':reason})
            print(reason, flush=True)
        time.sleep(4)
finally:
    (out / 'homepage-result.json').write_text(json.dumps({'ready':ready, 'serial':serial, 'scope':'actual anonymous webpage, no login/message/fixture content; emulator only, not physical-device acceptance', 'observations':observations}, indent=2) + '\n')
if not ready:
    raise SystemExit('No verified loaded homepage; candidate image is diagnostic only. ' + reason)
