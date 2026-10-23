#!/usr/bin/env python3
"""Fresh CI emulator only. Real engine values, HTTP headers, storage and process resources.

No DevTools, JS API replacements, personal accounts, production package or system locale writes.
The loopback server supplies deterministic rich pages. PoC outcome may legitimately be negative.
"""
from pathlib import Path
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs, urlencode
import hashlib
import json
import os
import re
import statistics
import struct
import subprocess
import sys
import threading
import time
import uuid
import zlib

PACKAGE = "com.forcemind.chatgpt.environmentpoc"
COMPONENT = PACKAGE + "/" + PACKAGE + ".AccountAActivity"
OUT = Path(sys.argv[1]); OUT.mkdir(parents=True, exist_ok=True)
events, requests, lock = [], [], threading.Lock()
phase = "isolation"
SNAPSHOT = """function snapshot(){const f=new Intl.DateTimeFormat().resolvedOptions();return {
 language:navigator.language,languages:Array.from(navigator.languages),locale:f.locale,
 numberLocale:new Intl.NumberFormat().resolvedOptions().locale,
 localeMaximized:new Intl.Locale(f.locale).maximize().toString(),
 numberLocaleMaximized:new Intl.Locale(new Intl.NumberFormat().resolvedOptions().locale).maximize().toString(),timeZone:f.timeZone,
 winterOffset:new Date('2026-01-15T12:00:00Z').getTimezoneOffset(),
 summerOffset:new Date('2026-07-15T12:00:00Z').getTimezoneOffset(),
 localNoon:new Date(2026,0,15,12).toISOString(),parsedNoon:new Date('2026-01-15T12:00:00').toISOString(),
 dstGap:new Date(2026,2,8,2,30).toISOString()};} """

def adb(*args, check=True, timeout=25):
    return subprocess.run(["adb", *args], capture_output=True, timeout=timeout, check=check).stdout.decode(errors="replace").strip()

def wait(predicate, seconds=60):
    end=time.monotonic()+seconds
    while time.monotonic()<end:
        result=predicate()
        if result: return result
        time.sleep(.5)
    return None

def records(case=None):
    with lock: return [dict(e) for e in events if case is None or e.get("phase")==case]

def native(account):
    try: return json.loads(adb("exec-out","run-as",PACKAGE,"cat","files/native-"+account+".json"))
    except (subprocess.SubprocessError, ValueError): return {}

def launch(account="A", tabs=0, **flags):
    args=["shell","am","start","-W","-n",COMPONENT,"--es","account",account]
    if tabs: args += ["--ei","tabs",str(tabs)]
    for key,value in flags.items(): args += ["--ez",key,str(value).lower()]
    adb(*args)

def screenshot(name):
    result=subprocess.run(["adb","exec-out","screencap","-p"],capture_output=True,timeout=25,check=True)
    (OUT/(name+".png")).write_bytes(result.stdout)

def image():
    # Deterministic pixels; distinct resource URLs and explicit decode() in rich pages.
    import random
    rng=random.Random(142); raw=b"".join(b"\0"+rng.randbytes(1024*3) for _ in range(768))
    def chunk(kind,data): return struct.pack(">I",len(data))+kind+data+struct.pack(">I",zlib.crc32(kind+data)&0xffffffff)
    return b"\x89PNG\r\n\x1a\n"+chunk(b"IHDR",struct.pack(">IIBBBBB",1024,768,8,2,0,0,0))+chunk(b"IDAT",zlib.compress(raw))+chunk(b"IEND",b"")

PNG=image()

