#!/usr/bin/env python3
"""Measure native Gecko recovery on a fresh CI emulator; no production data."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse, urlencode
import hashlib
import html
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

PACKAGE='com.forcemind.chatgpt.environmentpoc'
COMPONENT=PACKAGE+'/'+PACKAGE+'.RecoveryAActivity'
OUT=Path(sys.argv[1]);OUT.mkdir(parents=True,exist_ok=True)
TABS=(('A','0'),('A','1'),('B','0'),('C','0'))
phase='seed';conflict=False
events=[];requests=[];submissions=[];gestures=[];scroll_samples={};checkpoint_receipts=[];lock=threading.Lock()
markers={a+':'+t:'draft-'+a+'-'+t+'-'+uuid.uuid4().hex for a,t in TABS}
current_draft='server-current-'+uuid.uuid4().hex

def adb(*args,data=None,check=True,timeout=25):
    return subprocess.run(['adb',*args],input=data,capture_output=True,check=check,timeout=timeout).stdout

def text(*args,**kwargs):return adb(*args,**kwargs).decode(errors='replace').strip()
def wait(fn,seconds=50,label='observation'):
    end=time.monotonic()+seconds
    while time.monotonic()<end:
        value=fn()
        if value:return value
        time.sleep(.4)
    raise RuntimeError('Timed out: '+label)

def native(a):
    try:return json.loads(adb('exec-out','run-as',PACKAGE,'cat','files/recovery-native-'+a+'.json',check=False))
    except (ValueError,TypeError):return {}

def native_events(a):
    raw=adb('exec-out','run-as',PACKAGE,'cat','files/recovery-events-'+a+'.jsonl',check=False)
    rows=[]
    for line in raw.splitlines():
        try:rows.append(json.loads(line))
        except ValueError:pass
    return rows

def launch(a=None,action=None,index=0,tabs=0,sample=None):
    args=['shell','am','start','-W','-n',COMPONENT]
    if a:args+=['--es','account',a]
    if action:args+=['--es','action',action,'--ei','index',str(index)]
    if tabs:args+=['--ei','tabs',str(tabs)]
    if sample:args+=['--es','sampleText',sample]
    text(*args)

def latest(a,t,case=None):
    with lock:return next((dict(r) for r in reversed(events) if r['account']==a and r['tab']==t and (case is None or r['phase']==case)),None)

def fresh(a,t,case,after,document=None):
    row=latest(a,t,case)
    return row if row and row['received']>after and (document is None or row['document']!=document) else None

class Handler(BaseHTTPRequestHandler):
    def log_message(self,*args):pass
    def reply(self,body,status=200,mime='text/html; charset=utf-8'):
        if isinstance(body,str):body=body.encode()
        self.send_response(status);self.send_header('Content-Type',mime);self.send_header('Content-Length',str(len(body)));self.send_header('Cache-Control','no-store');self.end_headers()
        try:self.wfile.write(body)
        except (BrokenPipeError,ConnectionResetError):pass
    def do_POST(self):
        length=int(self.headers.get('Content-Length','0'))
        if length>16384:self.reply('too large',413);return
        try:data=json.loads(self.rfile.read(length))
        except ValueError:self.reply('bad json',400);return
        data.update(phase=phase,received=time.monotonic(),requestCookie=self.headers.get('Cookie',''),acceptLanguage=self.headers.get('Accept-Language',''))
        with lock:
            if self.path=='/observe':events.append(data)
            elif self.path=='/submit':submissions.append(data)
            else:self.reply('bad route',404);return
        self.reply('{}',mime='application/json')
    def do_GET(self):
        uri=urlparse(self.path);q=parse_qs(uri.query);a=q.get('account',[''])[0];t=q.get('tab',[''])[0];page=q.get('page',[''])[0]
        if uri.path!='/recovery' or (a,t) not in TABS or page not in ('start','detail'):self.reply('not found',404);return
        doc=uuid.uuid4().hex;prefill=current_draft if conflict and (a,t)==('A','0') and page=='detail' else ''
        with lock:requests.append({'path':self.path,'account':a,'tab':t,'page':page,'phase':phase,'document':doc,'prefill':prefill,'cookie':self.headers.get('Cookie',''),'acceptLanguage':self.headers.get('Accept-Language',''),'received':time.monotonic()})
        detail='/recovery?'+urlencode({'account':a,'tab':t,'page':'detail'})
        content=''.join('<p>合成对话 '+str(i)+' · 恢复阅读位置测试，不含个人数据。</p>' for i in range(160))
        config=json.dumps({'account':a,'tab':t,'page':page,'document':doc,'prefill':prefill})
        self.reply('''<!doctype html><meta name="viewport" content="width=device-width,initial-scale=1"><title>Recovery '''+a+'/'+t+' '+page+'''</title>
<style>body{font:18px sans-serif;margin:18px;color:#111;background:white}textarea,[contenteditable]{display:block;box-sizing:border-box;width:100%;min-height:62px;border:1px solid #333;padding:8px;margin:12px 0}p{margin:0;padding:12px 0}button,a{font:18px sans-serif;padding:10px}#summary{font-size:14px}</style>
<h2>原生页面恢复 · '''+a+'/'+t+' '+page+'''</h2><a id="detail" href="'''+html.escape(detail,quote=True)+'''">打开测试对话</a>
<form id="form"><label>标准表单草稿<textarea id="standard" name="draft">'''+html.escape(prefill)+'''</textarea></label>
<label>动态编辑器草稿<div id="rich" contenteditable="true" role="textbox">'''+html.escape(prefill)+'''</div></label><button type="submit">发送测试消息</button></form>
<div id="summary"></div><article>'''+content+'''</article><script>
const base='''+config+''';const documentId=base.document;let ticks=0,lastClick='',lastClickTrusted=false;
const previous=localStorage.getItem('recovery-owner');if(!previous)localStorage.setItem('recovery-owner',JSON.stringify({owner:base.account,token:crypto.randomUUID()}));
if(!document.cookie)document.cookie='recovery-owner='+base.account+'; SameSite=Lax; path=/';
function rect(id){const r=document.getElementById(id).getBoundingClientRect();return {x:r.x+r.width/2,y:r.y+r.height/2,ratio:devicePixelRatio};}
function observation(){return {...base,document:documentId,ticks:++ticks,url:location.href,standard:document.getElementById('standard').value,rich:document.getElementById('rich').textContent,activeElement:document.activeElement?.id,lastClick,lastClickTrusted,scrollY,scrollHeight:document.documentElement.scrollHeight,viewportHeight:innerHeight,language:navigator.language,timeZone:new Intl.DateTimeFormat().resolvedOptions().timeZone,cookie:document.cookie,owner:JSON.parse(localStorage.getItem('recovery-owner')),points:{detail:rect('detail'),standard:rect('standard'),rich:rect('rich')}};}
async function report(){try{await fetch('/observe',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(observation())});}catch(e){}}
document.getElementById('form').onsubmit=async e=>{e.preventDefault();await fetch('/submit',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(observation())});};
addEventListener('click',e=>{lastClick=e.target.id;lastClickTrusted=e.isTrusted;report();});addEventListener('input',report);addEventListener('scroll',report);setInterval(report,500);report();
</script>''')

def point_tap(a,t,name):
    started=time.monotonic();old=native(a).get('elapsedMs',-1);launch(a,'inspect')
    def focused():
        n=native(a)
        if n.get('elapsedMs',0)<=old:n=None
        dump=text('shell','dumpsys','activity','activities',check=False)
        lines=[line.strip() for line in dump.splitlines() if 'topResumedActivity' in line]
        if not lines:lines=[line.strip() for line in dump.splitlines() if 'mResumedActivity' in line]
        return n if n and n.get('windowFocused') and any('Recovery'+a+'Activity' in line for line in lines) else None
    geo=wait(focused,label='focused current-account native geometry')
    row=wait(lambda:fresh(a,t,phase,started),label='fresh page coordinates');p=row['points'][name]
    x=round(geo['viewX']+p['x']*p['ratio']);y=round(geo['viewY']+p['y']*p['ratio'])
    if not geo['viewY']<y<geo['viewY']+geo['viewHeight']:raise RuntimeError('Target outside visible native webpage: '+name)
    gestures.append({'account':a,'tab':t,'target':name,'x':x,'y':y,'native':geo,'page':row})
    if name=='detail':capture('before-navigation-'+a+'-'+t)
    text('shell','input','tap',str(x),str(y))

def select(a,t):
    started=time.monotonic();launch(a,'select',int(t));wait(lambda:(lambda n:n if n.get('selected')==int(t) and n.get('event') in ('layout','inspected','visible','session-state') else None)(native(a)),label='selected tab')
    return wait(lambda:fresh(a,t,phase,started),label='fresh selected page')

def seed(a,t):
    row=select(a,t);point_tap(a,t,'detail');wait(lambda:(lambda r:r if r and r['page']=='detail' and r['document']!=row['document'] else None)(latest(a,t,phase)),label='actual detail navigation')
    for field in ('standard','rich'):
        point_tap(a,t,field);wait(lambda:(lambda r:r if r and r.get('activeElement')==field else None)(latest(a,t,phase)),label='focused editor '+field)
        launch(a,'input',sample=markers[a+':'+t])
        wait(lambda:(lambda r:r if r and r[field]==markers[a+':'+t] else None)(latest(a,t,phase)),label=field+' real keyboard draft')
        launch(a,'hideKeyboard')
    wait(lambda:bool(re.search(r'mInputShown\s*=\s*false',text('shell','dumpsys','input_method',check=False))),10,label='software keyboard hidden before scroll')
    geo=native(a);x=geo['viewX']+geo['viewWidth']//2
    text('shell','input','swipe',str(x),str(geo['viewY']+int(geo['viewHeight']*.82)),str(x),str(geo['viewY']+int(geo['viewHeight']*.25)),'550')
    time.sleep(2);row=latest(a,t,phase);gesture_ok=row['scrollY']>250
    scroll_samples[a+':'+t]={'gestureObserved':gesture_ok,'gestureScrollY':row['scrollY'],'source':'ADB swipe' if gesture_ok else 'Gecko public PanZoomController.scrollTo; gesture gate remains failed'}
    if not gesture_ok:launch(a,'scrollSample')
    row=wait(lambda:(lambda r:r if r and r['scrollY']>250 else None)(latest(a,t,phase)),label='actual browser reading position');time.sleep(2);row=latest(a,t,phase)
    return row

def checkpoint(a,close=False):
    old=native(a).get('elapsedMs',-1);launch(a,'quit' if close else 'checkpoint')
    def done():
        rows=[r for r in native_events(a) if r.get('elapsedMs',0)>old]
        result=next((r for r in reversed(rows) if r.get('event') in (('closed',) if close else ('checkpoint-ack','checkpoint-timeout'))),None)
        if result:return {**result,'flushAcknowledged':any(r.get('event')=='checkpoint-ack' for r in rows),'flushTimedOut':any(r.get('event')=='checkpoint-timeout' for r in rows)}
    receipt=wait(done,20,label='native state flush/close result');checkpoint_receipts.append(receipt);return receipt

def capture(name):
    (OUT/(name+'.png')).write_bytes(adb('exec-out','screencap','-p'))
    text('shell','uiautomator','dump','--compressed','/sdcard/recovery-poc.xml',check=False)
    (OUT/(name+'.xml')).write_bytes(adb('exec-out','cat','/sdcard/recovery-poc.xml',check=False))

def stop():
    pids=text('shell','pidof',PACKAGE,check=False).split();text('shell','am','force-stop',PACKAGE)
    wait(lambda:not text('shell','pidof',PACKAGE,check=False),15,label='real process termination')
    return pids

def private(name):return adb('exec-out','run-as',PACKAGE,'cat','files/recovery-records/'+name)
def private_write(name,data):
    if not re.fullmatch('[ABC]-[01]\\.bin',name):raise ValueError('Invalid synthetic file')
    adb('shell','run-as '+PACKAGE+" sh -c 'cat > files/recovery-records/"+name+"'",data=data)

def observe_restore(case,before):
    global phase
    phase=case;started=time.monotonic();launch()
    initial=wait(lambda:(lambda n:n if n.get('tabs') and all(t['restored'] for t in n['tabs']) else None)(native('A')),label='saved launcher account A')
    rows={};readings={}
    for a,t in TABS:
        row=select(a,t);row=wait(lambda:fresh(a,t,case,started,before[a+':'+t]['document']),label=case+' new restored document')
        time.sleep(2);row=latest(a,t,case)
        if row['page']!='detail':raise RuntimeError('Saved URL was not restored: '+a+'/'+t)
        rows[a+':'+t]=row
        readings[a+':'+t]={'standardDraft':row['standard']==markers[a+':'+t],'richDraft':row['rich']==markers[a+':'+t],
                          'readingPosition':abs(row['scrollY']-before[a+':'+t]['scrollY'])<80,'scrollBefore':before[a+':'+t]['scrollY'],'scrollAfter':row['scrollY'],
                          'sameOwnerStorage':row['owner']==before[a+':'+t]['owner'],'newDocument':row['document']!=before[a+':'+t]['document']}
    return {'launcherSelected':initial['selected'],'expectedSelected':1,'restored':readings,'native':{a:native(a) for a in ('A','B','C')}},rows

def guard():
    devices=[x.split()[0] for x in text('devices').splitlines()[1:] if x.endswith('\tdevice')]
    if os.environ.get('GITHUB_ACTIONS')!='true' or len(devices)!=1 or not devices[0].startswith('emulator-') or text('shell','getprop','ro.kernel.qemu')!='1':raise SystemExit('Requires one fresh GitHub Actions emulator; physical devices refused')
    if text('shell','pidof',PACKAGE,check=False):raise SystemExit('Existing experiment process; refusing to continue')
    for name in ('recovery-records','recovery-poc-a','recovery-poc-b','poc-gecko-A','grouped-poc-grouped-a'):
        answer=text('shell','run-as',PACKAGE,'sh','-c',"'if [ -e files/"+name+" ]; then echo PRESENT; else echo ABSENT; fi'",check=False)
        if answer!='ABSENT':raise SystemExit('Experiment directory safety state not confirmed: '+name)

guard();report={'sourceSha':os.environ.get('GITHUB_SHA'),'runId':os.environ.get('GITHUB_RUN_ID'),'scope':'Independent native Gecko session-state measurements on synthetic loopback pages. No production data. Missing native capabilities are failures of migration readiness, not filled in by fixture scripts.',
    'inputMode':'Android InputConnection on its own handler; AVD hardware keyboard, software IME hidden before page gestures','api':text('shell','getprop','ro.build.version.sdk'),'deviceAbi':text('shell','getprop','ro.product.cpu.abi'),'systemBefore':{'locale':text('shell','getprop','persist.sys.locale'),'timeZone':text('shell','getprop','persist.sys.timezone')},'cases':{}}
server=ThreadingHTTPServer(('127.0.0.1',8765),Handler);threading.Thread(target=server.serve_forever,daemon=True).start();text('reverse','tcp:8765','tcp:8765')
def expired(_signal,_frame):raise TimeoutError('Recovery experiment time limit reached')
signal.signal(signal.SIGTERM,expired)
failure=None
try:
    print('1/7 Seed four real pages via Android input connection and page scroll',flush=True)
    launch('A',tabs=2)
    for t in ('0','1'):wait(lambda t=t:latest('A',t,phase),90,label='initial A page '+t)
    for a in ('C','B'):
        launch(a);wait(lambda a=a:latest(a,'0',phase),90,label='initial '+a+' page')
    baseline={a+':'+t:seed(a,t) for a,t in TABS}
    select('A','1');checkpoint('A');checkpoint('B')
    report['baseline']={'observations':baseline,'native':{a:native(a) for a in ('A','B','C')}}
    report['baseline']['sameAccountSharedStorage']=baseline['A:0']['owner']==baseline['A:1']['owner']
    report['baseline']['differentAccountStorage']=len({baseline[a+':0']['owner']['token'] for a in ('A','B','C')})==3
    originals={a+'-'+t+'.bin':private(a+'-'+t+'.bin') for a,t in TABS}
    report['encryptedRecords']={name:{'bytes':len(value),'sha256':hashlib.sha256(value).hexdigest(),'noDraftPlaintext':all(marker.encode() not in value for marker in markers.values())} for name,value in originals.items()}
    if not all(r['noDraftPlaintext'] for r in report['encryptedRecords'].values()):raise RuntimeError('Synthetic record encryption check failed')

    print('2/7 Activity recreation must reuse live sessions',flush=True)
    old={k:v['document'] for k,v in baseline.items()};started=time.monotonic();launch('A','recreate')
    after={a+':'+t:wait(lambda a=a,t=t:fresh(a,t,phase,started),label='live after recreation') for a,t in TABS}
    report['cases']['activityRecreation']={'sameDocuments':all(after[k]['document']==old[k] for k in old),'draftsUnchanged':all(after[k]['standard']==markers[k] and after[k]['rich']==markers[k] for k in old)}
    capture('recovery-seeded')

    print('3/7 Normal save/close then app-owned session restoration',flush=True)
    checkpoint('B',True);checkpoint('A',True)
    normal,normal_rows=observe_restore('normal-close',baseline);report['cases']['normalClose']=normal
    select('A','0');launch('A','back');back=wait(lambda:(lambda r:r if r and r['page']=='start' else None)(latest('A','0',phase)),label='restored browser back history')
    launch('A','forward');forward=wait(lambda:(lambda r:r if r and r['page']=='detail' else None)(latest('A','0',phase)),label='restored browser forward history')
    report['cases']['history']={'backPage':back['page'],'forwardPage':forward['page']}
    capture('normal-native-restoration')

    print('4/7 Real force-stop, no shutdown callback or script-provided restore URL',flush=True)
    old_pids=stop();force_reference={key:{**baseline[key],'document':normal_rows[key]['document']} for key in baseline};restart,restart_rows=observe_restore('force-stop',force_reference);restart['oldPids']=old_pids;restart['newPid']=str(native('A').get('pid'));restart['newProcess']=restart['newPid'] not in old_pids
    report['cases']['forceStop']=restart

    print('5/7 Disconnect loopback transport, restore offline, then reconnect',flush=True)
    stop();phase='offline';text('reverse','--remove','tcp:8765');started=time.monotonic();launch();time.sleep(8)
    report['cases']['offline']={'native':native('A'),'pageEvents':sum(e['phase']=='offline' for e in events),'httpRequests':sum(e['phase']=='offline' for e in requests),'contentRecovered':any(e['phase']=='offline' and e['page']=='detail' for e in events),'transportRemoved':True}
    capture('native-offline-restoration')
    root=ET.fromstring((OUT/'native-offline-restoration.xml').read_bytes());labels=' '.join(n.get('text','')+' '+n.get('content-desc','') for n in root.iter('node'))
    report['cases']['offline']['contentRecovered']='合成对话' in labels
    report['cases']['offline']['contentEvidence']='Android accessibility tree plus native-offline-restoration.png; lack of offline network reports alone is not treated as proof.'
    text('reverse','tcp:8765','tcp:8765');phase='reconnected';launch('A','retry')
    reconnected=wait(lambda:latest('A','1','reconnected'),label='explicit retry after reconnect');report['cases']['offline']['reconnectedPage']=reconnected['page']

    print('6/7 Existing webpage draft conflict; observe whether native restore overwrites it',flush=True)
    stop();phase='draft-conflict';conflict=True;started=time.monotonic();launch('A')
    row=wait(lambda:fresh('A','0',phase,started),label='conflicting restored page')
    # Observe through a bounded settling window: a later form restore must not be missed.
    time.sleep(4);row=latest('A','0',phase)
    report['cases']['draftConflict']={'serverDraft':current_draft,'previousDraft':markers['A:0'],'observedStandard':row['standard'],'observedRich':row['rich'],
        'standardCurrentPreserved':row['standard']==current_draft,'richCurrentPreserved':row['rich']==current_draft,
        'standardOverwroteCurrent':row['standard']==markers['A:0'],'richOverwroteCurrent':row['rich']==markers['A:0']}
    capture('native-draft-conflict')

    print('7/7 Reject damaged or account-swapped encrypted records; preserve originals',flush=True)
    stop();phase='damaged-record';conflict=False;damaged=bytearray(originals['A-0.bin']);damaged[-1]^=1;private_write('A-0.bin',bytes(damaged));launch('A')
    rejected=wait(lambda:(lambda n:n if any(t['index']=='0' and t['blocked'] for t in n.get('tabs',[])) else None)(native('A')),label='damaged encrypted state rejection')
    report['cases']['damagedRecord']={'blocked':True,'bytesPreserved':private('A-0.bin')==bytes(damaged),'native':rejected}
    stop();phase='swapped-record';private_write('C-0.bin',originals['A-0.bin']);launch('C')
    rejected=wait(lambda:(lambda n:n if any(t['index']=='0' and t['blocked'] for t in n.get('tabs',[])) else None)(native('C')),label='foreign account record rejection')
    report['cases']['swappedRecord']={'blocked':True,'bytesPreserved':private('C-0.bin')==originals['A-0.bin'],'native':rejected}
    report['experimentComplete']=True
except Exception as error:
    failure=str(error);report['failure']=failure;print('Recovery experiment failed:',failure,flush=True)
    try:
        capture('failure')
        (OUT/'failure-activities.txt').write_text(text('shell','dumpsys','activity','activities',check=False))
    except (OSError,subprocess.SubprocessError) as diagnostic:report['diagnosticFailure']=str(diagnostic)
finally:
    report['submissions']=submissions;report['noAutomaticSubmission']=not submissions
    report['scrollSamples']=scroll_samples
    report['checkpointReceipts']=checkpoint_receipts
    report['systemAfter']={'locale':text('shell','getprop','persist.sys.locale'),'timeZone':text('shell','getprop','persist.sys.timezone')};report['systemUnchanged']=report['systemAfter']==report['systemBefore']
    isolation_errors=[]
    for row in events:
        a=row['account'];expected_lang='en-US' if a=='B' else 'zh-CN';expected_zone='America/New_York' if a=='B' else 'Asia/Shanghai'
        if row.get('language')!=expected_lang or row.get('timeZone')!=expected_zone or row.get('owner',{}).get('owner')!=a or not row.get('acceptLanguage','').lower().startswith(expected_lang.lower()) or any(x!=a for x in re.findall(r'(?:^|;\s*)recovery-owner=([^;]*)',row.get('requestCookie',''))):isolation_errors.append({'account':a,'tab':row['tab'],'phase':row['phase'],'document':row['document']})
    report['isolationErrors']=isolation_errors
    log=text('logcat','-d',check=False,timeout=30);(OUT/'runtime.log').write_text(log)
    report['engineSignals']={'sessionStoreWindowNull':sum('WindowEventDispatcher", win is null' in line for line in log.splitlines()),'stateUpdateNoListener':sum('No listener for GeckoView:StateUpdated' in line for line in log.splitlines()),'fatalExceptions':sum('FATAL EXCEPTION' in line for line in log.splitlines())}
    restored=[item for case in ('normalClose','forceStop') for item in report['cases'].get(case,{}).get('restored',{}).values()]
    conflicts=report['cases'].get('draftConflict',{});offline=report['cases'].get('offline',{})
    cases=report['cases'];baseline=report.get('baseline',{})
    report['migrationGates']={'measurementsComplete':bool(report.get('experimentComplete')),'sameAccountStorageShared':bool(baseline.get('sameAccountSharedStorage')),'differentAccountStorage':bool(baseline.get('differentAccountStorage')),
        'allCheckpointRequestsAcknowledged':len(checkpoint_receipts)==4 and all(v['flushAcknowledged'] and not v['flushTimedOut'] for v in checkpoint_receipts),'allSwipeSamplesObserved':len(scroll_samples)==4 and all(v['gestureObserved'] for v in scroll_samples.values()),'activityDocumentsRetained':bool(cases.get('activityRecreation',{}).get('sameDocuments')),'activityDraftsRetained':bool(cases.get('activityRecreation',{}).get('draftsUnchanged')),
        'eightRestoreObservations':len(restored)==8,'savedActiveTabRestored':all(cases.get(k,{}).get('launcherSelected')==1 for k in ('normalClose','forceStop')),
        'backAndForwardHistory':cases.get('history',{}).get('backPage')=='start' and cases.get('history',{}).get('forwardPage')=='detail',
        'newProcessAfterForceStop':bool(cases.get('forceStop',{}).get('newProcess')),'standardDraftsRestored':len(restored)==8 and all(r['standardDraft'] for r in restored),
        'dynamicDraftsRestored':len(restored)==8 and all(r['richDraft'] for r in restored),'readingPositionsRestored':len(restored)==8 and all(r['readingPosition'] for r in restored),
        'ownerStorageRetained':len(restored)==8 and all(r['sameOwnerStorage'] for r in restored),'offlineContentObserved':bool(offline.get('contentRecovered')),
        'existingStandardDraftPreserved':bool(conflicts.get('standardCurrentPreserved')),'existingDynamicDraftPreserved':bool(conflicts.get('richCurrentPreserved')),
        'damagedRecordRejectedAndPreserved':bool(cases.get('damagedRecord',{}).get('blocked')) and bool(cases.get('damagedRecord',{}).get('bytesPreserved')),
        'foreignRecordRejectedAndPreserved':bool(cases.get('swappedRecord',{}).get('blocked')) and bool(cases.get('swappedRecord',{}).get('bytesPreserved')),
        'noAutomaticSubmission':report['noAutomaticSubmission'],'noIsolationErrors':not isolation_errors,'systemUnchanged':report['systemUnchanged']}
    report['nativeRecoveryReady']=all(report['migrationGates'].values())
    report['limitations']=['Synthetic textarea and contenteditable fixtures are not the real ChatGPT editor.','Native-only restoration has no custom offline snapshot, draft conflict guard or page-specific adapter.','A restored session may reload the network document; retaining a running JS heap after process death is not tested or promised.','Saved baseline records remain read-only during repeated restore measurements so a failed restore cannot replace the reference record.','API level and ABI are recorded; emulator results do not establish OEM phone or Android low-memory survival guarantees.']
    for name,data in (('recovery-report.json',report),('recovery-events.json',events),('recovery-requests.json',requests),('gestures.json',gestures)):(OUT/name).write_text(json.dumps(data,ensure_ascii=False,indent=2))
    for a in ('A','B','C'):
        (OUT/('native-'+a+'.jsonl')).write_bytes(adb('exec-out','run-as',PACKAGE,'cat','files/recovery-events-'+a+'.jsonl',check=False))
    text('shell','am','force-stop',PACKAGE,check=False);text('reverse','--remove','tcp:8765',check=False);server.shutdown()
if failure or not report.get('experimentComplete') or not report['systemUnchanged'] or report['isolationErrors'] or not report['noAutomaticSubmission'] or not report['cases'].get('damagedRecord',{}).get('bytesPreserved') or not report['cases'].get('swappedRecord',{}).get('bytesPreserved'):raise SystemExit(1)
if not report['nativeRecoveryReady']:
    print('MEASUREMENTS COMPLETE; native-only recovery does not meet the migration acceptance criteria. See recovery-report.json.',flush=True)
    raise SystemExit(2)
