"""Require real owned Gecko asset/Skill tasks on Android 9 and 15 before delivery."""
import argparse
import base64
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import uuid
import zipfile

from environment_ci_target import validate_receipt, assert_receipt_pair, REGULAR


def read(path):
    return json.loads(path.read_text(encoding='utf-8'))


def identifier(value):
    assert isinstance(value,str) and str(uuid.UUID(value))==value
    return value


def analyse_crash_buffer(text,package):
    """Only full process labels establish ownership; short native thread names never do."""
    owned=re.compile(re.escape(package)+r'(?::[A-Za-z0-9_.$:-]+)?')
    records=[];signals=[]
    for number,line in enumerate(text.splitlines(),1):
        java=re.search(r'\bProcess:\s*([^,\s]+)(?:,\s*PID:\s*(\d+))?',line)
        tombstone=re.search(r'>>>\s*(\S+)\s*<<<',line)
        if java:
            records.append({'kind':'java','lineNumber':number,'process':java[1],
                            'pid':int(java[2]) if java[2] else None,'owned':owned.fullmatch(java[1]) is not None,'line':line})
        if tombstone:
            pid=re.search(r'\bpid:\s*(\d+)',line)
            records.append({'kind':'native','lineNumber':number,'process':tombstone[1],
                            'pid':int(pid[1]) if pid else None,'owned':owned.fullmatch(tombstone[1]) is not None,'line':line})
        if re.search(r'\bFatal signal\s+\d+',line):
            pid=re.search(r'\bpid\s+(\d+)',line)
            prefix=re.match(r'^\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d+\s+(\d+)\s+\d+\s+[VDIWEF]\s+',line)
            signals.append({'lineNumber':number,'pid':int(pid[1]) if pid else int(prefix[1]) if prefix else None,'line':line})
    names={}
    for record in records:
        if record['pid'] is not None:names.setdefault(record['pid'],set()).add(record['process'])
    unresolved=[]
    for signal in signals:
        labels=sorted(names.get(signal['pid'],set()))
        if len(labels)!=1:unresolved.append(dict(signal,observedProcessLabels=labels))
    return {'fatalRecords':records,'ownedFatalRecords':[row for row in records if row['owned']],
            'nativeSignals':signals,'unresolvedNativeSignals':unresolved}


def native_node(value,package,api):
    assert value['package']==package and value.get('visible-to-user','true')=='true'
    x1,y1,x2,y2=value['rectangle'];assert 0<=x1<x2 and 0<=y1<y2
    source=value['uiSnapshot'];assert source['provider']=='ci-readonly-active-window-ax'
    assert source['api']==api and source['uid']==2000 and type(source['pid']) is int and source['pid']>0
    identifier(source['requestId']);assert re.fullmatch(r'[a-f0-9]{64}',source['xmlSHA256'])
    assert source['waitForIdle'] is False
    assert source['selection']=='unique-input-focused-application' and source['rootPackage']==package
    chosen=source['chosenWindow']
    assert chosen['type']==1 and chosen['isFocused'] is True and chosen['allowedPackage']==package
    assert chosen['rootPresent'] is True and chosen['packageQueried'] is True
    assert chosen['id']==chosen['rootWindowId']==source['rootWindowId']==int(value['window-id'])>=0
    windows=source['windows'];assert len({row['id'] for row in windows})==len(windows)
    focused=[row for row in windows if row['type']==1 and row['isFocused'] is True]
    assert focused==[chosen] and all(row['allowedPackage'] in ('',package) for row in windows)
    active=source['activeRoot'];assert active['allowedPackage'] in ('',package) and type(active['id']) is int