class Handler(BaseHTTPRequestHandler):
    def log_message(self,*args): pass
    def reply(self,body,mime):
        if isinstance(body,str):body=body.encode()
        self.send_response(200);self.send_header("Content-Type",mime);self.send_header("Content-Length",str(len(body)))
        self.send_header("Cache-Control","no-store");self.end_headers();self.wfile.write(body)
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
        path=urlparse(self.path);query=parse_qs(path.query)
        with lock:requests.append({"phase":phase,"path":self.path,"account":query.get("account",[None])[0],"host":self.headers.get("Host"),"acceptLanguage":self.headers.get("Accept-Language",""),"cookie":self.headers.get("Cookie","")})
        if path.path=="/image.png":self.reply(PNG,"image/png");return
        if path.path not in ("/page","/frame","/popup","/worker.js"):self.send_error(404);return
        kind={"/page":"main","/frame":"frame","/popup":"popup","/worker.js":"worker"}[path.path]
        account=query.get("account",["?"])[0];tab=query.get("tab",["?"])[0]
        base={"phase":phase,"account":account,"tab":tab,"kind":kind,"instance":str(uuid.uuid4())}
        script=SNAPSHOT+"const base="+json.dumps(base)+";let ticks=0,decodedImages=0;"
        if kind in ("main","popup"):script+="Promise.all(Array.from(document.images,i=>i.decode())).then(()=>{decodedImages=document.images.length;report()}).catch(e=>console.error('image decode failed',e));"
        script+="function report(){const payload={...base,...snapshot(),ticks,decodedImages,now:Date.now()};"
        if kind!="worker":
            script+="payload.previousCookie=document.cookie;payload.previousStorage=localStorage.getItem('poc-owner');if(!payload.previousStorage)localStorage.setItem('poc-owner',base.account);if(!document.cookie)document.cookie='poc-owner='+base.account+'; SameSite=Lax; path=/';payload.cookie=document.cookie;payload.storage=localStorage.getItem('poc-owner');"
            script+="const b=document.querySelector('#popup');if(b){const r=b.getBoundingClientRect();payload.popupButton={x:r.x+r.width/2,y:r.y+r.height/2,ratio:devicePixelRatio};}"
        script+="fetch('/report',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(payload)}).catch(()=>{});"
        if kind!="worker":script+="document.querySelector('#result').textContent=JSON.stringify(payload,null,2);"
        script+="}report();setInterval(()=>{ticks++;report()},2000);"
        if kind=="worker":self.reply(script,"application/javascript");return
        args=urlencode({"account":account,"tab":tab})
        if kind in ("main","popup"):
            script+="const worker=new Worker('/worker.js?"+args+"');"
        popup_args=urlencode({"account":account,"tab":"popup-"+str(uuid.uuid4())})
        body="<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'><title>独立环境验收</title><style>body{font:14px sans-serif;color:#171717;background:white;margin:14px}button{background:#171717;color:white;border:0;border-radius:12px;padding:14px}pre{white-space:pre-wrap;font-size:11px}img{width:100%;height:auto}</style><b>账号 "+account+" · "+kind+"</b><p><button id=popup onclick=\"window.open('/popup?"+popup_args+"','_blank')\">打开受控弹窗</button></p><pre id=result></pre>"
        if kind in ("main","popup"):
            body+="<iframe src='http://localhost:8765/frame?"+args+"' style='width:100%;height:250px'></iframe>"
            body+="".join("<p>离线对话 "+str(i)+" — 保留草稿、阅读内容及数字资产，仅用于资源测量。</p>" for i in range(240))
            body+="".join("<img loading=eager src='/image.png?asset="+str(i)+"&account="+account+"&tab="+tab+"'>" for i in range(6))
        self.reply(body+"<script>"+script+"</script>","text/html; charset=utf-8")

