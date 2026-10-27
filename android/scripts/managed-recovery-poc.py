#!/usr/bin/env python3
"""Real managed-recovery acceptance; fixture never writes restored values."""
from pathlib import Path
from http.server import ThreadingHTTPServer
import base64
import hashlib
import importlib.util
import json
import os
import re
import signal
import subprocess
import sys
import threading
import time
import uuid
import xml.etree.ElementTree as ET

spec=importlib.util.spec_from_file_location('native_fixture',Path(__file__).with_name('recovery-environment-poc.py'))
base=importlib.util.module_from_spec(spec);spec.loader.exec_module(base)
CORE='--core' in sys.argv
TABS=(('A','0'),) if CORE else (('A','0'),('A','1'),('B','0'),('C','0'))
base.TABS=TABS
OUT=base.OUT;PACKAGE=base.PACKAGE
markers={a+':'+t:'demo-'+a+'-'+t+'-'+uuid.uuid4().hex for a,t in TABS}
latest_markers={key:value+'-latest' for key,value in markers.items()}
wait=base.wait;text=base.text;adb=base.adb

class Handler(base.Handler):
    def reply(self,body,status=200,mime='text/html; charset=utf-8'):
        if isinstance(body,str) and '<script>' in body and 'function observation()' in body:
            body+='''<script>
const studioModels={standard:document.getElementById('standard').value,rich:document.getElementById('rich').innerText};
const nativeObservation=observation;
observation=()=>({...nativeObservation(),rich:document.getElementById('rich').innerText,standardModel:studioModels.standard,richModel:studioModels.rich,pageNativeAPI:typeof browser!=='undefined'&&!!browser.runtime?.connectNative});
document.addEventListener('input',event=>{if(event.isTrusted){studioModels.standard=document.getElementById('standard').value;studioModels.rich=document.getElementById('rich').innerText;}});
window.postMessage({type:'capture',token:'forged-web-token',snapshot:{rich:'UNTRUSTED-FORGED-DRAFT'}},'*');
report();
</script>'''
        super().reply(body,status,mime)

def native(a):
    try:return json.loads(adb('exec-out','run-as',PACKAGE,'cat','files/managed-native-'+a+'.json',check=False))
    except (ValueError,TypeError):return {}

def native_events(a):
    rows=[]
    for line in adb('exec-out','run-as',PACKAGE,'cat','files/managed-events-'+a+'.jsonl',check=False).splitlines():
        try:rows.append(json.loads(line))
        except ValueError:pass
    return rows

def entry(a,t):return next((e for e in native(a).get('entries',[]) if e['id']==t),None)
def latest(a,t):return base.latest(a,t,base.phase)
def component(a):return PACKAGE+'/'+PACKAGE+'.Managed'+a+'Activity'
def launch(a=None,action=None,index=0,tabs=0,sample=None,replace=False):
    args=['shell','am','start','-W','-n',component(a or 'A')]
    if a:args+=['--es','account',a]
    if action:args+=['--es','action',action,'--ei','index',str(index)]
    if tabs:args+=['--ei','tabs',str(tabs)]
    if sample:args+=['--es','sample',base64.b64encode(sample.encode()).decode()]
    if replace:args+=['--ez','replace','true']
    text(*args)

def foreground(a):
    n=native(a);dump=text('shell','dumpsys','activity','activities',check=False);lines=[x for x in dump.splitlines() if 'topResumedActivity' in x]
    if not lines:lines=[x for x in dump.splitlines() if 'mResumedActivity' in x]
    return n if n.get('windowFocused') and any('Managed'+a+'Activity' in x for x in lines) else None

def healthy(a,t,seconds=70):
    def ready():
        n=native(a);e=entry(a,t)
        if n.get('startupError') or n.get('bridgeError'):raise RuntimeError('Native startup failed: '+str(n))
        if e and e['state'] in ('failed','blocked'):raise RuntimeError('Managed page failed: '+str(e))
        return e if e and e['state']=='ready' and e['bridgeConnected'] else None
    return wait(ready,seconds,label='managed ready '+a+'/'+t)

def select(a,t):
    if not foreground(a) or native(a).get('selected')!=int(t):launch(a,'select',int(t))
    wait(lambda:foreground(a),label='focused managed account '+a)
    if entry(a,t) and entry(a,t)['state']=='offline':launch(a,'retry')
    return healthy(a,t)

