"""Optional owned Gecko asset/Skill task pages; all attachments come from FilePrompt."""
import base64
from email.parser import BytesParser
from email.policy import default
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import socket
import struct
import threading
import time
import uuid
import zlib

HOST = 'network-fixture.test'
MODES = ('normal','upload-hold','upload-error','send-ack-drop')


def assets():
    def chunk(kind, body):
        return struct.pack('>I',len(body))+kind+body+struct.pack('>I',zlib.crc32(kind+body)&0xffffffff)
    png = b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',1,1,8,6,0,0,0))
    png += chunk(b'IDAT',zlib.compress(b'\x00\x40\x80\xc0\xff'))+chunk(b'IEND',b'')
    return {'owned-report.html':('text/html','<!doctype html><meta charset="utf-8"><title>Owned task</title><p>合成附件原文，保留 e\u0301 与 🧭。</p>\n'.encode()),
            'owned-image.png':('image/png',png)}


def multipart(content_type, body):
    assert content_type.lower().startswith('multipart/form-data;')
    message = BytesParser(policy=default).parsebytes(b'Content-Type: '+content_type.encode('ascii')+b'\r\nMIME-Version: 1.0\r\n\r\n'+body)
    assert message.is_multipart() and not message.defects
    fields, files = {}, []
    for part in message.iter_parts():
        assert not part.is_multipart() and not part.defects and part.get_content_disposition() == 'form-data'
        key = part.get_param('name',header='content-disposition');name = part.get_filename()
        data = part.get_payload(decode=True);assert isinstance(data,bytes)
        if name is None:
            assert key in ('document','identity') and key not in fields
            fields[key] = data.decode('utf-8')
        else:
            assert key == 'files' and len(files) < 2 and name and len(name) < 200
            files.append({'name':name,'mime':part.get_content_type(),'bytes':len(data),
                          'sha256':hashlib.sha256(data).hexdigest(),'base64':base64.b64encode(data).decode('ascii')})
    assert set(fields) == {'document','identity'} and len(files) == 2
    return fields,files