def validate(row):
    expected={"A":("zh-CN","Asia/Shanghai",-480,-480,"2026-01-15T04:00:00.000Z","2026-03-07T18:30:00.000Z"),
              "B":("en-US","America/New_York",300,240,"2026-01-15T17:00:00.000Z","2026-03-08T07:30:00.000Z")}
    lang,zone,winter,summer,noon,gap=expected[row["account"]]
    errors=[]
    for key,value in {"language":lang,"timeZone":zone,"winterOffset":winter,"summerOffset":summer,"localNoon":noon,"parsedNoon":noon,"dstGap":gap}.items():
        if row.get(key)!=value:errors.append(key+": "+str(row.get(key))+" != "+str(value))
    # zh-CN maximizes to zh-Hans-CN. Verify semantic language/script/region;
    # never accept a different Chinese region or another language as equivalent.
    maximized="zh-Hans-CN" if row["account"]=="A" else "en-Latn-US"
    for key in ("locale","numberLocale"):
        if row.get(key) not in (("zh-CN","zh-Hans-CN") if row["account"]=="A" else ("en-US",)) or row.get(key+"Maximized")!=maximized:errors.append(key+": "+str(row.get(key))+" / "+str(row.get(key+"Maximized")))
    if not row.get("languages") or row["languages"][0]!=lang:errors.append("navigator.languages")
    if not row.get("acceptLanguage","").lower().startswith(lang.lower()):errors.append("network Accept-Language: "+row.get("acceptLanguage",""))
    if row.get("requestCookie") and "poc-owner="+row["account"] not in row["requestCookie"]:errors.append("network cookie leak")
    if row["kind"]!="worker":
        for key in ("storage","previousStorage"):
            if row.get(key) not in (None,"",row["account"]):errors.append("storage leak: "+key)
        cookie=row.get("cookie","")
        # Lax cookies are rejected in this cross-site iframe. First-party pages
        # must have their own cookie; an iframe may be blocked but never foreign.
        if (cookie or row["kind"]!="frame") and "poc-owner="+row["account"] not in cookie:errors.append("cookie isolation")
        previous=row.get("previousCookie","")
        if previous and "poc-owner="+row["account"] not in previous:errors.append("previous cookie leak")
    return errors

def process_list():
    rows=[]
    for line in adb("shell","ps","-A","-o","PID,NAME").splitlines()[1:]:
        parts=line.split()
        if len(parts)==2 and parts[1].startswith(PACKAGE):rows.append((int(parts[0]),parts[1]))
    return rows

def memory():
    values=[]
    for pid,name in process_list():
        data=adb("shell","dumpsys","meminfo",str(pid),check=False)
        (OUT/(phase+"-mem-"+str(pid)+".txt")).write_text(data)
        match=re.search(r"TOTAL PSS:\s*(\d+)",data) or re.search(r"^\s*TOTAL\s+(\d+)",data,re.M)
        values.append({"pid":pid,"name":name,"pssKiB":int(match.group(1)) if match else None})
    return {"processes":values,"totalPssMiB":round(sum(v["pssKiB"] or 0 for v in values)/1024,2),"complete":bool(values) and all(v["pssKiB"] is not None for v in values)}

def cpu():
    # Ignore top's first cumulative sample; following four samples use two-second deltas.
    expected={pid:name for pid,name in process_list()}
    if not expected:return {"samplesOneCorePercent":[],"failure":"No attributable processes"}
    output=adb("shell","top","-b","-n","5","-d","2","-m","512","-p",",".join(map(str,expected)),"-o","PID,%CPU,ARGS",timeout=35)
    (OUT/(phase+"-top.txt")).write_text(output)
    samples=[];coverage=[];sample=None;seen=set()
    for line in output.splitlines():
        if re.search(r"\bPID\s+%CPU(?:\s+|\[)ARGS",line):
            if sample is not None:samples.append(sample);coverage.append(sorted(seen))
            sample=0.0;seen=set();continue
        if sample is not None:
            parts=line.strip().split(None,2)
            if len(parts)==3 and parts[0].isdigit() and int(parts[0]) in expected:
                try:sample+=float(parts[1].replace("%",""));seen.add(int(parts[0]))
                except ValueError:pass
    if sample is not None:samples.append(sample);coverage.append(sorted(seen))
    samples=samples[1:];coverage=coverage[1:]
    return {"expectedProcesses":expected,"samplePids":coverage,"allProcessesCovered":bool(coverage) and all(set(pids)==set(expected) for pids in coverage),"samplesOneCorePercent":samples,"meanOneCorePercent":round(statistics.mean(samples),2) if samples else None,"peakOneCorePercent":max(samples) if samples else None}