def same_values(a,t,value,document=None,seconds=40):
    def good():
        r=latest(a,t)
        return r if r and all(r.get(k)==value for k in ('standard','rich','standardModel','richModel')) and (document is None or r['document']!=document) else None
    row=wait(good,seconds,label='real DOM and editor models '+a+'/'+t)
    digest=hashlib.sha256(value.encode()).hexdigest();wait(lambda:(lambda e:e if e and e['state']=='ready' and e['standardSha256']==digest and e['richSha256']==digest else None)(entry(a,t)),20,label='encrypted capture committed')
    return row

def point(a,t,name):
    launch(a,'inspect');wait(lambda:foreground(a),label='native input focus');started=time.monotonic();r=wait(lambda:(lambda r:r if r and r['received']>started else None)(latest(a,t)),label='fresh page point')
    n=native(a);p=r['points'][name];x=round(n['viewX']+p['x']*p['ratio']);y=round(n['viewY']+p['y']*p['ratio'])
    if not n['viewY']<y<n['viewY']+n['viewHeight']:raise RuntimeError('Point outside visible webpage: '+name)
    text('shell','input','tap',str(x),str(y))

def type_values(a,t,value,replace=False):
    select(a,t);launch(a,'scrollTop');wait(lambda:(lambda r:r if r and r['scrollY']<5 else None)(latest(a,t)),label='editors at top')
    for field in ('standard','rich'):
        point(a,t,field);wait(lambda:(lambda r:r if r and r['activeElement']==field else None)(latest(a,t)),label='focused '+field)
        launch(a,'input',sample=value,replace=replace)
        wait(lambda:(lambda r:r if r and r[field]==value and r.get(field+'Model')==value else None)(latest(a,t)),label='trusted input and '+field+' model')
    launch(a,'hideKeyboard');same_values(a,t,value)
    launch(a,'scrollSample');wait(lambda:(lambda r:r if r and abs(r['scrollY']-1400)<80 else None)(latest(a,t)),label='real reading position')

def checkpoint(a,close=False):
    old=native(a).get('elapsedMs',-1);launch(a,'quit' if close else 'checkpoint')
    def result():
        rows=[r for r in native_events(a) if r['elapsedMs']>old]
        if any(r['event'] in ('checkpoint-timeout','checkpoint-unavailable') for r in rows):raise RuntimeError('Managed checkpoint not saved')
        return next((r for r in reversed(rows) if r['event']==('closed' if close else 'checkpoint-saved')),None)
    return wait(result,15,label='managed durable checkpoint')

def capture(name):base.capture('managed-'+name)
def stop():return base.stop()
def restored(case,expected,old_documents,open_app=True):
    base.phase=case
    if open_app:launch()
    wait(lambda:foreground('A'),label='saved launcher account A');assert native('A')['selected']==0
    rows={}
    for a,t in TABS:
        select(a,t);r=same_values(a,t,expected[a+':'+t],old_documents[a+':'+t]);time.sleep(1);r=latest(a,t);assert abs(r['scrollY']-1400)<80;rows[a+':'+t]=r
    return rows

def tree():
    text('shell','uiautomator','dump','--compressed','/sdcard/managed-poc.xml');return ET.fromstring(adb('exec-out','cat','/sdcard/managed-poc.xml'))
