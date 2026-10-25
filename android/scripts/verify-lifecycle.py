#!/usr/bin/env python3
"""Native debug-app lifecycle acceptance, without instrumentation or DevTools."""
import hashlib,json,os,re,shlex,subprocess,sys,time,uuid,xml.etree.ElementTree as ET
from pathlib import Path

PKG='com.forcemind.chatgpt.profiles.debug'
OUT=Path(sys.argv[1] if len(sys.argv)>1 else 'app/build/lifecycle-evidence');OUT.mkdir(parents=True,exist_ok=True)
HOLD=int(os.environ.get('STUDIO_HOLD_SECONDS','330'));assert 10<=HOLD<=900
report={'scope':'Native debug app and synthetic HTTPS-intercept fixtures; no instrumentation/DevTools attached, no real ChatGPT or personal data','holdSeconds':HOLD,'phases':[],'transportScope':'Three account-specific Java-intercepted SSE fetch streams and five finite polling tabs; complete SSE data frames are parsed with a streaming reader, eight independent JS heartbeats and network acknowledgements'}
def adb(*args,check=True,timeout=30,input=None):
    r=subprocess.run(['adb',*args],input=input,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=timeout)
    if check and r.returncode:raise RuntimeError('adb '+str(args[:3])+': '+r.stderr.decode(errors='replace')[-800:])
    return r.stdout
def text(*args,**kwargs):return adb(*args,**kwargs).decode(errors='replace').strip()
def private(name):
    try:return json.loads(text('shell','run-as',PKG,'cat','files/'+name))
    except (ValueError,RuntimeError):return None
def wait(predicate,seconds=60):
    deadline=time.monotonic()+seconds;last=None
    while time.monotonic()<deadline:
        try:
            value=predicate()
            if value:return value
        except Exception as e:last=e
        time.sleep(.7)
    raise AssertionError('Timed out waiting for native fixture'+(': '+str(last) if last else ''))
def configure(phase):
    adb('shell','run-as',PKG,'mkdir','-p','files')
    data=json.dumps({'token':str(uuid.uuid4()),'phase':phase}).encode()
    adb('shell','run-as',PKG,'sh','-c',shlex.quote('cat > files/browser-lifecycle-fixture.json'),input=data)
def launch():adb('shell','am','start','-W','-n',PKG+'/com.forcemind.chatgpt.profiles.MainActivity')
def ui():
    adb('shell','uiautomator','dump','--compressed','/sdcard/studio-lifecycle.xml',timeout=20)
    raw=adb('shell','cat','/sdcard/studio-lifecycle.xml');return ET.fromstring(raw)
def tap(predicate,seconds=20):
    def action():
        for node in ui().iter('node'):
            if predicate(node):
                m=re.fullmatch(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]',node.get('bounds',''))
                if m:
                    x1,y1,x2,y2=map(int,m.groups())
                    if x2>x1 and y2>y1:adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2));return True
        return False
    return wait(action,seconds)
def tap_text(label):tap(lambda n:n.get('text')==label)
def visible_tabs():
    tap(lambda n:'查看所有 ' in n.get('content-desc',''));wait(lambda:any(n.get('content-desc')=='标签页总览' for n in ui().iter('node')));time.sleep(.4)
