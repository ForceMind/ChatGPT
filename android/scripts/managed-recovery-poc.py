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
import traceback
from types import SimpleNamespace
import uuid
import xml.etree.ElementTree as ET
from poc_warning_report import localize

spec=importlib.util.spec_from_file_location('native_fixture',Path(__file__).with_name('recovery-environment-poc.py'))
base=importlib.util.module_from_spec(spec);spec.loader.exec_module(base)
CORE='--core' in sys.argv
INPUT_DIAGNOSTIC='--input-diagnostic' in sys.argv
WARNING_PROBE='--warning-probe' in sys.argv
LONG_BACKGROUND='--long-background' in sys.argv
DOZE_ONLY='--doze-only' in sys.argv
RESOURCE_PROFILE='--resource-profile' in sys.argv
if DOZE_ONLY and not LONG_BACKGROUND:raise SystemExit('Doze-only requires prolonged measurement mode')
TRACE_INPUT=INPUT_DIAGNOSTIC or WARNING_PROBE or '--trace-input' in sys.argv
if CORE and INPUT_DIAGNOSTIC:raise SystemExit('Choose core or input diagnostic, not both')
CAPTURE_UI='--capture-ui' in sys.argv
TABS=(('A','0'),('A','1')) if INPUT_DIAGNOSTIC or WARNING_PROBE else (('A','0'),) if CORE else (('A','0'),('A','1'),('B','0'),('C','0'))
if sum((CORE,INPUT_DIAGNOSTIC,WARNING_PROBE,LONG_BACKGROUND))>1:raise SystemExit('Choose one independent experiment mode')
if RESOURCE_PROFILE and not LONG_BACKGROUND:raise SystemExit('Resource profile requires measurement mode')
OBSERVER_POLICY=os.environ.get('POC_OBSERVER_POLICY','bounded')
if OBSERVER_POLICY not in ('legacy','bounded'):raise SystemExit('Unknown observation policy')
base.TABS=TABS
OUT=base.OUT;PACKAGE=base.PACKAGE
markers={a+':'+t:'demo-'+a+'-'+t+'-'+uuid.uuid4().hex for a,t in TABS}
latest_markers={key:value+'-latest' for key,value in markers.items()}
if not CORE and not INPUT_DIAGNOSTIC:latest_markers['A:0']+='\n第二行恢复验证 🙂'
wait=base.wait;text=base.text;adb=base.adb

