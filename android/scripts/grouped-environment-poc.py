#!/usr/bin/env python3
"""Independent CI-only Gecko experiment; synthetic loopback data, never real accounts."""
from pathlib import Path
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs, urlencode
import hashlib
import json
import os
import random
import re
import statistics
import struct
import subprocess
import sys
import threading
import time
import uuid
import zlib
from grouped_environment_validation import expected,validate,shared_markers

PACKAGE = "com.forcemind.chatgpt.environmentpoc"
COMPONENT = PACKAGE + "/" + PACKAGE + ".GroupedAActivity"
OUT = Path(sys.argv[1]); OUT.mkdir(parents=True, exist_ok=True)
events, requests, lock = [], [], threading.Lock()
phase = "isolation"

def adb(*args, check=True, timeout=25):
    return subprocess.run(["adb", *args], capture_output=True, timeout=timeout, check=check).stdout.decode(errors="replace").strip()

def wait(predicate, seconds=60):
    end=time.monotonic()+seconds
    while time.monotonic()<end:
        value=predicate()
        if value:return value
        time.sleep(.5)
    return None

def records(case=None):
    with lock:return [dict(e) for e in events if case is None or e.get("phase")==case]

def native(account,mode):
    try:return json.loads(adb("exec-out","run-as",PACKAGE,"cat","files/grouped-native-"+mode+"-"+account+".json"))
    except (subprocess.SubprocessError,ValueError):return {}

def launch(account="A",mode="grouped",tabs=0,**flags):
    args=["shell","am","start","-W","-n",COMPONENT,"--es","account",account,"--es","mode",mode]
    if tabs:args += ["--ei","tabs",str(tabs)]
    for key,value in flags.items():args += ["--ez",key,str(value).lower()]
    adb(*args)

def screenshot(name):
    data=subprocess.run(["adb","exec-out","screencap","-p"],capture_output=True,timeout=25,check=True).stdout
    (OUT/(name+".png")).write_bytes(data)

def image():
    rng=random.Random(142);raw=b"".join(b"\0"+rng.randbytes(1024*3) for _ in range(768))
    def chunk(kind,data):return struct.pack(">I",len(data))+kind+data+struct.pack(">I",zlib.crc32(kind+data)&0xffffffff)
    return b"\x89PNG\r\n\x1a\n"+chunk(b"IHDR",struct.pack(">IIBBBBB",1024,768,8,2,0,0,0))+chunk(b"IDAT",zlib.compress(raw))+chunk(b"IEND",b"")

PNG=image()
ENVIRONMENT="""function environment(){const f=new Intl.DateTimeFormat().resolvedOptions();return {
 language:navigator.language,languages:Array.from(navigator.languages),locale:f.locale,
 localeMaximized:new Intl.Locale(f.locale).maximize().toString(),timeZone:f.timeZone,
 winterOffset:new Date('2026-01-15T12:00:00Z').getTimezoneOffset(),summerOffset:new Date('2026-07-15T12:00:00Z').getTimezoneOffset(),
 localNoon:new Date(2026,0,15,12).toISOString(),parsedNoon:new Date('2026-01-15T12:00:00').toISOString(),
 dstGap:new Date(2026,2,8,2,30).toISOString()};}"""
STORAGE="""async function dbValue(write){return new Promise((resolve,reject)=>{
 const open=indexedDB.open('grouped-owner',1);open.onupgradeneeded=()=>open.result.createObjectStore('markers');
 open.onerror=()=>reject(open.error);open.onsuccess=()=>{const db=open.result,tx=db.transaction('markers','readwrite'),store=tx.objectStore('markers'),get=store.get('owner');let previous=null,current=null;
 get.onsuccess=()=>{previous=get.result||null;current=previous;if(!current&&write){current={owner:base.account,token:crypto.randomUUID()};store.put(current,'owner');}};
 tx.oncomplete=()=>{db.close();resolve({previous,current});};tx.onerror=()=>{db.close();reject(tx.error);};};});}
async function stored(write){
 const result={};try{const key='grouped-owner',raw=localStorage.getItem(key);result.localPrevious=raw?JSON.parse(raw):null;
 if(!raw&&write)localStorage.setItem(key,JSON.stringify({owner:base.account,token:crypto.randomUUID()}));const now=localStorage.getItem(key);result.local=now?JSON.parse(now):null;result.localState='ready';}catch(e){result.localState=e&&['SecurityError','NotAllowedError'].includes(e.name)?'blocked':'error';}
 try{const v=await dbValue(write);result.idbPrevious=v.previous;result.idb=v.current;result.idbState='ready';}catch(e){result.idbState=e&&['SecurityError','NotAllowedError'].includes(e.name)?'blocked':'error';}
 try{const c=await caches.open('grouped-owner');let prior=await c.match('/__grouped_owner');result.cachePrevious=prior?await prior.json():null;
 if(!prior&&write){await c.put('/__grouped_owner',new Response(JSON.stringify({owner:base.account,token:crypto.randomUUID()})));prior=await c.match('/__grouped_owner');}
 result.cache=prior?await prior.json():null;result.cacheState='ready';}catch(e){result.cacheState=e&&['SecurityError','NotAllowedError'].includes(e.name)?'blocked':'error';}
 result.previousCookie=document.cookie;if(!document.cookie&&write)document.cookie='poc-owner='+base.account+'; SameSite=Lax; path=/';result.cookie=document.cookie;return result;
}"""

