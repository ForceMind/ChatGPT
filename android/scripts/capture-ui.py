#!/usr/bin/env python3
"""Exercise account controls on a fresh CI emulator; never log in or send chat text."""
from pathlib import Path
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

package = 'com.forcemind.chatgpt.profiles.debug'
out = Path(sys.argv[1]); out.mkdir(parents=True, exist_ok=True)
devices = subprocess.check_output(['adb', 'devices'], text=True).splitlines()[1:]
connected = [line.split()[0] for line in devices if line.strip().endswith('\tdevice')]
if len(connected) != 1 or not connected[0].startswith('emulator-'):
    raise SystemExit('UI acceptance runs only on one isolated emulator, never a physical device.')

def adb(*args):
    return subprocess.check_output(['adb', *args], timeout=30)

def tree():
    for attempt in range(3):
        try:
            adb('shell', 'uiautomator', 'dump', '--compressed', '/sdcard/profiles-ui.xml')
            return ET.fromstring(adb('exec-out', 'cat', '/sdcard/profiles-ui.xml'))
        except (subprocess.SubprocessError, ET.ParseError):
            if attempt == 2: raise
            time.sleep(1)

def find(predicate):
    for attempt in range(10):
        for node in tree().iter('node'):
            if predicate(node.attrib): return node
        time.sleep(1)
    raise RuntimeError('Expected native UI control was not present.')

def tap(node):
    bounds = [int(n) for n in re.findall(r'\d+', node.get('bounds'))]
    adb('shell', 'input', 'tap', str((bounds[0] + bounds[2]) // 2), str((bounds[1] + bounds[3]) // 2))
    time.sleep(0.5)

def text(label):
    tap(find(lambda a: a.get('text') == label))

def badge(name=None):
    return find(lambda a: a.get('content-desc', '').startswith('账号与操作菜单') and (name is None or name in a['content-desc']))

def screenshot(name):
    (out / name).write_bytes(adb('exec-out', 'screencap', '-p'))

# All text input is into native account metadata fields, never the ChatGPT webpage.
tap(badge('个人账号')); text('添加账号')
fields = list(tree().iter('node')); fields = [n for n in fields if n.get('class') == 'android.widget.EditText']
assert len(fields) == 2, 'Expected native name/email fields'
tap(fields[0]); adb('shell', 'input', 'text', 'Work')
tap(fields[1]); adb('shell', 'input', 'text', 'work@example.test')
text('保存'); badge('Work')
tap(badge('Work')); text('切换账号')
find(lambda a: 'Work' in a.get('text', ''))
personal = find(lambda a: '个人账号' in a.get('text', ''))
screenshot('android-accounts.png'); tap(personal); badge('个人账号')
adb('shell', 'am', 'force-stop', package)
adb('shell', 'am', 'start', '-n', package + '/com.forcemind.chatgpt.profiles.MainActivity')
badge('个人账号'); time.sleep(5)
screenshot('android-phone.png')
(out / 'android-phone.xml').write_bytes(ET.tostring(tree(), encoding='utf-8'))
print('Native account add, switch, restart restoration, and phone rendering passed. No login or chat input.')