devices=[x.split()[0] for x in adb("devices").splitlines()[1:] if x.endswith("\tdevice")]
if os.environ.get("GITHUB_ACTIONS")!="true" or len(devices)!=1 or not devices[0].startswith("emulator-") or adb("shell","getprop","ro.kernel.qemu")!="1":
    raise SystemExit("Only one isolated GitHub Actions emulator is allowed; physical devices refused")
if adb("shell","pidof",PACKAGE,check=False) or adb("shell","run-as",PACKAGE,"sh","-c","'test -d files/poc-gecko-A && echo exists'",check=False):
    raise SystemExit("Existing PoC process/profile found; refusing to touch existing state")

report={"schema":1,"engine":"GeckoView 158.0.20261021001945","package":PACKAGE,"remoteDebugging":False,
        "fixture":{"messages":240,"images":6,"imageDimensions":[1024,768],"imageBytes":len(PNG),"forcedImageDecode":True},
        "sourceSha":os.environ.get("GITHUB_SHA"),"runId":os.environ.get("GITHUB_RUN_ID"),
        "device":{"model":adb("shell","getprop","ro.product.model"),"release":adb("shell","getprop","ro.build.version.release"),"sdk":adb("shell","getprop","ro.build.version.sdk"),"abi":adb("shell","getprop","ro.product.cpu.abi")},
        "scope":"Synthetic loopback sites only; Android 15 x86_64 emulator, not real ChatGPT or physical-phone resource acceptance",
        "systemBefore":{"locale":adb("shell","getprop","persist.sys.locale"),"timeZone":adb("shell","getprop","persist.sys.timezone")},"resources":[]}