class Handler(BaseHTTPRequestHandler):
    def log_message(self,*args):pass
    def reply(self,body,mime,status=200):
        if isinstance(body,str):body=body.encode()
        self.send_response(status);self.send_header("Content-Type",mime);self.send_header("Content-Length",str(len(body)));self.send_header("Cache-Control","no-store");self.end_headers()
        try:self.wfile.write(body)
        except (BrokenPipeError,ConnectionResetError):pass
    def do_POST(self):
        if self.path!="/report":self.send_error(404);return
        size=int(self.headers.get("Content-Length","0"))
        if size>32768:self.send_error(413);return
        try:
            value=json.loads(self.rfile.read(size));value["received"]=time.monotonic();value["acceptLanguage"]=self.headers.get("Accept-Language","");value["requestCookie"]=self.headers.get("Cookie","")
            with lock:events.append(value)
            self.reply("{}","application/json")
        except (ValueError,TypeError):self.send_error(400)
    def do_GET(self):
        url=urlparse(self.path);q=parse_qs(url.query);account=q.get("account",["?"])[0];tab=q.get("tab",["?"])[0]
        with lock:requests.append({"phase":phase,"path":self.path,"account":account,"acceptLanguage":self.headers.get("Accept-Language",""),"cookie":self.headers.get("Cookie","")})
        if url.path=="/image.png":self.reply(PNG,"image/png");return
        if url.path=="/sw-check":self.reply("not controlled","text/plain",404);return
        if url.path=="/sw.js":
            owner=json.dumps(account);self.reply("const owner="+owner+";self.addEventListener('install',e=>e.waitUntil(self.skipWaiting()));self.addEventListener('activate',e=>e.waitUntil(self.clients.claim()));self.addEventListener('fetch',e=>{if(new URL(e.request.url).pathname==='/sw-check')e.respondWith(new Response(JSON.stringify({owner,language:self.navigator.language,timeZone:new Intl.DateTimeFormat().resolvedOptions().timeZone}),{headers:{'Content-Type':'application/json'}}));});","application/javascript");return
        if url.path not in ("/page","/frame","/popup","/worker.js"):self.send_error(404);return
        kind={"/page":"main","/frame":"frame","/popup":"popup","/worker.js":"worker"}[url.path]
        base={"phase":phase,"account":account,"tab":tab,"kind":kind,"instance":str(uuid.uuid4()),"pid":q.get("pid",[""])[0],"probe":q.get("probe",["false"])[0]=="true"}
        args=urlencode({"account":account,"tab":tab,"pid":base["pid"],"probe":str(base["probe"]).lower()})
        script=ENVIRONMENT+"const base="+json.dumps(base)+";let ticks=0,decodedImages=0;"
        if kind=="worker":
            script+="function report(){fetch('/report',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({...base,...environment(),ticks})}).catch(()=>{});}report();setInterval(()=>{ticks++;report()},2000);"
            self.reply(script,"application/javascript");return
        script+=STORAGE+"let service={state:'pending'},registrationPromise=null;"
        if kind=="main":
            script+="async function serviceCheck(){if(!('serviceWorker' in navigator))return {state:'unsupported'};try{if(!base.probe){if(!registrationPromise)registrationPromise=navigator.serviceWorker.register('/sw.js?account='+base.account,{scope:'/'});await registrationPromise;}const regs=await navigator.serviceWorker.getRegistrations();const active=regs.find(r=>r.active)?.active;let response=null;if(active){try{const r=await fetch('/sw-check?account='+base.account,{cache:'no-store'});if(r.ok)response=await r.json();}catch(e){}}return {state:'ready',owner:active?new URL(active.scriptURL).searchParams.get('account'):null,response};}catch(e){return {state:'error',message:String(e)};}}"
        else:script+="async function serviceCheck(){return {state:'not-applicable'};}"
        script+="let busy=false;async function report(){if(busy)return;busy=true;try{service=await serviceCheck();const payload={...base,...environment(),...(await stored(!base.probe)),service,ticks,decodedImages};const b=document.querySelector('#popup');if(b){const r=b.getBoundingClientRect();payload.popupButton={x:r.x+r.width/2,y:r.y+r.height/2,ratio:devicePixelRatio};}fetch('/report',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(payload)}).catch(()=>{});document.querySelector('#result').textContent=JSON.stringify(payload,null,2);}finally{busy=false;}}report();setInterval(()=>{ticks++;report()},2000);"
        if kind in ("main","popup"):
            script+="new Worker('/worker.js?"+args+"');Promise.all(Array.from(document.images,i=>i.decode())).then(()=>{decodedImages=document.images.length;report()}).catch(()=>{});"
        popup=urlencode({"account":account,"tab":"popup-"+str(uuid.uuid4()),"pid":base["pid"],"probe":"false"})
        body="<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'><title>独立分组环境验证</title><style>body{font:14px sans-serif;color:#171717;background:white;margin:14px}button{background:#171717;color:white;border:0;border-radius:12px;padding:14px}pre{white-space:pre-wrap;font-size:11px}img{width:100%;height:auto}</style><b>账号 "+account+" · "+kind+" · 本地测试</b><p><button id=popup onclick=\"window.open('/popup?"+popup+"','_blank')\">打开受控弹窗</button></p><pre id=result></pre>"
        if kind in ("main","popup"):
            body+="<iframe src='http://localhost:8765/frame?"+args+"' style='width:100%;height:250px'></iframe>"
            body+="".join("<p>离线对话 "+str(i)+" · 仅用于测量。</p>" for i in range(240))
            body+="".join("<img loading=eager src='/image.png?"+args+"&asset="+str(i)+"'>" for i in range(6))
        self.reply(body+"<script>"+script+"</script>","text/html; charset=utf-8")

