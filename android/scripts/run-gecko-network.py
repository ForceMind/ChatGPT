#!/usr/bin/env python3
"""Fresh CI Gecko/VLESS route acceptance. Real picker, native clicks, independent origin bytes."""
import argparse
import hashlib
import http.client
import importlib.util
import json
import os
from pathlib import Path
import re
import selectors
import ssl
import socket
import subprocess
import time
import traceback
import urllib.error
import urllib.parse
import urllib.request
import uuid

import environment_upgrade as native
from android_version import VERSION_CODE, validate_receipt

PACKAGE = native.PACKAGE
HOST = 'network-fixture.test'
UPLOAD = b'owned-gecko-network-upload\n' * 1024
DOWNLOAD = b'D' * 262144
OPERATION = re.compile(r'(?:raw|tls|reality)-https-gecko-[a-f0-9]{32}')
PICKER_PACKAGES = ('com.android.documentsui', 'com.google.android.documentsui')


def source_module():
    spec = importlib.util.spec_from_file_location('owned_environment_acceptance', Path(__file__).with_name('run-environment-client.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


common = source_module()


class NetworkAcceptance(common.Acceptance):
    def __init__(self, api, uid, fixture, output, transport):
        common.EVIDENCE = output
        common.HOST = HOST
        native.EVIDENCE = output
        super().__init__({'api': api, 'uid': uid})
        self.fixture = fixture
        self.transport = transport
        self.output = output
        self.ssl = ssl.create_default_context(cafile=str(fixture.parent / 'ca.pem'))
        self.pending_gates = set()
        self.business = []
        self.probe_operations = []
        self.failed_operations = []
        self.network_observations = []
        self._input_deadline = None
        self.input_prefix_receipts = []
        self.input_stream_receipts = []

    def input_timeout(self, timeout):
        if self._input_deadline is None:
            return timeout
        remaining = self._input_deadline-time.monotonic()
        if remaining <= 0:
            raise TimeoutError('Network native input exceeded its original 75-second deadline')
        return min(timeout, remaining)

    def command(self, args, timeout=60, check=True):
        result = super().command(args, timeout=self.input_timeout(timeout), check=check)
        self.input_timeout(timeout)
        return result

    def wait(self, description, sample, condition, timeout=75, interval=0.6):
        if self._input_deadline is None:
            return super().wait(description, sample, condition, timeout=timeout, interval=interval)
        original = self._input_deadline
        self._input_deadline = min(original, time.monotonic()+timeout)
        last = None
        try:
            while True:
                self.input_timeout(timeout)
                last = sample()
                self.input_timeout(timeout)
                if condition(last):
                    return last
                time.sleep(self.input_timeout(interval))
        except TimeoutError:
            native._write_json(self.output/'last-timeout.json', {'description':description,'last':last})
            raise
        finally:
            self._input_deadline = original

    def type_draft(self, account, tab, draft):
        assert self._input_deadline is None
        self._input_deadline = time.monotonic()+75
        try:
            return super().type_draft(account, tab, draft)
        finally:
            self._input_deadline = None

    def stream_json(self, stream):
        while b'\n' not in stream['buffer']:
            ready=stream['selector'].select(self.input_timeout(75))
            if not ready:raise TimeoutError('Native stream receipt exceeded the shared input deadline')
            chunk=os.read(stream['process'].stdout.fileno(),65536)
            if not chunk:raise AssertionError('Native input stream ended before its receipt')
            stream['stdout'].write(chunk);stream['stdout'].flush();stream['buffer']+=chunk
            assert len(stream['buffer'])<=1024*1024, 'Native stream line exceeded its bounded protocol'
        line,stream['buffer']=stream['buffer'].split(b'\n',1)
        self.input_timeout(75)
        return json.loads(line.decode('utf-8'))

    def stream_state(self, value, phase, dispatch, pid=None):
        assert value['provider']=='ci-native-ascii-stream' and value['phase']==phase
        assert value['api']==self.api and value['uid']==2000 and value['dispatch']==dispatch
        assert isinstance(value['pid'],int) and value['pid']>0 and (pid is None or value['pid']==pid)
        return value['pid']

    def start_input_stream(self):
        self.sequence+=1;label=str(self.sequence)+'-native-ascii-stream'
        args=['adb','shell','-T','su','2000','env','CLASSPATH=/data/local/tmp/studio-poc-ascii-input.jar',
              'app_process','/system/bin','com.forcemind.chatgpt.pocinput.AsciiInputStream']
        proof={'command':args,'complete':False,'dispatches':[],'devicePID':None,'exitCode':None}
        self.input_stream_receipts.append(proof)
        stream={'proof':proof,'path':self.output/(label+'.json'),'buffer':b'',
                'stdout':(self.output/(label+'-stdout.txt')).open('wb'),
                'stderr':(self.output/(label+'-stderr.txt')).open('wb'),
                'selector':selectors.DefaultSelector(),'process':None}
        try:
            self.input_timeout(75)
            process=subprocess.Popen(args,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=stream['stderr'],bufsize=0)
            stream['process']=process;proof['hostPID']=process.pid
            stream['selector'].register(process.stdout,selectors.EVENT_READ)
            ready=self.stream_json(stream);proof['devicePID']=self.stream_state(ready,'ready',0)
            assert ready['qemu'] is True;proof['ready']=ready
            native._write_json(stream['path'],proof)
            return stream
        except BaseException as failure:
            proof['errorClass']=type(failure).__name__
            try:self.stop_input_stream(stream,False)
            except BaseException as cleanup:proof['cleanupErrorClass']=type(cleanup).__name__
            raise

    def dispatch_input_character(self, stream, character, index):
        self.input_timeout(75)
        process=stream['process'];assert process.poll() is None
        assert process.stdin.write((character+'\n').encode('ascii'))==2
        process.stdin.flush();self.input_timeout(75)
        dispatched=self.stream_json(stream);self.stream_state(dispatched,'dispatch',index,stream['proof']['devicePID'])
        assert dispatched['unicodeChar']==ord(character)
        proof={'dispatch':dispatched,'validated':False};stream['proof']['dispatches'].append(proof)
        receipt=self.stream_json(stream);self.sequence+=1
        name='ascii-keyboard-'+str(self.sequence)+'.json';native._write_json(self.output/name,receipt)
        proof['nativeReceipt']=name
        self.validate_stream_keyboard_receipt(receipt,character)
        done=self.stream_json(stream);self.stream_state(done,'done',index,stream['proof']['devicePID'])
        proof.update(done=done,validated=True);native._write_json(stream['path'],stream['proof'])
        return receipt

    def stop_input_stream(self, stream, success):
        process=stream['process'];proof=stream['proof']
        try:
            if process is not None:
                try:process.stdin.close()
                except OSError:
                    if success:raise
                if success:
                    closed=self.stream_json(stream);self.stream_state(closed,'closed',len(proof['dispatches']),proof['devicePID'])
                    assert closed['complete'] is True;proof['closed']=closed
                    assert process.wait(timeout=self.input_timeout(75))==0
                    self.input_timeout(75);proof['complete']=True
        finally:
            if process is not None:
                if process.poll() is None:
                    # Failure cleanup sends no keyboard events and cannot turn a failed deadline into success.
                    pid=proof.get('devicePID')
                    if pid is not None:
                        command=['adb','shell','su','2000','kill','-KILL',str(pid)]
                        proof['cleanupCommand']=command
                        try:
                            result=subprocess.run(command,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=1)
                            proof['cleanupExitCode']=result.returncode;proof['cleanupOutput']=result.stdout.decode('utf-8',errors='replace')
                        except subprocess.TimeoutExpired:proof['cleanupTimedOut']=True
                    process.kill()
                    try:process.wait(timeout=1)
                    except subprocess.TimeoutExpired:proof['hostExitUnconfirmed']=True
                proof['exitCode']=process.poll()
                # Keep every available original line, including a failed receipt and its stderr first error.
                while stream['selector'].select(0):
                    chunk=os.read(process.stdout.fileno(),65536)
                    if not chunk:break
                    stream['stdout'].write(chunk)
                process.stdout.close()
            stream['selector'].close();stream['stdout'].close();stream['stderr'].close()
            native._write_json(stream['path'],proof)

    def validate_stream_keyboard_receipt(self, receipt, draft):
        # Same native receipt assertions as the unchanged common batch driver.
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

    def native_ascii_batch(self, draft):
        # Network acceptance serializes native input only. It does not fix or qualify batch ordering.
        assert self._input_deadline is not None and re.fullmatch(r'[A-Za-z0-9-]{1,128}', draft)
        path = common.PAGE[('A',0)]
        baseline = self.latest(self.status(), path)
        assert baseline and baseline['draft'] == baseline['modelDraft'] == '' and baseline['focused'] is True
        document, route = baseline['document'], baseline['route']
        counts = (baseline['inputCount'], baseline['trustedInputCount'])
        receipts_before = len(self.keyboard_events)
        reconstructed = []
        stream=self.start_input_stream();failure=None
        try:
            for index, character in enumerate(draft):
                self.input_timeout(75)
                receipt = self.dispatch_input_character(stream,character,index)
                if index:
                    assert receipt['events'][0]['eventTime'] >= self.keyboard_events[-2]['events'][-1]['completedAt']+receipt['paceMillis'], 'Native character batches overlap'
                reconstructed.extend(chr(event['unicodeChar']) for event in receipt['events'] if event['action']==0 and event['unicodeChar'])
                prefix, previous = draft[:index+1], draft[:index]
                wanted = (counts[0]+index+1,counts[1]+index+1)
                def acknowledged(status):
                    row = self.latest(status,path)
                    assert row and row['document']==document and row['route']==route, 'Input document or route changed'
                    assert row['draft'] in (previous,prefix) and row['modelDraft'] in (previous,prefix), 'Illegal native input prefix'
                    assert wanted[0]-1 <= row['inputCount'] <= wanted[0], 'Unexpected input event count'
                    assert wanted[1]-1 <= row['trustedInputCount'] <= wanted[1], 'Unexpected trusted input event count'
                    return row['draft']==row['modelDraft']==prefix and (row['inputCount'],row['trustedInputCount'])==wanted
                proof = {'index':index,'expectedPrefix':prefix,'document':document,'route':route,
                         'keyboardReceiptIndex':len(self.keyboard_events)-1,'streamPID':stream['proof']['devicePID'],'streamDispatch':index,'complete':False}
                evidence = self.output/('native-prefix-'+str(index+1)+'.json')
                native._write_json(evidence,proof)
                try:
                    status = self.wait('exact trusted native prefix '+str(index+1),self.status,acknowledged)
                except Exception as prefix_failure:
                    proof['errorClass']=type(prefix_failure).__name__;native._write_json(evidence,proof)
                    raise
                row = self.latest(status,path)
                proof.update(complete=True,draft=row['draft'],modelDraft=row['modelDraft'],inputCount=row['inputCount'],
                             trustedInputCount=row['trustedInputCount'],receivedAt=row['receivedAt'],
                             elapsedMillis=int((75-self.input_timeout(75))*1000))
                self.input_prefix_receipts.append(proof)
                native._write_json(evidence,proof)
            assert len(self.keyboard_events)-receipts_before == len(draft)
            assert ''.join(reconstructed)==draft, 'Combined native Unicode receipt differs from complete draft'
            assert row['draft']==row['modelDraft']==draft and (row['inputCount'],row['trustedInputCount'])==(counts[0]+len(draft),counts[1]+len(draft))
            self.assert_environment(row,'A')
        except BaseException as error:
            failure=error;stream['proof']['errorClass']=type(error).__name__
            raise
        finally:
            try:self.stop_input_stream(stream,failure is None)
            except BaseException as cleanup:
                if failure is None:raise
                stream['proof']['cleanupErrorClass']=type(cleanup).__name__
                native._write_json(stream['path'],stream['proof'])


    def http(self, path, value=None):
        # TCP stays on the runner loopback; SNI and hostname verification use the
        # certificate's exact DNS SAN. A Host header alone never changes TLS SNI.
        assert path == '/status', 'Network observations use only the owned status endpoint'
        self.sequence += 1
        label = str(self.sequence) + '-https'
        connection = http.client.HTTPSConnection(HOST, 18480, timeout=self.input_timeout(8), context=self.ssl)
        try:
            with socket.create_connection(('127.0.0.1', 18480), timeout=self.input_timeout(8)) as raw:
                raw.settimeout(self.input_timeout(8))
                secured = self.ssl.wrap_socket(raw, server_hostname=HOST)
                connection.sock = secured
                secured.settimeout(self.input_timeout(8))
                connection.request('GET' if value is None else 'POST', path,
                                   body=None if value is None else json.dumps(value).encode('utf-8'),
                                   headers={'Host': HOST+':18480', 'Content-Type': 'application/json'})
                secured.settimeout(self.input_timeout(8))
                response = connection.getresponse()
                if self._input_deadline is None:
                    body = response.read()
                else:
                    chunks=[]
                    while True:
                        secured.settimeout(self.input_timeout(8))
                        chunk=response.read1(65536)
                        self.input_timeout(8)
                        if not chunk:break
                        chunks.append(chunk)
                        if response.isclosed():break
                    body=b''.join(chunks)
                (self.output / (label + '.json')).write_bytes(body)
                native._write_json(self.output / (label+'-response.json'),
                                   {'path':path,'status':response.status,'tcpHost':'127.0.0.1','tlsServerName':HOST,'certificateVerified':True})
                assert response.status == 200, (path, response.status)
                result=json.loads(body)
                self.input_timeout(8)
                return result
        finally:
            connection.close()

    def owned_http(self, path, value=None, pending=False):
        assert path.startswith(('/transfer-control/', '/gecko-network/control/'))
        request = urllib.request.Request('http://127.0.0.1:18080' + path,
            data=None if value is None else (b'' if value == {} else json.dumps(value).encode()),
            headers={'Host': HOST + ':18080', 'Content-Type': 'application/json'})
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        self.sequence += 1
        label = str(self.sequence) + '-host-control'
        try:
            with opener.open(request, timeout=8) as response:
                body, code = response.read(), response.status
        except urllib.error.HTTPError as error:
            body, code = error.read(), error.code
        (self.output / (label + '.json')).write_bytes(body)
        native._write_json(self.output / (label + '-response.json'), {'path': path, 'status': code})
        if pending and code == 404:
            return None
        assert code == 200, (path, code, body)
        return json.loads(body)

    def gate(self, operation, release=False, pending=False):
        assert OPERATION.fullmatch(operation)
        return self.owned_http('/transfer-control/' + operation + ('/release' if release else '/state'),
                               {} if release else None, pending=pending)

    def release(self, operation):
        value = self.gate(operation, release=True)
        assert value['released'] is True and value['attemptCount'] == value['opCount'] == 1
        self.pending_gates.discard(operation)
        return value

    def optional_json(self, relative):
        text = self.command(['adb', 'shell', 'cat', common.FILES + relative], check=False)
        if re.fullmatch(r'cat: ' + re.escape(common.FILES + relative) + r': No such file or directory\s*', text):
            return None
        return json.loads(text)

    def network(self, action, index=None, wait_idle=False):
        assert action in ('network-config', 'network-status', 'network-select', 'network-retry', 'network-stop')
        assert isinstance(wait_idle, bool) and (not wait_idle or action == 'network-select' and index is not None)
        deadline = time.monotonic() + 60
        previous = self.optional_json('gecko-network-status.json')
        stamp = previous['sampledAt'] if previous else -1
        command = ['am', 'start', '-n', PACKAGE + '/com.forcemind.chatgpt.profiles.EnvironmentAcceptanceActivity', '--es', 'action', action]
        if index is not None:
            assert action == 'network-select' and isinstance(index, int) and 0 <= index < 4
            command += ['--ei', 'index', str(index)]
        if wait_idle:
            command += ['--ez', 'waitIdle', 'true']
        result = self.shell(*command, timeout=max(0.1, deadline-time.monotonic()))
        assert not re.search(r'Error:|Exception|Permission Denial', result), result
        value = self.wait('CI route callback ' + action, lambda: self.optional_json('gecko-network-status.json'),
            lambda row: row and row.get('complete') is True and row.get('operation') == action
            and row.get('sampledAt', -1) > stamp, timeout=max(0.1, deadline-time.monotonic()), interval=0.3)
        assert value['schema'] == 1
        if action == 'network-select':
            assert value['waitIdleRequested'] is wait_idle and value['networkSelectCalls'] == 1
        for key in ('ok', 'state', 'selected', 'enabled', 'generation', 'coreRunning', 'port', 'busy', 'status', 'error'):
            assert key in value, 'Missing actual network state: ' + key
        assert 'vless://' not in json.dumps(value).lower(), 'Do not expose node URIs in route evidence'
        self.network_observations.append(value)
        return value

    def connected(self, value, index):
        assert value['ok'] is True and value['state'] == 'CONNECTED' and value['selected'] == index
        assert value['enabled'] is True and value['coreRunning'] is True and 0 < value['port'] < 65536

    def configure(self, manifest):
        mode = manifest['transports'][self.transport]
        nodes = [{'name': 'Owned A', 'uri': mode['nodeA']}, {'name': 'Owned B', 'uri': mode['nodeB']}]
        if self.transport == 'tls':
            parsed = urllib.parse.urlsplit(mode['nodeA'])
            query = dict(urllib.parse.parse_qsl(parsed.query));query['sni'] = 'wrong.network-fixture.test'
            wrong = urllib.parse.urlunsplit(parsed._replace(query=urllib.parse.urlencode(query)))
            nodes += [{'name': 'Owned wrong hostname', 'uri': wrong}, {'name': 'Owned untrusted CA', 'uri': manifest['tlsTest']['untrustedAuthorityNodeA']}]
        elif self.transport == 'reality':
            nodes += [{'name': 'Owned wrong key', 'uri': manifest['realityTest']['wrongPublicKeyNodeA']},
                      {'name': 'Owned wrong short ID', 'uri': manifest['realityTest']['wrongShortIdNodeA']}]
        config = self.certificates / 'gecko-network-config.json'
        native._write_json(config, {'schema': 1, 'nodes': nodes, 'selected': 0})
        self.command(['adb', 'push', str(config), common.FILES + 'gecko-network-config.json'])
        self.shell('chown', str(self.upgrade['uid']) + ':' + str(self.upgrade['uid']), common.FILES + 'gecko-network-config.json')
        self.shell('chmod', '600', common.FILES + 'gecko-network-config.json')
        result = self.network('network-config');self.connected(result, 0)
        return result, len(nodes)

    def prepare(self, ca):
        self.control('prepare')
        self.manifest = self.json_file('environment-acceptance.json')
        assert self.manifest['schema'] == 1 and set(self.manifest['accounts']) == {'A', 'B', 'C'}
        accounts = self.manifest['accounts']
        assert accounts['A']['environmentSlot'] == accounts['C']['environmentSlot'] != accounts['B']['environmentSlot']
        assert len({row['storageId'] for row in accounts.values()}) == 3
        for label, account in accounts.items():
            for index, tab in enumerate(account['tabs']):
                assert tab['url'] == 'https://' + HOST + ':18480' + common.PAGE[(label, index)]
        root = common.FILES + 'environment-profiles'
        self.shell('mkdir', '-p', root)
        for slot in sorted({row['environmentSlot'] for row in accounts.values()}):
            folder = self.certificates / ('profile-' + str(slot));folder.mkdir(mode=0o700)
            self.command(['certutil', '-N', '-d', 'sql:' + str(folder), '--empty-password'])
            self.command(['certutil', '-A', '-d', 'sql:' + str(folder), '-n', 'OwnedVlessCA', '-t', 'C,,', '-i', str(ca)])
            destination = root + '/' + str(slot);self.shell('mkdir', '-p', destination)
            for name in ('cert9.db', 'key4.db'):
                self.command(['adb', 'push', str(folder/name), destination+'/'+name]);self.shell('chmod', '600', destination+'/'+name)
            self.shell('chown', '-R', str(self.upgrade['uid'])+':'+str(self.upgrade['uid']), destination);self.shell('chmod', '700', destination)
        self.shell('chown', str(self.upgrade['uid'])+':'+str(self.upgrade['uid']), root);self.shell('chmod', '700', root)
        config = self.certificates / (PACKAGE+'-geckoview-config.yaml')
        config.write_text('prefs:\n  network.dns.localDomains: "network-fixture.test,files.network-fixture.test"\n  network.trr.mode: 5\n', encoding='utf-8')
        destination = '/data/local/tmp/'+config.name
        self.command(['adb', 'push', str(config), destination]);self.shell('chmod', '644', destination)
        self.shell('am', 'set-debug-app', '--persistent', PACKAGE)
        assert self.shell('settings', 'get', 'global', 'debug_app').strip() == PACKAGE

    def route_page(self, route, document=None, draft=None):
        def complete_observation(value):
            row = self.latest(value, '/c/a1')
            return (row and row.get('route') == route and row.get('worker')
                    and row.get('indexedDB') is not None and row.get('envLogin') is not None)
        status = self.wait('actual browser HTTP exit '+route, self.status, complete_observation)
        row = self.latest(status, '/c/a1');self.assert_environment(row, 'A')
        assert row['ciIdentity'] == 'A' and row['url'] == 'https://' + HOST + ':18480/c/a1'
        if document is not None:
            assert row['document'] == document and status['pageCounts']['/c/a1'] == 1
        if draft is not None:
            assert row['draft'] == row['modelDraft'] == draft
        return status, row

    def picker_file(self):
        def file_input(nodes):
            pages = [row for row in nodes if row.get('package') == PACKAGE
                     and row.get('class') == 'android.webkit.WebView'
                     and row.get('text') == '合成对话 /c/a1' and row.get('enabled') == 'true']
            if not pages:
                return None
            assert len(pages) == 1, 'Ambiguous owned page for real file input'
            page = pages[0];left, top, right, bottom = page['rectangle']
            candidates = [row for row in nodes if row.get('package') == PACKAGE
                          and row.get('class') == 'android.widget.Button'
                          and row.get('text', '').startswith('选择合成上传文件 ')
                          and row.get('enabled') == 'true' and row.get('clickable') == 'true'
                          and row.get('visible-to-user', 'true') == 'true'
                          and row.get('webViewIndex') == page['nodeIndex']
                          and left <= row['rectangle'][0] < row['rectangle'][2] <= right
                          and top <= row['rectangle'][1] < row['rectangle'][3] <= bottom]
            assert len(candidates) <= 1, 'Ambiguous real native file chooser button'
            return candidates[0] if candidates else None
        nodes = self.wait('visible enabled real native file input', self.ui,
                          lambda value: file_input(value) is not None, timeout=30, interval=0.3)
        target = file_input(nodes)
        native._write_json(self.output / ('file-input-target-' + str(self.sequence) + '.json'), target)
        self.tap_node(target)
        def system_nodes():
            return [row for row in self.ui() if row.get('package') in PICKER_PACKAGES and row.get('enabled') == 'true']
        def find(rows, label):
            by_index = {row['nodeIndex']: row for row in rows}
            drawer_ids = (':id/roots_list', ':id/drawer_roots')
            def in_drawer(row):
                while row is not None:
                    if row.get('resource-id', '').endswith(drawer_ids):
                        return True
                    row = by_index.get(row.get('parentIndex'))
                return False
            drawer_open = any(row.get('resource-id', '').endswith(drawer_ids) for row in rows)
            def api28_file_card(row):
                if self.api != 28 or label != 'gecko-upload.bin' or drawer_open or row.get('resource-id') != 'android:id/title':
                    return None
                def under_toolbar(node):
                    while node is not None:
                        if node.get('resource-id', '').endswith(':id/toolbar'):
                            return True
                        node = by_index.get(node.get('parentIndex'))
                    return False
                if not any(node.get('text') == 'Owned Gecko files' and under_toolbar(node) for node in rows):
                    return None
                card = row
                while card is not None:
                    parent = by_index.get(card.get('parentIndex'))
                    if parent is not None and parent.get('resource-id', '').endswith(':id/dir_list'):
                        left, top, right, bottom = parent['rectangle']
                        x1, y1, x2, y2 = card['rectangle']
                        tx1, ty1, tx2, ty2 = row['rectangle']
                        if (card.get('enabled') == 'true' and left <= x1 < x2 <= right
                                and top <= y1 < y2 <= bottom and x1 <= tx1 < tx2 <= x2
                                and y1 <= ty1 < ty2 <= y2):
                            return card
                        return None
                    card = parent
                return None
            targets = {}
            for row in rows:
                if row.get('text') != label and row.get('content-desc') != label:
                    continue
                if drawer_open and not in_drawer(row):
                    continue  # AX also exposes the provider tile behind an open drawer.
                owner = row
                while owner is not None and owner.get('clickable') != 'true':
                    owner = by_index.get(owner.get('parentIndex'))
                tap_target = owner
                if owner is None:
                    # Android9's real DocumentsUI handles its grid item touch in
                    # RecyclerView: this observed file card has no clickable AX ancestor.
                    owner = api28_file_card(row)
                    tap_target = row
                if owner is None or owner.get('enabled') != 'true' or owner.get('visible-to-user', 'true') != 'true':
                    continue
                # Labels/descendants of one clickable row, or duplicate AX nodes
                # for its identical physical bounds, represent the same tap target.
                key = (owner['package'], tuple(owner['rectangle']))
                targets[key] = tap_target
            assert len(targets) <= 1, 'Ambiguous real picker tap targets: '+label
            return next(iter(targets.values())) if targets else None
        rows = self.wait('actual Android document picker', system_nodes, lambda value: bool(value), timeout=40)
        root = find(rows, 'Owned Gecko files')
        if root is None:
            drawers = [row for row in rows if row.get('content-desc', '').lower() in ('show roots', 'open navigation drawer') or '显示根目录' in row.get('content-desc', '')]
            assert len(drawers) == 1, 'Actual provider navigation unavailable'
            self.tap_node(drawers[0])
            rows = self.wait('owned provider in real picker', system_nodes, lambda value: find(value, 'Owned Gecko files') is not None, timeout=30)
            root = find(rows, 'Owned Gecko files')
        self.tap_node(root)
        rows = self.wait('fixed file in owned provider', system_nodes, lambda value: find(value, 'gecko-upload.bin') is not None, timeout=30)
        self.tap_node(find(rows, 'gecko-upload.bin'))
        status = self.wait('actual Gecko File selection bytes', self.status,
            lambda value: ((self.latest(value, '/c/a1') or {}).get('network') or {}).get('selected') is not None)
        selected = self.latest(status, '/c/a1')['network']['selected']
        assert selected == {'name': 'gecko-upload.bin', 'size': len(UPLOAD), 'sha256': hashlib.sha256(UPLOAD).hexdigest()}
        return {'realSystemPicker': True, **selected}

    def tap_node(self, node):
        x1,y1,x2,y2 = node['rectangle'];self.shell('input', 'tap', (x1+x2)//2, (y1+y2)//2)

    def upload(self, route, busy_probe=False):
        before = self.latest(self.status(), '/c/a1')['network']
        old_count = before['uploadRequests']
        self.tap_native_action('上传合成文件')
        status = self.wait('single real multipart request', self.status,
            lambda value: (self.latest(value, '/c/a1')['network']['uploadRequests'] == old_count+1))
        operation = self.latest(status, '/c/a1')['network']['uploadOperation'];assert OPERATION.fullmatch(operation)
        self.pending_gates.add(operation)
        state = self.wait('origin received selected multipart bytes', lambda: self.gate(operation, pending=True),
            lambda value: value and value['bodyComplete'] is True)
        assert state['route'] == route and state['syntheticAccount'] == 'A' and state['commitCount'] == 1
        assert state['multipart'] is True and state['fileBytes'] == len(UPLOAD) and state['payloadSha256'] == hashlib.sha256(UPLOAD).hexdigest()
        if busy_probe:
            before_route = self.network('network-status');assert before_route['busy'] is True
            rejected = self.network('network-select', 1)
            assert rejected['ok'] is False and rejected['state'] == 'CONNECTED' and rejected['selected'] == 0
            assert rejected['generation'] == before_route['generation']
        self.release(operation)
        status = self.wait('browser received upload response', self.status,
            lambda value: ((self.latest(value, '/c/a1')['network'].get('upload') or {}).get('operation') == operation))
        result = self.latest(status, '/c/a1')['network']['upload']
        assert result['route'] == result['headerRoute'] == route and result['identity'] == 'A' and result['multipart'] is True
        assert result['fileBytes'] == len(UPLOAD) and result['fileSha256'] == hashlib.sha256(UPLOAD).hexdigest()
        final = self.wait('origin upload completion ACK', lambda: self.gate(operation), lambda value: value['completed'] is True, timeout=10)
        assert final['opCount'] == final['attemptCount'] == final['commitCount'] == 1
        assert final['released'] is True and final['completed'] is True and final['timedOut'] is False
        proof = {'kind': 'upload', **result, 'gate': final, 'realPickerFile': True}
        self.business.append(proof);return proof

    def ledger(self):
        return self.optional_json('assets/'+self.manifest['accounts']['A']['id']+'/index.json')

    def download(self, route):
        before = self.ledger();known = {row['id'] for row in before['records']} if before else set()
        previous = self.latest(self.status(), '/c/a1')['network']['downloadOperation']
        self.tap_native_action('保存合成文件', description=True)
        status = self.wait('single native download request', self.status,
            lambda value: (self.latest(value, '/c/a1')['network']['downloadOperation'] not in (None, previous)))
        operation = self.latest(status, '/c/a1')['network']['downloadOperation'];self.pending_gates.add(operation)
        state = self.wait('actual download prefix through selected exit', lambda: self.gate(operation, pending=True),
            lambda value: value and value['prefix'] is True)
        assert state['route'] == route and state['syntheticAccount'] == 'A' and state['payloadSha256'] == hashlib.sha256(DOWNLOAD).hexdigest()
        self.release(operation)
        ledger = self.wait('owned full asset publication', self.ledger,
            lambda value: value and any(row['id'] not in known and row['status'] == 'ready' for row in value['records']))
        added = [row for row in ledger['records'] if row['id'] not in known];assert len(added) == 1
        asset = added[0];account = self.manifest['accounts']['A']['id']
        assert asset['accountId'] == account and asset['source'] == 'download' and asset['size'] == len(DOWNLOAD)
        result = subprocess.run(['adb','exec-out','cat',common.FILES+'assets/'+account+'/'+asset['id']+'.bin'], stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=20)
        assert result.returncode == 0, result.stderr.decode(errors='replace')
        assert result.stdout == DOWNLOAD and asset['sha256'] == hashlib.sha256(result.stdout).hexdigest()
        final = self.gate(operation);assert final['completed'] is True and final['attemptCount'] == final['opCount'] == 1
        assert final['responseBytesWritten'] == len(DOWNLOAD) and final['route'] == route
        proof = {'kind':'download','operation':operation,'route':route,'accountId':account,'assetId':asset['id'],
                 'bytes':len(result.stdout),'sha256':asset['sha256'],'gate':final}
        self.business.append(proof);return proof

    def probe(self, route):
        previous = self.latest(self.status(), '/c/a1')['network'].get('probeOperation')
        self.tap_native_action('验证网页出口')
        def current_response(value):
            network = self.latest(value, '/c/a1')['network'];response = network.get('probe') or {}
            return response.get('operation') not in (None, previous) and response.get('operation') == network.get('probeOperation')
        status = self.wait('independent browser GET exit '+route, self.status, current_response)
        result = self.latest(status, '/c/a1')['network']['probe']
        assert result['route'] == result['headerRoute'] == route and result['identity'] == 'A'
        assert OPERATION.fullmatch(result['operation']);self.probe_operations.append(result)
        return result

    def blocked_attempt(self, kind):
        labels = {'probe': ('验证网页出口','网页已阻止 '), 'upload': ('上传合成文件','上传已阻止 '), 'download': ('保存合成文件','下载请求 ')}
        label, prefix = labels[kind]
        self.tap_native_action(label, description=kind=='download')
        rows = self.wait('actual browser '+kind+' blocked state', self.ui,
            lambda value: any(row.get('text','').startswith(prefix) for row in value), timeout=30)
        matches = {row['text'][len(prefix):] for row in rows if row.get('text','').startswith(prefix)}
        assert len(matches) == 1
        operation = next(iter(matches));assert OPERATION.fullmatch(operation);self.failed_operations.append(operation)
        assert self.gate(operation, pending=True) is None
        snapshot = self.status();assert not any(operation in row['path'] for row in snapshot['networkRequests'])
        return {'kind':kind,'operation':operation,'originReached':False,'nativeAttemptObserved':True,'nativeFailureObserved':kind!='download'}

    def negative(self, index):
        rejected = self.network('network-select', index, wait_idle=True)
        assert rejected['ok'] is False and rejected['state'] == 'FAILED' and rejected['coreRunning'] is False
        attempt = self.blocked_attempt('probe')
        recovered = self.network('network-select', 1, wait_idle=True);self.connected(recovered, 1)
        actual = self.probe('B')
        assert not any(attempt['operation'] in row['path'] for row in self.status()['networkRequests'])
        return {'index':index,'rejected':rejected,'blocked':attempt,'explicitRecovery':recovered,'probe':actual}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--transport', choices=('raw','tls','reality'), default=os.environ.get('STUDIO_GECKO_TRANSPORT'))
    parser.add_argument('--fixture', type=Path, default=Path(os.environ.get('STUDIO_GECKO_FIXTURE','android/network-poc/secure-fixture/fixture.json')))
    parser.add_argument('--apk', type=Path, default=Path(os.environ.get('STUDIO_GECKO_APK','android/app/build/environment-client-input/gecko-network-x86.apk')))
    parser.add_argument('--receipt', type=Path, default=Path(os.environ.get('STUDIO_GECKO_RECEIPT','android/app/build/environment-client-input/gecko-network-receipt.json')))
    parser.add_argument('--output', type=Path, default=Path(os.environ.get('STUDIO_GECKO_OUTPUT','android/app/build/gecko-network-evidence')))
    args = parser.parse_args();assert args.transport in ('raw','tls','reality')
    acceptance = None;guarded = False
    native.EVIDENCE=args.output;common.EVIDENCE=args.output
    try:
        api = native._guard();guarded = True
        devices = native._run(['adb','devices']).stdout.strip().splitlines()[1:]
        assert len(devices) == 1 and devices[0].split()[1:] == ['device'], 'Exactly one attached emulator required'
        args.output.mkdir(parents=True,exist_ok=True);native.EVIDENCE=args.output;common.EVIDENCE=args.output
        assert not native._shell('pm','list','packages',PACKAGE).strip(), 'Fresh package installation only'
        manifest = native._json(args.fixture);assert manifest['schemaVersion']==2 and manifest['ready'] is True
        assert native._json(args.fixture.parent/'ready.json')['ready'] is True
        signer = native._json(Path('docs/signing/android-network-beta.json'))['certificateSHA256']
        receipt = native._json(args.receipt)
        version_metadata=validate_receipt(receipt)
        assert receipt['ciOnly'] is True and receipt['package'] == PACKAGE and receipt['versionCode'] == VERSION_CODE
        assert receipt['signerSHA256'] == signer
        application_sha = os.environ.get('STUDIO_GECKO_APPLICATION_SHA', os.environ['GITHUB_SHA'])
        assert re.fullmatch(r'[a-f0-9]{40}', application_sha)
        assert receipt['sourceSHA'] == application_sha and receipt['file'] == args.apk.name
        assert receipt['bytes'] == args.apk.stat().st_size and receipt['sha256'] == native._sha(args.apk)
        apk = native._inspect_apk(args.apk,native._tools(),signer,PACKAGE,VERSION_CODE)
        assert apk['versionName']==version_metadata['versionName'] and apk['sha256'] == receipt['sha256']
        fingerprint = native._run(['openssl','x509','-in',str(args.fixture.parent/'ca.pem'),'-noout','-fingerprint','-sha256']).stdout
        match_ca = re.search(r'Fingerprint=([A-Fa-f0-9:]+)',fingerprint)
        assert match_ca and match_ca[1].replace(':','').lower() == manifest['certificateSha256']
        native._root_and_block()
        ports = [urllib.parse.urlsplit(manifest['transports'][args.transport][key]).port for key in ('nodeA','nodeB')]
        if args.transport=='tls':ports.append(19543)
        for port in ports:native._shell('iptables','-I','OUTPUT','1','-p','tcp','-d','10.0.2.2','--dport',str(port),'-j','ACCEPT')
        for tool in ('iptables','ip6tables'):native._shell(tool,'-I','OUTPUT','1','-o','lo','-j','ACCEPT')
        (args.output/'egress.txt').write_text(native._shell('iptables-save')+native._shell('ip6tables-save'))
        native._run(['adb','install',str(args.apk)]);installed=native._installed(apk['sha256'],'gecko-network')
        uid_text=native._shell('pm','list','packages','-U',PACKAGE).strip()
        match=re.fullmatch(r'package:'+re.escape(PACKAGE)+r' uid:(\d+)',uid_text);assert match
        uid=int(match[1]);assert native._shell('id','-u').strip()=='0'
        origin_tls=native._json(args.fixture.parent/'gecko-origin-tls.json')
        assert origin_tls['caUnchanged'] is True and origin_tls['leafIsCA'] is False
        assert origin_tls['caCertificateSha256']==manifest['certificateSha256']
        assert origin_tls['dnsSANs']==[HOST,'files.network-fixture.test'] and origin_tls['purpose']=='sslserver'
        native._write_json(args.output/'origin-tls.json',origin_tls)
        acceptance=NetworkAcceptance(api,uid,args.fixture,args.output,args.transport)
        assert acceptance.owned_http('/gecko-network/control/configure',{'transport':args.transport,'runId':str(uuid.uuid4())})['ok'] is True
        acceptance.prepare(args.fixture.parent/'ca.pem')
        connected,node_count=acceptance.configure(manifest)
        acceptance.control('open','A',0)
        _,first=acceptance.route_page('A')
        draft='OwnedNetworkDraft-'+uuid.uuid4().hex;acceptance.type_draft('A',0,draft)
        picker=acceptance.picker_file()
        upload_a=acceptance.upload('A',busy_probe=True);download_a=acceptance.download('A')
        switched=acceptance.network('network-select',1,wait_idle=True);acceptance.connected(switched,1)
        assert switched['waitIdleRequested'] is True and switched['networkSelectCalls']==1
        assert switched['generation']>connected['generation']
        probe_b=acceptance.probe('B');_,retained=acceptance.route_page('B',first['document'],draft)
        upload_b=acceptance.upload('B');download_b=acceptance.download('B')
        stop=acceptance.network('network-stop');assert stop['ok'] is True
        failed=acceptance.wait('unexpected core stop becomes fail-closed',lambda:acceptance.network('network-status'),
            lambda value:value['state']=='FAILED' and value['coreRunning'] is False,timeout=40)
        old_ledger=acceptance.ledger();failures=[acceptance.blocked_attempt(kind) for kind in ('probe','upload','download')]
        assert acceptance.ledger()==old_ledger
        recovered=acceptance.network('network-retry');acceptance.connected(recovered,1);recovery_probe=acceptance.probe('B')
        negatives=[acceptance.negative(index) for index in range(2,node_count)]
        _,last=acceptance.route_page('B',first['document'],draft);final=acceptance.status()
        for probe in acceptance.probe_operations:
            assert len([row for row in final['networkRequests'] if row.get('kind')=='browser-probe' and row.get('operation')==probe['operation']])==1, 'Browser GET replayed'
        for transaction in acceptance.business:
            terminal=acceptance.gate(transaction['operation'])
            assert terminal['attemptCount']==terminal['opCount']==1 and terminal['completed'] is True
        assert all(row['route'] in ('A','B') for row in final['networkRequests']), 'Origin observed direct fallback'
        assert not any(operation in row['path'] for operation in acceptance.failed_operations for row in final['networkRequests']), 'Blocked operation replayed after recovery'
        assert final['pageCounts']['/c/a1']==1 and last['document']==first['document']
        assert len(final['uploads'])==2 and {row['operation'] for row in final['uploads']}=={upload_a['operation'],upload_b['operation']}
        proof={'schema':1,'complete':True,'api':api,'transport':args.transport,'engine':'gecko','candidateAPK':apk,
               'candidateInstalledSHA256':installed,'ciReceipt':receipt,'versionMetadata':version_metadata,'applicationSourceSHA':application_sha,'originTLS':origin_tls,'uid':uid,'sourceSHA':os.environ.get('GITHUB_SHA'),
               'picker':picker,'routeA':{'upload':upload_a,'download':download_a},'routeB':{'probe':probe_b,'upload':upload_b,'download':download_b},
               'busySelectRejected':True,'idleSwitchNoReload':True,'idleSwitchWaited':switched['waitIdleRequested'],
               'idleSwitchSelectCalls':switched['networkSelectCalls'],'idleSwitchState':switched,'sameDocument':last['document'],
               'coreFailure':failed,'failedOperations':failures,'explicitRecovery':{'state':recovered,'probe':recovery_probe},'negatives':negatives,
               'noDirect':True,'noReplay':True,'draftCookieIdentityPreserved':True,'networkStates':acceptance.network_observations,
               'origin':final,'nativeAsciiKeyboardReceipts':acceptance.keyboard_events,
               'nativePrefixReceipts':acceptance.input_prefix_receipts,
               'nativeInputStreams':acceptance.input_stream_receipts,
               'nativeInputSchedule':{'mode':'single-jvm-native-character-then-exact-prefix-ack','totalDeadlineSeconds':75,'commonBatchHelperUnchanged':True,'claimsBatchOrderingFixed':False},'sendCount':final['sendCount'],
               'screenshots':False,'realServicesAccessed':False,'adbReverseOriginUsed':False}
        native._write_json(args.output/'gecko-network.json',proof);print(json.dumps(proof,indent=2),flush=True);return proof
    except Exception:
        native._record_failure();raise
    finally:
        if acceptance is not None:
            for operation in list(acceptance.pending_gates):
                try:acceptance.release(operation)
                except Exception:(args.output/'gate-cleanup-error.txt').write_text(traceback.format_exc())
        if guarded:native._logcat('gecko-network')


if __name__=='__main__':main()
