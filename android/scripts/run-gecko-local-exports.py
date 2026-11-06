#!/usr/bin/env python3
"""CI-only real Gecko Blob/data export acceptance over the owned RAW fixture."""
import argparse
import base64
import hashlib
import http.client
import importlib.util
import json
import os
from pathlib import Path
import re
import socket
import subprocess
import time
import traceback
import urllib.parse
import uuid

from android_version import VERSION_CODE, validate_receipt
from native_ax import NativeAXMixin

_spec = importlib.util.spec_from_file_location('owned_network_acceptance', Path(__file__).with_name('run-gecko-network.py'))
network = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(network)
native, common = network.native, network.common
PACKAGE, HOST, FILES = network.PACKAGE, network.HOST, common.FILES
LABELS = {'html': '导出 HTML', 'png': '导出 PNG', 'data': '导出 data 文本'}


class LocalExportsAcceptance(NativeAXMixin, network.NetworkAcceptance):
    def __init__(self, api, uid, fixture, output):
        super().__init__(api, uid, fixture, output, 'raw')
        self.export_operations = []
        self.main_pid = None

    def http(self, path, value=None):
        if path == '/status':
            return super().http(path, value)
        assert path == '/local-exports/manifest' and value is None
        self.sequence += 1
        connection = http.client.HTTPSConnection(HOST, 18480, timeout=8, context=self.ssl)
        try:
            with socket.create_connection(('127.0.0.1', 18480), timeout=8) as raw:
                raw.settimeout(8)
                connection.sock = self.ssl.wrap_socket(raw, server_hostname=HOST)
                connection.request('GET', path, headers={'Host': HOST + ':18480'})
                response = connection.getresponse(); body = response.read()
                (self.output / (str(self.sequence)+'-local-manifest.json')).write_bytes(body)
                assert response.status == 200
                return json.loads(body)
        finally:
            connection.close()

    def events(self):
        listing = self.shell('ls', '-1', FILES).splitlines()
        rows = []
        for name in sorted(listing):
            match = re.fullmatch(r'environment-local-exports-events-([1-9][0-9]*)\.json', name)
            if not match:
                continue
            value = self.json_file(name)
            assert value['schema'] == 1 and value['pid'] == int(match[1])
            previous = 0
            for event in value['events']:
                assert set(event) == {'sequence','pid','stage','operation','accountId','tabId','bytes','elapsedMillis'}
                assert event['pid'] == value['pid'] and event['sequence'] > previous
                previous = event['sequence']
                assert str(uuid.UUID(event['operation'])) == event['operation']
                assert re.fullmatch(r'[a-z0-9-]{1,40}', event['stage']) and event['bytes'] >= 0
                rows.append(event)
        native._write_json(self.output/'local-export-native-events.json', {'schema':1,'events':rows})
        return rows

    def hold(self, enabled):
        assert isinstance(enabled, bool)
        source = self.certificates/'environment-local-exports-control.json'
        native._write_json(source, {'schema':1,'hold':enabled})
        pending = FILES+'environment-local-exports-control.json.new'
        self.command(['adb','push',str(source),pending])
        self.shell('chown', str(self.upgrade['uid'])+':'+str(self.upgrade['uid']), pending)
        self.shell('chmod','600',pending)
        self.shell('mv','-f',pending,FILES+'environment-local-exports-control.json')

    def account_ledger(self, label='A'):
        return self.optional_json('assets/'+self.manifest['accounts'][label]['id']+'/index.json')

    def ids(self, label='A'):
        ledger = self.account_ledger(label)
        return {row['id'] for row in ledger['records']} if ledger else set()

    def fresh_operation(self, before, stage, expected_bytes=None):
        owner = self.manifest['accounts']['A']
        def choose(rows):
            matches = [row for row in rows if row['operation'] not in before and row['stage'] == stage
                       and row['accountId'] == owner['id'] and row['tabId'] == owner['tabs'][0]['id']]
            if not matches:
                return None
            operations = {row['operation'] for row in matches}
            assert len(operations) == 1, 'Multiple native local exports for one native click'
            if expected_bytes is not None:
                assert all(row['bytes'] == expected_bytes for row in matches)
            return matches[0]['operation']
        rows = self.wait('native local export '+stage, self.events, lambda value: choose(value) is not None,
                         timeout=15, interval=0.2)
        return choose(rows)

    def terminal(self, operation, success):
        stage = 'publish' if success else 'cancel'
        rows = self.wait('main broker '+stage, self.events,
            lambda value: any(row['operation'] == operation and row['pid'] == self.main_pid
                              and row['stage'] == stage for row in value)
            and any(row['operation'] == operation and row['pid'] != self.main_pid
                    and row['stage'] == ('complete' if success else 'cancel') for row in value),
            timeout=20, interval=0.2)
        selected = [row for row in rows if row['operation'] == operation]
        assert not any(row['stage'] == ('cancel' if success else 'publish') and row['pid'] == self.main_pid for row in selected)
        return selected

    def begin_export(self, kind, confirm=True):
        assert kind in LABELS
        before = {row['operation'] for row in self.events()}
        self.tap_native_action(LABELS[kind])
        self.wait('real save webpage sheet', self.ui,
                  lambda rows: any(row.get('package') == PACKAGE and row.get('text') == '保存网页文件' for row in rows),
                  timeout=30, interval=0.2)
        self.tap_native_action('保存到资产库' if confirm else '取消')
        return before

    def inspect_asset(self, kind, known):
        expected = self.definitions[kind]
        ledger = self.wait('local export ledger ready', self.account_ledger,
            lambda value: value and any(row['id'] not in known and row['status'] == 'ready' for row in value['records']),
            timeout=30, interval=0.2)
        added = [row for row in ledger['records'] if row['id'] not in known]
        assert len(added) == 1
        asset = added[0]; account = self.manifest['accounts']['A']['id']
        assert asset['accountId'] == account and asset['status'] == 'ready' and asset['source'] == 'import'
        assert asset['name'] == expected['name'] and asset['mime'] == expected['mime'] and asset['size'] == expected['bytes']
        command = ['adb','exec-out','cat',FILES+'assets/'+account+'/'+asset['id']+'.bin']
        result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=20)
        self.sequence += 1
        native._write_json(self.output/(str(self.sequence)+'-asset-read.json'),
                           {'command':command,'exitCode':result.returncode,'stderr':result.stderr.decode(errors='replace')})
        assert result.returncode == 0, result.stderr.decode(errors='replace')
        assert result.stdout == base64.b64decode(expected['base64'], validate=True)
        digest = hashlib.sha256(result.stdout).hexdigest()
        assert digest == asset['sha256'] == expected['sha256']
        return {'assetId':asset['id'],'accountId':account,'name':asset['name'],'mime':asset['mime'],
                'bytes':len(result.stdout),'sha256':digest,'originalStreamBytesEqual':True,
                'assetSource':asset['source'],'assetBase64':base64.b64encode(result.stdout).decode('ascii'),
                'streamReadExitCode':result.returncode}

    def save(self, kind, mode, during_hold=None):
        known, other = self.ids(), self.account_ledger('B')
        self.hold(True)
        before = self.begin_export(kind)
        self._input_deadline = time.monotonic()+18
        try:
            operation = self.fresh_operation(before, 'publication-held', self.definitions[kind]['bytes'])
            held = [row for row in self.events() if row['operation'] == operation]
            assert any(row['stage'] == 'copy-eof' and row['bytes'] == self.definitions[kind]['bytes'] for row in held)
            foreground_target = during_hold() if during_hold else None
            foreground = self.json_file('workspace.json')['activeAccount']
            self.hold(False)
        finally:
            self._input_deadline = None
        events = self.terminal(operation, True)
        assert any(row['stage'] == 'publication-released' for row in events)
        asset = self.inspect_asset(kind, known)
        after_other = self.account_ledger('B')
        assert after_other == other, 'Foreground account received another owner export'
        proof = dict(kind=kind, mode=mode, operation=operation, nativeEvents=events,
                     publicationForegroundAccountId=foreground,foregroundNativeTarget=foreground_target,
                     otherLedgerBefore=other,otherLedgerAfter=after_other,**asset)
        self.export_operations.append(proof)
        return proof

    def cancel_sheet(self):
        before_a, before_b = self.account_ledger(), self.account_ledger('B')
        before = self.begin_export('html', confirm=False)
        operation = self.fresh_operation(before, 'cancel')
        events = self.terminal(operation, False)
        assert not any(row['stage'] in ('confirm','copy-eof','publication-held','publish') for row in events)
        assert self.account_ledger() == before_a and self.account_ledger('B') == before_b
        return {'complete':True,'operation':operation,'nativeSheetCancelled':True,'noAssetPublished':True,'nativeEvents':events,
                'ledgersBefore':{'A':before_a,'B':before_b},
                'ledgersAfter':{'A':self.account_ledger(),'B':self.account_ledger('B')}}

    def close_source(self):
        known_a, known_b = self.account_ledger(), self.account_ledger('B')
        self.hold(True); before = self.begin_export('png')
        self._input_deadline = time.monotonic()+18
        try:
            operation = self.fresh_operation(before, 'publication-held', self.definitions['png']['bytes'])
            self.tap_native_action('更多功能', description=True)
            self.tap_native_action('标签管理')
            self.tap_native_action('关闭当前标签页')
            owner = self.manifest['accounts']['A']
            workspace = self.wait('source tab actually removed from workspace',lambda:self.json_file('workspace.json'),
                lambda value:owner['tabs'][0]['id'] not in {
                    tab['id'] for row in value['accounts'] if row['id'] == owner['id'] for tab in row['tabs']},
                timeout=15,interval=0.2)
            self.hold(False)
        finally:
            self._input_deadline = None
        events = self.terminal(operation, False)
        previous = {row['id']:row for row in known_a['records']} if known_a else {}
        def cancelled_ledger(value):
            if not value:
                return not previous
            records = {row['id']:row for row in value['records']}
            assert all(records.get(key) == row for key,row in previous.items())
            added = [row for key,row in records.items() if key not in previous]
            assert len(added) <= 1 and all(row['status'] in ('processing','failed') for row in added)
            return all(row['status'] == 'failed' for row in added)
        ledger = self.wait('cancelled source has no ready asset',self.account_ledger,cancelled_ledger,timeout=20,interval=0.2)
        owner = self.manifest['accounts']['A']['id']
        cancelled_ids = {row['id'] for row in ledger['records'] if row['id'] not in previous} if ledger else set()
        directory = FILES+'assets/'+owner+'/'
        files = self.wait('cancelled source temporary file removed',lambda:self.shell('ls','-1',directory).splitlines(),
                  lambda names:not any(key+suffix in names for key in cancelled_ids for suffix in ('.bin','.part')),
                  timeout=20,interval=0.2)
        assert self.account_ledger('B') == known_b
        assert not any(row['stage'] == 'publish' for row in events)
        return {'complete':True,'operation':operation,'sourceTabClosed':True,'noAssetPublished':True,'nativeEvents':events,
                'sourceTabIdsBefore':[tab['id'] for tab in self.manifest['accounts']['A']['tabs']],
                'sourceTabIdsAfter':[tab['id'] for row in workspace['accounts'] if row['id']==owner for tab in row['tabs']],
                'ledgersBefore':{'A':known_a,'B':known_b},
                'ledgersAfter':{'A':ledger,'B':self.account_ledger('B')},'assetDirectoryFilesAfter':files}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--fixture', type=Path, default=Path(os.environ.get('STUDIO_GECKO_FIXTURE','android/network-poc/secure-fixture/fixture.json')))
    parser.add_argument('--apk', type=Path, default=Path(os.environ.get('STUDIO_GECKO_APK','android/app/build/environment-client-input/gecko-network-x86.apk')))
    parser.add_argument('--receipt', type=Path, default=Path(os.environ.get('STUDIO_GECKO_RECEIPT','android/app/build/environment-client-input/gecko-network-receipt.json')))
    parser.add_argument('--output', type=Path, default=Path(os.environ.get('STUDIO_GECKO_OUTPUT','android/app/build/gecko-local-exports-evidence')))
    args = parser.parse_args(); acceptance = None; guarded = False
    native.EVIDENCE = args.output; common.EVIDENCE = args.output
    try:
        api = native._guard(); guarded = True
        devices = native._run(['adb','devices']).stdout.strip().splitlines()[1:]
        assert len(devices) == 1 and devices[0].split()[1:] == ['device']
        args.output.mkdir(parents=True,exist_ok=True)
        assert not native._shell('pm','list','packages',PACKAGE).strip(), 'Fresh package installation only'
        receipt = native._json(args.receipt); version = validate_receipt(receipt)
        source = os.environ.get('STUDIO_GECKO_APPLICATION_SHA',os.environ['GITHUB_SHA'])
        assert re.fullmatch(r'[a-f0-9]{40}', source) and receipt['sourceSHA'] == source
        signer = native._json(Path('docs/signing/android-network-beta.json'))['certificateSHA256']
        assert receipt['ciOnly'] is True and receipt['package'] == PACKAGE and receipt['versionCode'] == VERSION_CODE
        assert receipt['signerSHA256'] == signer and receipt['file'] == args.apk.name
        assert receipt['bytes'] == args.apk.stat().st_size and receipt['sha256'] == native._sha(args.apk)
        apk = native._inspect_apk(args.apk,native._tools(),signer,PACKAGE,VERSION_CODE)
        assert apk['versionName'] == version['versionName'] and apk['sha256'] == receipt['sha256']
        manifest = native._json(args.fixture)
        assert manifest['schemaVersion'] == 2 and manifest['ready'] is True and native._json(args.fixture.parent/'ready.json')['ready'] is True
        fingerprint = native._run(['openssl','x509','-in',str(args.fixture.parent/'ca.pem'),'-noout','-fingerprint','-sha256']).stdout
        ca = re.search(r'Fingerprint=([A-Fa-f0-9:]+)',fingerprint)
        assert ca and ca[1].replace(':','').lower() == manifest['certificateSha256']
        tls = native._json(args.fixture.parent/'gecko-origin-tls.json')
        assert tls['caUnchanged'] is True and tls['leafIsCA'] is False and tls['purpose'] == 'sslserver'
        assert tls['caCertificateSha256'] == manifest['certificateSha256'] and tls['dnsSANs'] == [HOST,'files.network-fixture.test']
        native._root_and_block()
        for port in {urllib.parse.urlsplit(manifest['transports']['raw'][key]).port for key in ('nodeA','nodeB')}:
            assert port in (18443,18444)
            native._shell('iptables','-I','OUTPUT','1','-p','tcp','-d','10.0.2.2','--dport',str(port),'-j','ACCEPT')
        for tool in ('iptables','ip6tables'): native._shell(tool,'-I','OUTPUT','1','-o','lo','-j','ACCEPT')
        (args.output/'egress.txt').write_text(native._shell('iptables-save')+native._shell('ip6tables-save'))
        native._run(['adb','install',str(args.apk)]); installed = native._installed(apk['sha256'],'gecko-local-exports')
        match = re.fullmatch(r'package:'+re.escape(PACKAGE)+r' uid:(\d+)',native._shell('pm','list','packages','-U',PACKAGE).strip())
        assert match and native._shell('id','-u').strip() == '0'; uid = int(match[1])
        acceptance = LocalExportsAcceptance(api,uid,args.fixture,args.output)
        assert acceptance.owned_http('/gecko-network/control/configure',{'transport':'raw','runId':str(uuid.uuid4())})['ok'] is True
        acceptance.prepare(args.fixture.parent/'ca.pem'); connected,_ = acceptance.configure(manifest)
        acceptance.control('open','A',0); _, first = acceptance.route_page('A')
        ready = acceptance.wait('browser local payloads prepared',acceptance.status,
            lambda row:(acceptance.latest(row,'/c/a1').get('localExports') or {}).get('prepared') is True)
        first = acceptance.latest(ready,'/c/a1')
        definitions = acceptance.http('/local-exports/manifest')
        assert definitions['schema'] == 1 and set(definitions['exports']) == set(LABELS)
        acceptance.definitions = definitions['exports']
        for definition in acceptance.definitions.values():
            body = base64.b64decode(definition['base64'],validate=True)
            assert len(body) == definition['bytes'] and hashlib.sha256(body).hexdigest() == definition['sha256']
        pid = acceptance.shell('pidof',PACKAGE).strip(); assert re.fullmatch(r'[1-9][0-9]*',pid)
        acceptance.main_pid = int(pid)
        online = [acceptance.save(kind,'online') for kind in LABELS]
        status = acceptance.wait('three actual trusted export button events',acceptance.status,
            lambda row:len(acceptance.latest(row,'/c/a1')['localExports']['clicks']) == 3)
        observed = acceptance.latest(status,'/c/a1')
        assert len(observed['localExports']['clicks']) == 3 and all(row['trusted'] is True for row in observed['localExports']['clicks'])
        assert [row['kind'] for row in observed['localExports']['clicks']] == list(LABELS)
        stop = acceptance.network('network-stop'); assert stop['ok'] is True
        failed = acceptance.wait('core stopped before offline export',lambda:acceptance.network('network-status'),
            lambda row:row['state'] == 'FAILED' and row['coreRunning'] is False,timeout=40)
        offline_start = acceptance.shell('cat','/proc/uptime').split()[0]
        offline = [acceptance.save(kind,'offline') for kind in LABELS]
        offline_end = acceptance.shell('cat','/proc/uptime').split()[0]
        offline_after = acceptance.network('network-status')
        assert offline_after['state']=='FAILED' and offline_after['coreRunning'] is False
        recovery = acceptance.network('network-retry'); acceptance.connected(recovery,0)
        _, recovered = acceptance.route_page('A',first['document'],'')
        assert recovered['localExports']['prepared'] is True
        offline_status = acceptance.wait('offline clicks observed after explicit recovery',acceptance.status,
            lambda row:len(acceptance.latest(row,'/c/a1')['localExports']['clicks']) == 6)
        offline_clicks = acceptance.latest(offline_status,'/c/a1')['localExports']['clicks']
        assert [row['kind'] for row in offline_clicks] == list(LABELS)*2 and all(row['trusted'] is True for row in offline_clicks)
        http_ticket = acceptance.download('A')
        def foreground_b():
            acceptance.control('open','B',0,deadline=acceptance._input_deadline)
            target = acceptance.native_action('切换账号，当前：环境 B',description=True)
            assert acceptance.json_file('workspace.json')['activeAccount'] == acceptance.manifest['accounts']['B']['id']
            return target
        foreground = acceptance.save('html','foreground-B',foreground_b)
        acceptance.control('open','A',0); acceptance.route_page('A',first['document'],'')
        cancelled = acceptance.cancel_sheet()
        closed = acceptance.close_source()
        final = acceptance.status()
        assert final['sendCount'] == 0 and all(row['route'] in ('A','B') for row in final['networkRequests'])
        proof = {'schema':1,'complete':True,'api':api,'engine':'gecko','transport':'raw',
                 'sourceSHA':os.environ['GITHUB_SHA'],'applicationSourceSHA':source,'versionMetadata':version,
                 'ciReceipt':receipt,'candidateAPK':apk,'candidateInstalledSHA256':installed,'uid':uid,'originTLS':tls,
                 'accounts':acceptance.manifest['accounts'],'mainProcessPID':acceptance.main_pid,
                 'exportDefinitions':acceptance.definitions,'trustedExportClicks':offline_clicks,'browserDocument':first['document'],
                 'online':online,'offline':offline,'offlineCoreState':failed,'explicitRecovery':recovery,
                 'offlineWindow':{'startUptimeSeconds':offline_start,'endUptimeSeconds':offline_end,
                                  'before':failed,'after':offline_after},
                 'foregroundOwnerPreserved':foreground,'sheetCancellation':cancelled,'sourceTabCancellation':closed,
                 'httpTicketRegression':http_ticket,'noDirect':True,'sendCount':0,
                 'origin':final,
                 'nativeEvents':acceptance.events(),'networkStates':acceptance.network_observations,
                 'screenshots':False,'realServicesAccessed':False,'adbReverseOriginUsed':False}
        native._write_json(args.output/'gecko-local-exports.json',proof)
        print(json.dumps({'complete':True,'api':api,'engine':'gecko','onlineExports':len(online),
                          'offlineExports':len(offline),'versionMetadata':version,
                          'proofFile':str(args.output/'gecko-local-exports.json')}),flush=True)
        return proof
    except Exception:
        native._record_failure(); raise
    finally:
        if acceptance is not None:
            acceptance._input_deadline = None
            try: acceptance.events()
            except Exception: (args.output/'native-events-cleanup-error.txt').write_text(traceback.format_exc())
            try: acceptance.hold(False)
            except Exception: (args.output/'hold-cleanup-error.txt').write_text(traceback.format_exc())
            for operation in list(acceptance.pending_gates):
                try: acceptance.release(operation)
                except Exception: (args.output/'gate-cleanup-error.txt').write_text(traceback.format_exc())
        if guarded: native._logcat('gecko-local-exports')


if __name__ == '__main__':
    main()