def latest(case):
    result={}
    for row in records(case):result[(row.get("account"),row.get("tab"),row.get("kind"),row.get("instance"))]=row
    return result

def main_row(case,account,tab="0",probe=False):
    return next((r for r in reversed(records(case)) if r.get("account")==account and r.get("tab")==tab and r.get("kind")=="main" and r.get("probe")==probe),None)

def ready_main(case,account,tab="0"):
    row=main_row(case,account,tab)
    if not row or row.get("decodedImages")!=6 or any(row.get(k+"State") not in ("ready","blocked","error") for k in ("local","idb","cache")):return False
    service=row.get("service",{});response=service.get("response") or {}
    return service.get("state") in ("unsupported","error") or (service.get("state")=="ready" and bool(service.get("owner")) and bool(response.get("owner")))

def expected_contexts(case):
    keys={(r.get("account"),r.get("tab"),r.get("kind")) for r in records(case) if not r.get("probe") and not str(r.get("tab","")).startswith("popup-")}
    return all((a,t,k) in keys for a,t in (("A","0"),("A","1"),("B","0"),("C","0")) for k in ("main","frame","worker"))

def process_list():
    rows=[]
    for line in adb("shell","ps","-A","-o","PID,NAME").splitlines()[1:]:
        parts=line.split()
        if len(parts)==2 and parts[1].startswith(PACKAGE):rows.append((int(parts[0]),parts[1]))
    return rows

def memory(case):
    values=[]
    for pid,name in process_list():
        raw=adb("shell","dumpsys","meminfo",str(pid),check=False);(OUT/(case+"-mem-"+str(pid)+".txt")).write_text(raw)
        match=re.search(r"TOTAL PSS:\s*(\d+)",raw) or re.search(r"^\s*TOTAL\s+(\d+)",raw,re.M)
        values.append({"pid":pid,"name":name,"pssKiB":int(match.group(1)) if match else None})
    return {"processes":values,"totalPssMiB":round(sum(v["pssKiB"] or 0 for v in values)/1024,2),"complete":bool(values) and all(v["pssKiB"] is not None for v in values)}