def open_tab(title):
    visible_tabs()
    for _ in range(8):
        for node in ui().iter('node'):
            if node.get('content-desc','').startswith('标签页：'+title+'，账号：'):
                bounds=list(map(int,re.findall(r'\d+',node.get('bounds',''))));assert len(bounds)==4
                x1,y1,x2,y2=bounds;adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2));return
        size=list(map(int,re.findall(r'\d+',text('shell','wm','size'))[-2:]));w,h=size;adb('shell','input','swipe',str(w//2),str(h*3//4),str(w//2),str(h//3),'220')
    raise AssertionError('Native card not visible: '+title)
def observations(phase=None):
    result=private('lifecycle-observations.json')
    return result if result and (phase is None or result.get('phase')==phase) else None
def tab_ready(tab,phase,hash_value=None):
    result=observations(phase);entry=(result or {}).get('tabs',{}).get(tab)
    return entry if entry and entry.get('images',0)>=6 and (hash_value is None or entry.get('draftHash')==hash_value) else None
def screenshot(name):
    (OUT/(name+'.png')).write_bytes(adb('exec-out','screencap','-p'));(OUT/(name+'.xml')).write_bytes(ET.tostring(ui()))
def processes():
    rows=text('shell','ps','-A','-o','PID,NAME').splitlines();owned=[]
    for line in rows[1:]:
        parts=line.split(None,1)
        if len(parts)==2 and parts[0].isdigit() and (parts[1].startswith(PKG) or ':sandboxed_process' in parts[1]):owned.append((int(parts[0]),parts[1]))
    return owned
def metrics(label):
    owned=processes();samples=[]
    def ticks():
        raw=text('shell','cat','/proc/stat');cpus=sum(1 for l in raw.splitlines() if re.match(r'cpu\d+ ',l));total=sum(map(int,raw.splitlines()[0].split()[1:9]));proc=0
        for pid,_ in owned:
            s=text('shell','cat','/proc/'+str(pid)+'/stat',check=False)
            if ')' in s:
                fields=s[s.rfind(')')+2:].split()
                if len(fields)>12:proc+=int(fields[11])+int(fields[12])
        return total,proc,cpus
    try:
        before=ticks()
        for _ in range(3):
            time.sleep(1);after=ticks();delta=after[0]-before[0];samples.append(round((after[1]-before[1])/delta*after[2]*100,3) if delta>0 else None);before=after
    except Exception:samples=[]
    pss=[]
    for pid,name in owned:
        s=text('shell','cat','/proc/'+str(pid)+'/smaps_rollup',check=False);m=re.search(r'^Pss:\s+(\d+)',s,re.M)
        if not m:
            s=text('shell','dumpsys','meminfo',str(pid),check=False);m=re.search(r'TOTAL PSS:\s*(\d+)',s) or re.search(r'^\s*TOTAL\s+(\d+)',s,re.M)
        if m:pss.append({'pid':pid,'name':name,'pssMiB':round(int(m.group(1))/1024,2)})
    return {'label':label,'processes':pss,'pssMiB':round(sum(p['pssMiB'] for p in pss),2),'cpuPercentOfOneCoreSamples':samples,'ownership':'Target package plus sandboxed WebView renderer processes in an isolated emulator; raw process list retained for coverage review. Any other provider client would overcount rather than silently omit renderer memory.','caveat':'Emulator PSS and short CPU samples with fixture beacon overhead, not physical-device or real ChatGPT measurements'}
def retained_after_resume(label,baseline):
    resumed=int(time.time()*1000)
    def ready():
        current=observations('seed')
        return current if current and set(current['tabs'])==set(baseline['tabs']) and all(e['instance']==baseline['tabs'][i]['instance'] and e['documents']==1 and e['navigations']==1 and e['sent']==0 and e['lastSeen']>=resumed and e['events']>baseline['tabs'][i]['events'] for i,e in current['tabs'].items()) else None
    result=wait(ready,45);report['phases'].append({'name':label,'sameDocuments':True,'networkFramesResumed':True,'autoSendCount':0});return result
try:
    adb('shell','am','force-stop',PKG);configure('seed');launch();environment=wait(lambda:private('lifecycle-environment.json'));report['environment']=environment
    if not environment['multiProfile']:
        tap(lambda n:'请更新 Android System WebView' in n.get('text',''));screenshot('unsupported-webview')
        report.update({'outcome':'unsupported-stock-webview','guardVerified':True,'retentionPassed':False,'reason':'Stock image lacks MULTI_PROFILE; updated compatible WebView required. No isolation/retention claim.'})
    else:
        if environment['api']>=33:adb('shell','pm','grant',PKG,'android.permission.POST_NOTIFICATIONS')
        workspace=wait(lambda:private('workspace.json'));tabs=[t for a in workspace['accounts'] for t in a['tabs']];assert len(tabs)==8
        resources=[];report['resources']=resources
        for n,tab in enumerate(tabs):
            if n:open_tab(tab['title'])
            wait(lambda:tab_ready(tab['id'],'seed') if (tab_ready(tab['id'],'seed') or {}).get('events',0)>0 else None);time.sleep(1)
            if n in [0,3,7]:resources.append(metrics(str(n+1)+' visited tabs'))
        visible_tabs();screenshot('native-tab-overview');tap_text('完成')
        before=observations('seed');assert len(before['tabs'])==8
        adb('shell','settings','put','system','accelerometer_rotation','0');adb('shell','settings','put','system','user_rotation','1');time.sleep(3)
        wait(lambda:len((observations('seed') or {}).get('tabs',{}))==8)
        rotated=observations('seed');assert all(rotated['tabs'][i]['instance']==v['instance'] for i,v in before['tabs'].items())
        adb('shell','settings','put','system','user_rotation','0');time.sleep(2)
        tap(lambda n:n.get('content-desc','').startswith('账号与操作菜单'));tap_text('保活与诊断');screenshot('browser-diagnostics');tap_text('开启后台任务');tap_text('开启')
        service=wait(lambda:'isForeground=true' in text('shell','dumpsys','activity','services',PKG,check=False));report['foregroundServiceVerified']=bool(service)
        background_start=int(time.time()*1000);adb('shell','input','keyevent','HOME');deadline=time.monotonic()+HOLD
        while time.monotonic()<deadline:
            time.sleep(min(15,max(.1,deadline-time.monotonic())));print(json.dumps({'phase':'native-background','seconds':int((time.time()*1000-background_start)/1000),'tabs':len((observations('seed') or {}).get('tabs',{}))}),flush=True)
        final=observations('seed');cutoff=int(time.time()*1000)-35000
        assert all(e['instance']==before['tabs'][i]['instance'] and e['documents']==1 and e['navigations']==1 and e['sent']==0 and e['events']>before['tabs'][i]['events'] and sum(t>=cutoff for t in e['times'])>=2 for i,e in final['tabs'].items()),'Background continuity or page identity failed'
        report.update({'retentionPassed':True,'before':before,'afterBackground':final,'rotationRetained':True,'resources':resources})
        resources.append(metrics('background final'))
        adb('shell','input','keyevent','WAKEUP');adb('shell','input','keyevent','82');launch();final=retained_after_resume('return from background',final)
        print(json.dumps({'phase':'screen-lock-and-resume'}),flush=True);adb('shell','input','keyevent','SLEEP');time.sleep(30);report['lockedObservations']=observations('seed');adb('shell','input','keyevent','WAKEUP');adb('shell','input','keyevent','82');launch();final=retained_after_resume('screen lock 30s then resume',final)
        print(json.dumps({'phase':'battery-saver-doze-and-resume'}),flush=True);adb('shell','cmd','power','set-mode','1',check=False);adb('shell','dumpsys','battery','unplug');adb('shell','input','keyevent','SLEEP');idle=text('shell','dumpsys','deviceidle','force-idle',check=False);report['dozeCommand']=idle;time.sleep(30);(OUT/'device-idle.txt').write_text(text('shell','dumpsys','deviceidle'));report['idleObservations']=observations('seed');adb('shell','dumpsys','deviceidle','unforce',check=False);adb('shell','dumpsys','battery','reset');adb('shell','cmd','power','set-mode','0',check=False);adb('shell','input','keyevent','WAKEUP');adb('shell','input','keyevent','82');launch();final=retained_after_resume('battery saver and forced Doze then resume; continuous work during Doze not required',final)
        print(json.dumps({'phase':'network-loss-and-resume'}),flush=True);network_start=int(time.time()*1000);adb('shell','svc','wifi','disable');adb('shell','svc','data','disable');time.sleep(8);lost=any(e.get('type')=='network-lost' and e.get('time',0)>=network_start for e in (private('browser-events.json') or []));(OUT/'network-disabled.txt').write_text(text('shell','dumpsys','connectivity'));adb('shell','svc','wifi','enable');adb('shell','svc','data','enable');wait(lambda:any(e.get('type')=='network-available' and e.get('time',0)>=network_start for e in (private('browser-events.json') or [])),45);final=retained_after_resume('system network disabled and restored',final);report['networkLossDiagnosticVerified']=lost;assert lost,'Network-loss diagnostic not observed';report['networkScope']='Real ConnectivityManager loss/reconnect callbacks; intercepted fixture transport does not measure real remote service reconnection.'
        (OUT/'native-processes.txt').write_text(text('shell','ps','-A','-o','PID,PPID,NAME'));(OUT/'native-services.txt').write_text(text('shell','dumpsys','activity','services',PKG));(OUT/'diagnostics.json').write_text(json.dumps(private('browser-events.json'),ensure_ascii=False,indent=2))
        old_pid=text('shell','pidof',PKG);adb('shell','am','force-stop',PKG);wait(lambda:not text('shell','pidof',PKG,check=False),15);configure('verify');launch();new_env=wait(lambda:private('lifecycle-environment.json') if (private('lifecycle-environment.json') or {}).get('phase')=='verify' else None);assert str(new_env['pid'])!=old_pid
        restored=[]
        for tab in tabs:
            open_tab(tab['title']);expected=hashlib.sha256(('SAVED_DRAFT_'+tab['id']).encode()).hexdigest();entry=wait(lambda:tab_ready(tab['id'],'verify',expected));assert entry['sent']==0;restored.append(tab['id'])
        report.update({'outcome':'passed','coldRestoredTabs':restored,'pidChanged':True,'coldRestoreNoAutoSend':True});screenshot('cold-restored-page')
        tap(lambda n:n.get('content-desc','').startswith('账号与操作菜单'));tap_text('保活与诊断');tap_text('关闭后台任务');wait(lambda:'isForeground=true' not in text('shell','dumpsys','activity','services',PKG,check=False),20);report['explicitStopVerified']=True
except Exception as error:
    report.update({'outcome':'failed','error':str(error)});
    try:screenshot('failure')
    except Exception:pass
    raise
finally:
    adb('shell','dumpsys','deviceidle','unforce',check=False);adb('shell','dumpsys','battery','reset',check=False);adb('shell','cmd','power','set-mode','0',check=False);adb('shell','svc','wifi','enable',check=False);adb('shell','svc','data','enable',check=False)
    report['observationsAtEnd']=observations()
    (OUT/'lifecycle-verification.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps({'outcome':report.get('outcome'),'error':report.get('error'),'environment':report.get('environment'),'tabs':{i:{k:e.get(k) for k in ['transport','streamStatus','documents','navigations','events','beats']} for i,e in (report['observationsAtEnd'] or {}).get('tabs',{}).items()}),flush=True)
    adb('shell','am','force-stop',PKG,check=False)