def tap_label(label):
    root=tree()
    for node in root.iter('node'):
        if node.get('text')==label:
            m=re.fullmatch(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]',node.get('bounds',''))
            if m:
                x1,y1,x2,y2=map(int,m.groups());text('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2));return
    raise RuntimeError('Native control missing: '+label)

def private(name):return adb('exec-out','run-as',PACKAGE,'cat','files/managed-recovery-records/'+name)
def private_write(name,value):
    if not re.fullmatch('[ABC]-page-[01]\.bin',name):raise ValueError('Invalid fixture file')
    adb('shell','run-as '+PACKAGE+" sh -c 'cat > files/managed-recovery-records/"+name+"'",data=value)

def guard():
    devices=[x.split()[0] for x in text('devices').splitlines()[1:] if x.endswith('\tdevice')]
    if os.environ.get('GITHUB_ACTIONS')!='true' or len(devices)!=1 or not devices[0].startswith('emulator-') or text('shell','getprop','ro.kernel.qemu')!='1':raise SystemExit('One fresh CI emulator required; physical devices refused')
    if base.process_rows()[1]:raise SystemExit('Existing experiment process')
    for name in ('managed-recovery-records','managed-poc-a','managed-poc-b','recovery-records','recovery-poc-a'):
        result=text('shell','run-as',PACKAGE,'sh','-c',"'if [ -e files/"+name+" ]; then echo PRESENT; else echo ABSENT; fi'",check=False)
        if result!='ABSENT':raise SystemExit('Cannot confirm empty independent experiment directory '+name)

guard();report={'sourceSha':os.environ.get('GITHUB_SHA'),'runId':os.environ.get('GITHUB_RUN_ID'),'scope':'Single-account core' if CORE else 'Three-account four-tab managed recovery','api':text('shell','getprop','ro.build.version.sdk'),'fixtureOnly':True,'productionDataUsed':False,'cases':{}}
server=ThreadingHTTPServer(('127.0.0.1',8765),Handler);threading.Thread(target=server.serve_forever,daemon=True).start();text('reverse','tcp:8765','tcp:8765')
def timeout(_signal,_frame):raise TimeoutError('Managed recovery experiment timed out')
signal.signal(signal.SIGTERM,timeout)
failure=None
try:
    print('1/8 Capture actual editor models, text and reading position',flush=True);base.phase='seed'
    launch('A',tabs=1 if CORE else 2)
    for a,t in TABS:
        select(a,t);r=wait(lambda:latest(a,t),label='initial page');point(a,t,'detail');wait(lambda:(lambda r:r if r and r['page']=='detail' else None)(latest(a,t)),label='real detail navigation');healthy(a,t)
        type_values(a,t,markers[a+':'+t]);checkpoint(a)
    baseline={a+':'+t:latest(a,t) for a,t in TABS};report['cases']['initialCapture']=True;capture('captured')

    print('2/8 Normal close restores through app-owned state and trusted input',flush=True)
    if not CORE:checkpoint('B',True)
    select('A','0');checkpoint('A',True)
    rows=restored('normal-close',markers,{k:r['document'] for k,r in baseline.items()});report['cases']['normalClose']=True;capture('normal-restored')

    print('3/8 Updates after restoration must replace the saved cache',flush=True);base.phase='latest-input'
    for a,t in TABS:type_values(a,t,latest_markers[a+':'+t],replace=True);checkpoint(a)
    select('A','0');launch('A','back');healthy('A','0')
    wait(lambda:(lambda r:r if r and r['page']=='start' and r['standardModel']=='' and r['richModel']=='' else None)(latest('A','0')),label='history URL does not inherit another page draft')
    launch('A','forward');healthy('A','0');same_values('A','0',latest_markers['A:0']);report['cases']['historyAndURLIsolation']=True
    select('A','0');checkpoint('A');latest_rows={a+':'+t:latest(a,t) for a,t in TABS};report['cases']['saveAfterRestore']=True

    print('4/8 True process stop, offline read-only native snapshot, reconnect',flush=True)
    old_pids=stop();text('reverse','--remove','tcp:8765');base.phase='offline';launch();time.sleep(8);root=tree();labels=' '.join(n.get('text','')+' '+n.get('content-desc','') for n in root.iter('node'))
    assert '上次保存的对话（只读）' in labels and '合成对话' in labels
    assert not any(n.get('editable')=='true' for n in root.iter('node'))
    assert entry('A','0')['richSha256']==hashlib.sha256(latest_markers['A:0'].encode()).hexdigest()
    assert not any(r['phase']=='offline' for r in base.events)
    report['cases']['offlineReadOnlySnapshot']=True;capture('offline')
    text('reverse','tcp:8765','tcp:8765');base.phase='reconnected';launch('A','retry')
    rows=restored('reconnected',latest_markers,{k:r['document'] for k,r in latest_rows.items()},open_app=False);assert str(native('A')['pid']) not in old_pids;report['cases']['forceStopAndReconnect']=True

    print('5/8 Conflict preserves both versions before user choice',flush=True)
    select('A','0');stop();base.phase='conflict-keep';base.conflict=True;base.current_draft='current-web-'+uuid.uuid4().hex;first_current=base.current_draft;launch('A')
    wait(lambda:entry('A','0') and entry('A','0')['state']=='conflict',label='native conflict prompt');r=wait(lambda:latest('A','0'),label='live conflicting values');assert all(r[k]==first_current for k in ('standard','rich','standardModel','richModel'));assert entry('A','0')['pendingConflict'];capture('conflict')
    tap_label('保留网页草稿');healthy('A','0');same_values('A','0',first_current);assert entry('A','0')['alternativeCount']>=1;report['cases']['keepCurrentConflict']=True

    print('6/8 Explicit restore uses native editor input and preserves other draft',flush=True)
    stop();base.phase='conflict-restore';base.current_draft='current-web-'+uuid.uuid4().hex;second_current=base.current_draft;launch('A')
    wait(lambda:entry('A','0') and entry('A','0')['state']=='conflict',label='second conflict prompt');r=wait(lambda:latest('A','0'),label='second current draft');assert r['richModel']==second_current
    tap_label('恢复上次草稿');healthy('A','0');same_values('A','0',first_current);assert entry('A','0')['alternativeCount']>=2
    hashes=entry('A','0')['alternativeHashes'];assert any(p['currentRichSha256']==hashlib.sha256(second_current.encode()).hexdigest() and p['savedRichSha256']==hashlib.sha256(first_current.encode()).hexdigest() for p in hashes)
    launch('A','backups');root=tree();labels=' '.join(n.get('text','') for n in root.iter('node'));assert first_current in labels and second_current in labels;tap_label('关闭');report['cases']['restoreSavedConflictAndBackupAccess']=True

    print('7/8 Reject damaged encrypted record and retain original bytes',flush=True)
    stop();base.phase='corrupt-record';base.conflict=False;original=private('A-page-0.bin');damaged=bytearray(original);damaged[-1]^=1;private_write('A-page-0.bin',bytes(damaged));launch('A')
    wait(lambda:entry('A','0') and entry('A','0')['blocked'],label='corrupt record blocked');assert private('A-page-0.bin')==bytes(damaged);report['cases']['corruptRecordPreserved']=True
    if not CORE:
        stop();base.phase='foreign-record';private_write('C-page-0.bin',original);launch('C')
        wait(lambda:entry('C','0') and entry('C','0')['blocked'],label='cross-account encrypted record blocked');assert private('C-page-0.bin')==original;report['cases']['crossAccountRecordPreserved']=True

    print('8/8 Verify no sends, forged webpage access or foreign cookies',flush=True)
    assert not base.submissions and all(not r.get('pageNativeAPI',False) for r in base.events)
    assert not any(r['rich']=='UNTRUSTED-FORGED-DRAFT' for r in base.events)
    report['cases']['noAutomaticSendAndNoWebNativeAccess']=True
    report['experimentComplete']=True
except Exception as error:
    failure=str(error);report['failure']=failure;print('Managed recovery failure:',failure,flush=True)
    try:capture('failure')
    except Exception as diagnostic:report['diagnosticFailure']=str(diagnostic)
finally:
    report['native']={a:native(a) for a in ('A','B','C')};report['submissions']=base.submissions;report['processStops']=base.process_stops
    required={'initialCapture','normalClose','saveAfterRestore','historyAndURLIsolation','offlineReadOnlySnapshot','forceStopAndReconnect','keepCurrentConflict','restoreSavedConflictAndBackupAccess','corruptRecordPreserved','noAutomaticSendAndNoWebNativeAccess'}
    if not CORE:required.add('crossAccountRecordPreserved')
    report['managedControllerReady']=bool(report.get('experimentComplete')) and all(report['cases'].get(name,False) for name in required)
    report['formalMigrationReady']=False
    report['limits']=['Synthetic textarea/contenteditable editor models only; no real ChatGPT.','Touch scrolling, OEM/physical devices, production lifecycle integration and package-size work remain separate gates.','This controller intentionally avoids blanket native form restoration; browser history is tracked in app-owned records.']
    for name,value in (('managed-report.json',report),('managed-events.json',base.events),('managed-requests.json',base.requests)):(OUT/name).write_text(json.dumps(value,ensure_ascii=False,indent=2))
    for a in ('A','B','C'):(OUT/('managed-native-'+a+'.jsonl')).write_bytes(adb('exec-out','run-as',PACKAGE,'cat','files/managed-events-'+a+'.jsonl',check=False))
    (OUT/'managed-runtime.log').write_text(text('logcat','-d',check=False,timeout=30));text('shell','am','force-stop',PACKAGE,check=False);text('reverse','--remove','tcp:8765',check=False);server.shutdown()
if failure or not report['managedControllerReady']:raise SystemExit(1)