def task_page(document, owner):
    config = json.dumps({'document':document,'account':owner},ensure_ascii=True)
    return (r'''<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>合成任务 __DOCUMENT__</title><style>*{box-sizing:border-box}body{margin:0;font:16px system-ui;color:#111;background:#fff}main{padding:16px}textarea{width:100%;height:140px;font:16px system-ui}button{font:16px system-ui;padding:12px}input[type=file]{display:none}.attachment{padding:8px;border:1px solid #ccc;margin-bottom:6px}[role=alert]{color:#a00000}[data-message-author-role=user]{white-space:pre-wrap}</style></head><body><main>
<h1>合成资产任务</h1><p>仅已授权的合成材料，不访问真实服务。</p><form><input id="owned-files" type="file" multiple accept="*/*"><div id="files"></div><textarea id="prompt-textarea" aria-label="合成任务指令"></textarea><button type="button" data-testid="send-button" disabled>发送合成任务</button></form><section id="messages"></section></main><script>
'use strict';const config=__CONFIG__,doc=config.document,input=document.getElementById('owned-files'),editor=document.getElementById('prompt-textarea'),send=document.querySelector('[data-testid=send-button]'),box=document.getElementById('files');
let modelDraft='',browserFiles=[],inputCount=0,trustedInputCount=0,fileChanges=0,trustedFileChanges=0,sendClicks=0,uploadBusy=false,error=false,submitted=false,sequence=0;
const hex=bytes=>Array.from(new Uint8Array(bytes),x=>x.toString(16).padStart(2,'0')).join('');
function update(){send.disabled=uploadBusy||error||submitted||browserFiles.length!==2||!editor.value;}
function snapshot(){return {document:doc,account:config.account,url:location.href,language:navigator.language,timeZone:Intl.DateTimeFormat().resolvedOptions().timeZone,
 draft:editor.value,modelDraft,inputCount,trustedInputCount,fileChanges,trustedFileChanges,browserFiles,uploadBusy,error,sendClicks,submitted,
 focused:document.activeElement===editor,userMessages:Array.from(document.querySelectorAll('[data-message-author-role=user]'),e=>e.textContent),eventSequence:++sequence,visibility:document.visibilityState};}
let reporting=false;async function report(){if(reporting)return;reporting=true;try{await fetch('/handoff/event',{method:'POST',credentials:'same-origin',headers:{'Content-Type':'application/json'},body:JSON.stringify(snapshot())});}catch(unavailable){}finally{reporting=false;}}
editor.addEventListener('input',event=>{modelDraft=editor.value;inputCount++;if(event.isTrusted)trustedInputCount++;update();report();});
input.addEventListener('change',async event=>{fileChanges++;if(event.isTrusted)trustedFileChanges++;uploadBusy=true;error=false;browserFiles=[];box.innerHTML='<div role="progressbar">正在上传合成附件</div>';update();report();
 try{const actual=Array.from(input.files);if(actual.length!==2)throw Error('Expected two real files');const form=new FormData();form.append('document',doc);form.append('identity',config.account);
 for(const file of actual){const body=await file.arrayBuffer();browserFiles.push({name:file.name,mime:file.type,bytes:file.size,sha256:hex(await crypto.subtle.digest('SHA-256',body))});form.append('files',file,file.name);}
 const response=await fetch('/handoff/upload',{method:'POST',credentials:'same-origin',body:form});if(!response.ok)throw Error('Owned upload rejected');const result=await response.json();if(!result.accepted)throw Error('Owned upload not accepted');
 box.innerHTML='';for(const file of browserFiles){const row=document.createElement('div');row.className='attachment';row.dataset.testid='attachment';row.dataset.attachmentName=file.name;row.textContent=file.name;const remove=document.createElement('button');remove.type='button';remove.dataset.testid='remove-file';remove.setAttribute('aria-label','Remove attachment '+file.name);remove.textContent='移除附件';remove.addEventListener('click',()=>{browserFiles=browserFiles.filter(value=>value.name!==file.name);row.remove();update();report();});row.appendChild(remove);box.appendChild(row);}}
 catch(failure){error=true;box.innerHTML='<div role="alert">合成附件上传失败，未发送任务</div>';}
 finally{uploadBusy=false;update();report();}});
send.addEventListener('click',async event=>{if(send.disabled)return;sendClicks++;submitted=true;update();report();const prompt=editor.value;
 try{const response=await fetch('/handoff/send',{method:'POST',credentials:'same-origin',headers:{'Content-Type':'application/json'},body:JSON.stringify({document:doc,identity:config.account,prompt,modelDraft,files:browserFiles})});if(!response.ok)throw Error('Owned send rejected');const result=await response.json();if(!result.accepted)throw Error('Owned send not accepted');
 history.pushState({},'', '/c/handoff-'+doc);const message=document.createElement('article');message.dataset.messageAuthorRole='user';message.textContent=prompt;document.getElementById('messages').appendChild(message);editor.value='';modelDraft='';}
 catch(failure){error=true;box.insertAdjacentHTML('beforeend','<div role="alert">合成发送结果待确认，不会自动重发</div>');}
 finally{update();report();}});
document.addEventListener('visibilitychange',report);setInterval(report,1000);report();
</script></body></html>'''.replace('__CONFIG__',config).replace('__DOCUMENT__',document)).encode('utf-8')


