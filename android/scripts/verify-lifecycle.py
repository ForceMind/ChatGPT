#!/usr/bin/env python3
"""Native debug-app lifecycle acceptance, without instrumentation or DevTools."""
import hashlib,json,os,re,shlex,subprocess,sys,time,uuid,xml.etree.ElementTree as ET
from pathlib import Path

PKG='com.forcemind.chatgpt.profiles.debug'
OUT=Path(sys.argv[1] if len(sys.argv)>1 else 'app/build/lifecycle-evidence');OUT.mkdir(parents=True,exist_ok=True)
HOLD=int(os.environ.get('STUDIO_HOLD_SECONDS','330'));assert 10<=HOLD<=900
report={'scope':'Native debug app and synthetic HTTPS-intercept fixtures; no instrumentation/DevTools attached, no real ChatGPT or personal data','holdSeconds':HOLD,'phases':[],'transportScope':'Three account-specific Java-intercepted SSE streams and five finite polling tabs, avoiding interception-reader pool exhaustion; eight independent JS heartbeat and network acknowledgements'}
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
def visible_tabs():tap(lambda n:'查看所有 ' in n.get('content-desc',''))
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
        if len(parts)==2 and parts[0].isdigit() and parts[1].startswith(PKG):owned.append((int(parts[0]),parts[1]))
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
    return {'label':label,'processes':pss,'pssMiB':round(sum(p['pssMiB'] for p in pss),2),'cpuPercentOfOneCoreSamples':samples,'ownership':'Processes with target package prefix, including named sandboxed children; raw process list retained for coverage review','caveat':'Emulator PSS and short CPU samples with fixture beacon overhead, not physical-device or real ChatGPT measurements'}
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
            wait(lambda:tab_ready(tab['id'],'seed'));time.sleep(1)
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
    report['observationsAtEnd']=observations()
    (OUT/'lifecycle-verification.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    adb('shell','am','force-stop',PKG,check=False)