def verify(proof,api,receipt,metadata,definitions,skill,artifact):
    assert proof['schema']==1 and proof['complete'] is True and proof['api']==api
    assert proof['engine']=='gecko' and proof['transport']=='raw'
    assert proof['onceScope']=='application-task' and proof['transportLevelReplayGuarantee'] is False
    assert proof['uncertaintyMode']=='send-business-ack-missing'
    assert proof['knownTransportReplayFailure']=={'status':'known-transport-replay-failure','mode':'send-ack-drop',
        'run':42254916165,'sourceSHA':'2bc002f29f7424e0413c335721d61b09a320c4b7',
        'api28Artifact':13522023748,'api35Artifact':13523037625,'includedInCurrentPass':False}
    assert proof['ciReceipt']==receipt and proof['versionMetadata']==metadata
    assert proof['applicationSourceSHA']==receipt['sourceSHA'] and re.fullmatch(r'[a-f0-9]{40}',proof['sourceSHA'])
    for key in ('file','sha256','package','versionCode','versionName','signerSHA256'):assert proof['candidateAPK'][key]==receipt[key]
    assert proof['candidateInstalledSHA256']==receipt['sha256']
    assert proof['screenshots'] is proof['realServicesAccessed'] is proof['adbReverseOriginUsed'] is False
    assert proof['noDirect'] is True and proof['sendCount']==3
    crash=proof['ownedAppCrashCheck'];crash_source=crash['source']
    assert crash['schema']==1 and crash['package']==receipt['package'] and crash['passed'] is True
    assert crash_source['command']==['adb','shell','logcat','-b','crash','-d','-v','threadtime']
    assert crash_source['complete'] is True and crash_source['exitCode']==0 and crash_source['buffer']=='crash' and crash_source['cleared'] is False
    assert crash_source['timeBudgetMillis']==25000
    assert 0<=crash_source['finishedMonotonicMillis']-crash_source['startedMonotonicMillis']<=25000
    assert crash_source['file']=='owned-app-crash-buffer.txt' and crash_source['stderrFile']=='owned-app-crash-buffer.stderr.txt'
    raw=(artifact/crash_source['file']).read_bytes();stderr=(artifact/crash_source['stderrFile']).read_bytes()
    assert len(raw)==crash_source['bytes'] and hashlib.sha256(raw).hexdigest()==crash_source['sha256']
    assert stderr==b'' and crash_source['stderrBytes']==0 and hashlib.sha256(stderr).hexdigest()==crash_source['stderrSHA256']
    text=raw.decode('utf-8');assert not re.search(r'(?m)^(?:logcat:|Permission Denial|Error:)',text)
    analysis=analyse_crash_buffer(text,receipt['package']);assert crash['analysis']==analysis
    assert type(crash['ownedFatalCount']) is int and crash['ownedFatalCount']==len(analysis['ownedFatalRecords'])==0
    assert type(crash['unresolvedNativeFatalCount']) is int and crash['unresolvedNativeFatalCount']==len(analysis['unresolvedNativeSignals'])==0
    assert crash['pidMap'] is None
    accounts=proof['accounts'];assert set(accounts)=={'A','B','C'}
    for account in accounts.values():
        identifier(account['id']);identifier(account['storageId'])
        for tab in account['tabs']:identifier(tab['id'])
    assert len({row['id'] for row in accounts.values()})==len({row['storageId'] for row in accounts.values()})==3
    assert accounts['A']['environmentSlot']==accounts['C']['environmentSlot']!=accounts['B']['environmentSlot']
    source,target=accounts['A'],accounts['B'];package=receipt['package']
    originals=proof['originalsBefore'];assert originals==proof['originalsAfter'] and set(originals)==set(definitions)
    assets={}
    for name,value in originals.items():
        row=value['record'];expected=definitions[name];body=base64.b64decode(value['base64'],validate=True)
        assert body==base64.b64decode(expected['base64'],validate=True)
        assert row['name']==name and row['accountId']==source['id'] and row['mime']==expected['mime']
        assert row['status']=='ready' and row['source']=='download' and row['trashed'] is False
        assert row['size']==expected['bytes']==len(body) and row['sha256']==expected['sha256']==hashlib.sha256(body).hexdigest()
        identifier(row['id']);assert row['id'] not in assets;assets[row['id']]=row
    baseline=proof['sourceHotBaseline'];page=baseline['page'];draft=baseline['draft']
    assert page['path']=='/c/a1' and page['account']=='A' and page['document']==identifier(page['document'])
    assert page['draft']==page['modelDraft']==draft and re.fullmatch(r'OwnedHandoffSource-[a-f0-9]{32}',draft)
    assert page['trustedInputCount']>=len(draft) and page['sendClicks']==0
    assert baseline['pageCounts']=={'/c/a1':1,'/c/a2':1,'/c/b1':1}
    assert set(baseline['documents'])==set(baseline['pageCounts'])
    for ids in baseline['documents'].values():assert len(ids)==1 and identifier(ids[0])==ids[0]
    assert baseline['documents']['/c/a1']==[page['document']]
    prefixes=proof['nativePrefixReceipts'];assert len(prefixes)==len(draft)
    for index,prefix in enumerate(prefixes):
        assert prefix['index']==index and prefix['draft']==prefix['modelDraft']==prefix['expectedPrefix']==draft[:index+1]
    events=proof['nativeTaskEvents'];main_pid=proof['mainProcessPID']
    assert type(main_pid) is int and main_pid>0 and events['schema']==1 and events['pid']==main_pid
    journal_events=events['events'];assert journal_events
    by_job={};previous_time=-1
    for index,event in enumerate(journal_events):
        assert set(event) in ({'sequence','pid','jobId','status','sourceAccountId','targetAccountId','promptSha256','elapsedMillis'},
                              {'sequence','pid','jobId','status','sourceAccountId','targetAccountId','tabId','promptSha256','elapsedMillis'})
        assert event['sequence']==index+1 and event['pid']==main_pid
        assert type(event['elapsedMillis']) is int and event['elapsedMillis']>=previous_time;previous_time=event['elapsedMillis']
        identifier(event['jobId']);assert event['sourceAccountId']==source['id'] and event['targetAccountId']==target['id']
        assert re.fullmatch(r'[a-f0-9]{64}',event['promptSha256'])
        if 'tabId' in event:identifier(event['tabId'])
        by_job.setdefault(event['jobId'],[]).append(event)
    cases=(('positive','normal','submitted',1),('cancelled','upload-hold','cancelled',0),
           ('failed','upload-error','failed',0),('explicitNewTaskRecovery','normal','submitted',1),
           ('uncertain','send-business-ack-missing','uncertain',1))
    used_jobs=set();used_docs=set();used_stages=set();case_summaries=[]
    for key,mode,terminal,sends in cases:
        case=proof[key];job=case['job'];identifier(job['id']);document=identifier(case['document'])
        assert job['id'] not in used_jobs and document not in used_docs;used_jobs.add(job['id']);used_docs.add(document)
        assert job['status']==terminal and job['sourceAccountId']==source['id'] and job['targetAccountId']==target['id']
        assert job['sourceStorageId']==source['storageId'] and job['targetStorageId']==target['storageId']
        assert re.fullmatch(r'[a-f0-9]{64}',job['promptSha256']) and identifier(job['tabId'])==job['tabId']
        assert case['originalsAfter']==originals and case['stagingAfter']==[]
        hot=case['hotPages']
        assert hot['document']==page['document'] and hot['draft']==hot['modelDraft']==draft
        assert hot['documents']==baseline['documents'] and hot['pageCounts']==baseline['pageCounts']
        review=case['review'];assert review['skillId']=='skill-001'
        for field,label,name in (('skillSelection','处理 Skill',skill['name']),('targetSelection','目标账号','环境 B')):
            choice=review[field];native_node(choice['spinner'],package,api);native_node(choice['selected'],package,api)
            assert choice['spinner']['content-desc']==label and choice['spinner']['class']=='android.widget.Spinner'
            assert choice['selected']['text']==name
        before,after=review['crossBefore'],review['crossAfter'];native_node(before,package,api);native_node(after,package,api)
        assert before['text']==after['text']=='我确认将所选文件发送到另一个账号'
        assert before['checked']=='false' and after['checked']=='true' and before['enabled']==after['enabled']=='true'
        status=case['nativeStatus'];assert status['status']==terminal
        native_node(status['statusNode'],package,api)
        label={'submitted':'已发送','cancelled':'已取消','failed':'未能发送','uncertain':'结果待确认'}[terminal]
        assert status['statusNode']['text'].startswith(label+' · '+job['label'])
        assert status['statusNode']['clickable']==status['statusNode']['enabled']=='true'
        history=by_job[job['id']];compressed=[]
        for event in history:
            assert event['promptSha256']==job['promptSha256']
            if not compressed or compressed[-1]!=event['status']:compressed.append(event['status'])
            if event['status'] in ('uploading','drafting','sending',terminal):assert event['tabId']==job['tabId']
            elif 'tabId' in event:assert event['tabId']==job['tabId']
        expected=['preparing','ready','opening','uploading']+(['drafting','sending'] if sends else [])+[terminal]
        assert compressed==expected,(api,key,compressed)
        assert len(job['files'])==2
        expected_uploads=[]
        for index,(name,expected_file) in enumerate(definitions.items()):
            file=job['files'][index];original=originals[name]['record']
            assert file['assetId']==original['id'] and file['sourceAccountId']==source['id'] and file['name']==name
            assert file['sha256']==original['sha256'] and file['size']==original['size'] and file['mime']==original['mime']
            assert identifier(file['stageId']) not in used_stages;used_stages.add(file['stageId'])
            upload_name=name+'.txt' if name.endswith('.html') else name
            upload_mime='text/plain' if name.endswith('.html') else expected_file['mime']
            assert file['uploadName']==upload_name and file['uploadMime']==upload_mime
            expected_uploads.append(dict(expected_file,name=upload_name,mime=upload_mime))
        task=case['server']
        assert task['document']==document and task['mode']==mode and task['identity']=='B' and task['route']=='A'
        assert task['documentLoads']==task['uploadAttempts']==1 and task['uploads']==expected_uploads
        assert re.fullmatch(r'[a-f0-9]{64}',task['uploadBodySHA256'])
        assert task['sendAttempts']==sends and len(task['sends'])==sends
        latest=task['latest'];assert latest and latest['document']==document and latest['account']==latest['ciIdentity']=='B'
        assert latest['route']=='A' and latest['language'].lower().startswith('en-us') and latest['timeZone']=='America/New_York'
        assert latest['fileChanges']==latest['trustedFileChanges']==1
        expected_browser=[{key:row[key] for key in ('name','mime','bytes','sha256')} for row in expected_uploads]
        assert latest['browserFiles']==expected_browser
        if sends:
            sent=task['sends'][0];prompt=sent['prompt']
            assert sent['promptSha256']==job['promptSha256']==hashlib.sha256(prompt.encode()).hexdigest()
            assert sent['utf8Bytes']==len(prompt.encode()) and sent['utf16Length']==len(prompt.encode('utf-16-le'))//2
            assert '\n\n' in prompt and skill['instructions'] in prompt and skill['name'] in prompt
            assert '请分析这些附件，说明主要内容并给出处理建议。' in prompt and '附件清单：\n' in prompt
            assert all(row['name'] in prompt for row in expected_browser)
            assert sent['files']==expected_browser and sent['route']=='A' and sent['identity']=='B'
            assert latest['trustedInputCount']>=1 and latest['inputCount']>=latest['trustedInputCount'] and latest['sendClicks']==1
            fence=case['sendFence'];assert fence['id']==job['id'] and fence['tabId']==job['tabId'] and fence['promptSha256']==job['promptSha256']
            assert latest['userMessages']==([prompt] if terminal=='submitted' else [])
            if terminal=='submitted':
                assert latest['draft']==latest['modelDraft']=='' and latest['url']==job['conversationURL']=='https://network-fixture.test:18480/c/handoff-'+document
            else:
                assert latest['draft']==latest['modelDraft']==prompt and latest['error'] is True
                response=task['sendResponse'];observed=latest['sendResponse']
                body=base64.b64decode(response['bodyBase64'],validate=True)
                expected_body=json.dumps({'document':document,'receipt':'owned-commit-without-business-ack'},separators=(',',':')).encode('utf-8')
                assert body==expected_body and response['bodyBytes']==response['bytesWritten']==len(body)
                assert response['complete'] is True and response['status']==200
                assert response['contentType']=='application/json; charset=utf-8' and response['contentLength']==str(len(body))
                assert response['bodySHA256']==hashlib.sha256(body).hexdigest()
                for field in ('status','contentType','contentLength','bodyBytes','bodySHA256','bodyBase64'):assert observed[field]==response[field]
                assert observed['httpOK'] is True and observed['jsonParsed'] is True
                assert observed['hasAccepted'] is False and observed['acceptedValue'] is None
                assert 'accepted' not in json.loads(body.decode('utf-8'))
        else:
            assert case['sendFence'] is None and latest['userMessages']==[] and latest['sendClicks']==0
            assert latest['draft']==latest['modelDraft']=='' and latest['trustedInputCount']==latest['inputCount']==0
            assert task['releaseCalls']==(1 if mode=='upload-hold' else 0)
        case_summaries.append({'case':key,'status':terminal,'actualSendRequests':sends,'complete':True})
    guard=proof['positive']['review']['unconfirmedGuard']
    assert guard['jobIdsBefore']==guard['jobIdsAfter']==[]
    assert guard['nativeMessages'] and all('确认' in text and '另一个账号' in text for text in guard['nativeMessages'])
    assert set(by_job)==used_jobs
    final_jobs={row['id']:row for row in proof['finalJobs']};assert len(final_jobs)==5 and set(final_jobs)==used_jobs
    for key,_,_,_ in cases:assert final_jobs[proof[key]['job']['id']]==proof[key]['job']
    assert proof['failed']['job']['id']!=proof['explicitNewTaskRecovery']['job']['id']
    assert proof['failed']['job']['createdAt']<proof['explicitNewTaskRecovery']['job']['createdAt']
    reopened=proof['uncertain']['afterNativeReopen'];assert reopened['job']==proof['uncertain']['job']
    for key in ('document','mode','identity','route','documentLoads','uploadAttempts','sendAttempts','uploads','sends','sendResponse'):
        assert reopened['server'][key]==proof['uncertain']['server'][key]
    assert reopened['server']['latest']['userMessages']==[] and reopened['server']['sendAttempts']==1
    assert reopened['server']['latest']['sendResponse']==proof['uncertain']['server']['latest']['sendResponse']
    origin=proof['origin'];assert origin['schema']==1 and origin['host']=='network-fixture.test' and origin['sendCount']==3
    assert origin['rejectedEventCount']==0 and set(origin['taskDocuments'])==used_docs
    assert origin['networkRequests'] and all(row['route'] in ('A','B') for row in origin['networkRequests'])
    for key,_,_,sends in cases:
        task=origin['taskDocuments'][proof[key]['document']]
        assert task['sendAttempts']==sends and task['uploadAttempts']==task['documentLoads']==1
        assert task['uploads']==proof[key]['server']['uploads'] and task['sends']==proof[key]['server']['sends']
        if key=='uncertain':assert task['sendResponse']==proof[key]['server']['sendResponse']
    assert {path:origin['pageCounts'][path] for path in baseline['pageCounts']}==baseline['pageCounts']
    assert {path:list(origin['pages'][path]) for path in baseline['documents']}==baseline['documents']
    assert len(origin['handoffAssetDownloads'])==2
    assert {row['name'] for row in origin['handoffAssetDownloads']}==set(definitions)
    assert all(row['route']=='A' and row['identity']=='A' for row in origin['handoffAssetDownloads'])
    tls=proof['originTLS'];assert tls['caUnchanged'] is True and tls['leafIsCA'] is False and tls['purpose']=='sslserver'
    assert tls['dnsSANs']==['network-fixture.test','files.network-fixture.test'] and re.fullmatch(r'[a-f0-9]{64}',tls['caCertificateSha256'])
    return {'api':api,'complete':True,'cases':case_summaries,'originalBytesPreserved':True,
            'onceScope':'application-task','transportLevelReplayGuarantee':False,
            'uncertaintyMode':'send-business-ack-missing','knownTransportReplayFailure':proof['knownTransportReplayFailure'],
            'businessAckResponse':{key:proof['uncertain']['server']['sendResponse'][key]
                                   for key in ('status','contentType','contentLength','bodyBytes','bodySHA256','bytesWritten')},
            'realMultipartVerified':True,'completeUnicodePromptVerified':True,'skillAndCrossAccountNativeReview':True,
            'persistedTaskStagesVerified':True,'onceSendFencesVerified':True,'explicitFailureRecovery':True,
            'uncertainReopenNoReplay':True,'hotDocumentsAndDraftPreserved':True,
            'ownedAppFatalCount':crash['ownedFatalCount'],'crashBufferSHA256':crash_source['sha256'],
            'crashBufferEvidenceFile':f"gecko-handoff-{api}-{crash_source['file']}",
            'crashStderrEvidenceFile':f"gecko-handoff-{api}-{crash_source['stderrFile']}",
            'screenshots':False,'realServicesAccessed':False}


