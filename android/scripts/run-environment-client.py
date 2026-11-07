#!/usr/bin/env python3
"""Owned CI environment acceptance through native controls and independent HTTPS observation."""
import hashlib
import json
import os
from pathlib import Path
import re
import ssl
import subprocess
import sys
import time
import traceback
import urllib.error
import urllib.request
import uuid
import xml.etree.ElementTree as ET

import environment_upgrade as native

PACKAGE = native.PACKAGE
EVIDENCE = native.EVIDENCE
FILES = '/data/user/0/' + PACKAGE + '/files/'
PORT = 18480
HOST = 'environment-fixture.test'
PAGE = {('A', 0): '/c/a1', ('A', 1): '/c/a2', ('B', 0): '/c/b1', ('C', 0): '/c/c1'}
UUID = re.compile(r'[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}')


class Acceptance:
    def __init__(self, upgrade):
        self.upgrade = upgrade
        self.api = upgrade['api']
        self.sequence = 0
        self.server = None
        self.server_log = None
        self.certificates = Path(os.environ['RUNNER_TEMP']) / ('environment-fixture-' + uuid.uuid4().hex)
        self.certificates.mkdir(mode=0o700)
        self.ssl = None
        self.manifest = None
        self.touch_events = []
        self.keyboard_events = []
        self.navigation_row = None
        self.cold_process_exits = []

    def command(self, args, timeout=60, check=True):
        self.sequence += 1
        label = f'{self.sequence:04d}-command'
        try:
            result = subprocess.run(args, text=True, stdout=subprocess.PIPE,
                                    stderr=subprocess.STDOUT, timeout=timeout)
        except subprocess.TimeoutExpired as error:
            partial = error.stdout or ''
            if isinstance(partial, bytes):
                partial = partial.decode('utf-8', errors='replace')
            (EVIDENCE / (label + '.txt')).write_text(partial, encoding='utf-8')
            raise
        native._write_json(EVIDENCE / (label + '.json'), {'command': args, 'exitCode': result.returncode})
        (EVIDENCE / (label + '.txt')).write_text(result.stdout, encoding='utf-8')
        if check and result.returncode:
            raise RuntimeError(f'{args} exited {result.returncode}:\n{result.stdout}')
        return result.stdout

    def shell(self, *args, timeout=60):
        return self.command(['adb', 'shell', *map(str, args)], timeout=timeout)

    def json_file(self, relative):
        assert relative and '..' not in relative and not relative.startswith('/')
        return json.loads(self.shell('cat', FILES + relative))

    def http(self, path, value=None):
        self.sequence += 1
        label = f'{self.sequence:04d}-https'
        data = None if value is None else json.dumps(value).encode('utf-8')
        request = urllib.request.Request('https://127.0.0.1:' + str(PORT) + path, data=data,
                                        headers={'Host': HOST + ':' + str(PORT), 'Content-Type': 'application/json'})
        # No proxy inheritance, even if the runner has HTTP(S)_PROXY configured.
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}),
                                            urllib.request.HTTPSHandler(context=self.ssl))
        try:
            with opener.open(request, timeout=8) as response:
                body = response.read()
                status = response.status
        except urllib.error.HTTPError as error:
            body = error.read()
            (EVIDENCE / (label + '.json')).write_bytes(body)
            native._write_json(EVIDENCE / (label + '-response.json'), {'path': path, 'status': error.code})
            raise
        (EVIDENCE / (label + '.json')).write_bytes(body)
        native._write_json(EVIDENCE / (label + '-response.json'), {'path': path, 'status': status})
        assert status == 200, (path, status)
        return json.loads(body)

    def status(self):
        value = self.http('/status')
        assert value['schema'] == 1 and value['host'] == HOST
        assert value['sendCount'] == 0, 'Unexpected send request'
        assert value['rejectedEventCount'] == 0, 'Fixture rejected a document event'
        return value

    def wait(self, description, sample, condition, timeout=75, interval=0.6):
        print('[environment] waiting: ' + description, flush=True)
        deadline = time.monotonic() + timeout
        last = None
        while True:
            last = sample()
            if condition(last):
                print('[environment] observed: ' + description, flush=True)
                return last
            if time.monotonic() >= deadline:
                native._write_json(EVIDENCE / 'last-timeout.json', {'description': description, 'last': last})
                raise AssertionError('Timed out: ' + description)
            time.sleep(interval)

    def tls_server(self):
        ca = self.certificates / 'ca.crt'
        leaf = self.certificates / 'leaf.crt'
        key = self.certificates / 'leaf.key'
        self.command(['openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-sha256', '-days', '2',
                      '-subj', '/CN=OwnedEnvCA', '-addext', 'basicConstraints=critical,CA:TRUE',
                      '-addext', 'keyUsage=critical,keyCertSign,cRLSign', '-keyout', str(self.certificates / 'ca.key'), '-out', str(ca)])
        self.command(['openssl', 'req', '-new', '-newkey', 'rsa:2048', '-nodes', '-sha256',
                      '-subj', '/CN=' + HOST, '-keyout', str(key), '-out', str(self.certificates / 'leaf.csr')])
        extensions = self.certificates / 'leaf.ext'
        extensions.write_text('basicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature,keyEncipherment\nextendedKeyUsage=serverAuth\nsubjectAltName=DNS:' + HOST + ',IP:127.0.0.1\n', encoding='utf-8')
        self.command(['openssl', 'x509', '-req', '-sha256', '-days', '2', '-in', str(self.certificates / 'leaf.csr'),
                      '-CA', str(ca), '-CAkey', str(self.certificates / 'ca.key'), '-CAcreateserial',
                      '-extfile', str(extensions), '-out', str(leaf)])
        for private in self.certificates.glob('*.key'):
            private.chmod(0o600)
        self.ssl = ssl.create_default_context(cafile=str(ca))
        self.server_log = (EVIDENCE / 'fixture-process.txt').open('w', encoding='utf-8')
        self.server = subprocess.Popen([sys.executable, 'android/scripts/environment-fixture.py', '--cert', str(leaf),
                                        '--key', str(key), '--port', str(PORT), '--report', str(EVIDENCE / 'server.json')],
                                       stdout=self.server_log, stderr=subprocess.STDOUT)
        deadline = time.monotonic() + 20
        while True:
            assert self.server.poll() is None, 'Fixture server exited before ready'
            try:
                self.status()
                break
            except urllib.error.URLError:
                if time.monotonic() >= deadline:
                    raise
                time.sleep(0.2)
        self.shell('iptables', '-I', 'OUTPUT', '1', '-o', 'lo', '-j', 'ACCEPT')
        self.shell('ip6tables', '-I', 'OUTPUT', '1', '-o', 'lo', '-j', 'ACCEPT')
        self.command(['adb', 'reverse', 'tcp:' + str(PORT), 'tcp:' + str(PORT)])
        self.shell('iptables', '-C', 'OUTPUT', '-j', 'REJECT')
        self.shell('ip6tables', '-C', 'OUTPUT', '-j', 'REJECT')
        (EVIDENCE / 'functional-egress.txt').write_text(self.shell('iptables-save') + self.shell('ip6tables-save'), encoding='utf-8')
        config = self.certificates / (PACKAGE + '-geckoview-config.yaml')
        config.write_text('prefs:\n  network.dns.localDomains: "' + HOST + '"\n  network.trr.mode: 5\n', encoding='utf-8')
        target = '/data/local/tmp/' + config.name
        self.command(['adb', 'push', str(config), target])
        self.shell('chmod', '644', target)
        self.shell('am', 'set-debug-app', '--persistent', PACKAGE)
        assert self.shell('settings', 'get', 'global', 'debug_app').strip() == PACKAGE

    def control(self, action, account=None, tab=0, deadline=None):
        assert action in ('prepare', 'open', 'record')
        shared_deadline = deadline is not None
        if deadline is None:
            deadline = time.monotonic() + 60
        def budget(cap=None):
            remaining = deadline-time.monotonic()
            if shared_deadline:
                if remaining <= 0:
                    raise TimeoutError('Cold process exit/open exhausted its original 60-second deadline')
                return min(cap, remaining) if cap is not None else remaining
            return max(0.1, min(cap, remaining) if cap is not None else remaining)
        def private_result(relative):
            path = FILES + relative
            text = self.command(['adb', 'shell', 'cat', path],
                                timeout=budget(10), check=False)
            if re.fullmatch(r'cat: ' + re.escape(path) + r': No such file or directory\s*', text):
                return None
            if not text.lstrip().startswith('{'):
                raise RuntimeError('Private JSON read failed: ' + text)
            return json.loads(text)

        previous_sample = -1
        started_device_ms = -1
        if action == 'record':
            # Android wall time may differ from the runner. Pair the device's start-second
            # bound with a strict increase over the pre-command probe, so stale JSON cannot pass.
            started_device_ms = int(self.shell('date', '+%s', timeout=budget(10)).strip()) * 1000
            previous = private_result('environment-record-probe.json')
            if previous is not None:
                previous_sample = previous['sampledAt']
        args = ['am', 'start', '-n', PACKAGE + '/com.forcemind.chatgpt.profiles.EnvironmentAcceptanceActivity', '--es', 'action', action]
        if action != 'prepare':
            assert (account, tab) in PAGE
            args += ['--es', 'account', account, '--ei', 'tab', str(tab)]
        text = self.shell(*args, timeout=budget())
        assert not re.search(r'Error:|Exception|Permission Denial', text), text
        if action == 'prepare':
            self.wait('completed private acceptance manifest',
                      lambda: private_result('environment-acceptance.json'),
                      lambda value: isinstance(value, dict) and value.get('schema') == 1
                      and isinstance(value.get('accounts'), dict) and set(value['accounts']) == {'A', 'B', 'C'}
                      and isinstance(value.get('legacyAccounts'), list),
                      timeout=budget(), interval=0.3)
        elif action == 'record':
            result = self.wait('fresh native record probe',
                               lambda: private_result('environment-record-probe.json'),
                               lambda value: isinstance(value, dict)
                               and isinstance(value.get('sampledAt'), int)
                               and value['sampledAt'] >= started_device_ms
                               and value['sampledAt'] > previous_sample,
                               timeout=budget(), interval=0.3)
            assert result.get('schema') == 1, 'Invalid native record probe schema'
            assert result.get('complete') is True, 'Native record probe failed: ' + str(result.get('errorClass'))
        # open is accepted only by its existing real server/preview conditions in the caller.

    def prepare_profiles(self):
        self.control('prepare')
        self.manifest = self.json_file('environment-acceptance.json')
        assert self.manifest['schema'] == 1 and set(self.manifest['accounts']) == {'A', 'B', 'C'}
        accounts = self.manifest['accounts']
        assert len(self.manifest['legacyAccounts']) == 1
        assert self.manifest['legacyAccounts'][0]['storageId'] == self.upgrade['seed']['storageId']
        assert accounts['A']['environmentSlot'] == accounts['C']['environmentSlot']
        assert accounts['A']['environmentSlot'] != accounts['B']['environmentSlot']
        assert {accounts[label]['environmentSlot'] for label in accounts} == {0, 1}
        assert len({accounts[label]['storageId'] for label in accounts}) == 3
        for label, account in accounts.items():
            assert UUID.fullmatch(account['id']) and UUID.fullmatch(account['storageId'])
            assert len(account['tabs']) == (2 if label == 'A' else 1)
            for index, tab in enumerate(account['tabs']):
                assert UUID.fullmatch(tab['id']) and tab['url'] == 'https://' + HOST + ':' + str(PORT) + PAGE[(label, index)]
        native._write_json(EVIDENCE / 'acceptance-manifest.json', self.manifest)
        profile_root = FILES + 'environment-profiles'
        self.shell('mkdir', '-p', profile_root)
        for slot in (0, 1):
            profile = self.certificates / ('profile-' + str(slot))
            profile.mkdir(mode=0o700)
            self.command(['certutil', '-N', '-d', 'sql:' + str(profile), '--empty-password'])
            self.command(['certutil', '-A', '-d', 'sql:' + str(profile), '-n', 'OwnedEnvCA', '-t', 'C,,', '-i', str(self.certificates / 'ca.crt')])
            destination = profile_root + '/' + str(slot)
            self.shell('mkdir', '-p', destination)
            for name in ('cert9.db', 'key4.db'):
                assert (profile / name).is_file()
                self.command(['adb', 'push', str(profile / name), destination + '/' + name])
                self.shell('chmod', '600', destination + '/' + name)
            self.shell('chown', '-R', str(self.upgrade['uid']) + ':' + str(self.upgrade['uid']), destination)
            self.shell('chmod', '700', destination)
        self.shell('chown', str(self.upgrade['uid']) + ':' + str(self.upgrade['uid']), profile_root)
        self.shell('chmod', '700', profile_root)
        self.shell('readlink','-f',profile_root)
        self.shell('ls','-ldZ',profile_root,profile_root+'/0',profile_root+'/1')

    def ui(self):
        self.shell('uiautomator', 'dump', '/data/local/tmp/environment-ui.xml', timeout=25)
        xml = self.shell('cat', '/data/local/tmp/environment-ui.xml')
        self.sequence += 1
        (EVIDENCE / f'{self.sequence:04d}-ui.xml').write_text(xml, encoding='utf-8')
        root = ET.fromstring(xml)
        parents = {child: parent for parent in root.iter() for child in parent}
        elements = list(root.iter('node'))
        indices = {node: index for index, node in enumerate(elements)}
        nodes = []
        for node in elements:
            bounds = re.fullmatch(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', node.attrib.get('bounds', ''))
            if bounds:
                left, top, right, bottom = map(int, bounds.groups())
                if right > left and bottom > top:
                    ancestor = node
                    while ancestor is not None and ancestor.attrib.get('class') != 'android.webkit.WebView':
                        ancestor = parents.get(ancestor)
                    nodes.append(dict(node.attrib, rectangle=[left, top, right, bottom],
                                      nodeIndex=indices[node], parentIndex=indices.get(parents.get(node)),
                                      webViewIndex=indices[ancestor] if ancestor is not None else None))
        return nodes

    def editor(self, account, tab, document):
        path = PAGE[(account, tab)]
        title = '合成对话 ' + path
        def visible(node):
            return node.get('package') == PACKAGE and node.get('enabled') == 'true' and node.get('visible-to-user', 'true') == 'true'
        def choose(nodes):
            pages = [node for node in nodes if node.get('class') == 'android.webkit.WebView'
                     and node.get('text') == title and visible(node)]
            if not pages:
                return None
            assert len(pages) == 1, 'Ambiguous native synthetic page title: ' + title
            page = pages[0]
            left, top, right, bottom = page['rectangle']
            editable = []
            for node in nodes:
                if node['webViewIndex'] != page['nodeIndex'] or not visible(node):
                    continue
                if node.get('class') != 'android.widget.EditText' and node.get('editable') != 'true':
                    continue
                if node.get('focusable') != 'true' or node.get('clickable') != 'true' or node.get('password') != 'false':
                    continue
                x1, y1, x2, y2 = node['rectangle']
                if left <= x1 < x2 <= right and top <= y1 < y2 <= bottom:
                    editable.append(node)
            if not editable:
                return None
            assert len(editable) == 1, 'Multiple visible editable descendants in ' + title
            target = editable[0]
            assert target.get('resource-id') == 'prompt-textarea', 'Editable node is not the owned fixture composer'
            return target
        nodes = self.wait('unique native composer in ' + title, self.ui,
                          lambda value: choose(value) is not None, timeout=50, interval=0.3)
        target = choose(nodes)
        native._write_json(EVIDENCE / ('editor-target-' + str(self.sequence) + '.json'),
                           {'path': path, 'exactNativeTitle': title, 'serverDocument': document,
                            'nativeNode': target, 'uniqueEnabledVisibleEditableDescendant': True})
        return target

    @staticmethod
    def labelled(nodes, label):
        candidates = [node for node in nodes if label in node.get('content-desc', '') or label == node.get('text', '')]
        candidates.sort(key=lambda node: (not node.get('class', '').endswith('EditText'),
                                         (node['rectangle'][2]-node['rectangle'][0])*(node['rectangle'][3]-node['rectangle'][1])))
        return candidates[0] if candidates else None

    def node(self, label, timeout=50):
        nodes = self.wait('native accessibility label ' + label, self.ui,
                          lambda value: self.labelled(value, label) is not None, timeout=timeout, interval=0.3)
        return self.labelled(nodes, label)

    def click(self, label):
        left, top, right, bottom = self.node(label)['rectangle']
        self.shell('input', 'tap', (left + right)//2, (top + bottom)//2)

    @staticmethod
    def latest(status, path, excluded=None):
        documents = status['pages'][path]
        candidates = [value for key, value in documents.items() if key != excluded]
        return max(candidates, key=lambda value: value['receivedAt']) if candidates else None

    def assert_environment(self, value, account):
        language, zone, offset = ('en-US', 'America/New_York', 300) if account == 'B' else ('zh-CN', 'Asia/Shanghai', -480)
        assert value['account'] == account and UUID.fullmatch(value['document'])
        assert value['language'].lower().startswith(language.lower())
        assert value['languages'][0].lower().startswith(language.lower())
        assert value['timeZone'] == zone and value['offsetMinutes'] == offset
        worker = value['worker']
        assert worker and not value['workerError']
        assert worker['language'].lower().startswith(language.lower())
        assert worker['languages'][0].lower().startswith(language.lower())
        assert worker['timeZone'] == zone and worker['offsetMinutes'] == offset
        assert value['acceptLanguage'].split(',')[0].split(';')[0].strip().lower() == language.lower()
        assert value['localStorage'] == value['indexedDB'] == value['envLogin'] == 'environment-' + account
        assert not value['localError'] and not value['idbError']
        assert 'env_login=' not in value['visibleCookies'], 'Login fixture cookie must remain HttpOnly'
        assert value['sendClicks'] == 0

    def page(self, account, tab=0, excluded=None, minimum_received=0):
        return self.observed_path(PAGE[(account, tab)], account, excluded, minimum_received)

    def observed_path(self, path, account='A', excluded=None, minimum_received=0):
        assert path in set(PAGE.values()) | {'/c/navigation'}
        def ready(status):
            value = self.latest(status, path, excluded)
            return value and value.get('receivedAt', 0) >= minimum_received and value.get('worker') and value.get('indexedDB') is not None and value.get('envLogin') is not None
        status = self.wait('page observation ' + path, self.status, ready)
        value = self.latest(status, path, excluded)
        self.assert_environment(value, account)
        expected_language = 'en-US' if account == 'B' else 'zh-CN'
        assert status['requests'][path]['acceptLanguage'].split(',')[0].split(';')[0].strip().lower() == expected_language.lower()
        return status, value

    def open(self, account, tab=0):
        started = int(time.time() * 1000)
        self.control('open', account, tab)
        return self.page(account, tab, minimum_received=started)

    @staticmethod
    def ime_shown(dump):
        # Android 9/15's current service state is authoritative. The IME's cached
        # mIsInputViewShown can remain true with mWindowVisible=false after hiding.
        values = re.findall(r'\bmInputShown\s*=\s*(true|false)\b', dump)
        assert len(values) == 1, 'Missing or ambiguous current system IME visibility'
        return values[0] == 'true'

    def native_action(self, label, enabled=True, description=False):
        def choose(nodes):
            key = 'content-desc' if description else 'text'
            targets = [node for node in nodes if node.get('package') == PACKAGE and node.get(key) == label]
            if not targets:
                return None
            assert len(targets) == 1, 'Ambiguous native action: ' + label
            by_index = {node['nodeIndex']: node for node in nodes}
            target = targets[0]
            while target.get('clickable') != 'true':
                target = by_index.get(target.get('parentIndex'))
                if target is None:
                    return None
            if target.get('visible-to-user', 'true') != 'true' or target.get('enabled') != str(enabled).lower():
                return None
            return target
        nodes = self.wait('native action ' + label + ' enabled=' + str(enabled), self.ui,
                          lambda value: choose(value) is not None, timeout=30, interval=0.3)
        return choose(nodes)

    def tap_native_action(self, label, description=False):
        left, top, right, bottom = self.native_action(label, description=description)['rectangle']
        self.shell('input', 'tap', (left+right)//2, (top+bottom)//2)

    def header_switch(self, account, tab):
        expected = self.manifest['accounts'][account]
        state = self.json_file('workspace.json')
        assert state['activeAccount'] == expected['id']
        owner = next(value for value in state['accounts'] if value['id'] == expected['id'])
        target_id = expected['tabs'][tab]['id']
        assert owner['activeTab'] != target_id
        source = next(value for value in owner['tabs'] if value['id'] == owner['activeTab'])
        target = next(value for value in owner['tabs'] if value['id'] == target_id)
        self.native_action('当前标签页：' + source['title'], description=True)
        before = self.status()
        started = int(time.time()*1000)
        self.tap_native_action('切换标签页：' + target['title'], description=True)
        status, page = self.page(account, tab, minimum_received=started)
        self.native_action('当前标签页：合成对话 ' + PAGE[(account, tab)], description=True)
        for path, documents in before['pages'].items():
            if documents:
                assert status['pageCounts'][path] == before['pageCounts'][path], 'Header switch reloaded ' + path
                assert set(status['pages'][path]) == set(documents), 'Header switch replaced a document'
                previous = self.latest(before, path)
                current = status['pages'][path][previous['document']]
                assert current['draft'] == current['modelDraft'] == previous['draft'] == previous['modelDraft']
        return status, page

    def more_navigation(self, can_back, can_forward, choose=None):
        self.tap_native_action('更多功能', description=True)
        back = self.native_action('后退', enabled=can_back)
        forward = self.native_action('前进', enabled=can_forward)
        if self.navigation_row is None:
            refresh = self.node('强制刷新')
            assert back['rectangle'][1] < refresh['rectangle'][1] and forward['rectangle'][1] < refresh['rectangle'][1]
            self.navigation_row = {'back': back['rectangle'], 'forward': forward['rectangle'], 'forceRefresh': refresh['rectangle']}
        if choose is None:
            self.tap_native_action('关闭弹层', description=True)
        else:
            assert choose in ('后退', '前进')
            assert can_back if choose == '后退' else can_forward
            self.tap_native_action(choose)

    def chrome_navigation(self, draft, current):
        assert self.http('/configure', {'path': '/c/a1', 'initialDraft': ''})['appliesTo'] == 'future documents only'
        self.more_navigation(False, False)
        refreshes = []
        for force in (False, True):
            before = self.status()
            old = self.latest(before, '/c/a1')
            assert old['draft'] == old['modelDraft'] == draft
            started = int(time.time()*1000)
            if force:
                self.tap_native_action('更多功能', description=True)
                self.tap_native_action('强制刷新')
            else:
                self.tap_native_action('刷新页面', description=True)
            def restored(status):
                page = self.latest(status, '/c/a1', old['document'])
                return (page and page.get('receivedAt', 0) >= started and page.get('draft') == draft
                        and page.get('modelDraft') == draft and page.get('worker')
                        and page.get('indexedDB') is not None and page.get('envLogin') is not None)
            status = self.wait('native ' + ('force refresh' if force else 'refresh') + ' preserves controlled draft', self.status, restored)
            page = self.latest(status, '/c/a1', old['document'])
            self.assert_environment(page, 'A')
            assert page['url'] == 'https://' + HOST + ':' + str(PORT) + '/c/a1'
            assert UUID.fullmatch(page['document']) and page['document'] != old['document']
            counts = dict(before['pageCounts']);counts['/c/a1'] += 1
            assert status['pageCounts'] == counts, 'Refresh must perform exactly one document GET'
            self.native_action('当前标签页：合成对话 /c/a1', description=True)
            refreshes.append({'force': force, 'beforeDocument': old['document'], 'afterDocument': page['document'], 'pageCounts': counts,
                              'draftAndModelPreserved': True, 'cookieAndStoragePreserved': True})
            current = page
        self.more_navigation(False, False)
        started = int(time.time()*1000)
        self.tap_native_action('进入合成下一页', description=True)
        status, navigation = self.observed_path('/c/navigation', minimum_received=started)
        assert navigation['url'] == 'https://' + HOST + ':' + str(PORT) + '/c/navigation'
        assert navigation['draft'] == navigation['modelDraft'] == ''
        assert status['pageCounts']['/c/navigation'] == 1
        self.native_action('当前标签页：合成对话 /c/navigation', description=True)
        started = int(time.time()*1000)
        self.more_navigation(True, False, '后退')
        self.native_action('当前标签页：合成对话 /c/a1', description=True)
        started = int(time.time()*1000)
        def back_ready(status):
            page = self.latest(status, '/c/a1')
            return page and page['receivedAt'] >= started and page['draft'] == page['modelDraft'] == draft
        back_status = self.wait('native history back restores original URL and local draft', self.status, back_ready)
        back = self.latest(back_status, '/c/a1')
        assert back['url'] == current['url']
        self.assert_environment(back, 'A')
        self.native_action('当前标签页：合成对话 /c/a1', description=True)
        started = int(time.time()*1000)
        self.more_navigation(False, True, '前进')
        self.native_action('当前标签页：合成对话 /c/navigation', description=True)
        started = int(time.time()*1000)
        forward_status, forward = self.observed_path('/c/navigation', minimum_received=started)
        assert forward['url'] == navigation['url'] and forward['draft'] == forward['modelDraft'] == ''
        self.native_action('当前标签页：合成对话 /c/navigation', description=True)
        self.more_navigation(True, False)
        proof = {'complete': True, 'engine': 'gecko', 'nativeHeaderTitleSwitch': True, 'switchNoReload': True,
                 'disabledEmptyHistory': True, 'backForward': True, 'normalAndForceRefresh': True,
                 'draftCookieStoragePreserved': True, 'screenshots': False, 'realServicesAccessed': False,
                 'realHeaderHotSwitch': True, 'headerHotSwitchNoReload': True, 'nativeNavigationFirstRowBounds': self.navigation_row,
                 'disabledHistoryWithoutEntries': True, 'nativeMoreFirstRowHistory': True,
                 'nativeRefreshDraftAndStoragePreserved': True, 'nativeForceRefreshDraftAndStoragePreserved': True,
                 'refreshes': refreshes, 'realHrefNavigation': True, 'nativeBack': True, 'nativeForward': True,
                 'historyUrlAndLocalDraftPreserved': True, 'historyAllowsBFCacheDocumentIdentity': True,
                 'backDocument': back['document'], 'forwardDocument': forward['document'],
                 'finalPageCounts': forward_status['pageCounts'], 'sendCount': forward_status['sendCount']}
        assert proof['sendCount'] == 0
        native._write_json(EVIDENCE / 'gecko-chrome-navigation.json', proof)
        return proof

    def focus_editor(self, account, tab, before):
        target = self.editor(account, tab, before['document'])
        left, top, right, bottom = target['rectangle']
        tapped = int(time.time() * 1000)
        self.shell('input', 'tap', (left + right)//2, (top + bottom)//2)
        path = PAGE[(account, tab)]
        def focused(status):
            page = self.latest(status, path)
            assert page['document'] == before['document'], 'Composer document changed before typing'
            assert page['draft'] == page['modelDraft'] == '', 'Composer changed before native input'
            return page['receivedAt'] >= tapped and page['focused'] is True and page['visibility'] == 'visible'
        self.wait('same-document empty composer has actual DOM focus', self.status, focused, timeout=20)
        stable = {'signature': None, 'since': None, 'firstReceivedAt': None}
        def sample():
            status = self.status()
            assert focused(status), 'Composer lost focus before native typing'
            page = self.latest(status, path)
            dump = self.shell('dumpsys', 'input_method')
            lines = dump.splitlines()
            sections = []
            for index, line in enumerate(lines):
                marker = re.match(r'^(\s*)(?:mCurAttribute|mCurEditorInfo|mCurrentEditorInfo|mCurrentTextBoxAttribute)\s*[:=]', line)
                if marker:
                    block = [line]
                    for following in lines[index+1:]:
                        if re.match(r'^\s{0,' + str(len(marker[1])) + r'}m[A-Z]\w*\s*[:=]', following):
                            break
                        block.append(following)
                    sections.append('\n'.join(block))
            owned = [section for section in sections if re.search(r'\bpackageName=' + re.escape(PACKAGE) + r'(?:\s|$)', section)]
            types = [int(value, 16) for section in owned for value in re.findall(r'\binputType=(?:0x)?([0-9a-fA-F]+)\b', section)]
            shown = self.ime_shown(dump)
            signature = [page.get('viewportWidth'), page.get('viewportHeight')] + [round(page['inputRect'][key]) for key in ('x', 'y', 'width', 'height')]
            return {'ownedTextEditor': any(value & 15 == 1 for value in types), 'inputTypes': types,
                    'imeShown': shown, 'document': page['document'], 'receivedAt': page['receivedAt'],
                    'viewportAndEditorGeometry': signature, 'draftStillEmpty': True, 'focused': True}
        def settled(value):
            signature = value['viewportAndEditorGeometry']
            if not value['ownedTextEditor'] or not value['imeShown']:
                stable.update(signature=None, since=None, firstReceivedAt=None)
                return False
            if signature != stable['signature']:
                stable.update(signature=signature, since=time.monotonic(), firstReceivedAt=value['receivedAt'])
                return False
            return time.monotonic()-stable['since'] >= 1.5 and value['receivedAt'] > stable['firstReceivedAt']
        ready = self.wait('owned native text connection and stable IME layout', sample, settled, timeout=30, interval=0.4)
        native._write_json(EVIDENCE / ('focus-ready-' + str(self.sequence) + '.json'), ready)

    def native_ascii_batch(self, draft):
        assert re.fullmatch(r'[A-Za-z0-9-]{1,128}', draft)
        output = self.shell('su', '2000', 'env', 'CLASSPATH=/data/local/tmp/studio-poc-ascii-input.jar',
                            'app_process', '/system/bin', 'com.forcemind.chatgpt.pocinput.AsciiInput', draft)
        receipt = json.loads(output.strip())
        native._write_json(EVIDENCE / ('ascii-keyboard-' + str(self.sequence) + '.json'), receipt)
        assert receipt['provider'] == 'ci-paced-ascii-keyboard' and receipt['complete'] is True
        assert receipt['api'] == self.api and receipt['uid'] == 2000 and receipt['qemu'] is True
        assert receipt['managerClass'] == ('android.hardware.input.InputManagerGlobal' if self.api == 35 else 'android.hardware.input.InputManager')
        assert receipt['injectionMode'] == 2 and receipt['paceMillis'] == 40
        assert receipt['textLength'] == len(draft) and receipt['textSHA256'] == hashlib.sha256(draft.encode('utf-8')).hexdigest()
        events = receipt['events']
        assert events and [event['index'] for event in events] == list(range(len(events)))
        assert all(event['accepted'] is True and event['waitForFinish'] is True and event['source'] == 257
                   and event['deviceId'] == -1 and event['repeatCount'] == 0 for event in events)
        down = {}
        reconstructed = []
        previous = None
        for event in events:
            assert event['action'] in (0, 1) and event['eventTime'] >= event['downTime']
            assert event['completedAt'] >= event['eventTime']
            if previous is not None:
                assert event['eventTime'] >= previous['completedAt'] + receipt['paceMillis']
            if event['action'] == 0:
                assert event['keyCode'] not in down
                assert event['downTime'] == event['eventTime']
                down[event['keyCode']] = event['downTime']
                if event['unicodeChar']:
                    reconstructed.append(chr(event['unicodeChar']))
            else:
                assert down.pop(event['keyCode']) == event['downTime']
            previous = event
        assert not down and ''.join(reconstructed) == draft
        self.keyboard_events.append(receipt)

    def type_draft(self, account, tab, draft):
        assert re.fullmatch(r'[A-Za-z0-9-]+', draft)
        _, before = self.page(account, tab)
        assert before['draft'] == before['modelDraft'] == ''
        self.focus_editor(account, tab, before)
        self.native_ascii_batch(draft)
        path = PAGE[(account, tab)]
        status = self.wait('trusted native composer input', self.status,
                           lambda value: (self.latest(value, path) or {}).get('draft') == draft
                           and self.latest(value, path).get('modelDraft') == draft
                           and self.latest(value, path).get('trustedInputCount', 0) > before['trustedInputCount'])
        result = self.latest(status, path)
        self.assert_environment(result, account)
        ime = self.shell('dumpsys', 'input_method')
        if self.ime_shown(ime):
            self.shell('input', 'keyevent', '4')
            self.wait('system IME hidden after typing', lambda: self.shell('dumpsys', 'input_method'),
                      lambda value: not self.ime_shown(value), timeout=20)
        return result

    def scroll(self):
        for _ in range(5):
            left, top, right, bottom = self.node('合成对话阅读区')['rectangle']
            x, start, end = (left+right)//2, top+(bottom-top)*4//5, top+(bottom-top)//5
            assert start-end > 60, 'Reading area too small for a real gesture'
            if self.api == 28:
                output = self.shell('su', '2000', 'env', 'CLASSPATH=/data/local/tmp/studio-poc-input.jar',
                                    'app_process', '/system/bin', 'com.forcemind.chatgpt.pocinput.TouchInput', x, start, x, end, '550')
                receipt = json.loads(output.strip())
                assert receipt['provider'] == 'ci-android9-shell-touchscreen'
                events = receipt['events']
                assert len(events) == 33 and events[0]['action'] == 0 and events[-1]['action'] == 1
                assert len({event['downTime'] for event in events}) == 1
                assert all(event['eventTime'] >= event['downTime'] for event in events)
                assert [event['eventTime'] for event in events] == sorted(event['eventTime'] for event in events)
                assert all(event['toolType'] == 1 and event['source'] == 4098 for event in events)
                assert all(event['action'] == 2 for event in events[1:-1])
                self.touch_events.append(receipt)
            else:
                self.shell('input', 'touchscreen', 'swipe', x, start, x, end, '550')
            time.sleep(0.8)
            value = self.latest(self.status(), '/c/a1')
            if value['scroll'] > 200:
                return value
        raise AssertionError('Real native gestures did not scroll the reading area beyond 200px')

    def rotate_no_reload(self, before, draft):
        ime = self.shell('dumpsys', 'input_method')
        if self.ime_shown(ime):
            self.shell('input', 'keyevent', '4')
            self.wait('system IME hidden before rotation', lambda: self.shell('dumpsys', 'input_method'),
                      lambda value: not self.ime_shown(value), timeout=20)
        counts = self.status()['pageCounts']
        self.shell('settings', 'put', 'system', 'accelerometer_rotation', '0')

        def observation(rotation, landscape):
            changed = int(time.time() * 1000)
            self.shell('settings', 'put', 'system', 'user_rotation', str(rotation))
            def oriented(status):
                page = self.latest(status, '/c/a1')
                if not page or page.get('receivedAt', 0) < changed:
                    return False
                width, height = page.get('viewportWidth', 0), page.get('viewportHeight', 0)
                return width > 0 and height > 0 and (width > height if landscape else height > width)
            status = self.wait('real viewport ' + ('landscape' if landscape else 'portrait'),
                               self.status, oriented, timeout=45)
            page = self.latest(status, '/c/a1')
            self.assert_environment(page, 'A')
            assert page['document'] == before['document'], 'Rotation replaced the live document'
            assert status['pageCounts'] == counts, 'Rotation reloaded a document'
            assert page['draft'] == page['modelDraft'] == draft, 'Rotation changed the composer'
            assert page['scroll'] > 200, 'Rotation lost the reading position'
            assert self.shell('settings', 'get', 'system', 'user_rotation').strip() == str(rotation)
            return page

        portrait_before = observation(0, False)
        landscape = observation(1, True)
        portrait_after = observation(0, False)
        assert landscape['viewportWidth'] > portrait_before['viewportWidth']
        assert landscape['viewportHeight'] < portrait_before['viewportHeight']
        assert portrait_after['viewportWidth'] == portrait_before['viewportWidth']
        assert portrait_after['viewportHeight'] == portrait_before['viewportHeight']
        def measured(page):
            return {key: page[key] for key in ('document', 'viewportWidth', 'viewportHeight',
                                               'scroll', 'receivedAt', 'trustedInputCount')}
        proof = {'complete': True, 'rotationNoReload': True, 'systemRotations': [0, 1, 0],
                 'actualViewportDirections': ['portrait', 'landscape', 'portrait'],
                 'before': measured(portrait_before), 'landscape': measured(landscape),
                 'after': measured(portrait_after), 'pageCounts': counts,
                 'draftSHA256': hashlib.sha256(draft.encode('utf-8')).hexdigest(),
                 'domAndModelPreserved': True, 'readingScrollRemainedAbove200': True}
        native._write_json(EVIDENCE / 'rotation.json', proof)
        return proof, portrait_after

    def record(self, draft, minimum_scroll):
        digest = hashlib.sha256(draft.encode('utf-8')).hexdigest()
        def sample():
            self.control('record', 'A', 0)
            return self.json_file('environment-record-probe.json')
        def saved(value):
            return value.get('complete') is True and value.get('account') == 'A' and value.get('tab') == 0 and value.get('recordPresent') is True and value.get('documentPresent') is True and value.get('nativeStatePresent') is True and value.get('draftSHA256') == digest and value.get('draftLength') == len(draft) and value.get('scroll', 0) >= minimum_scroll and bool(value.get('cipherSHA256'))
        probe = self.wait('native encrypted recovery checkpoint', sample, saved, timeout=60, interval=0.8)
        assert probe['previewLength'] > 0 and 0 <= probe['ratio'] <= 1 and probe['capturedAt'] > 0
        native._write_json(EVIDENCE / ('native-record-' + str(self.sequence) + '.json'), probe)
        return probe

    def cold_open_api35(self):
        assert self.api == 35, 'Process-exit preflight is limited to the failing API35 runner'
        started = time.monotonic()
        deadline = started + 60
        receipt = {'api': 35, 'packagePrefix': PACKAGE, 'complete': False,
                   'deadlineSeconds': 60, 'forceStopAttempts': 0, 'openAttempts': 0,
                   'before': None, 'after': None, 'exitObservations': [], 'processExitVerified': False}
        self.cold_process_exits.append(receipt)
        index = len(self.cold_process_exits)
        def remaining(cap=None):
            value = deadline-time.monotonic()
            if value <= 0:
                raise TimeoutError('API35 cold exit/open exceeded the original 60-second deadline')
            return min(value, cap) if cap is not None else value
        def processes():
            output = self.shell('ps', '-A', '-o', 'PID,NAME', timeout=remaining(10))
            lines = output.strip().splitlines()
            assert lines and re.fullmatch(r'\s*PID\s+NAME\s*', lines[0]), 'Unexpected ps PID,NAME header'
            targets = []
            for line in lines[1:]:
                fields = line.strip().split(None, 1)
                assert len(fields) == 2 and fields[0].isdigit(), 'Unexpected ps PID,NAME row'
                if fields[1] == PACKAGE or fields[1].startswith(PACKAGE + ':'):
                    targets.append({'pid': int(fields[0]), 'name': fields[1]})
            return sorted(targets, key=lambda value: value['pid'])
        try:
            for name in ('meminfo', 'loadavg'):
                (EVIDENCE / ('cold-' + str(index) + '-host-' + name + '.txt')).write_text(
                    Path('/proc/' + name).read_text(encoding='utf-8'), encoding='utf-8')
            receipt['before'] = processes()
            assert receipt['before'], 'Cold restart must begin with live target processes'
            guest = self.shell('cat', '/proc/meminfo', timeout=remaining(4))
            (EVIDENCE / ('cold-' + str(index) + '-guest-meminfo.txt')).write_text(guest, encoding='utf-8')
            receipt['forceStopAttempts'] = 1
            self.shell('am', 'force-stop', PACKAGE, timeout=remaining())
            while True:
                after = processes()
                receipt['after'] = after
                receipt['exitObservations'].append({'elapsedMillis': int((time.monotonic()-started)*1000), 'processes': after})
                if not after:
                    break
                time.sleep(min(0.25, remaining()))
            receipt['processExitVerified'] = True
            receipt['remainingMillisBeforeOpen'] = int(remaining()*1000)
            receipt['openAttempts'] = 1
            self.control('open', 'A', 0, deadline=deadline)
            receipt['openCommandReturned'] = True
            receipt['complete'] = True
        except Exception as error:
            receipt['errorClass'] = type(error).__name__
            raise
        finally:
            receipt['elapsedMillis'] = int((time.monotonic()-started)*1000)
            native._write_json(EVIDENCE / 'cold-process-exit-receipts.json', self.cold_process_exits)

    def cold(self, old, draft, saved_scroll, conflict=False):
        before = self.status()['pageCounts']['/c/a1']
        if self.api == 35:
            self.cold_open_api35()
        else:
            self.shell('am', 'force-stop', PACKAGE)
            self.control('open', 'A', 0)
        self.node('本地恢复副本')
        assert self.status()['pageCounts']['/c/a1'] == before, 'Preview loaded a page before user consent'
        continued = int(time.time() * 1000)
        self.click('继续页面')
        status, page = self.page('A', 0, excluded=old['document'], minimum_received=continued)
        assert page['document'] != old['document']
        if conflict:
            status = self.wait('server draft remains before conflict choice', self.status,
                               lambda value: (self.latest(value, '/c/a1', old['document']) or {}).get('draft') == 'server-conflict'
                               and self.latest(value, '/c/a1', old['document']).get('modelDraft') == 'server-conflict')
            self.node('保留网页草稿')
            self.node('恢复本地草稿')
            unchanged = self.latest(self.status(), '/c/a1', old['document'])
            assert unchanged['draft'] == unchanged['modelDraft'] == 'server-conflict'
            self.click('恢复本地草稿')
        status = self.wait('controlled composer and scroll recovery', self.status,
                           lambda value: (self.latest(value, '/c/a1', old['document']) or {}).get('draft') == draft
                           and self.latest(value, '/c/a1', old['document']).get('modelDraft') == draft
                           and abs(self.latest(value, '/c/a1', old['document']).get('scroll', -10000) - saved_scroll) <= 80)
        page = self.latest(status, '/c/a1', old['document'])
        self.assert_environment(page, 'A')
        assert page['scroll'] > 200
        assert status['pageCounts']['/c/a1'] == before + 1, 'Cold recovery loaded duplicate documents'
        return page

    def download(self):
        self.click('保存合成文件')
        account = self.manifest['accounts']['A']['id']
        def index():
            result = self.command(['adb', 'shell', 'cat', FILES + 'assets/' + account + '/index.json'], check=False)
            return json.loads(result) if result.lstrip().startswith('{') else None
        ledger = self.wait('production asset import', index,
                           lambda value: value and any(record.get('status') == 'ready' and record.get('size') == 128 for record in value['records']), timeout=40)
        matches = [record for record in ledger['records'] if record.get('status') == 'ready' and record.get('size') == 128]
        assert len(matches) == 1
        asset = matches[0]
        assert UUID.fullmatch(asset['id']) and asset['accountId'] == account and asset['source'] == 'download'
        result = subprocess.run(['adb', 'exec-out', 'cat', FILES + 'assets/' + account + '/' + asset['id'] + '.bin'],
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=20)
        assert result.returncode == 0, result.stderr.decode('utf-8', errors='replace')
        digest = hashlib.sha256(result.stdout).hexdigest()
        status = self.status()
        assert len(result.stdout) == status['downloadBytes'] == 128
        assert digest == status['downloadSha256'] == asset['sha256']
        assert any(event['account'] == 'A' and event['envLogin'] == 'environment-A' and event['sha256'] == digest for event in status['downloads'])
        native._write_json(EVIDENCE / 'download-ledger.json', ledger)
        return {'complete': True, 'accountId': account, 'assetId': asset['id'], 'bytes': 128, 'sha256': digest,
                'nativeLinkClicked': True, 'productionAssetRouteUsed': True}

    def close(self):
        if self.server is not None:
            self.server.terminate()
            try:
                self.server.wait(timeout=10)
            except subprocess.TimeoutExpired:
                self.server.kill()
                self.server.wait(timeout=10)
        if self.server_log is not None:
            self.server_log.close()


def main():
    acceptance = None
    guarded = False
    try:
        # Native helper performs the fresh emulator, identity, signer and immutable-byte guards.
        upgrade = native.run_upgrade('synthetic')
        guarded = True
        acceptance = Acceptance(upgrade)
        acceptance.tls_server()
        acceptance.prepare_profiles()
        first_status, first = acceptance.open('A', 0)
        assert first_status['pageCounts']['/c/a1'] == 1
        draft = 'OwnedEnvA1-' + uuid.uuid4().hex
        acceptance.type_draft('A', 0, draft)
        scrolled = acceptance.scroll()
        assert scrolled['draft'] == scrolled['modelDraft'] == draft
        saved_scroll = scrolled['scroll']
        initial_record = acceptance.record(draft, saved_scroll - 2)
        download = acceptance.download()
        baseline = acceptance.status()
        original_document = acceptance.latest(baseline, '/c/a1')['document']
        acceptance.header_switch('A', 1)
        second_draft = 'OwnedEnvA2-' + uuid.uuid4().hex
        second = acceptance.type_draft('A', 1, second_draft)
        _, header_returned = acceptance.header_switch('A', 0)
        assert header_returned['document'] == original_document
        assert header_returned['draft'] == header_returned['modelDraft'] == draft
        assert abs(header_returned['scroll'] - saved_scroll) <= 2 and header_returned['scroll'] > 200
        _, b = acceptance.open('B', 0)
        _, c = acceptance.open('C', 0)
        switched_status, returned = acceptance.open('A', 0)
        assert returned['document'] == original_document
        assert returned['draft'] == returned['modelDraft'] == draft
        assert abs(returned['scroll'] - saved_scroll) <= 2 and returned['scroll'] > 200
        assert switched_status['pageCounts'] == {'/c/a1': 1, '/c/a2': 1, '/c/b1': 1, '/c/c1': 1, '/c/navigation': 0}
        for path in PAGE.values():
            assert len(switched_status['pages'][path]) == 1
        assert second['localStorage'] == second['indexedDB'] == second['envLogin'] == 'environment-A'
        assert b['timeZone'] != returned['timeZone'] and c['timeZone'] == returned['timeZone']
        rotation, returned = acceptance.rotate_no_reload(returned, draft)
        saved_scroll = returned['scroll']
        checkpoint = acceptance.record(draft, saved_scroll - 2)
        restored = acceptance.cold(returned, draft, saved_scroll)
        restored_record = acceptance.record(draft, saved_scroll - 80)
        assert acceptance.http('/configure', {'path': '/c/a1', 'initialDraft': 'server-conflict'})['appliesTo'] == 'future documents only'
        conflict_restored = acceptance.cold(restored, draft, restored['scroll'], conflict=True)
        conflict_record = acceptance.record(draft, saved_scroll - 80)
        gecko_chrome = acceptance.chrome_navigation(draft, conflict_restored)
        final_status = acceptance.status()
        assert final_status['sendCount'] == 0 and final_status['rejectedEventCount'] == 0
        native._write_json(EVIDENCE / 'server-final.json', final_status)
        # Instrumentation can end app processes; it deliberately runs after all live-switch checks.
        legacy_chrome = native.verify_browser_chrome()
        assert legacy_chrome['complete'] is True
        legacy = native.verify_after_environment()
        assert legacy['complete'] is True and legacy['legacyAccountPreserved'] is True
        assert acceptance.status()['sendCount'] == 0
        proof = {'schema': 1, 'complete': True, 'api': upgrade['api'], 'target': 'synthetic',
                 'candidateScope': 'synthetic-x86-ci', 'originalHumanCandidateBytesInstalled': False,
                 'candidateInstalledSHA256': upgrade['candidateInstalledSHA256'], 'uid': upgrade['uid'],
                 'signerSHA256': upgrade['signerSHA256'], 'sourceSHA': os.environ.get('GITHUB_SHA'),
                 'legacyUpgrade': upgrade, 'manifest': acceptance.manifest,
                 'languageTimeZoneAndWorkerVerified': True, 'httpAcceptLanguageVerified': True,
                 'cookieLocalStorageIndexedDBAccountIsolation': True, 'sameEnvironmentDifferentStorageContexts': True,
                 'trustedNativeTextareaAndContenteditableInput': True, 'realNativeReadingScroll': True,
                 'liveSwitchKeptDocumentAndDraftAndScroll': True, 'switchedPageCounts': switched_status['pageCounts'],
                 'rotationNoReload': True, 'rotation': rotation,
                 'originalDocument': original_document, 'draftSHA256': hashlib.sha256(draft.encode()).hexdigest(),
                 'savedScroll': saved_scroll, 'initialRecovery': initial_record, 'switchRecovery': checkpoint,
                 'coldRecovery': {'document': restored['document'], 'scroll': restored['scroll'], 'native': restored_record},
                 'conflictRecovery': {'document': conflict_restored['document'], 'scroll': conflict_restored['scroll'], 'native': conflict_record,
                                      'webDraftPreservedUntilNativeChoice': True, 'nativeRestoreChosen': True},
                 'download': download, 'geckoChromeNavigation': gecko_chrome,
                 'legacyChromeNavigation': legacy_chrome, 'legacyAfterEnvironmentUse': legacy,
                 'fixedFingerReceipts': acceptance.touch_events, 'nativeAsciiKeyboardReceipts': acceptance.keyboard_events, 'sendCount': 0,
                 'onlyEmulatorLoopbackEgressAllowed': True, 'testCAInjectedOnlyIntoCiProfiles': True,
                 'screenshots': False, 'realServicesAccessed': False}
        if upgrade['api'] == 35:
            proof['coldProcessExitReceipts'] = acceptance.cold_process_exits
        native._write_json(EVIDENCE / 'functional.json', proof)
        print(json.dumps(proof, indent=2), flush=True)
        return proof
    except Exception:
        native._record_failure()
        if acceptance is not None:
            try:
                acceptance.ui()
                acceptance.shell('dumpsys','activity','activities',timeout=20)
            except Exception:
                (EVIDENCE/'failure-ui-inspection-error.txt').write_text(traceback.format_exc(),encoding='utf-8')
        raise
    finally:
        if guarded:
            native._logcat('functional')
        if acceptance is not None:
            original_exception = sys.exc_info()[0] is not None
            try:
                acceptance.close()
            except Exception:
                (EVIDENCE / 'server-cleanup-error.txt').write_text(traceback.format_exc(), encoding='utf-8')
                if not original_exception:
                    raise


if __name__ == '__main__':
    main()
    if os.environ.get('STUDIO_NAVIGATION_TIMING_CASES') == 'true':
        native.verify_navigation_timing_cases()