def cpu(case):
    expected_pids={pid:name for pid,name in process_list()}
    if not expected_pids:return {"samplesOneCorePercent":[],"failure":"No attributable processes"}
    raw=adb("shell","top","-b","-n","5","-d","2","-m","512","-p",",".join(map(str,expected_pids)),"-o","PID,%CPU,ARGS",timeout=35);(OUT/(case+"-top.txt")).write_text(raw)
    samples=[];coverage=[];sample=None;seen=set()
    for line in raw.splitlines():
        if re.search(r"\bPID\s+%CPU(?:\s+|\[)ARGS",line):
            if sample is not None:samples.append(sample);coverage.append(sorted(seen))
            sample=0.0;seen=set();continue
        if sample is not None:
            parts=line.strip().split(None,2)
            if len(parts)==3 and parts[0].isdigit() and int(parts[0]) in expected_pids:
                try:sample+=float(parts[1].replace("%",""));seen.add(int(parts[0]))
                except ValueError:pass
    if sample is not None:samples.append(sample);coverage.append(sorted(seen))
    samples=samples[1:];coverage=coverage[1:]
    return {"expectedProcesses":expected_pids,"samplePids":coverage,"allProcessesCovered":bool(coverage) and all(set(pids)==set(expected_pids) for pids in coverage),"samplesOneCorePercent":samples,"meanOneCorePercent":round(statistics.mean(samples),2) if samples else None,"peakOneCorePercent":max(samples) if samples else None}

def start_workload(case,mode):
    started=time.monotonic();launch("A",mode,1)
    if not wait(lambda:ready_main(case,"A"),90):raise RuntimeError(case+": A first page/storage/service worker not ready")
    launch("A",mode,2);launch("C",mode,1);launch("B",mode,1)
    if not wait(lambda:expected_contexts(case) and all(ready_main(case,a,t) for a,t in (("A","0"),("A","1"),("B","0"),("C","0"))),100):raise RuntimeError(case+": four tabs or their storage not ready")
    times=round(time.monotonic()-started,2);info={a:native(a,mode) for a in ("A","B","C")}
    if not all(info[a].get("pid") for a in info):raise RuntimeError(case+": missing native account identity")
    pids={a:info[a]["pid"] for a in info};same=pids["A"]==pids["C"] and pids["B"]!=pids["A"]
    separate=len(set(pids.values()))==3
    return {"readySeconds":times,"native":info,"processRelationCorrect":same if mode=="grouped" else separate}

def same_account_tokens(case):
    a0,a1,c=main_row(case,"A","0"),main_row(case,"A","1"),main_row(case,"C")
    return shared_markers(a0,a1,c)

def popup(case,account):
    launch(account);geo=wait(lambda:native(account,"grouped") if native(account,"grouped").get("viewHeight",0)>0 else None,15)
    row=main_row(case,account);point=row and row.get("popupButton")
    if not geo or not point:return False
    before={r["instance"] for r in records(case) if r.get("account")==account and r.get("kind")=="popup"}
    adb("shell","input","tap",str(round(geo["viewX"]+point["x"]*point["ratio"])),str(round(geo["viewY"]+point["y"]*point["ratio"])))
    def shared_popup():
        main=main_row(case,account)
        for row in reversed(records(case)):
            if row.get("account")!=account or row.get("kind")!="popup" or row.get("instance") in before or validate(row) or not main:continue
            if row.get("cookie")==main.get("cookie") and all(row.get(k)==main.get(k) for k in ("local","idb","cache")):return row
        return None
    return bool(wait(shared_popup,35))

def popup_contexts_ready(case):
    rows=records(case)
    for account in ("A","B","C"):
        tabs={r.get("tab") for r in rows if r.get("account")==account and r.get("kind")=="popup"}
        if not any(all(any(r.get("account")==account and r.get("tab")==tab and r.get("kind")==kind for r in rows) for kind in ("popup","frame","worker")) for tab in tabs):return False
    return True

