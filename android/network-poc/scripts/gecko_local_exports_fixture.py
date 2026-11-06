"""Optional stage-one local exports, produced by actual browser Blob/data APIs."""
import base64
import hashlib
import importlib.util
import json
from pathlib import Path
import struct
import threading
import time
import uuid
import zlib

HOST = 'network-fixture.test'


def payloads():
    def chunk(kind, body):
        return struct.pack('>I', len(body))+kind+body+struct.pack('>I', zlib.crc32(kind+body)&0xffffffff)
    png = b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',1,1,8,6,0,0,0))
    png += chunk(b'IDAT',zlib.compress(b'\x00\x40\x80\xc0\xff'))+chunk(b'IEND',b'')
    return {
        'html': ('owned-local-page.html','text/html', '<!doctype html><meta charset="utf-8"><title>Owned export</title><p>浏览器已有合成 HTML 数据</p>\n'.encode('utf-8')),
        'png': ('owned-local-image.png','image/png',png),
        'data': ('owned-local-text.txt','text/plain','浏览器已有合成 data 文本\nOwned local export.\n'.encode('utf-8')),
    }


def handler_for(network_handler, route_name):
    spec = importlib.util.spec_from_file_location('owned_local_page_source',Path(__file__).resolve().parents[2]/'scripts/environment-fixture.py')
    page = importlib.util.module_from_spec(spec);spec.loader.exec_module(page)
    state = page.Evidence(Path('/dev/null'));state.persist=lambda:None
    state.state['host']=HOST;state.state.update(networkRequests=[],localExportsFixture=True)
    lock = threading.RLock()
    exports = {kind:{'name':name,'mime':mime,'bytes':len(data),'sha256':hashlib.sha256(data).hexdigest(),
                     'base64':base64.b64encode(data).decode('ascii')}
               for kind,(name,mime,data) in payloads().items()}

    class Handler(network_handler):
        def request_metadata(self,path):
            now=int(time.time()*1000);cookie=self.headers.get('Cookie','')
            with lock:
                row=state.state['requests'].setdefault(path,{'count':0,'firstAt':now})
                row.update(count=row['count']+1,lastAt=now,acceptLanguage=self.headers.get('Accept-Language',''),
                           cookie=cookie,envLogin=page.env_cookie(cookie)[1])
        def record(self,**fields):
            event={'event':'gecko-local-origin-access','method':self.command,'path':self.path.partition('?')[0],
                   'route':route_name(self.client_address[0]),'identity':self.joint_account(),'at':int(time.time()*1000)}
            event.update(fields)
            with lock:
                state.state['networkRequests'].append(event)
                if fields.get('kind')=='document' and event['path'] in state.state['pageCounts']:
                    state.state['pageCounts'][event['path']]+=1
            print(json.dumps(event,sort_keys=True),flush=True)
            return event
        def do_GET(self):
            path=self.path.partition('?')[0]
            if path not in ('/status','/local-exports/manifest','/c/a1'):
                return super().do_GET()
            self.request_metadata(path)
            route=route_name(self.client_address[0])
            if path=='/status':
                with lock:response=json.loads(json.dumps(state.state))
                self.transfer_json(200,response,route);return
            if path=='/local-exports/manifest':
                self.transfer_json(200,{'schema':1,'exports':exports},route);return
            self.record(kind='document')
            body=page.page_html(path,'').decode('utf-8')
            panel='<div style="display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:8px"><button id="local-html" type="button">导出 HTML</button><button id="local-png" type="button">导出 PNG</button><button id="local-data" type="button">导出 data 文本</button></div><p id="local-export-state" role="status">本地数据已准备</p>'
            assert body.count('<div class="actions">')==1 and body.count('return {path:config.path,')==1
            body=body.replace('<div class="actions">',panel+'<div class="actions">',1)
            body=body.replace('return {path:config.path,','return {localExports:window.ownedLocalExports||null,network:window.ownedNetwork||null,path:config.path,',1)
            script=r'''<script>
const localDefinitions=__DEFINITIONS__,localSources={};
window.ownedLocalExports={prepared:true,clicks:[]};window.ownedNetwork={downloadOperation:null};
for(const [kind,value] of Object.entries(localDefinitions)){
 const bytes=Uint8Array.from(atob(value.base64),letter=>letter.charCodeAt(0));
 localSources[kind]=kind==='data'?'data:'+value.mime+';base64,'+value.base64:URL.createObjectURL(new Blob([bytes],{type:value.mime}));
 document.getElementById('local-'+kind).addEventListener('click',event=>{
  const click={kind,name:value.name,mime:value.mime,bytes:bytes.length,sha256:value.sha256,trusted:event.isTrusted,document:doc,index:window.ownedLocalExports.clicks.length};
  window.ownedLocalExports.clicks.push(click);document.getElementById('local-export-state').textContent='本地导出点击 '+kind+' '+click.index;queue();
  const detached=document.createElement('a');detached.href=localSources[kind];detached.download=value.name;detached.click();
 });
}
document.getElementById('download').addEventListener('click',event=>{const operation='raw-https-gecko-'+crypto.randomUUID().replaceAll('-','');window.ownedNetwork.downloadOperation=operation;event.currentTarget.href='/transfer/'+operation+'/download';queue();});
</script>'''.replace('__DEFINITIONS__',json.dumps(exports,ensure_ascii=True))
            body=body.replace('</body>',script+'</body>',1).encode('utf-8')
            self.send_response(200);self.send_header('Content-Type','text/html; charset=utf-8')
            self.send_header('Content-Length',str(len(body)));self.send_header('Cache-Control','no-store');self.send_header('X-Fixture-Route',route)
            if self.joint_account() not in ('A','B','C'):
                self.send_header('Set-Cookie','ci_joint_account=A; Path=/; Secure; HttpOnly; SameSite=Lax; Max-Age=86400')
            if 'env_login=' not in self.headers.get('Cookie',''):
                self.send_header('Set-Cookie','env_login=environment-A; Path=/; Secure; HttpOnly; SameSite=Lax; Max-Age=86400')
            self.end_headers();self.wfile.write(body);self.wfile.flush()
        def do_POST(self):
            path=self.path.partition('?')[0]
            if path=='/send':
                self.body()
                with lock:state.state['sendCount']+=1
                self.transfer_json(409,{'error':'synthetic fixture never sends'},route_name(self.client_address[0]));return
            if path!='/event':return super().do_POST()
            self.request_metadata(path);value=json.loads(self.body())
            target,document=value.get('path'),value.get('document')
            if target not in page.PAGE_ACCOUNTS or value.get('account')!=page.PAGE_ACCOUNTS[target] or str(uuid.UUID(document))!=document:
                self.transfer_json(400,{'error':'invalid owned observation'},route_name(self.client_address[0]));return
            value.update(receivedAt=int(time.time()*1000),route=route_name(self.client_address[0]),
                         acceptLanguage=self.headers.get('Accept-Language',''),ciIdentity=self.joint_account(),
                         envLogin=page.env_cookie(self.headers.get('Cookie',''))[1])
            with lock:
                previous=state.state['pages'][target].get(document)
                if previous and value.get('eventSequence',0)<=previous.get('eventSequence',0):
                    response=(409,{'error':'stale observation'})
                else:
                    state.state['pages'][target][document]=value;state.state['eventCount']+=1;response=(200,{'ok':True})
            self.record(kind='document-observation',document=document)
            self.transfer_json(response[0],response[1],value['route'])
    return Handler
