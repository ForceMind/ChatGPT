"""Gate owned local exports on both Android APIs; include only synthetic evidence."""
import argparse
import base64
from decimal import Decimal
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import uuid

from android_version import validate_receipt


def read(path):
    return json.loads(path.read_text(encoding='utf-8'))


def canonical_exports():
    source = Path(__file__).resolve().parents[1]/'network-poc/scripts/gecko_local_exports_fixture.py'
    spec = importlib.util.spec_from_file_location('owned_local_export_definitions',source)
    fixture = importlib.util.module_from_spec(spec);spec.loader.exec_module(fixture)
    return {kind:{'name':name,'mime':mime,'bytes':len(data),'sha256':hashlib.sha256(data).hexdigest(),
                  'base64':base64.b64encode(data).decode('ascii')}
            for kind,(name,mime,data) in fixture.payloads().items()}


def identifier(value):
    assert isinstance(value,str) and str(uuid.UUID(value)) == value
    return value


def ledger_records(value, account):
    if value is None:
        return {}
    rows = value['records'];assert isinstance(rows,list)
    result = {}
    for row in rows:
        identifier(row['id']);assert row['id'] not in result and row['accountId'] == account
        result[row['id']] = row
    return result


def verify_proof(proof, api, receipt, metadata, definitions):
    assert proof['schema'] == 1 and proof['complete'] is True and proof['api'] == api
    assert proof['engine'] == 'gecko' and proof['transport'] == 'raw'
    assert proof['ciReceipt'] == receipt and proof['versionMetadata'] == metadata
    assert proof['applicationSourceSHA'] == receipt['sourceSHA']
    assert re.fullmatch(r'[a-f0-9]{40}',proof['sourceSHA'])
    apk = proof['candidateAPK']
    for key in ('file','sha256','package','versionCode','versionName','signerSHA256'):
        assert apk[key] == receipt[key], key
    assert proof['candidateInstalledSHA256'] == receipt['sha256']
    assert type(proof['uid']) is int and proof['uid'] > 0
    assert proof['noDirect'] is True and proof['sendCount'] == 0
    assert proof['screenshots'] is False and proof['realServicesAccessed'] is False and proof['adbReverseOriginUsed'] is False
    tls = proof['originTLS']
    assert tls['caUnchanged'] is True and tls['leafIsCA'] is False and tls['purpose'] == 'sslserver'
    assert tls['dnsSANs'] == ['network-fixture.test','files.network-fixture.test']
    assert re.fullmatch(r'[a-f0-9]{64}',tls['caCertificateSha256'])
    accounts = proof['accounts'];assert set(accounts) == {'A','B','C'}
    for account in accounts.values():
        identifier(account['id']);identifier(account['storageId'])
        for tab in account['tabs']:identifier(tab['id'])
    assert len({row['id'] for row in accounts.values()}) == len({row['storageId'] for row in accounts.values()}) == 3
    assert accounts['A']['environmentSlot'] == accounts['C']['environmentSlot'] != accounts['B']['environmentSlot']
    assert len(accounts['A']['tabs']) == 2
    for label,paths in {'A':['/c/a1','/c/a2'],'B':['/c/b1'],'C':['/c/c1']}.items():
        assert [row['url'] for row in accounts[label]['tabs']] == ['https://network-fixture.test:18480'+path for path in paths]
    account, other = accounts['A']['id'], accounts['B']['id']
    tab = accounts['A']['tabs'][0]['id'];document = identifier(proof['browserDocument'])
    assert proof['exportDefinitions'] == definitions
    main_pid = proof['mainProcessPID'];assert type(main_pid) is int and main_pid > 0
    all_events = proof['nativeEvents'];assert isinstance(all_events,list) and all_events
    event_index = {}
    sequences = {}
    for event in all_events:
        assert set(event) == {'sequence','pid','stage','operation','accountId','tabId','bytes','elapsedMillis'}
        assert type(event['pid']) is int and event['pid'] > 0 and type(event['sequence']) is int and event['sequence'] > 0
        assert type(event['bytes']) is int and event['bytes'] >= 0 and type(event['elapsedMillis']) is int and event['elapsedMillis'] >= 0
        assert re.fullmatch(r'[a-z0-9-]{1,40}',event['stage']);identifier(event['operation'])
        assert event['accountId'] == account and event['tabId'] == tab
        key = (event['pid'],event['sequence']);assert key not in event_index
        event_index[key] = event;sequences.setdefault(event['pid'],[]).append(event)
    for events in sequences.values():
        ordered = sorted(events,key=lambda row:row['sequence'])
        assert [row['sequence'] for row in ordered] == list(range(1,len(ordered)+1))
        assert [row['elapsedMillis'] for row in ordered] == sorted(row['elapsedMillis'] for row in ordered)
    used = set();asset_ids = set()

    def native_events(task, success, size=None, sheet_cancel=False):
        operation = identifier(task['operation']);assert operation not in used;used.add(operation)
        rows = task['nativeEvents'];assert rows and len({(row['pid'],row['sequence']) for row in rows}) == len(rows)
        for row in rows:
            assert event_index[(row['pid'],row['sequence'])] == row and row['operation'] == operation
        # No later broker outcome can be omitted from the task-local evidence.
        final = [row for row in all_events if row['operation'] == operation]
        assert all(row in rows for row in final if row['stage'] in ('publish','complete','cancel'))
        children = {row['pid'] for row in rows if row['pid'] != main_pid}
        assert len(children) == 1 and {row['pid'] for row in rows} == children|{main_pid}
        child = next(iter(children))
        def stage(pid,name):
            found = [row for row in rows if row['pid'] == pid and row['stage'] == name]
            assert len(found) == 1, (api,operation,pid,name)
            return found[0]
        def ordered(pid,names):
            values = [stage(pid,name) for name in names]
            assert [row['sequence'] for row in values] == sorted(row['sequence'] for row in values)
            return values
        if success:
            ordered(child,('received','claimed','confirm','complete'))
            values = ordered(main_pid,('claimed','confirm','copy-eof','publication-held','publication-released','publish'))
            assert not any(row['stage'] in ('cancel','error') for row in final)
            assert all(row['bytes'] == size for row in values[2:])
            assert 0 <= stage(main_pid,'publication-released')['elapsedMillis']-stage(main_pid,'publication-held')['elapsedMillis'] <= 20000
            assert stage(main_pid,'publish')['elapsedMillis'] <= stage(child,'complete')['elapsedMillis']
        else:
            stage(main_pid,'cancel');stage(child,'cancel')
            assert not any(row['stage'] in ('publish','complete') for row in final)
            if sheet_cancel:
                assert not any(row['stage'] in ('confirm','copy-eof','publication-held','publication-released') for row in final)
            else:
                values = ordered(main_pid,('claimed','confirm','copy-eof','publication-held','cancel'))
                assert all(row['bytes'] == size for row in values[2:])
        return rows

    def saved(task, kind, mode, foreground):
        expected = definitions[kind]
        assert task['kind'] == kind and task['mode'] == mode and task['accountId'] == account
        assert task['publicationForegroundAccountId'] == foreground
        assert task['otherLedgerBefore'] == task['otherLedgerAfter']
        assert not ledger_records(task['otherLedgerAfter'],other)
        for key in ('name','mime','bytes','sha256'):assert task[key] == expected[key]
        assert task['assetSource'] == 'import' and task['streamReadExitCode'] == 0 and task['originalStreamBytesEqual'] is True
        body = base64.b64decode(task['assetBase64'],validate=True)
        assert body == base64.b64decode(expected['base64'],validate=True)
        assert len(body) == task['bytes'] and hashlib.sha256(body).hexdigest() == task['sha256']
        asset = identifier(task['assetId']);assert asset not in asset_ids;asset_ids.add(asset)
        return native_events(task,True,len(body))

    assert len(proof['online']) == len(proof['offline']) == 3
    for tasks,mode in ((proof['online'],'online'),(proof['offline'],'offline')):
        assert [task['kind'] for task in tasks] == ['html','png','data']
        for task in tasks:saved(task,task['kind'],mode,account)
    window = proof['offlineWindow']
    assert window['before'] == proof['offlineCoreState']
    for state in (window['before'],window['after']):
        assert state['schema'] == 1 and state['complete'] is True and state['operation'] == 'network-status'
        assert state['state'] == 'FAILED' and state['coreRunning'] is False and state['selected'] == 0
        assert state in proof['networkStates']
    assert window['before']['generation'] == window['after']['generation']
    start,end = Decimal(window['startUptimeSeconds'])*1000,Decimal(window['endUptimeSeconds'])*1000
    assert 0 < start < end
    for task in proof['online']:
        assert max(row['elapsedMillis'] for row in task['nativeEvents']) <= start
    for task in proof['offline']:
        # /proc/uptime has centisecond resolution; the endpoint represents [n,n+10ms).
        assert all(start <= row['elapsedMillis'] < end+10 for row in task['nativeEvents'])
    recovered = proof['explicitRecovery']
    assert recovered['ok'] is True and recovered['state'] == 'CONNECTED' and recovered['coreRunning'] is True
    assert recovered['selected'] == 0 and recovered['operation'] == 'network-retry' and recovered in proof['networkStates']
    states = proof['networkStates']
    stops = [index for index,state in enumerate(states) if state['operation'] == 'network-stop' and state['ok'] is True]
    assert len(stops) == 1 and stops[0] < states.index(window['before']) <= states.index(window['after']) < states.index(recovered)
    assert any(state['operation'] == 'network-config' and state['ok'] is True and state['state'] == 'CONNECTED'
               and state['coreRunning'] is True and state['selected'] == 0 for state in states[:stops[0]])
    clicks = proof['trustedExportClicks'];assert len(clicks) == 6
    for index,click in enumerate(clicks):
        kind = ('html','png','data')[index%3]
        assert click['index'] == index and click['kind'] == kind and click['trusted'] is True and click['document'] == document
        for key in ('name','mime','bytes','sha256'):assert click[key] == definitions[kind][key]
    saved(proof['foregroundOwnerPreserved'],'html','foreground-B',other)
    target = proof['foregroundOwnerPreserved']['foregroundNativeTarget']
    assert target['package'] == receipt['package'] and target['content-desc'] == '切换账号，当前：环境 B'
    assert target['enabled'] == 'true' and target.get('visible-to-user','true') == 'true'
    left,top,right,bottom = target['rectangle'];assert 0 <= left < right and 0 <= top < bottom
    ticket = proof['httpTicketRegression'];gate = ticket['gate']
    digest = hashlib.sha256(b'D'*262144).hexdigest()
    assert ticket['kind'] == 'download' and ticket['route'] == 'A' and ticket['accountId'] == account
    assert ticket['bytes'] == 262144 and ticket['sha256'] == digest
    assert re.fullmatch(r'raw-https-gecko-[a-f0-9]{32}',ticket['operation']) and ticket['operation'] not in used
    asset = identifier(ticket['assetId']);assert asset not in asset_ids;asset_ids.add(asset)
    assert gate['completed'] is True and gate['opCount'] == gate['attemptCount'] == 1
    assert gate['route'] == 'A' and gate['syntheticAccount'] == 'A' and gate['payloadSha256'] == digest
    assert gate['responseBytesWritten'] == 262144
    cancelled = proof['sheetCancellation']
    assert cancelled['complete'] is True and cancelled['nativeSheetCancelled'] is True and cancelled['noAssetPublished'] is True
    native_events(cancelled,False,sheet_cancel=True)
    assert cancelled['ledgersBefore'] == cancelled['ledgersAfter']
    closed = proof['sourceTabCancellation']
    assert closed['complete'] is True and closed['sourceTabClosed'] is True and closed['noAssetPublished'] is True
    native_events(closed,False,definitions['png']['bytes'])
    assert closed['sourceTabIdsBefore'] == [row['id'] for row in accounts['A']['tabs']]
    assert set(closed['sourceTabIdsAfter']) == set(closed['sourceTabIdsBefore'])-{tab}
    assert len(closed['sourceTabIdsAfter']) == 1
    assert cancelled['ledgersAfter'] == closed['ledgersBefore']
    assert closed['ledgersBefore']['B'] == closed['ledgersAfter']['B']
    assert not ledger_records(closed['ledgersAfter']['B'],other)
    before = ledger_records(closed['ledgersBefore']['A'],account)
    after = ledger_records(closed['ledgersAfter']['A'],account)
    assert set(before) == asset_ids and all(row['status'] == 'ready' for row in before.values())
    assert all(after.get(key) == row for key,row in before.items())
    added = {key:row for key,row in after.items() if key not in before}
    assert len(added) <= 1 and all(row['status'] == 'failed' for row in added.values())
    files = closed['assetDirectoryFilesAfter'];assert isinstance(files,list) and len(files) == len(set(files))
    assert not any(key+suffix in files for key in added for suffix in ('.bin','.part'))
    assert {name[:-4] for name in files if name.endswith('.bin')} == asset_ids
    assert not any(name.endswith('.part') for name in files)
    assert len(used) == 9 and {row['operation'] for row in all_events} == used
    assert len(asset_ids) == 8
    origin = proof['origin']
    assert origin['schema'] == 1 and origin['host'] == 'network-fixture.test'
    assert origin['sendCount'] == origin['rejectedEventCount'] == 0 and origin['pageCounts']['/c/a1'] == 1
    assert origin['networkRequests'] and all(row['route'] in ('A','B') for row in origin['networkRequests'])
    documents = [row for row in origin['networkRequests'] if row.get('kind') == 'document' and row['path'] == '/c/a1']
    assert len(documents) == 1 and documents[0]['route'] == 'A'
    return {'api':api,'complete':True,'engine':'gecko','onlineFormats':['html','png','data'],
            'offlineFormats':['html','png','data'],'localOperations':9,'publishedAssets':8,
            'sameCIAPK':True,'rawBytesAndHashesVerified':True,'separateNativeProcessesVerified':True,
            'offlineCoreStopped':True,'httpTicketPreserved':True,'foregroundBKeptOwnerA':True,
            'sheetCancelUnpublished':True,'closedSourceUnpublished':True,'screenshots':False,'realServicesAccessed':False}


