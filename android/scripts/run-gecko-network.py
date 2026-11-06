#!/usr/bin/env python3
"""Fresh CI Gecko/VLESS route acceptance. Real picker, native clicks, independent origin bytes."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import ssl
import subprocess
import time
import traceback
import urllib.error
import urllib.parse
import urllib.request
import uuid

import environment_upgrade as native

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

    def network(self, action, index=None):
        assert action in ('network-config', 'network-status', 'network-select', 'network-retry', 'network-stop')
        deadline = time.monotonic() + 60
        previous = self.optional_json('gecko-network-status.json')
        stamp = previous['sampledAt'] if previous else -1
        command = ['am', 'start', '-n', PACKAGE + '/com.forcemind.chatgpt.profiles.EnvironmentAcceptanceActivity', '--es', 'action', action]
        if index is not None:
            assert action == 'network-select' and isinstance(index, int) and 0 <= index < 4
            command += ['--ei', 'index', str(index)]
        result = self.shell(*command, timeout=max(0.1, deadline-time.monotonic()))
        assert not re.search(r'Error:|Exception|Permission Denial', result), result
        value = self.wait('CI route callback ' + action, lambda: self.optional_json('gecko-network-status.json'),
            lambda row: row and row.get('complete') is True and row.get('operation') == action
            and row.get('sampledAt', -1) > stamp, timeout=max(0.1, deadline-time.monotonic()), interval=0.3)
        assert value['schema'] == 1
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
        self.tap_native_action('选择合成上传文件', description=True)
        def system_nodes():
            return [row for row in self.ui() if row.get('package') in PICKER_PACKAGES and row.get('enabled') == 'true']
        def find(rows, label):
            found = [row for row in rows if row.get('text') == label or row.get('content-desc') == label]
            assert len(found) <= 1, 'Ambiguous real picker target: '+label
            return found[0] if found else None
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
        self.tap_native_action('上传合成文件', description=True)
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
        self.tap_native_action('验证网页出口', description=True)
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
        self.tap_native_action(label, description=True)
        rows = self.wait('actual browser '+kind+' blocked state', self.ui,
            lambda value: any(row.get('text','').startswith(prefix) for row in value), timeout=30)
        matches = {row['text'][len(prefix):] for row in rows if row.get('text','').startswith(prefix)}
        assert len(matches) == 1
        operation = next(iter(matches));assert OPERATION.fullmatch(operation);self.failed_operations.append(operation)
        assert self.gate(operation, pending=True) is None
        snapshot = self.status();assert not any(operation in row['path'] for row in snapshot['networkRequests'])
        return {'kind':kind,'operation':operation,'originReached':False,'nativeAttemptObserved':True,'nativeFailureObserved':kind!='download'}

    def negative(self, index):
        rejected = self.network('network-select', index)
        assert rejected['ok'] is False and rejected['state'] == 'FAILED' and rejected['coreRunning'] is False
        attempt = self.blocked_attempt('probe')
        recovered = self.network('network-select', 1);self.connected(recovered, 1)
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
        assert receipt['ciOnly'] is True and receipt['package'] == PACKAGE and receipt['versionCode'] == 3000031
        assert receipt['versionName'] == '3.0.0-alpha.24-beta.7' and receipt['signerSHA256'] == signer
        assert receipt['sourceSHA'] == os.environ['GITHUB_SHA'] and receipt['file'] == args.apk.name
        assert receipt['bytes'] == args.apk.stat().st_size and receipt['sha256'] == native._sha(args.apk)
        apk = native._inspect_apk(args.apk,native._tools(),signer,PACKAGE,3000031)
        assert apk['versionName']=='3.0.0-alpha.24-beta.7' and apk['sha256'] == receipt['sha256']
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
        acceptance=NetworkAcceptance(api,uid,args.fixture,args.output,args.transport)
        assert acceptance.owned_http('/gecko-network/control/configure',{'transport':args.transport,'runId':str(uuid.uuid4())})['ok'] is True
        acceptance.prepare(args.fixture.parent/'ca.pem')
        connected,node_count=acceptance.configure(manifest)
        acceptance.control('open','A',0)
        _,first=acceptance.route_page('A')
        draft='OwnedNetworkDraft-'+uuid.uuid4().hex;acceptance.type_draft('A',0,draft)
        picker=acceptance.picker_file()
        upload_a=acceptance.upload('A',busy_probe=True);download_a=acceptance.download('A')
        switched=acceptance.network('network-select',1);acceptance.connected(switched,1);assert switched['generation']>connected['generation']
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
               'candidateInstalledSHA256':installed,'ciReceipt':receipt,'uid':uid,'sourceSHA':os.environ.get('GITHUB_SHA'),
               'picker':picker,'routeA':{'upload':upload_a,'download':download_a},'routeB':{'probe':probe_b,'upload':upload_b,'download':download_b},
               'busySelectRejected':True,'idleSwitchNoReload':True,'sameDocument':last['document'],
               'coreFailure':failed,'failedOperations':failures,'explicitRecovery':{'state':recovered,'probe':recovery_probe},'negatives':negatives,
               'noDirect':True,'noReplay':True,'draftCookieIdentityPreserved':True,'networkStates':acceptance.network_observations,
               'origin':final,'nativeAsciiKeyboardReceipts':acceptance.keyboard_events,'sendCount':final['sendCount'],
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