devices=[x.split()[0] for x in adb("devices").splitlines()[1:] if x.endswith("\tdevice")]
if os.environ.get("GITHUB_ACTIONS")!="true" or len(devices)!=1 or not devices[0].startswith("emulator-") or adb("shell","getprop","ro.kernel.qemu")!="1":raise SystemExit("Only one fresh GitHub Actions emulator is allowed; physical devices refused")
profile_names=["poc-gecko-A","poc-gecko-B"]+["grouped-poc-"+mode+"-"+slot for mode in ("grouped","control") for slot in ("a","b","c")]
if process_list() or any(adb("exec-out","run-as",PACKAGE,"ls","-d","files/"+name,check=False) for name in profile_names):raise SystemExit("Existing experiment process/profile found; refusing to touch it")

report={"schema":1,"engine":"GeckoView 158.0.20261021001945","package":PACKAGE,"sourceSha":os.environ.get("GITHUB_SHA"),"runId":os.environ.get("GITHUB_RUN_ID"),"remoteDebugging":False,
        "scope":"Independent synthetic loopback fixture; Android 15 x86_64 emulator. No production package, personal account or real ChatGPT.",
        "fixture":{"messagesPerPage":240,"imagesPerPage":6,"imageDimensions":[1024,768],"imageBytes":len(PNG)},
        "device":{"model":adb("shell","getprop","ro.product.model"),"release":adb("shell","getprop","ro.build.version.release"),"sdk":adb("shell","getprop","ro.build.version.sdk"),"abi":adb("shell","getprop","ro.product.cpu.abi")},
        "systemBefore":{"locale":adb("shell","getprop","persist.sys.locale"),"timeZone":adb("shell","getprop","persist.sys.timezone")},"resources":[]}
server=ThreadingHTTPServer(("127.0.0.1",8765),Handler);threading.Thread(target=server.serve_forever,daemon=True).start();adb("reverse","tcp:8765","tcp:8765")
failure=None
try:
    phase="isolation";isolation=start_workload(phase,"grouped");isolation["sameAccountStorageTokens"]=same_account_tokens(phase)
    isolation["popups"]={a:popup(phase,a) for a in ("A","C","B")}
    if not wait(lambda:popup_contexts_ready("isolation"),40):raise RuntimeError("Missing popup/frame/worker evidence")
    time.sleep(3);before=set(latest("isolation"))
    for account in ("A","C","B"):launch(account,recreate=True)
    launch("A");time.sleep(5);after=set(latest("isolation"));isolation["recreationNewDocuments"]=len(after-before)
    isolation["environmentErrors"]=[{"account":r["account"],"kind":r["kind"],"tab":r["tab"],"errors":validate(r)} for r in records("isolation") if validate(r)]
    isolation["passed"]=isolation["processRelationCorrect"] and isolation["sameAccountStorageTokens"] and all(isolation["popups"].values()) and isolation["recreationNewDocuments"]==0 and not isolation["environmentErrors"]
    report["isolation"]=isolation;screenshot("grouped-isolation")

    adb("shell","am","force-stop",PACKAGE);time.sleep(2);phase="grouped-resource"
    grouped=start_workload(phase,"grouped");time.sleep(12);grouped.update({"mode":"grouped","memory":memory(phase),"cpu":cpu(phase),"sameAccountStorageTokens":same_account_tokens(phase)})
    start={r["instance"]:r for r in latest(phase).values() if not r.get("probe")};begin=time.monotonic();time.sleep(330)
    end={r["instance"]:r for r in latest(phase).values() if not r.get("probe")};grouped["keepAlive"]={"durationSeconds":round(time.monotonic()-begin,1),"originalContexts":len(start),"continuingContexts":sum(k in end and end[k]["ticks"]>v["ticks"] and end[k]["received"]>begin+320 for k,v in start.items()),"newDocuments":len(set(end)-set(start))}
    for account in ("A","C","B"):launch(account,next=True)
    time.sleep(5);grouped["keepAlive"]["newDocumentsAfterSwitch"]=len({r["instance"] for r in latest(phase).values()}-set(end));report["resources"].append(grouped)

    before_clear={a:main_row(phase,a) for a in ("A","B","C")};phase="selective-clear";launch("C",clear=True)
    clear_file="files/grouped-clear-grouped-C.json"
    if not wait(lambda:adb("exec-out","run-as",PACKAGE,"cat",clear_file,check=False),20):raise RuntimeError("C clear was not requested")
    launch("C",probe=True)
    def cleared():
        row=main_row("selective-clear","C",probe=True)
        if not row or row.get("cookie") or row.get("requestCookie"):return None
        if any(row.get(k+"State")!="ready" or row.get(k) is not None for k in ("local","idb","cache")):return None
        if row.get("service",{}).get("state")!="ready" or row.get("service",{}).get("owner") is not None:return None
        return row
    clean=wait(cleared,45);time.sleep(3)
    unchanged={}
    for account in ("A","B"):
        after=main_row("grouped-resource",account);before=before_clear[account]
        unchanged[account]=bool(after and before and all(after.get(k)==before.get(k) for k in ("local","idb","cache","cookie")) and after.get("service",{}).get("owner")==account and after.get("instance")==before.get("instance"))
    report["selectiveClear"]={"clearedCMarkers":bool(clean),"unaffectedAccounts":unchanged,"passed":bool(clean) and all(unchanged.values()),"probe":clean}

    adb("shell","am","force-stop",PACKAGE);time.sleep(2);phase="control-resource"
    control=start_workload(phase,"control");time.sleep(12);control.update({"mode":"control","memory":memory(phase),"cpu":cpu(phase),"sameAccountStorageTokens":same_account_tokens(phase)});report["resources"].append(control)
    report["resourceEnvironmentErrors"]=[{"phase":r["phase"],"account":r["account"],"kind":r["kind"],"errors":validate(r)} for r in records() if r["phase"] in ("grouped-resource","control-resource") and validate(r)]
    screenshot("control-resource")
