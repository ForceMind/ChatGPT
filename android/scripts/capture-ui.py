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
    (out / 'failed-native-ui.xml').write_bytes(ET.tostring(tree(), encoding='utf-8'))
    screenshot('failed-native-ui.png')
    raise RuntimeError('Expected native UI control was not present.')

def tap(node):
    bounds = [int(n) for n in re.findall(r'\d+', node.get('bounds'))]
    adb('shell', 'input', 'tap', str((bounds[0] + bounds[2]) // 2), str((bounds[1] + bounds[3]) // 2))
    time.sleep(0.5)

def text(label):
    tap(find(lambda a: a.get('text') == label))

def badge(name=None):
    return find(lambda a: a.get('content-desc', '').startswith('切换账号，当前：') and (name is None or a['content-desc'] == '切换账号，当前：' + name))

def screenshot(name):
    (out / name).write_bytes(adb('exec-out', 'screencap', '-p'))

# All text input is into native account metadata fields, never the ChatGPT webpage.
print('Checking initial native account menu.', flush=True)
initial_badge = badge()
initial_name = initial_badge.get('content-desc').split('当前：', 1)[1]
tap(initial_badge); text('添加账号')
def field(index):
    fields = [n for n in tree().iter('node') if n.get('class') == 'android.widget.EditText']
    assert len(fields) == 2, 'Expected native name/email fields'
    return fields[index]

tap(field(0)); adb('shell', 'input', 'text', 'Work')
# The IME changes dialog bounds. Read the current tree before the second tap.
tap(field(1)); adb('shell', 'input', 'text', 'work@example.test')
text('保存'); badge('Work')
print('Native account creation passed.', flush=True)
tap(badge('Work'))
find(lambda a: 'Work' in a.get('text', '') and 'work@example.test' in a.get('text', ''))
personal = find(lambda a: initial_name in a.get('text', ''))
screenshot('android-accounts.png'); tap(personal); badge(initial_name)
print('Native account switch passed; checking restart.', flush=True)
adb('shell', 'am', 'force-stop', package)
adb('shell', 'am', 'start', '-n', package + '/com.forcemind.chatgpt.profiles.MainActivity')
badge(initial_name); time.sleep(5)
print('Native account restart restoration passed.', flush=True)
screenshot('android-phone.png')
(out / 'android-phone.xml').write_bytes(ET.tostring(tree(), encoding='utf-8'))
# Homepage evidence must show the loaded webpage, with native menus closed.
# The personal space may still be on Cloudflare's check; use the second,
# isolated space only after its actual anonymous ChatGPT page is ready.
tap(badge(initial_name))
tap(find(lambda a: 'Work' in a.get('text', '') and 'work@example.test' in a.get('text', '')))
badge('Work')
print('Checking actual anonymous webpage for homepage evidence.', flush=True)
for attempt in range(30):
    current = tree()
    labels = ' '.join(n.get('text', '') + ' ' + n.get('content-desc', '') for n in current.iter('node'))
    logged_out = re.search(r'Log in|登录|登入', labels, re.I)
    challenge = re.search(r'Verifying|Just a moment|Checking your browser|验证您是真人', labels, re.I)
    if logged_out and 'ChatGPT' in labels and not challenge:
        time.sleep(2)
        screenshot('android-main.png')
        (out / 'android-main.xml').write_bytes(ET.tostring(current, encoding='utf-8'))
        break
    time.sleep(2)
else:
    raise RuntimeError('No loaded anonymous ChatGPT page for the homepage; do not publish a challenge/menu screenshot.')
print('Native account add, switch, restart restoration, and phone rendering passed. No login or chat input.')