def deliver(input_root=Path('input'), candidate=Path('candidate'), acceptance=Path('acceptance/local-exports')):
    assert os.environ.get('GITHUB_ACTIONS') == 'true', 'CI delivery gate only'
    assert candidate.is_dir() and (candidate/'client-environment-and-recovery-acceptance.json').is_file()
    network_proofs = read(candidate/'gecko-vless-route-acceptance.json')
    assert len(network_proofs) == 6, 'Existing network gate must finish first'
    receipt = read(input_root/'gecko-network-receipt.json');metadata = validate_receipt(receipt)
    human = read(input_root/'receipt.json')
    assert validate_receipt(human,require_human_filename=True) == metadata and receipt['ciOnly'] is True
    assert receipt['sourceSHA'] == human['sourceSHA']
    assert receipt['signerSHA256'] == human['signerSHA256'] == read(Path('docs/signing/android-network-beta.json'))['certificateSHA256']
    apk = input_root/receipt['file']
    assert receipt['file'] == 'gecko-network-x86.apk' and apk.stat().st_size == receipt['bytes']
    assert hashlib.sha256(apk.read_bytes()).hexdigest() == receipt['sha256']
    definitions = canonical_exports();summaries = [];proofs = []
    for api in (28,35):
        source = acceptance/f'gecko-local-exports-{api}'/'gecko-local-exports.json'
        proof = read(source)
        assert all(proof['originTLS']['caCertificateSha256'] == row['originTLS']['caCertificateSha256'] for row in network_proofs)
        summaries.append(verify_proof(proof,api,receipt,metadata,definitions));proofs.append(proof)
    destination = candidate/'evidence';destination.mkdir(exist_ok=True)
    for proof in proofs:
        (destination/f"gecko-local-exports-{proof['api']}.json").write_text(json.dumps(proof,indent=2)+'\n',encoding='utf-8')
    summary = {'schema':1,'complete':True,'engine':'gecko','versionMetadata':metadata,
               'applicationSourceSHA':receipt['sourceSHA'],'ciAPKsha256':receipt['sha256'],'apis':summaries,
               'screenshots':False,'realServicesAccessed':False}
    (destination/'gecko-local-exports-summary.json').write_text(json.dumps(summary,indent=2)+'\n',encoding='utf-8')
    return summary


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input',type=Path,default=Path('input'))
    parser.add_argument('--candidate',type=Path,default=Path('candidate'))
    parser.add_argument('--acceptance',type=Path,default=Path('acceptance/local-exports'))
    args = parser.parse_args();print(json.dumps(deliver(args.input,args.candidate,args.acceptance),indent=2))