class Handler(base.Handler):
    def reply(self,body,status=200,mime='text/html; charset=utf-8'):
        if body=='{}' and mime=='application/json' and self.path=='/observe':body=json.dumps({'mutationStorm':base.phase=='prolonged-mutation-storm'})
        if isinstance(body,str) and '<script>' in body and 'function observation()' in body:
            # Install before the source's first report/interval so an initial
            # unbounded request cannot escape the controlled transport.
            observer=Path(__file__).with_name('resource-observer.js').read_text().replace('STUDIO_OBSERVER_POLICY',json.dumps(OBSERVER_POLICY))
            body=body.replace("document.getElementById('form').onsubmit=",observer+"\ndocument.getElementById('form').onsubmit=",1)
            body+='''<style>#rich{white-space:pre-wrap;overflow-wrap:anywhere}</style><script>
const studioInputEvents=[];
const studioTouch={starts:0,terminals:0,untrusted:0};
for(const type of ['touchstart','touchend','touchcancel'])document.addEventListener(type,event=>{if(type==='touchstart')studioTouch.starts++;else studioTouch.terminals++;if(!event.isTrusted)studioTouch.untrusted++;},{passive:true,capture:true});
for(const type of ['beforeinput','input','focusin','focusout'])document.addEventListener(type,event=>{studioInputEvents.push({type,target:event.target.id,trusted:event.isTrusted,inputType:event.inputType||'',dataLength:event.data?.length||0,activeElement:document.activeElement?.id,at:performance.now()});if(studioInputEvents.length>24)studioInputEvents.shift();});
const studioModels={standard:document.getElementById('standard').value,rich:document.getElementById('rich').innerText};
const nativeObservation=observation;
observation=()=>({...nativeObservation(),rich:document.getElementById('rich').innerText,standardModel:studioModels.standard,richModel:studioModels.rich,inputEvents:studioInputEvents,touch:studioTouch,documentFocused:document.hasFocus(),visibility:document.visibilityState,pageNativeAPI:typeof browser!=='undefined'&&!!browser.runtime?.connectNative});
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
def state_entry(a,t,state):
    value=entry(a,t);return value if value and value.get('state')==state else None
def latest(a,t):return base.latest(a,t,base.phase)
def mark_phase(phase,step='begin'):
    if not re.fullmatch('[a-z0-9-]+',phase) or not re.fullmatch('[a-z0-9-]+',step):raise ValueError('Invalid fixture marker')
    base.phase=phase;text('shell','log','-t','StudioPocPhase','-p','i','phase='+phase,'step='+step)
def component(a):return PACKAGE+'/'+PACKAGE+'.Managed'+a+'Activity'
def launch(a=None,action=None,index=0,tabs=0,sample=None,replace=False):
    args=['shell','am','start','-W','-n',component(a or 'A')]
    if TRACE_INPUT:args+=['--ez','diagnoseInput','true']
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

def current_values(a,t,value):
    return wait(lambda:(lambda r:r if r and all(r.get(k)==value for k in ('standard','rich','standardModel','richModel')) else None)(latest(a,t)),label='complete current editor models')

def reading_sample(a,t):
    # Native scroll setup is idempotent. A fast input/IME transition may ignore
    # the first PanZoom command; never retry an accepted text commit instead.
    attempts=[]
    for attempt in range(3):
        issued=time.monotonic();launch(a,'scrollSample');deadline=time.monotonic()+4;first_good=None
        while time.monotonic()<deadline:
            row=latest(a,t)
            if row and row['received']>issued and abs(row['scrollY']-1400)<80:
                if first_good is None:first_good=row['received']
                if row['received']-first_good>=.6:
                    report.setdefault('readingSetup',[]).append({'account':a,'tab':t,'attempts':attempt+1,'observations':attempts,'stableAt':row['scrollY']});return
            else:first_good=None
            if row:attempts.append({'attempt':attempt+1,'received':row['received'],'scrollY':row['scrollY']})
            time.sleep(.2)
    raise RuntimeError('Native reading setup did not settle at1400: '+a+'/'+t)

def point(a,t,name):
    launch(a,'inspect');wait(lambda:foreground(a),label='native input focus');started=time.monotonic();r=wait(lambda:(lambda r:r if r and r['received']>started else None)(latest(a,t)),label='fresh page point')
    if name in ('standard','rich'):
        previous=r
        def stable():
            nonlocal previous
            row=latest(a,t)
            if not row or row['document']!=previous['document']:raise RuntimeError('Point document changed')
            if row['received']<=previous['received']:return None
            old=previous;previous=row
            return row if row.get('documentFocused') and all(abs(row['points'][name][k]-old['points'][name][k])<=1 for k in ('x','y')) else None
        r=wait(stable,12,label='stable actual editor coordinates')
    n=native(a);p=r['points'][name];x=round(n['viewX']+p['x']*p['ratio']);y=round(n['viewY']+p['y']*p['ratio'])
    if not n['viewY']<y<n['viewY']+n['viewHeight']:raise RuntimeError('Point outside visible webpage: '+name)
    text('shell','input','tap',str(x),str(y))

def focus_editor(a,t,field):
    document=latest(a,t)['document']
    for attempt in range(3):
        point(a,t,field);started=time.monotonic();deadline=started+3
        while time.monotonic()<deadline:
            row=latest(a,t)
            if row and row['document']!=document:raise RuntimeError('Editor document changed before input')
            if row and row['received']>started and row.get('documentFocused') and row['activeElement']==field:
                report.setdefault('focusSetup',[]).append({'account':a,'tab':t,'editor':field,'attempts':attempt+1});return
            time.sleep(.2)
    raise RuntimeError('Actual editor focus was not confirmed: '+a+'/'+t+'/'+field)

def type_values(a,t,value,replace=False):
    select(a,t);launch(a,'scrollTop');wait(lambda:(lambda r:r if r and r['scrollY']<5 else None)(latest(a,t)),label='editors at top')
    for field in ('standard','rich'):
        focus_editor(a,t,field)
        launch(a,'input',sample=value,replace=replace)
        wait(lambda:(lambda r:r if r and r[field]==value and r.get(field+'Model')==value else None)(latest(a,t)),label='trusted input and '+field+' model')
    launch(a,'hideKeyboard');same_values(a,t,value)
    reading_sample(a,t)

def checkpoint(a,close=False):
    if close:mark_phase(base.phase,'complete-exit-request')
    old=native(a).get('elapsedMs',-1);launch(a,'quit' if close else 'checkpoint')
    def result():
        rows=[r for r in native_events(a) if r['elapsedMs']>old]
        if any(r['event'] in ('checkpoint-timeout','checkpoint-unavailable') for r in rows):raise RuntimeError('Managed checkpoint not saved')
        return next((r for r in reversed(rows) if r['event']==('closed' if close else 'checkpoint-saved')),None)
    receipt=wait(result,15,label='managed durable checkpoint')
    if close:wait(lambda:not any(r['pid']==str(receipt['pid']) and r['state']!='Z' for r in base.process_rows()[1]),20,label='explicit complete app exit')
    return receipt

def capture(name):
    # Screenshot capture requires explicit task authorization; never enabled by CI defaults.
    if CAPTURE_UI:base.capture('managed-'+name)
def stop():
    mark_phase(base.phase,'force-stop-request');return base.stop()
def restored(case,expected,old_documents,open_app=True):
    mark_phase(case)
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
    if not re.fullmatch('[ABC]-page-[01]\.bin(?:\.bak)?',name):raise ValueError('Invalid fixture file')
    adb('shell','run-as '+PACKAGE+" sh -c 'cat > files/managed-recovery-records/"+name+"'",data=value)

def guard():
    devices=[x.split()[0] for x in text('devices').splitlines()[1:] if x.endswith('\tdevice')]
    if os.environ.get('GITHUB_ACTIONS')!='true' or len(devices)!=1 or not devices[0].startswith('emulator-') or text('shell','getprop','ro.kernel.qemu')!='1':raise SystemExit('One fresh CI emulator required; physical devices refused')
    if base.process_rows()[1]:raise SystemExit('Existing experiment process')
    for name in ('managed-recovery-records','managed-poc-a','managed-poc-b','recovery-records','recovery-poc-a'):
        result=text('shell','run-as',PACKAGE,'sh','-c',"'if [ -e files/"+name+" ]; then echo PRESENT; else echo ABSENT; fi'",check=False)
        if result!='ABSENT':raise SystemExit('Cannot confirm empty independent experiment directory '+name)

def input_diagnostic(report):
    print('1/3 Seed two real editors in two same-account pages',flush=True)
    mark_phase('input-diagnostic-seed');launch('A',tabs=2)
    for a,t in TABS:
        select(a,t);wait(lambda:latest(a,t),label='initial diagnostic page')
        point(a,t,'detail');wait(lambda:(lambda r:r if r and r['page']=='detail' else None)(latest(a,t)),label='detail page')
        healthy(a,t);type_values(a,t,markers[a+':'+t]);checkpoint(a)
    before={a+':'+t:latest(a,t) for a,t in TABS}
    report['cases']['initialTwoPageCapture']=True
    print('2/3 Switch to each retained page and verify its document and models',flush=True)
    for a,t in TABS:
        select(a,t);row=same_values(a,t,markers[a+':'+t]);assert row['document']==before[a+':'+t]['document']
    report['cases']['switchWithoutReload']=True
    print('3/3 Complete exit, reopen the two saved pages, compare native writes with DOM/models',flush=True)
    select('A','0');checkpoint('A',True)
    restored('input-diagnostic-restored',markers,{key:value['document'] for key,value in before.items()})
    report['cases']['twoPageExitRestoration']=True
    observed=[row for row in base.events if 'pageNativeAPI' in row]
    assert observed and not base.submissions and all(not row['pageNativeAPI'] for row in observed)
    report['cases']['noSendOrWebNativeAccess']=True
    report['experimentComplete']=True


def swipe(report,direction,x,y1,y2):
    if text('shell','getprop','ro.build.version.sdk')!='28':
        text('shell','input','swipe',str(x),str(y1),str(x),str(y2),'550');return 'stock-adb-input'
    # Android 9's stock input command resets downTime on every move. The fixed
    # engine correctly drops those invalid continuations; do not relax it.
    result=subprocess.run(['adb','shell','env','CLASSPATH=/data/local/tmp/studio-poc-input.jar','app_process','/system/bin','com.forcemind.chatgpt.pocinput.TouchInput',str(x),str(y1),str(x),str(y2),'550'],capture_output=True,text=True,timeout=20)
    (OUT/('touch-input-'+direction+'-stderr.txt')).write_text(result.stderr)
    if result.returncode:raise RuntimeError('CI touchscreen input failed: '+result.stderr[-1600:])
    receipt=json.loads(result.stdout.strip().splitlines()[-1]);events=receipt['events']
    assert len(events)>=3 and events[0]['action']==0 and events[-1]['action']==1 and any(e['action']==2 for e in events)
    assert len({e['downTime'] for e in events})==1 and all(e['source']==4098 and e['toolType']==1 for e in events)
    assert all(b['eventTime']>=a['eventTime'] for a,b in zip(events,events[1:]))
    report.setdefault('touchInputReceipts',[]).append(receipt)
    return receipt['provider']

def touch_and_lifecycle(report):
    print('Additional scope: trusted touch, Home/foreground and Activity recreation',flush=True)
    mark_phase('touch-scroll');select('A','0');launch('A','hideKeyboard')
    n=native('A');x=round(n['viewX']+n['viewWidth']*.5);top=round(n['viewY']+n['viewHeight']*.30);bottom=round(n['viewY']+n['viewHeight']*.78)
    text('shell','input','tap',str(x),str(round(n['viewY']+n['viewHeight']*.6)))
    started=time.monotonic();before=wait(lambda:(lambda r:r if r and r['received']>started and r['activeElement'] not in ('standard','rich') and 'touch' in r else None)(latest('A','0')),label='article touch clears editor focus')
    gestures=[]
    for direction,y1,y2 in (('up',bottom,top),('down',top,bottom)):
        started=time.monotonic();provider=swipe(report,direction,x,y1,y2)
        def changed():
            row=latest('A','0')
            if not row or row['received']<=started or row['document']!=before['document']:return None
            touch=row.get('touch',{});delta=row['scrollY']-before['scrollY']
            return row if touch.get('starts',0)>before['touch']['starts'] and touch.get('terminals',0)>before['touch']['terminals'] and not touch.get('untrusted') and (delta>100 if direction=='up' else delta<-100) else None
        row=wait(changed,15,label='trusted '+direction+' swipe changes actual scroll')
        time.sleep(1);row=latest('A','0');delta=row['scrollY']-before['scrollY'];assert delta>100 if direction=='up' else delta<-100
        gestures.append({'direction':direction,'inputProvider':provider,'before':before['scrollY'],'after':row['scrollY'],'touchStartsAdded':row['touch']['starts']-before['touch']['starts'],'touchTerminalsAdded':row['touch']['terminals']-before['touch']['terminals'],'documentUnchanged':row['document']==before['document']});before=row
    report['touchGestures']=gestures;report['cases']['trustedTouchScrollUpDown']=True

    mark_phase('lifecycle-background');references={a+':'+t:base.latest(a,t) for a,t in TABS};pid=native('A')['pid'];started=time.monotonic()
    text('shell','input','keyevent','KEYCODE_HOME');wait(lambda:not foreground('A'),label='real Home backgrounds app');time.sleep(5)
    background={}
    for a,t in TABS:
        row=wait(lambda:(lambda r:r if r and r['received']>started+2 and r['document']==references[a+':'+t]['document'] else None)(latest(a,t)),12,label='same background document '+a+'/'+t);background[a+':'+t]=row['ticks']-references[a+':'+t]['ticks'];assert background[a+':'+t]>0
    background_seconds=round(time.monotonic()-started,3)
    launch('A','select',0);select('A','0');assert native('A')['pid']==pid
    for a,t in TABS:
        select(a,t);row=same_values(a,t,markers[a+':'+t]);assert row['document']==references[a+':'+t]['document']
    report['backgroundSample']={'backgroundSeconds':background_seconds,'totalRoundTripSeconds':round(time.monotonic()-started,3),'tickDeltas':background,'mainPidUnchanged':True};report['cases']['homeForegroundRetainsDocuments']=True

    mark_phase('lifecycle-recreate');select('A','0');old=native('A')['elapsedMs'];launch('A','recreate')
    wait(lambda:native('A').get('elapsedMs',0)>old and foreground('A'),label='recreated Activity foreground');healthy('A','0');assert native('A')['pid']==pid
    for a,t in TABS:
        select(a,t);row=same_values(a,t,markers[a+':'+t]);assert row['document']==references[a+':'+t]['document']
    report['cases']['activityRecreationRetainsDocuments']=True
    # Separate reset for the existing exact reading-recovery sample. The touch
    # gate above observes only actual input gestures, with no programmatic scroll.
    mark_phase('reading-sample-reset');select('A','0');launch('A','scrollSample');wait(lambda:(lambda r:r if r and abs(r['scrollY']-1400)<80 else None)(latest('A','0')),label='original recovery reading sample');checkpoint('A')

def full_experiment(report):
    print('1/8 Capture actual editor models, text and reading position',flush=True);mark_phase('seed')
    launch('A',tabs=1 if CORE else 2)
    for a,t in TABS:
        select(a,t);r=wait(lambda:latest(a,t),label='initial page');point(a,t,'detail');wait(lambda:(lambda r:r if r and r['page']=='detail' else None)(latest(a,t)),label='real detail navigation');healthy(a,t)
        type_values(a,t,markers[a+':'+t]);checkpoint(a)
    baseline={a+':'+t:latest(a,t) for a,t in TABS};baseline_time=time.monotonic();reference_tokens={a:baseline[a+':0']['owner']['token'] for a in {a for a,t in TABS}}
    if not CORE:assert baseline['A:0']['owner']==baseline['A:1']['owner']
    report['cases']['initialCapture']=True;capture('captured')
    if not CORE:touch_and_lifecycle(report)
    if LONG_BACKGROUND:
        from poc_extended_measurements import long_background
        long_background(SimpleNamespace(**globals(),report_variant=report['runtimeVariant']),report)

    print('2/8 Normal close restores through app-owned state and trusted input',flush=True)
    mark_phase('normal-exit')
    if not CORE:checkpoint('B',True)
    select('A','0');checkpoint('A',True)
    rows=restored('normal-close',markers,{k:r['document'] for k,r in baseline.items()});report['cases']['normalClose']=True;capture('normal-restored')

    print('3/8 Updates after restoration must replace the saved cache',flush=True);mark_phase('latest-input')
    for a,t in TABS:type_values(a,t,latest_markers[a+':'+t],replace=True);checkpoint(a)
    select('A','0');launch('A','back');healthy('A','0')
    wait(lambda:(lambda r:r if r and r['page']=='start' and r.get('standardModel')=='' and r.get('richModel')=='' else None)(latest('A','0')),label='history URL does not inherit another page draft')
    launch('A','forward');healthy('A','0');same_values('A','0',latest_markers['A:0']);report['cases']['historyAndURLIsolation']=True
    select('A','0');checkpoint('A');latest_rows={a+':'+t:latest(a,t) for a,t in TABS};report['cases']['saveAfterRestore']=True

    print('4/8 True process stop, offline read-only native snapshot, reconnect',flush=True)
    old_pids=stop();text('reverse','--remove','tcp:8765');mark_phase('offline');launch();time.sleep(8);root=tree();labels=' '.join(n.get('text','')+' '+n.get('content-desc','') for n in root.iter('node'))
    assert '上次保存的对话（只读）' in labels and '合成对话' in labels
    assert not any(n.get('editable')=='true' for n in root.iter('node'))
    assert entry('A','0')['richSha256']==hashlib.sha256(latest_markers['A:0'].encode()).hexdigest()
    assert not any(r['phase']=='offline' for r in base.events)
    report['cases']['offlineReadOnlySnapshot']=True;capture('offline')
    text('reverse','tcp:8765','tcp:8765');mark_phase('reconnected');launch('A','retry')
    rows=restored('reconnected',latest_markers,{k:r['document'] for k,r in latest_rows.items()},open_app=False);assert str(native('A')['pid']) not in old_pids;report['cases']['forceStopAndReconnect']=True

    print('5/8 Conflict preserves both versions before user choice',flush=True)
    select('A','0');stop();mark_phase('conflict-keep');base.conflict=True;base.current_draft='current-web-'+uuid.uuid4().hex;first_current=base.current_draft;launch('A')
    conflict_snapshot=wait(lambda:state_entry('A','0','conflict'),label='native conflict prompt');r=current_values('A','0',first_current);assert conflict_snapshot['pendingConflict'];capture('conflict')
    if not CORE:
        stop();mark_phase('unresolved-conflict-restart');launch('A');conflict_snapshot=wait(lambda:state_entry('A','0','conflict'),label='unresolved conflict survives restart')
        r=current_values('A','0',first_current);assert conflict_snapshot['richSha256']==hashlib.sha256(latest_markers['A:0'].encode()).hexdigest()
        launch('A','back');healthy('A','0');wait(lambda:(lambda r:r if r and r['page']=='start' and r.get('richModel')=='' and r.get('standardModel')=='' else None)(latest('A','0')),label='pending conflict cannot leak into another URL')
        history_snapshot=entry('A','0');assert not history_snapshot['pendingConflict'] and history_snapshot['pendingConflictCount']==1
        launch('A','forward');wait(lambda:state_entry('A','0','conflict'),label='original URL retains pending conflict')
        r=wait(lambda:(lambda r:r if r and r['page']=='detail' and r.get('richModel')==first_current else None)(latest('A','0')),label='original current draft preserved')
        report['cases']['unresolvedConflictRestartAndURLBinding']=True
    tap_label('保留网页草稿');healthy('A','0');same_values('A','0',first_current);assert entry('A','0')['alternativeCount']>=1;report['cases']['keepCurrentConflict']=True

    print('6/8 Explicit restore uses native editor input and preserves other draft',flush=True)
    stop();mark_phase('conflict-restore');base.current_draft='current-web-'+uuid.uuid4().hex;second_current=base.current_draft;launch('A')
    wait(lambda:state_entry('A','0','conflict'),label='second conflict prompt');r=current_values('A','0',second_current)
    tap_label('恢复上次草稿');healthy('A','0');same_values('A','0',first_current);assert entry('A','0')['alternativeCount']>=2
    hashes=entry('A','0')['alternativeHashes'];assert any(p['currentRichSha256']==hashlib.sha256(second_current.encode()).hexdigest() and p['savedRichSha256']==hashlib.sha256(first_current.encode()).hexdigest() for p in hashes)
    launch('A','backups');root=tree();labels=' '.join(n.get('text','') for n in root.iter('node'));assert first_current in labels and second_current in labels;tap_label('关闭');report['cases']['restoreSavedConflictAndBackupAccess']=True

    print('7/8 Reject damaged encrypted record and retain original bytes',flush=True)
    if not CORE:
        stop();mark_phase('atomic-backup');base.conflict=False;original=private('A-page-0.bin');private_write('A-page-0.bin.bak',original)
        text('shell','run-as',PACKAGE,'rm','files/managed-recovery-records/A-page-0.bin');launch('A');healthy('A','0');same_values('A','0',first_current)
        report['cases']['interruptedAtomicRecordRecovery']=True
    stop();mark_phase('corrupt-record');base.conflict=False;original=private('A-page-0.bin');damaged=bytearray(original);damaged[-1]^=1;private_write('A-page-0.bin',bytes(damaged));launch('A')
    blocked_snapshot=wait(lambda:state_entry('A','0','blocked'),label='corrupt record blocked');assert blocked_snapshot['error']=='saved-record-rejected:AEADBadTagException';assert private('A-page-0.bin')==bytes(damaged);report['cases']['corruptRecordPreserved']=True
    if not CORE:
        stop();mark_phase('foreign-record');private_write('C-page-0.bin',original);launch('C')
        blocked_snapshot=wait(lambda:state_entry('C','0','blocked'),label='cross-account encrypted record blocked');assert blocked_snapshot['error']=='saved-record-rejected:AEADBadTagException';assert private('C-page-0.bin')==original;report['cases']['crossAccountRecordPreserved']=True

    print('8/8 Verify no sends, forged webpage access or foreign cookies',flush=True)
    model_rows=[r for r in base.events if 'pageNativeAPI' in r]
    assert not base.submissions and model_rows and all(not r['pageNativeAPI'] for r in model_rows)
    assert not any(r['rich']=='UNTRUSTED-FORGED-DRAFT' for r in base.events)
    tokens={}
    for r in base.events:
        a=r['account'];lang='en-US' if a=='B' else 'zh-CN';zone='America/New_York' if a=='B' else 'Asia/Shanghai'
        assert r['language']==lang and r['timeZone']==zone and r['acceptLanguage'].lower().startswith(lang.lower())
        assert r['owner']['owner']==a and all(value==a for value in re.findall(r'(?:^|;\s*)recovery-owner=([^;]*)',r['requestCookie']))
        if r['received']>=baseline_time:assert reference_tokens[a]==r['owner']['token']
        tokens[a]=r['owner']['token']
    if not CORE:assert set(tokens)=={'A','B','C'} and len(set(tokens.values()))==3
    for request in base.requests:
        assert request['acceptLanguage'].lower().startswith('en-us' if request['account']=='B' else 'zh-cn')
        assert all(value==request['account'] for value in re.findall(r'(?:^|;\s*)recovery-owner=([^;]*)',request['cookie']))
    report['cases']['accountEnvironmentAndStorageIsolation']=True
    report['cases']['noAutomaticSendAndNoWebNativeAccess']=True
    report['experimentComplete']=True

guard();report={'sourceSha':os.environ.get('GITHUB_SHA'),'runId':os.environ.get('GITHUB_RUN_ID'),'scope':'Two-page native input diagnostic' if INPUT_DIAGNOSTIC else 'Single-account core' if CORE else 'Three-account four-tab managed recovery','api':text('shell','getprop','ro.build.version.sdk'),'fixtureOnly':True,'productionDataUsed':False,'cases':{}}
report['runtimeVariant']=os.environ.get('POC_RUNTIME_VARIANT','debug')
report['observerPolicy']=OBSERVER_POLICY
report['resourceCase']=os.environ.get('POC_RESOURCE_CASE','standard')
report['resourceTuning']=os.environ.get('POC_RESOURCE_TUNING','baseline')
if WARNING_PROBE:report['scope']='Controlled two-page input warning probe'
if report['runtimeVariant']=='optimized-release':
    identity=json.loads((OUT/'runtime-release-identity.json').read_text());device=json.loads((OUT/'release-device-attestation.json').read_text())
    assert identity['debuggable'] is False and identity['payloadUnchangedBySigning'] and identity['temporaryCiCertificate'] and device['nonDebuggableRunAsRefused'] and identity['signedApkSha256']==device['installedApkSha256']
    report['runtimeIdentity']=identity;report['installedReleaseAttestation']=device
server=ThreadingHTTPServer(('127.0.0.1',8765),Handler);threading.Thread(target=server.serve_forever,daemon=True).start();text('reverse','tcp:8765','tcp:8765')
log_output=(OUT/'managed-runtime.log').open('wb');log_errors=(OUT/'managed-logcat-stderr.log').open('wb')
# Stream throughout the experiment: a final logcat dump can silently lose the
# early touch/lifecycle phases when Android's ring buffer wraps.
log_process=subprocess.Popen(['adb','logcat','-v','threadtime','-T','1'],stdout=log_output,stderr=log_errors)
def timeout(_signal,_frame):raise TimeoutError('Managed recovery experiment timed out')
signal.signal(signal.SIGTERM,timeout)
failure=None
try:
    if WARNING_PROBE:
        from poc_extended_measurements import warning_probe
        warning_probe(SimpleNamespace(**globals()),report)
    elif INPUT_DIAGNOSTIC:input_diagnostic(report)
    else:full_experiment(report)
except Exception as error:
    failure=str(error);report['failure']=failure;print('Managed recovery failure:',failure,flush=True)
    (OUT/'managed-failure-traceback.txt').write_text(traceback.format_exc())
    try:capture('failure')
    except Exception as diagnostic:report['diagnosticFailure']=str(diagnostic)
finally:
    report['native']={a:native(a) for a in ('A','B','C')};report['submissions']=base.submissions;report['processStops']=base.process_stops
    required={'initialCapture','normalClose','saveAfterRestore','historyAndURLIsolation','offlineReadOnlySnapshot','forceStopAndReconnect','keepCurrentConflict','restoreSavedConflictAndBackupAccess','corruptRecordPreserved','noAutomaticSendAndNoWebNativeAccess','accountEnvironmentAndStorageIsolation'}
    if not CORE:required.update(('crossAccountRecordPreserved','unresolvedConflictRestartAndURLBinding','interruptedAtomicRecordRecovery','trustedTouchScrollUpDown','homeForegroundRetainsDocuments','activityRecreationRetainsDocuments'))
    report['inputDiagnosticComplete']=INPUT_DIAGNOSTIC and bool(report.get('experimentComplete'))
    if LONG_BACKGROUND:required.update(('prolonged-mutation-storm','prolonged-home','prolonged-forced-doze') if RESOURCE_PROFILE else ('prolonged-forced-doze',) if DOZE_ONLY else ('prolonged-home','prolonged-screen-off','prolonged-forced-doze'))
    report['warningProbeComplete']=WARNING_PROBE and bool(report.get('experimentComplete'))
    report['managedControllerReady']=not (INPUT_DIAGNOSTIC or WARNING_PROBE) and bool(report.get('experimentComplete')) and all(report['cases'].get(name,False) for name in required)
    report['formalMigrationReady']=False
    report['platformFindings']={'unexpectedProcessRestartObserved':any(s['unexpectedNewProcesses'] for s in base.process_stops),'extraForceStopRequests':sum(s['forceStopRequests']-1 for s in base.process_stops),'touchScrollAcceptance':'Real trusted touch starts/terminals and bidirectional scroll measured.' if report['cases'].get('trustedTouchScrollUpDown') else 'Not passed in this run; reading samples use native PanZoom API.'}
    report['limits']=['Synthetic textarea/contenteditable editor models only; no real ChatGPT.','Fresh x86_64 CI emulators and a short background sample; no arm64/physical/OEM, prolonged OS-pressure or production lifecycle guarantee.','Optimized release uses a disposable CI certificate; root is limited to emulator fixture evidence, while browser processes run as the normal app UID.' if report['runtimeVariant']=='optimized-release' else 'Debug runtime does not establish optimized-release acceptance.','The existing reading recovery sample still uses native PanZoom separately from the genuine touch gate.','Warning phases show temporal correlation, not proof of cause or repair.']
    for a in ('A','B','C'):(OUT/('managed-native-'+a+'.jsonl')).write_bytes(adb('exec-out','run-as',PACKAGE,'cat','files/managed-events-'+a+'.jsonl',check=False))
    report['logCaptureComplete']=log_process.poll() is None
    log_process.terminate()
    try:log_process.wait(timeout=5)
    except subprocess.TimeoutExpired:log_process.kill();log_process.wait(timeout=5)
    log_output.close();log_errors.close()
    runtime_log=(OUT/'managed-runtime.log').read_text(errors='replace')
    warnings=localize(runtime_log);(OUT/'managed-warning-localization.json').write_text(json.dumps(warnings,ensure_ascii=False,indent=2))
    report['warningCounts']=warnings['counts'];report['warningCausalAttribution']=False
    if WARNING_PROBE and 'warningControls' in report:
        passive=('warning-passive-open','warning-passive-switch','warning-navigation-without-input','warning-focus-without-commit')
        report['warningControls']['warningsWithoutAnyCommit']={phase:warnings['byPhase'].get(phase,{}).get('noEditableParent',0) for phase in passive}
    if LONG_BACKGROUND:report['limits'][1]='Fresh x86_64 CI emulator; requested 10-minute forced Doze only; Home/screen evidence is from separate runs.' if DOZE_ONLY else 'Fresh x86_64 CI emulators: Home, screen off and forced Doze measured for 10 minutes each; no arm64/physical/OEM, production OS-pressure or permanent survival guarantee.'
    if not report['logCaptureComplete']:failure=failure or 'Continuous device log capture ended unexpectedly';report['failure']=failure;report['managedControllerReady']=False
    for name,value in (('managed-report.json',report),('managed-events.json',base.events),('managed-requests.json',base.requests)):(OUT/name).write_text(json.dumps(value,ensure_ascii=False,indent=2))
    text('shell','am','force-stop',PACKAGE,check=False);text('reverse','--remove','tcp:8765',check=False);server.shutdown()
if failure or not (report['warningProbeComplete'] if WARNING_PROBE else report['inputDiagnosticComplete'] if INPUT_DIAGNOSTIC else report['managedControllerReady']):raise SystemExit(1)