except Exception as error:
    failure=str(error);report["failure"]=failure;print("Grouped PoC failure:",failure,flush=True)
finally:
    report["systemAfter"]={"locale":adb("shell","getprop","persist.sys.locale"),"timeZone":adb("shell","getprop","persist.sys.timezone")};report["systemUnchanged"]=report["systemBefore"]==report["systemAfter"]
    report["initialRequestErrors"]=[]
    for request in requests:
        account=request.get("account")
        if account not in ("A","B","C"):continue
        if not request["acceptLanguage"].lower().startswith(expected(account)[0].lower()) or any(value!=account for value in re.findall(r"(?:^|;\s*)poc-owner=([^;]*)",request["cookie"])):report["initialRequestErrors"].append(request)
    for case in ("isolation","grouped-resource","control-resource"):
        for account,tab in (("A","0"),("A","1"),("B","0"),("C","0")):
            for path in ("/page","/frame","/worker.js","/image.png"):
                if not any(item["phase"]==case and item["account"]==account and urlparse(item["path"]).path==path and parse_qs(urlparse(item["path"]).query).get("tab")==[tab] for item in requests):
                    report["initialRequestErrors"].append({"phase":case,"account":account,"tab":tab,"missing":path})
    resources=report["resources"];report["resourcesComplete"]=len(resources)==2 and all(r.get("processRelationCorrect") and r.get("sameAccountStorageTokens") and r["memory"]["complete"] and r["cpu"].get("allProcessesCovered") and len(r["cpu"].get("samplesOneCorePercent",[]))>=3 for r in resources)
    keep=resources[0].get("keepAlive",{}) if resources else {};report["keepAlivePassed"]=keep.get("originalContexts")==12 and keep.get("continuingContexts")==12 and keep.get("newDocuments")==0 and keep.get("newDocumentsAfterSwitch")==0
    report["apks"]=[{"file":apk.name,"bytes":apk.stat().st_size,"sha256":hashlib.sha256(apk.read_bytes()).hexdigest()} for apk in Path("environment-poc/build/outputs/apk/debug").glob("*.apk")]
    for name,data in (("report.json",report),("events.json",records()),("requests.json",requests)):(OUT/name).write_text(json.dumps(data,ensure_ascii=False,indent=2))
    (OUT/"runtime.log").write_text(adb("logcat","-d",check=False,timeout=30));(OUT/"processes.txt").write_text(adb("shell","ps","-A","-o","PID,PPID,NAME,ARGS"))
    for apk in report["apks"]:print(apk["file"],apk["bytes"],apk["sha256"],flush=True)
    adb("shell","am","force-stop",PACKAGE,check=False);adb("reverse","--remove","tcp:8765",check=False);server.shutdown()
if failure or not report.get("isolation",{}).get("passed") or not report.get("selectiveClear",{}).get("passed") or not report["systemUnchanged"] or not report["resourcesComplete"] or not report["keepAlivePassed"] or report.get("resourceEnvironmentErrors") or report["initialRequestErrors"]:raise SystemExit(1)