def handler_for(network_handler, route_name):
    spec = importlib.util.spec_from_file_location('owned_handoff_hot_pages',Path(__file__).resolve().parents[2]/'scripts/environment-fixture.py')
    page = importlib.util.module_from_spec(spec);spec.loader.exec_module(page)
    evidence = page.Evidence(Path('/dev/null'));evidence.persist=lambda:None
    state = evidence.state;state['host']=HOST
    state.update(networkRequests=[],taskDocuments={},handoffAssetDownloads=[],nextTaskMode=None)
    lock = threading.RLock();gates = {};sources = assets()
    definitions = {name:{'name':name,'mime':mime,'bytes':len(data),'sha256':hashlib.sha256(data).hexdigest(),
                         'base64':base64.b64encode(data).decode('ascii')} for name,(mime,data) in sources.items()}

    class Handler(network_handler):
        def request_metadata(self,path):
            now=int(time.time()*1000)
            with lock:
                row=state['requests'].setdefault(path,{'count':0,'firstAt':now})
                row.update(count=row['count']+1,lastAt=now,acceptLanguage=self.headers.get('Accept-Language',''),
                           cookie=self.headers.get('Cookie',''),envLogin=page.env_cookie(self.headers.get('Cookie',''))[1])
        def record(self,**fields):
            value={'event':'owned-gecko-handoff-access','method':self.command,'path':self.path.partition('?')[0],
                   'route':route_name(self.client_address[0]),'identity':self.joint_account(),'at':int(time.time()*1000)}
            value.update(fields)
            with lock:
                assert len(state['networkRequests'])<4096
                state['networkRequests'].append(value)
                if fields.get('kind')=='document' and value['path'] in state['pageCounts']:
                    state['pageCounts'][value['path']]+=1
            print(json.dumps(value,sort_keys=True),flush=True);return value
        def response(self,code,value):
            return self.transfer_json(code,value,route_name(self.client_address[0]))
        def do_GET(self):
            path=self.path.partition('?')[0]
            if path not in ('/status','/','/c/a1','/handoff/assets/manifest') and not path.startswith(('/handoff/assets/','/c/handoff-')):
                return super().do_GET()
            self.request_metadata(path)
            if path=='/status':
                with lock:value=json.loads(json.dumps(state))
                return self.response(200,value)
            if path=='/handoff/assets/manifest':return self.response(200,{'schema':1,'assets':definitions})
            if path=='/c/a1':
                self.record(kind='document')
                body=page.page_html(path,'').decode('utf-8')
                links='<div class="actions"><a href="/handoff/assets/owned-report.html" aria-label="下载任务 HTML">下载任务 HTML</a><a href="/handoff/assets/owned-image.png" aria-label="下载任务 PNG">下载任务 PNG</a></div>'
                body=body.replace('<div class="actions">',links+'<div class="actions">',1).encode('utf-8')
                self.send_response(200);self.send_header('Content-Type','text/html; charset=utf-8');self.send_header('Content-Length',str(len(body)));self.send_header('Cache-Control','no-store')
                if self.joint_account() not in ('A','B','C'):self.send_header('Set-Cookie','ci_joint_account=A; Path=/; Secure; HttpOnly; SameSite=Lax; Max-Age=86400')
                if 'env_login=' not in self.headers.get('Cookie',''):self.send_header('Set-Cookie','env_login=environment-A; Path=/; Secure; HttpOnly; SameSite=Lax; Max-Age=86400')
                self.end_headers();self.wfile.write(body);return
            if path.startswith('/handoff/assets/'):
                name=path.removeprefix('/handoff/assets/')
                if name not in sources:return self.response(404,{'error':'unknown owned asset'})
                mime,data=sources[name]
                self.record(kind='handoff-asset-download',name=name,bytes=len(data),sha256=definitions[name]['sha256'])
                with lock:state['handoffAssetDownloads'].append({'name':name,'route':route_name(self.client_address[0]),'identity':self.joint_account()})
                self.send_response(200);self.send_header('Content-Type',mime);self.send_header('Content-Length',str(len(data)))
                self.send_header('Content-Disposition','attachment; filename="'+name+'"');self.send_header('Cache-Control','no-store');self.end_headers();self.wfile.write(data);return
            if path.startswith('/c/handoff-'):
                # A submitted task may be reopened, but must never regenerate its side effects.
                document=path.removeprefix('/c/handoff-')
                with lock:known=state['taskDocuments'].get(document)
                if not known or self.joint_account()!=known['identity']:return self.response(404,{'error':'unknown owned conversation'})
                self.record(kind='handoff-reopen',document=document)
                body=('<!doctype html><meta charset="utf-8"><title>已提交合成任务</title><main><p>此对话已经提交，不会重新发送。</p></main>').encode()
            else:
                owner=self.joint_account()
                if owner not in ('A','B','C'):return self.response(401,{'error':'owned account cookie required'})
                with lock:
                    mode=state['nextTaskMode']
                    if mode not in MODES:return self.response(409,{'error':'configure exactly one next task'})
                    state['nextTaskMode']=None;document=str(uuid.uuid4())
                    state['taskDocuments'][document]={'document':document,'mode':mode,'identity':owner,
                        'route':route_name(self.client_address[0]),'documentLoads':1,'uploadAttempts':0,'sendAttempts':0,
                        'uploads':[],'sends':[],'latest':None,'releaseCalls':0,'createdAt':int(time.time()*1000)}
                    gates[document]=threading.Event()
                self.record(kind='handoff-document',document=document,mode=mode)
                body=task_page(document,owner)
            self.send_response(200);self.send_header('Content-Type','text/html; charset=utf-8');self.send_header('Content-Length',str(len(body)));self.send_header('Cache-Control','no-store');self.end_headers();self.wfile.write(body)
        def do_POST(self):
            path=self.path.partition('?')[0]
            if path=='/send':
                self.body()
                with lock:state['sendCount']+=1
                return self.response(400,{'error':'hot source page must never send'})
            if path=='/handoff-control/configure' or re.fullmatch(r'/handoff-control/[a-f0-9-]{36}/release',path):
                if self.server.server_port!=18080 or route_name(self.client_address[0])!='direct':return self.response(403,{'error':'host-only control'})
                value=json.loads(self.body(4096))
                if path.endswith('/configure'):
                    if set(value)!={'schema','mode'} or value['schema']!=1 or value['mode'] not in MODES:return self.response(400,{'error':'invalid next mode'})
                    with lock:
                        if state['nextTaskMode'] is not None:return self.response(409,{'error':'next task already configured'})
                        state['nextTaskMode']=value['mode']
                    return self.response(200,{'ok':True,'mode':value['mode']})
                document=path.split('/')[2]
                if value!={}:return self.response(400,{'error':'no page body allowed in control'})
                with lock:
                    task=state['taskDocuments'].get(document)
                    if task is None:return self.response(404,{'error':'unknown task'})
                    task['releaseCalls']+=1
                    if task['releaseCalls']!=1:return self.response(409,{'error':'release called more than once'})
                    gates[document].set()
                return self.response(200,{'released':True,'document':document})
            if path=='/event':
                value=json.loads(self.body());target,document=value.get('path'),value.get('document')
                if target not in page.PAGE_ACCOUNTS or value.get('account')!=page.PAGE_ACCOUNTS[target] or str(uuid.UUID(document))!=document:return self.response(400,{'error':'invalid hot document'})
                self.request_metadata(path);value.update(receivedAt=int(time.time()*1000),route=route_name(self.client_address[0]),acceptLanguage=self.headers.get('Accept-Language',''),ciIdentity=self.joint_account(),envLogin=page.env_cookie(self.headers.get('Cookie',''))[1])
                with lock:
                    previous=state['pages'][target].get(document)
                    if previous and previous.get('eventSequence',0)>=value.get('eventSequence',0):return self.response(409,{'error':'stale hot document'})
                    state['pages'][target][document]=value;state['eventCount']+=1
                return self.response(200,{'ok':True})
            if path not in ('/handoff/upload','/handoff/send','/handoff/event'):return super().do_POST()
            route=route_name(self.client_address[0]);identity=self.joint_account()
            try:
                raw=self.body(2*1024*1024)
                if path=='/handoff/upload':value,files=multipart(self.headers.get('Content-Type',''),raw)
                else:value=json.loads(raw)
                document=value['document'];assert str(uuid.UUID(document))==document
                with lock:
                    task=state['taskDocuments'][document]
                    assert identity==task['identity']==value.get('identity',value.get('account')) and route==task['route']
                    if path=='/handoff/event':
                        assert type(value['eventSequence']) is int
                        if task['latest'] and task['latest']['eventSequence']>=value['eventSequence']:return self.response(409,{'error':'stale task observation'})
                        value.update(receivedAt=int(time.time()*1000),route=route,ciIdentity=identity,acceptLanguage=self.headers.get('Accept-Language',''))
                        task['latest']=value;return self.response(200,{'ok':True})
                    if path=='/handoff/upload':
                        task['uploadAttempts']+=1;assert task['uploadAttempts']==1
                        expected=[dict(definitions[name],name=name+('.txt' if name.endswith('.html') else ''),mime='text/plain' if name.endswith('.html') else definitions[name]['mime']) for name in sources]
                        assert files==expected
                        task['uploads']=files;task['uploadBodySHA256']=hashlib.sha256(raw).hexdigest();mode=task['mode']
                    else:
                        state['sendCount']+=1;task['sendAttempts']+=1;assert task['sendAttempts']==1 and task['uploadAttempts']==1 and task['mode']!='upload-error'
                        assert value['prompt']==value['modelDraft'] and isinstance(value['prompt'],str) and 0<len(value['prompt'])<=70000
                        expected=[{key:row[key] for key in ('name','mime','bytes','sha256')} for row in task['uploads']]
                        assert value['files']==expected
                        prompt=value['prompt'];task['sends'].append({'prompt':prompt,'promptSha256':hashlib.sha256(prompt.encode()).hexdigest(),
                            'utf8Bytes':len(prompt.encode()),'utf16Length':len(prompt.encode('utf-16-le'))//2,'files':value['files'],'route':route,'identity':identity})
                        mode=task['mode']
                self.record(kind='handoff-upload' if path.endswith('/upload') else 'handoff-send',document=document)
                if path.endswith('/upload'):
                    if mode=='upload-hold' and not gates[document].wait(40):return self.response(504,{'error':'owned upload hold expired'})
                    if mode=='upload-error':return self.response(422,{'error':'owned upload rejected'})
                    return self.response(200,{'accepted':True,'document':document})
                if mode=='send-ack-drop':
                    self.close_connection=True;self.connection.shutdown(socket.SHUT_RDWR);self.connection.close();return
                return self.response(200,{'accepted':True,'document':document})
            except (AssertionError,KeyError,ValueError,UnicodeError) as invalid:
                with lock:state['rejectedEventCount']+=1
                self.record(kind='handoff-rejected',errorClass=type(invalid).__name__)
                return self.response(400,{'error':'invalid owned task request'})
    return Handler
