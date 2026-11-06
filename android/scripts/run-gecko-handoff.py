#!/usr/bin/env python3
"""Owned CI Gecko asset/Skill acceptance through real native UI and FilePrompt."""
import argparse
import base64
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import time
import traceback
import urllib.parse
import urllib.request
import uuid
import zipfile

from android_version import VERSION_CODE, validate_receipt
from native_ax import NativeAXMixin

_spec=importlib.util.spec_from_file_location('owned_handoff_network',Path(__file__).with_name('run-gecko-network.py'))
network=importlib.util.module_from_spec(_spec);_spec.loader.exec_module(network)
native,common=network.native,network.common
PACKAGE,HOST,FILES=network.PACKAGE,network.HOST,network.common.FILES


class HandoffAcceptance(NativeAXMixin,network.NetworkAcceptance):
    def __init__(self,api,uid,fixture,output):
        super().__init__(api,uid,fixture,output,'raw')
        self.pending_tasks=set();self.cases=[];self.reviews={}
        self._ui_read_deadline=None
        self.last_ui_receipt=None

    def selected_spinner(self,label,value):
        def choose(rows):
            spinners=[row for row in rows if row.get('package')==PACKAGE and row.get('content-desc')==label
                      and row.get('class')=='android.widget.Spinner']
            if not spinners:return None
            assert len(spinners)==1
            by_index={row['nodeIndex']:row for row in rows};selected=[]
            for row in rows:
                if row.get('text')!=value:continue
                cursor=row
                while cursor is not None:
                    if cursor['nodeIndex']==spinners[0]['nodeIndex']:
                        selected.append(row);break
                    cursor=by_index.get(cursor.get('parentIndex'))
            if not selected:return None
            assert len(selected)==1
            return {'spinner':spinners[0],'selected':selected[0]}
        rows=self.wait('native selected spinner '+label,self.ui,lambda value:choose(value) is not None,timeout=15,interval=.2)
        return choose(rows)

    def task_events(self,main_pid):
        value=self.json_file('environment-handoff-events-'+str(main_pid)+'.json')
        assert value['schema']==1 and value['pid']==main_pid
        assert value['events'] and all(row['pid']==main_pid for row in value['events'])
        return value

    def failure_focus(self):
        deadline=time.monotonic()+25;observations=[]
        pattern=re.compile(r'\b(?:mCurrentFocus|mFocusedApp|mTopFocusedDisplayId|mFocusedWindow|mInputMethodTarget|mImeInputTarget|mImeLayeringTarget|topResumedActivity|mTopResumedActivity|mResumedActivity|ResumedActivity|mFocusedActivity)\b')
        for arguments in (('window','windows'),('activity','activities')):
            command=['adb','shell','dumpsys',*arguments]
            value={'command':command,'complete':False,'readOnly':True}
            try:
                remaining=deadline-time.monotonic()
                if remaining<=0:raise TimeoutError('Failure focus reads exceeded their single original budget')
                result=subprocess.run(command,text=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=min(10,remaining))
                # Preserve focus facts, never the Activity Intent extras or page/prompt content.
                value.update(exitCode=result.returncode,stderr=result.stderr,
                    rawStdoutSHA256=hashlib.sha256(result.stdout.encode('utf-8')).hexdigest(),
                    focusLines=[line for line in result.stdout.splitlines() if pattern.search(line)])
                value['complete']=result.returncode==0 and bool(value['focusLines'])
            except Exception as error:
                value.update(errorClass=type(error).__name__,error=traceback.format_exc())
            observations.append(value)
        return {'schema':1,'readOnly':True,'complete':all(row['complete'] for row in observations),'samples':observations}

    def status(self):
        value=self.http('/status')
        assert value['schema']==1 and value['host']==HOST and value['rejectedEventCount']==0
        assert isinstance(value['sendCount'],int) and 0<=value['sendCount']<=3
        return value

    def host(self,path,value):
        assert path=='/handoff-control/configure' or re.fullmatch(r'/handoff-control/[a-f0-9-]{36}/release',path)
        request=urllib.request.Request('http://127.0.0.1:18080'+path,data=json.dumps(value).encode(),
            headers={'Host':HOST+':18080','Content-Type':'application/json'})
        opener=urllib.request.build_opener(urllib.request.ProxyHandler({}));self.sequence+=1
        with opener.open(request,timeout=8) as response:
            body=response.read();assert response.status==200
        (self.output/(str(self.sequence)+'-handoff-host.json')).write_bytes(body)
        return json.loads(body)

    def release_task(self,document):
        result=self.host('/handoff-control/'+document+'/release',{})
        assert result['released'] is True and result['document']==document
        self.pending_tasks.discard(document)

    def action_node(self,rows,label,description=False,prefix=False,popup=False):
        key='content-desc' if description else 'text'
        matches=[row for row in rows if row.get('package')==PACKAGE
                 and (row.get(key,'').startswith(label) if prefix else row.get(key)==label)
                 and row.get('enabled')=='true' and row.get('visible-to-user','true')=='true']
        if not matches:return None
        assert len(matches)==1,'Ambiguous native task action: '+label
        target=matches[0];by_index={row['nodeIndex']:row for row in rows};cursor=target
        if popup:
            while cursor is not None and cursor.get('class')!='android.widget.ListView':
                cursor=by_index.get(cursor.get('parentIndex'))
            if cursor is None:return None
            cursor=target
        while cursor is not None:
            if cursor.get('clickable')=='true' or cursor.get('class') in ('android.widget.ListView','android.widget.AbsListView'):
                return target
            cursor=by_index.get(cursor.get('parentIndex'))
        return None

    def tap_label(self,label,description=False,prefix=False,scroll=False,backward=False,popup=False):
        deadline=time.monotonic()+45;swipes=0
        while True:
            rows=self.ui(deadline=deadline);target=self.action_node(rows,label,description,prefix,popup)
            if target:
                x1,y1,x2,y2=target['rectangle'];assert 0<=x1<x2 and 0<=y1<y2
                self.shell('input','tap',(x1+x2)//2,(y1+y2)//2,timeout=max(.1,deadline-time.monotonic()))
                return target
            if time.monotonic()>=deadline:raise AssertionError('Native task action not ready: '+label)
            if not rows:
                time.sleep(min(.2,max(0,deadline-time.monotonic())));continue
            if scroll:
                containers=[row for row in rows if row.get('package')==PACKAGE and row.get('scrollable')=='true'
                            and row['rectangle'][3]-row['rectangle'][1]>180
                            and (not popup or row.get('class')=='android.widget.ListView')]
                if popup and not containers:
                    # A dismissed attachment dialog can expose the form before the spinner popup.
                    # Await the actual option list; never scroll the underlying review form for an option.
                    time.sleep(.2);continue
                assert len(containers)==1,'Missing or ambiguous native task scroll container'
                assert swipes<20,'Native task action absent after bounded scroll: '+label
                x1,y1,x2,y2=containers[0]['rectangle'];x=(x1+x2)//2
                top,bottom=y1+(y2-y1)//5,y2-(y2-y1)//5
                self.shell('input','swipe',x,top if backward else bottom,x,bottom if backward else top,'400',timeout=max(.1,deadline-time.monotonic()))
                swipes+=1
            else:time.sleep(.2)

    def jobs(self):
        result=self.optional_json('handoff/jobs.json')
        if result is None:return []
        assert result['schema']==1
        return result['jobs']

    def job(self,identifier):
        matches=[row for row in self.jobs() if row['id']==identifier];assert len(matches)==1
        return matches[0]

    def terminal(self,identifier,status):
        result=self.wait('native task terminal '+status,lambda:self.job(identifier),
                         lambda row:row['status'] not in ('preparing','ready','opening','uploading','drafting','sending'),timeout=120,interval=.3)
        assert result['status']==status,(status,result['status'],result.get('message'))
        return result

    def readable_status(self,status):
        label={'submitted':'已发送','cancelled':'已取消','failed':'未能发送','uncertain':'结果待确认'}[status]
        rows=self.wait('native readable terminal status '+status,self.ui,
            lambda rows:any(row.get('text','').startswith(label+' · ') for row in rows),timeout=15)
        matches=[row for row in rows if row.get('text','').startswith(label+' · ')]
        assert len(matches)==1 and matches[0].get('clickable')=='true' and matches[0].get('enabled')=='true'
        return {'status':status,'statusNode':matches[0]}

    def asset_bytes(self,row):
        owner=self.manifest['accounts']['A']['id'];assert row['accountId']==owner and row['status']=='ready'
        command=['adb','exec-out','cat',FILES+'assets/'+owner+'/'+row['id']+'.bin']
        result=subprocess.run(command,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=20)
        self.sequence+=1;native._write_json(self.output/(str(self.sequence)+'-task-asset-read.json'),
            {'command':command,'exitCode':result.returncode,'bytes':len(result.stdout)})
        assert result.returncode==0 and len(result.stdout)==row['size']
        assert hashlib.sha256(result.stdout).hexdigest()==row['sha256']
        return result.stdout

    def originals(self):
        ledger=self.ledger();assert ledger and len(ledger['records'])==2
        values={row['name']:row for row in ledger['records']};assert set(values)==set(self.definitions)
        observed={}
        for name,row in values.items():
            data=self.asset_bytes(row);expected=self.definitions[name]
            assert data==base64.b64decode(expected['base64'],validate=True)
            assert row['mime']==expected['mime'] and row['sha256']==expected['sha256']
            observed[name]={'record':row,'base64':base64.b64encode(data).decode('ascii')}
        other=self.optional_json('assets/'+self.manifest['accounts']['B']['id']+'/index.json')
        assert other is None or other['records']==[]
        return observed

    def download_originals(self):
        for name,label in (('owned-report.html','下载任务 HTML'),('owned-image.png','下载任务 PNG')):
            before=self.ledger();ids={row['id'] for row in before['records']} if before else set()
            self.tap_native_action(label,description=True)
            ledger=self.wait('real source HTTP asset '+name,self.ledger,
                lambda value:value and any(row['id'] not in ids and row['status']=='ready' for row in value['records']))
            added=[row for row in ledger['records'] if row['id'] not in ids]
            assert len(added)==1 and added[0]['name']==name and added[0]['source']=='download'
            self.asset_bytes(added[0])

    def launch(self,mode,verify_cross=False):
        assert mode in ('normal','upload-hold','upload-error','send-ack-drop')
        before_jobs={row['id'] for row in self.jobs()};before_docs=set(self.status()['taskDocuments'])
        assert self.host('/handoff-control/configure',{'schema':1,'mode':mode})['ok'] is True
        self.control('open','A',0);self.route_page('A',self.source_page['document'],self.source_draft)
        self.tap_native_action('更多功能',description=True);self.tap_native_action('资产库')
        self.tap_label('owned-report.html');self.tap_label('交给 GPT')
        self.tap_label('选择附件（1）',scroll=True)
        self.tap_label('owned-image.png · ',prefix=True)
        self.wait('real PNG checkbox checked',self.ui,lambda rows:any(row.get('text','').startswith('owned-image.png · ') and row.get('checked')=='true' for row in rows),timeout=15)
        self.tap_label('确定')
        self.tap_label('目标账号',description=True,scroll=True,backward=True);self.tap_label('环境 B',scroll=True,popup=True)
        target_selection=self.selected_spinner('目标账号','环境 B')
        self.tap_label('处理 Skill',description=True,scroll=True);self.tap_label(self.skill['name'],scroll=True,popup=True)
        skill_selection=self.selected_spinner('处理 Skill',self.skill['name'])
        guard=None
        if verify_cross:
            self.tap_label('新建对话并发送')
            guard_ui=self.wait('native cross-account confirmation guard',self.ui,
                lambda rows:any('确认' in row.get('text','') and '另一个账号' in row.get('text','') for row in rows),timeout=15)
            guarded_ids={row['id'] for row in self.jobs()};assert guarded_ids==before_jobs
            guard={'jobIdsBefore':sorted(before_jobs),'jobIdsAfter':sorted(guarded_ids),
                   'nativeMessages':[row['text'] for row in guard_ui if '确认' in row.get('text','') and '另一个账号' in row.get('text','')]}
        cross_before=self.tap_label('我确认将所选文件发送到另一个账号',scroll=True,backward=True)
        assert cross_before.get('checked')=='false'
        cross_rows=self.wait('native cross-account checkbox checked',self.ui,
            lambda rows:any(row.get('text')=='我确认将所选文件发送到另一个账号' and row.get('checked')=='true' for row in rows),timeout=15,interval=.2)
        cross_after=next(row for row in cross_rows if row.get('text')=='我确认将所选文件发送到另一个账号' and row.get('checked')=='true')
        self.tap_label('新建对话并发送')
        rows=self.wait('actual new task journal',self.jobs,lambda value:len([row for row in value if row['id'] not in before_jobs])==1)
        job=next(row for row in rows if row['id'] not in before_jobs)
        self.reviews[job['id']]={'skillId':self.skill['id'],'skillSelection':skill_selection,'targetSelection':target_selection,
                                'crossBefore':cross_before,'crossAfter':cross_after,'unconfirmedGuard':guard}
        assert job['sourceAccountId']==self.manifest['accounts']['A']['id'] and job['targetAccountId']==self.manifest['accounts']['B']['id']
        assert job['sourceStorageId']==self.manifest['accounts']['A']['storageId'] and job['targetStorageId']==self.manifest['accounts']['B']['storageId']
        status=self.wait('actual new browser task document',self.status,
                         lambda value:len(set(value['taskDocuments'])-before_docs)==1)
        document=next(iter(set(status['taskDocuments'])-before_docs));task=status['taskDocuments'][document]
        assert task['mode']==mode and task['identity']=='B' and task['route']=='A' and task['documentLoads']==1
        return job['id'],document

    def task(self,document):
        return self.status()['taskDocuments'][document]

    def validate_sent(self,identifier,document,status='submitted'):
        job=self.terminal(identifier,status)
        task=self.wait('actual single server send',lambda:self.task(document),lambda row:row['sendAttempts']==1 and len(row['sends'])==1)
        assert task['uploadAttempts']==1 and task['identity']=='B' and task['route']=='A'
        sent=task['sends'][0];prompt=sent['prompt']
        assert sent['promptSha256']==job['promptSha256']==hashlib.sha256(prompt.encode()).hexdigest()
        assert '\n\n' in prompt and self.skill['name'] in prompt and self.skill['instructions'] in prompt
        assert '请分析这些附件，说明主要内容并给出处理建议。' in prompt
        assert '附件清单：\n' in prompt and 'owned-report.html.txt' in prompt and 'owned-image.png' in prompt
        assert sent['files']==[{key:row[key] for key in ('name','mime','bytes','sha256')} for row in task['uploads']]
        fence=self.json_file('handoff/sent/'+identifier+'.json')
        assert fence['id']==identifier and fence['tabId']==job['tabId'] and fence['promptSha256']==job['promptSha256']
        if status=='submitted':
            task=self.wait('real whole user message after ACK',lambda:self.task(document),
                lambda row:row['latest'] and row['latest']['userMessages']==[prompt] and row['latest']['draft']==row['latest']['modelDraft']=='')
            assert job['conversationURL']=='https://'+HOST+':18480/c/handoff-'+document
        else:
            assert task['latest'] and task['latest']['userMessages']==[]
        latest=task['latest'];assert latest['inputCount']>=1 and latest['trustedInputCount']>=1
        assert latest['fileChanges']==latest['trustedFileChanges']==1
        return {'job':job,'document':document,'server':task,'sendFence':fence,'originalsAfter':self.originals(),
                'review':self.reviews[identifier],'nativeStatus':self.readable_status(status)}

    def cleanup(self,identifier):
        names=self.wait('real staging removed',lambda:self.shell('ls','-1',FILES+'handoff/staging').splitlines(),
                        lambda value:identifier not in value,timeout=20,interval=.3)
        assert identifier not in names
        return names

    def hot_pages(self):
        deadline=time.monotonic()+60
        self.control('open','A',0,deadline=deadline)
        owner=self.manifest['accounts']['A'];tab_id=owner['tabs'][0]['id']
        def selected_source():
            remaining=deadline-time.monotonic()
            if remaining<=0:raise TimeoutError('Source account open exceeded its original deadline')
            state=json.loads(self.shell('cat',FILES+'workspace.json',timeout=min(10,remaining)))
            if state['activeAccount']!=owner['id']:return None
            account=next(row for row in state['accounts'] if row['id']==owner['id'])
            if account['activeTab']!=tab_id:return None
            title=next(row['title'] for row in account['tabs'] if row['id']==tab_id)
            rows=self.ui(deadline=deadline)
            for label in ('切换账号，当前：环境 A','当前标签页：'+title):
                found=[row for row in rows if row.get('package')==PACKAGE and row.get('content-desc')==label
                       and row.get('enabled')=='true' and row.get('visible-to-user','true')=='true']
                if not found:return None
                assert len(found)==1,'Ambiguous native source account header'
            return state
        remaining=deadline-time.monotonic()
        if remaining<=0:raise TimeoutError('Source account open exceeded its original deadline')
        self.wait('source account and tab actually foreground',selected_source,lambda value:value is not None,
                  timeout=remaining,interval=.2)
        self.route_page('A',self.source_page['document'],self.source_draft)
        self.header_switch('A',1);self.header_switch('A',0)
        status,page=self.route_page('A',self.source_page['document'],self.source_draft)
        for path,before in self.hot_baseline.items():assert status['pageCounts'][path]==before
        documents={path:list(status['pages'][path]) for path in self.hot_baseline}
        assert documents==self.hot_documents
        return {'document':page['document'],'draft':page['draft'],'modelDraft':page['modelDraft'],
                'pageCounts':{path:status['pageCounts'][path] for path in self.hot_baseline},'documents':documents}


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--fixture',type=Path,default=Path(os.environ.get('STUDIO_GECKO_FIXTURE','android/network-poc/secure-fixture/fixture.json')))
    parser.add_argument('--apk',type=Path,default=Path(os.environ.get('STUDIO_GECKO_APK','android/app/build/environment-client-input/gecko-network-x86.apk')))
    parser.add_argument('--receipt',type=Path,default=Path(os.environ.get('STUDIO_GECKO_RECEIPT','android/app/build/environment-client-input/gecko-network-receipt.json')))
    parser.add_argument('--output',type=Path,default=Path(os.environ.get('STUDIO_GECKO_OUTPUT','android/app/build/gecko-handoff-evidence')))
    args=parser.parse_args();acceptance=None;guarded=False;capture_failure=False;main_pid=None
    native.EVIDENCE=args.output;common.EVIDENCE=args.output
    try:
        api=native._guard();guarded=True
        devices=native._run(['adb','devices']).stdout.strip().splitlines()[1:]
        assert len(devices)==1 and devices[0].split()[1:]==['device']
        args.output.mkdir(parents=True,exist_ok=True)
        assert not native._shell('pm','list','packages',PACKAGE).strip(),'Fresh emulator package only'
        receipt=native._json(args.receipt);version=validate_receipt(receipt)
        source=os.environ.get('STUDIO_GECKO_APPLICATION_SHA',os.environ['GITHUB_SHA'])
        assert re.fullmatch(r'[a-f0-9]{40}',source) and receipt['sourceSHA']==source
        signer=native._json(Path('docs/signing/android-network-beta.json'))['certificateSHA256']
        assert receipt['ciOnly'] is True and receipt['package']==PACKAGE and receipt['versionCode']==VERSION_CODE and receipt['signerSHA256']==signer
        assert receipt['file']==args.apk.name and receipt['bytes']==args.apk.stat().st_size and receipt['sha256']==native._sha(args.apk)
        apk=native._inspect_apk(args.apk,native._tools(),signer,PACKAGE,VERSION_CODE)
        assert apk['versionName']==version['versionName'] and apk['sha256']==receipt['sha256']
        manifest=native._json(args.fixture);assert manifest['schemaVersion']==2 and manifest['ready'] is True
        assert native._json(args.fixture.parent/'ready.json')['ready'] is True
        tls=native._json(args.fixture.parent/'gecko-origin-tls.json')
        assert tls['caUnchanged'] is True and tls['leafIsCA'] is False and tls['purpose']=='sslserver'
        assert tls['caCertificateSha256']==manifest['certificateSha256'] and tls['dnsSANs']==[HOST,'files.network-fixture.test']
        ca=native._run(['openssl','x509','-in',str(args.fixture.parent/'ca.pem'),'-noout','-fingerprint','-sha256']).stdout
        match=re.search(r'Fingerprint=([a-fA-F0-9:]+)',ca);assert match and match[1].replace(':','').lower()==manifest['certificateSha256']
        with zipfile.ZipFile(args.apk) as archive:
            skills=json.loads(archive.read('assets/capabilities/skills.json'))['skills']
            skill=next(row for row in skills if row['id']=='skill-001')
        native._root_and_block()
        for port in {urllib.parse.urlsplit(manifest['transports']['raw'][key]).port for key in ('nodeA','nodeB')}:
            assert port in (18443,18444)
            native._shell('iptables','-I','OUTPUT','1','-p','tcp','-d','10.0.2.2','--dport',str(port),'-j','ACCEPT')
        for tool in ('iptables','ip6tables'):native._shell(tool,'-I','OUTPUT','1','-o','lo','-j','ACCEPT')
        (args.output/'egress.txt').write_text(native._shell('iptables-save')+native._shell('ip6tables-save'))
        native._run(['adb','install',str(args.apk)]);installed=native._installed(apk['sha256'],'gecko-handoff')
        match=re.fullmatch(r'package:'+re.escape(PACKAGE)+r' uid:(\d+)',native._shell('pm','list','packages','-U',PACKAGE).strip());assert match
        acceptance=HandoffAcceptance(api,int(match[1]),args.fixture,args.output);acceptance.skill=skill
        assert acceptance.owned_http('/gecko-network/control/configure',{'transport':'raw','runId':str(uuid.uuid4())})['ok'] is True
        acceptance.prepare(args.fixture.parent/'ca.pem');acceptance.configure(manifest)
        main_pid=acceptance.shell('pidof',PACKAGE).strip();assert re.fullmatch(r'[1-9][0-9]*',main_pid)
        acceptance.control('open','A',0);acceptance.route_page('A')
        definition_module=importlib.util.spec_from_file_location('owned_task_asset_definitions',Path(__file__).resolve().parents[1]/'network-poc/scripts/gecko_handoff_fixture.py')
        definitions=importlib.util.module_from_spec(definition_module);definition_module.loader.exec_module(definitions)
        acceptance.definitions={name:{'name':name,'mime':mime,'bytes':len(body),'sha256':hashlib.sha256(body).hexdigest(),'base64':base64.b64encode(body).decode('ascii')} for name,(mime,body) in definitions.assets().items()}
        acceptance.download_originals();originals=acceptance.originals()
        draft='OwnedHandoffSource-'+uuid.uuid4().hex;acceptance.type_draft('A',0,draft)
        acceptance.source_draft=draft;acceptance.source_page=acceptance.latest(acceptance.status(),'/c/a1')
        acceptance.control('open','A',1);acceptance.page('A',1)
        acceptance.control('open','B',0);acceptance.page('B',0)
        baseline=acceptance.status();acceptance.hot_baseline={path:baseline['pageCounts'][path] for path in ('/c/a1','/c/a2','/c/b1')}
        acceptance.hot_documents={path:list(baseline['pages'][path]) for path in acceptance.hot_baseline}
        identifier,document=acceptance.launch('normal',verify_cross=True)
        positive=acceptance.validate_sent(identifier,document);positive['stagingAfter']=acceptance.cleanup(identifier);positive['hotPages']=acceptance.hot_pages()
        identifier,document=acceptance.launch('upload-hold');acceptance.pending_tasks.add(document)
        acceptance.wait('real upload held after bytes received',lambda:acceptance.task(document),
            lambda row:row['uploadAttempts']==1 and len(row['uploads'])==2 and row['latest']
            and len(row['latest']['browserFiles'])==2 and row['latest']['uploadBusy'] is True)
        acceptance.wait('native uploading task',lambda:acceptance.job(identifier),lambda row:row['status']=='uploading',timeout=20)
        acceptance.tap_native_action('停止自动任务',description=True)
        cancelled=acceptance.terminal(identifier,'cancelled');acceptance.release_task(document)
        assert acceptance.task(document)['sendAttempts']==0 and acceptance.optional_json('handoff/sent/'+identifier+'.json') is None
        staging=acceptance.cleanup(identifier)
        native_status=acceptance.readable_status('cancelled')
        cancellation={'job':cancelled,'document':document,'server':acceptance.task(document),'originalsAfter':acceptance.originals(),'hotPages':acceptance.hot_pages(),'review':acceptance.reviews[identifier],
                      'sendFence':acceptance.optional_json('handoff/sent/'+identifier+'.json'),'stagingAfter':staging,'nativeStatus':native_status}
        identifier,document=acceptance.launch('upload-error')
        failed=acceptance.terminal(identifier,'failed');task=acceptance.task(document)
        assert task['uploadAttempts']==1 and task['sendAttempts']==0 and acceptance.optional_json('handoff/sent/'+identifier+'.json') is None
        acceptance.wait('real upload error in DOM',lambda:acceptance.task(document),lambda row:row['latest'] and row['latest']['error'] is True)
        staging=acceptance.cleanup(identifier)
        native_status=acceptance.readable_status('failed')
        failure={'job':failed,'document':document,'server':acceptance.task(document),'originalsAfter':acceptance.originals(),'hotPages':acceptance.hot_pages(),'review':acceptance.reviews[identifier],
                 'sendFence':acceptance.optional_json('handoff/sent/'+identifier+'.json'),'stagingAfter':staging,'nativeStatus':native_status}
        identifier,document=acceptance.launch('normal')
        recovery=acceptance.validate_sent(identifier,document);recovery['stagingAfter']=acceptance.cleanup(identifier);recovery['hotPages']=acceptance.hot_pages()
        assert acceptance.job(failed['id'])['status']=='failed'
        identifier,document=acceptance.launch('send-ack-drop')
        uncertain=acceptance.validate_sent(identifier,document,'uncertain');uncertain['stagingAfter']=acceptance.cleanup(identifier)
        acceptance.tap_native_action('更多功能',description=True)
        acceptance.tap_native_action('查看网页任务',description=True)
        acceptance.tap_label('结果待确认 · '+uncertain['job']['label'],prefix=True)
        acceptance.tap_label('打开目标对话')
        acceptance.wait('native reopened the original uncertain task tab',lambda:acceptance.json_file('workspace.json'),
            lambda row:row['activeAccount']==acceptance.manifest['accounts']['B']['id'] and any(
                account['id']==acceptance.manifest['accounts']['B']['id'] and account['activeTab']==uncertain['job']['tabId']
                for account in row['accounts']),timeout=20)
        acceptance.native_action('当前标签页：合成任务 '+document,description=True)
        current=acceptance.task(document)
        assert current['sendAttempts']==1 and acceptance.job(identifier)['status']=='uncertain'
        uncertain['afterNativeReopen']={'job':acceptance.job(identifier),'server':current}
        uncertain['hotPages']=acceptance.hot_pages()
        final=acceptance.status();assert final['sendCount']==3 and len(final['taskDocuments'])==5
        assert sorted(row['sendAttempts'] for row in final['taskDocuments'].values())==[0,0,1,1,1]
        assert all(row['route'] in ('A','B') for row in final['networkRequests'])
        assert acceptance.originals()==originals
        proof={'schema':1,'complete':True,'api':api,'engine':'gecko','transport':'raw','sourceSHA':os.environ['GITHUB_SHA'],
            'applicationSourceSHA':source,'versionMetadata':version,'ciReceipt':receipt,'candidateAPK':apk,'candidateInstalledSHA256':installed,
            'originTLS':tls,'accounts':acceptance.manifest['accounts'],'originalsBefore':originals,'originalsAfter':acceptance.originals(),
            'mainProcessPID':int(main_pid),'nativeTaskEvents':acceptance.task_events(int(main_pid)),
            'sourceHotBaseline':{'page':acceptance.source_page,'pageCounts':acceptance.hot_baseline,'draft':draft,'documents':acceptance.hot_documents},
            'positive':positive,'cancelled':cancellation,'failed':failure,'explicitNewTaskRecovery':recovery,'uncertain':uncertain,
            'finalJobs':acceptance.jobs(),'origin':final,'networkStates':acceptance.network_observations,
            'nativeAsciiKeyboardReceipts':acceptance.keyboard_events,'nativePrefixReceipts':acceptance.input_prefix_receipts,
            'nativeInputStreams':acceptance.input_stream_receipts,'sendCount':3,'noDirect':True,
            'screenshots':False,'realServicesAccessed':False,'adbReverseOriginUsed':False}
        native._write_json(args.output/'gecko-handoff.json',proof)
        print(json.dumps({'complete':True,'api':api,'cases':5,'sendCount':3,'proofFile':str(args.output/'gecko-handoff.json')}),flush=True)
        return proof
    except Exception:
        capture_failure=True;native._record_failure();raise
    finally:
        if acceptance is not None:
            if capture_failure:
                # Observe the failed attempt once. Never tap, resume, retry or replace its first error.
                observations=[('server',lambda:acceptance.http('/status')),
                              ('journal',acceptance.jobs),
                              ('workspace',lambda:acceptance.json_file('workspace.json')),
                              ('native-ui',acceptance.failure_ui),
                              ('window-focus',acceptance.failure_focus)]
                if main_pid is not None:
                    observations.append(('native-task-events',lambda:acceptance.task_events(int(main_pid))))
                for label,observe in observations:
                    try:native._write_json(args.output/('failure-final-'+label+'.json'),observe())
                    except Exception:(args.output/('failure-final-'+label+'-error.txt')).write_text(traceback.format_exc())
            for document in list(acceptance.pending_tasks):
                try:acceptance.release_task(document)
                except Exception:(args.output/'task-release-cleanup-error.txt').write_text(traceback.format_exc())
        if guarded:native._logcat('gecko-handoff')


if __name__=='__main__':main()