def deliver(input_root=Path('input'),candidate=Path('candidate'),acceptance=Path('acceptance/handoff')):
    assert os.environ.get('GITHUB_ACTIONS')=='true','CI delivery only'
    assert candidate.is_dir() and (candidate/'client-environment-and-recovery-acceptance.json').is_file()
    assert (candidate/'gecko-vless-route-acceptance.json').is_file()
    local_exports=read(candidate/'evidence/gecko-local-exports-summary.json')
    assert local_exports['complete'] is True
    receipt=read(input_root/'gecko-network-receipt.json');metadata=validate_receipt(receipt)
    human=read(input_root/'receipt.json');assert validate_receipt(human,require_human_filename=True)==metadata
    assert receipt['ciOnly'] is True and receipt['sourceSHA']==human['sourceSHA']
    assert local_exports['versionMetadata']==metadata and local_exports['applicationSourceSHA']==receipt['sourceSHA']
    assert local_exports['ciAPKsha256']==receipt['sha256']
    assert_receipt_pair(receipt, human)
    apk=input_root/receipt['file'];assert receipt['file']=='gecko-network-x86.apk'
    assert apk.stat().st_size==receipt['bytes'] and hashlib.sha256(apk.read_bytes()).hexdigest()==receipt['sha256']
    with zipfile.ZipFile(apk) as archive:skill=next(row for row in json.loads(archive.read('assets/capabilities/skills.json'))['skills'] if row['id']=='skill-001')
    spec=importlib.util.spec_from_file_location('owned_handoff_delivery_assets',Path(__file__).resolve().parents[1]/'network-poc/scripts/gecko_handoff_fixture.py')
    fixture=importlib.util.module_from_spec(spec);spec.loader.exec_module(fixture)
    definitions={name:{'name':name,'mime':mime,'bytes':len(body),'sha256':hashlib.sha256(body).hexdigest(),
                       'base64':base64.b64encode(body).decode('ascii')} for name,(mime,body) in fixture.assets().items()}
    proofs=[];summaries=[]
    for api in (28,35):
        artifact=acceptance/f'gecko-handoff-{api}'
        proof=read(artifact/'gecko-handoff.json')
        summaries.append(verify(proof,api,receipt,metadata,definitions,skill,artifact));proofs.append(proof)
    evidence=candidate/'evidence';evidence.mkdir(exist_ok=True)
    for proof in proofs:
        (evidence/f"gecko-handoff-{proof['api']}.json").write_text(json.dumps(proof,indent=2)+'\n',encoding='utf-8')
        artifact=acceptance/f"gecko-handoff-{proof['api']}"
        for key in ('file','stderrFile'):
            name=proof['ownedAppCrashCheck']['source'][key]
            (evidence/f"gecko-handoff-{proof['api']}-{name}").write_bytes((artifact/name).read_bytes())
    summary={'schema':1,'complete':True,'engine':'gecko','versionMetadata':metadata,'applicationSourceSHA':receipt['sourceSHA'],
             'onceScope':'application-task','transportLevelReplayGuarantee':False,
             'uncertaintyMode':'send-business-ack-missing','knownTransportReplayFailure':proofs[0]['knownTransportReplayFailure'],
             'ciAPKsha256':receipt['sha256'],'apis':summaries,'screenshots':False,'realServicesAccessed':False}
    (evidence/'gecko-handoff-summary.json').write_text(json.dumps(summary,indent=2)+'\n',encoding='utf-8')
    return summary


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input',type=Path,default=Path('input'))
    parser.add_argument('--candidate',type=Path,default=Path('candidate'))
    parser.add_argument('--acceptance',type=Path,default=Path('acceptance/handoff'))
    args=parser.parse_args();print(json.dumps(deliver(args.input,args.candidate,args.acceptance),indent=2))