server=ThreadingHTTPServer(("127.0.0.1",8765),Handler);threading.Thread(target=server.serve_forever,daemon=True).start()
adb("reverse","tcp:8765","tcp:8765")
failure=None
try:
    launch("A",1)
    if not wait(lambda:len({r["kind"] for r in records() if r["account"]=="A"})>=3,90):raise RuntimeError("Account A main/frame/worker did not all report")
    # A real pointer gesture opens the browser-created popup, rather than simulating a new native tab.
    geo=wait(lambda:native("A") if native("A").get("viewHeight",0)>0 else None,10)
    row=next(r for r in reversed(records()) if r["account"]=="A" and r["kind"]=="main")
    p=row["popupButton"];adb("shell","input","tap",str(round(geo["viewX"]+p["x"]*p["ratio"])),str(round(geo["viewY"]+p["y"]*p["ratio"])))
    popup=bool(wait(lambda:any(r["kind"]=="popup" and r["account"]=="A" for r in records()),30))
    screenshot("account-a")
    launch("B",1)
    b=bool(wait(lambda:len({r["kind"] for r in records() if r["account"]=="B"})>=3,60))
    if b:
        launch("A",4);launch("B",4)
        contexts_ready=bool(wait(lambda:len({(r["account"],r["tab"],r["kind"]) for r in records()})==24,45))
        report["allExpandedContextsReported"]=contexts_ready
        before={r["instance"] for r in records()}
        launch("A",recreate=True);launch("B",recreate=True);launch("A");time.sleep(5)
        after=records();report["activityRecreationNewDocuments"]=len({r["instance"] for r in after}-before)
    else: report["activityRecreationNewDocuments"]=None
    rows=records();latest={}
    for r in rows:latest[(r["account"],r["tab"],r["kind"],r["instance"])]=r
    checks=[{"account":r["account"],"tab":r["tab"],"kind":r["kind"],"observed":r,"errors":validate(r)} for r in latest.values()]
    report["isolation"]={"popupOpened":popup,"bothAccountsReported":b,"checks":checks,
                         "passed":popup and b and report.get("allExpandedContextsReported",False) and all(not c["errors"] for c in checks) and report.get("activityRecreationNewDocuments")==0}
    screenshot("isolation-final")
    report["nativeAccounts"]={a:native(a) for a in ("A","B")}
    # Reset only the experimental package's process, keeping its synthetic storage to test reuse.
    for count in (1,4,8):
        adb("shell","am","force-stop",PACKAGE);time.sleep(2);phase="tabs-"+str(count)
        launch("A",1 if count==1 else count//2)
        if count>1:launch("B",count//2)
        ready=bool(wait(lambda:len({(r["account"],r["tab"]) for r in records(phase) if r["kind"]=="main" and r.get("decodedImages")==6})==count,60))
        time.sleep(12)
        measurement={"tabs":count,"allPagesReported":ready,"memory":memory(),"cpu":cpu()}
        if count==8 and ready:
            start={r["instance"]:r for r in records(phase)};begin=time.monotonic();time.sleep(330)
            end={r["instance"]:r for r in records(phase)}
            measurement["keepAlive"]={"durationSeconds":round(time.monotonic()-begin,1),"originalContexts":len(start),
                "continuingContexts":sum(k in end and end[k]["ticks"]>v["ticks"] and end[k]["received"]>begin+320 for k,v in start.items()),
                "newDocuments":len(set(end)-set(start))}
            launch("A",next=True);launch("B",next=True);time.sleep(4)
            switched={r["instance"] for r in records(phase)}
            measurement["keepAlive"]["newDocumentsAfterSwitch"]=len(switched-set(end))
        report["resources"].append(measurement);print(json.dumps(measurement),flush=True)
except Exception as e:
    failure=str(e);report["failure"]=failure;print("PoC failure:",failure,flush=True)
finally:
    report["nativeAccounts"]={a:native(a) for a in ("A","B")}
    report["systemAfter"]={"locale":adb("shell","getprop","persist.sys.locale"),"timeZone":adb("shell","getprop","persist.sys.timezone")}
    report["systemUnchanged"]=report["systemBefore"]==report["systemAfter"]
    resource_rows=report["resources"]
    keep=resource_rows[-1].get("keepAlive",{}) if resource_rows else {}
    report["resourcesComplete"]=len(resource_rows)==3 and all(r["allPagesReported"] and r["memory"]["complete"] and r["cpu"].get("allProcessesCovered",False) and len(r["cpu"]["samplesOneCorePercent"])>=3 for r in resource_rows)
    report["keepAlivePassed"]=keep.get("originalContexts")==24 and keep.get("continuingContexts")==24 and keep.get("newDocuments")==0 and keep.get("newDocumentsAfterSwitch")==0
    report["resourcePhaseEnvironmentErrors"]=[{"phase":r["phase"],"account":r["account"],"tab":r["tab"],"kind":r["kind"],"errors":validate(r)} for r in records() if r["phase"]!="isolation" and validate(r)]
    report["initialRequestErrors"]=[]
    for request in requests:
        a=request.get("account")
        if a not in ("A","B"):continue
        lang="zh-CN" if a=="A" else "en-US"
        if not request["acceptLanguage"].lower().startswith(lang.lower()) or (request["cookie"] and "poc-owner="+a not in request["cookie"]):report["initialRequestErrors"].append(request)
    (OUT/"report.json").write_text(json.dumps(report,ensure_ascii=False,indent=2))
    (OUT/"events.json").write_text(json.dumps(records(),ensure_ascii=False,indent=2))
    (OUT/"requests.json").write_text(json.dumps(requests,ensure_ascii=False,indent=2))
    (OUT/"runtime.log").write_text(adb("logcat","-d",check=False,timeout=30))
    (OUT/"processes.txt").write_text(adb("shell","ps","-A","-o","PID,PPID,NAME,ARGS"))
    for apk in Path("environment-poc/build/outputs/apk/debug").glob("*.apk"):
        print(apk.name,apk.stat().st_size,hashlib.sha256(apk.read_bytes()).hexdigest(),flush=True)
    adb("shell","am","force-stop",PACKAGE,check=False);adb("reverse","--remove","tcp:8765",check=False);server.shutdown()
if failure or not report.get("isolation",{}).get("passed") or not report["systemUnchanged"] or not report["resourcesComplete"] or not report["keepAlivePassed"] or report["resourcePhaseEnvironmentErrors"] or report["initialRequestErrors"]:
    raise SystemExit(1)
