"""Optional Gecko pages on the existing owned VLESS origin; never enabled by old fixture runs."""
import email.parser
import email.policy
import hashlib
import importlib.util
import json
import os
import ssl
import subprocess
from pathlib import Path
import re
import threading
import time
import uuid

UPLOAD_BYTES = b'owned-gecko-network-upload\n' * 1024
HOST = 'network-fixture.test'
PAGES = {'/c/a1': 'A', '/c/a2': 'A', '/c/b1': 'B', '/c/c1': 'C'}



def gecko_tls_context(folder):
    """Keep the prepared CA immutable; Mozilla requires a CA:FALSE TLS end entity."""
    if os.environ.get('GITHUB_ACTIONS') != 'true':
        raise RuntimeError('Owned CI TLS fixture only')
    ca, ca_key = folder / 'ca.pem', folder / 'key.pem'
    ca_bytes = hashlib.sha256(ca.read_bytes()).digest()
    key_bytes = hashlib.sha256(ca_key.read_bytes()).digest()
    leaf, key = folder / 'gecko-origin-cert.pem', folder / 'gecko-origin-key.pem'
    csr, extension = folder / 'gecko-origin.csr', folder / 'gecko-origin.ext'
    def openssl(*arguments):
        result = subprocess.run(['openssl', *map(str, arguments)], text=True,
                                stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=30)
        if result.returncode:
            raise RuntimeError('Owned origin certificate command failed: ' + result.stdout)
        return result.stdout
    openssl('req', '-new', '-newkey', 'rsa:2048', '-nodes', '-sha256',
            '-keyout', key, '-out', csr, '-subj', '/CN='+HOST+'/O=Owned Gecko origin')
    key.chmod(0o600)
    extension.write_text('basicConstraints=critical,CA:FALSE\n'
                         'keyUsage=critical,digitalSignature,keyEncipherment\n'
                         'extendedKeyUsage=serverAuth\n'
                         'subjectAltName=DNS:network-fixture.test,DNS:files.network-fixture.test\n', encoding='utf-8')
    openssl('x509', '-req', '-in', csr, '-CA', ca, '-CAkey', ca_key,
            '-set_serial', str(uuid.uuid4().int), '-days', '2', '-sha256', '-extfile', extension, '-out', leaf)
    verification = []
    for host in (HOST, 'files.network-fixture.test'):
        verification.append(openssl('verify', '-CAfile', ca, '-purpose', 'sslserver', '-verify_hostname', host, leaf).strip())
    constraints = openssl('x509', '-in', leaf, '-noout', '-ext', 'basicConstraints')
    if 'CA:FALSE' not in constraints or 'CA:TRUE' in constraints:
        raise RuntimeError('Origin leaf must be an end-entity certificate')
    sans = re.findall(r'DNS:([^,\s]+)', openssl('x509', '-in', leaf, '-noout', '-ext', 'subjectAltName'))
    if sans != [HOST, 'files.network-fixture.test']:
        raise RuntimeError('Origin SAN differs from the two owned DNS names')
    if hashlib.sha256(ca.read_bytes()).digest() != ca_bytes or hashlib.sha256(ca_key.read_bytes()).digest() != key_bytes:
        raise RuntimeError('Prepared CA or private key changed')
    def fingerprint(path):
        match = re.search(r'Fingerprint=([A-Fa-f0-9:]+)', openssl('x509', '-in', path, '-noout', '-fingerprint', '-sha256'))
        if not match:
            raise RuntimeError('Certificate fingerprint missing')
        return match[1].replace(':', '').lower()
    proof = {'event': 'owned-gecko-origin-tls', 'caUnchanged': True, 'caCertificateSha256': fingerprint(ca),
             'leafCertificateSha256': fingerprint(leaf), 'leafIsCA': False, 'dnsSANs': sans,
             'purpose': 'sslserver', 'verification': verification}
    (folder / 'gecko-origin-tls.json').write_text(json.dumps(proof, indent=2)+'\n', encoding='utf-8')
    print(json.dumps(proof, sort_keys=True), flush=True)
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.minimum_version = ssl.TLSVersion.TLSv1_2
    context.set_alpn_protocols(['http/1.1'])
    context.load_cert_chain(str(leaf), str(key))
    return context


def handler_for(base, route_name):
    source = Path(__file__).resolve().parents[2] / 'scripts/environment-fixture.py'
    spec = importlib.util.spec_from_file_location('owned_gecko_page_source', source)
    page = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(page)
    evidence = page.Evidence(Path('/dev/null'))
    # The in-memory observations and JSONL are the evidence. No /dev/null.new writes.
    evidence.persist = lambda: None
    evidence.state['host'] = HOST
    evidence.state.update(networkRequests=[], uploads=[], transport=None, runId=None)
    gate = base._transfer_gates
    lock = threading.RLock()
    upload_pattern = re.compile(r'/gecko-network/((?:raw|tls|reality)-https-[a-z][a-z0-9-]{0,47})/upload')

    class Handler(base):
        def record(self, **fields):
            event = dict(event='gecko-origin-access', method=self.command, path=self.path.partition('?')[0],
                         route=route_name(self.client_address[0]), identity=self.joint_account(),
                         at=int(time.time()*1000))
            event.update(fields)
            with lock:
                evidence.state['networkRequests'].append(event)
                if len(evidence.state['networkRequests']) > 4096:
                    raise ValueError('owned request evidence limit')
            print(json.dumps(event, sort_keys=True), flush=True)
            return event

        def body(self, maximum=512*1024):
            self.connection.settimeout(10)
            length = self.headers.get('Content-Length')
            transfer = self.headers.get('Transfer-Encoding', '').lower()
            if length is not None and transfer:
                raise ValueError('ambiguous body framing')
            if length is not None:
                size = int(length)
                if not 0 <= size <= maximum:
                    raise ValueError('bounded body required')
                data = self.rfile.read(size)
                if len(data) != size:
                    raise EOFError('incomplete body')
                return data
            if transfer != 'chunked':
                raise ValueError('body framing required')
            chunks, total = [], 0
            while True:
                line = self.rfile.readline(1025)
                if not line.endswith(b'\r\n') or len(line) > 1024:
                    raise ValueError('chunk framing')
                size = int(line[:-2].split(b';', 1)[0], 16)
                if size == 0:
                    for _ in range(32):
                        trailer = self.rfile.readline(1025)
                        if trailer == b'\r\n':
                            return b''.join(chunks)
                        if not trailer.endswith(b'\r\n'):
                            raise ValueError('chunk trailer')
                    raise ValueError('trailer limit')
                total += size
                if size < 0 or total > maximum:
                    raise ValueError('body limit')
                data = self.rfile.read(size)
                if len(data) != size or self.rfile.read(2) != b'\r\n':
                    raise EOFError('incomplete chunk')
                chunks.append(data)

        def do_GET(self):
            path = self.path.partition('?')[0]
            if path == '/status':
                with lock:
                    self.transfer_json(200, evidence.state, route_name(self.client_address[0]))
                return
            if path == '/worker.js':
                self.finite_response(200, page.WORKER_JS.encode(), route_name(self.client_address[0]), 'application/javascript')
                return
            echo = re.fullmatch(r'/gecko-network/((?:raw|tls|reality)-https-[a-z][a-z0-9-]{0,47})/echo', path)
            if echo:
                route = route_name(self.client_address[0]);self.record(kind='browser-probe', operation=echo[1])
                self.transfer_json(200, {'operation': echo[1], 'route': route, 'identity': self.joint_account()}, route);return
            if path not in PAGES:
                if path.startswith('/transfer/'):
                    self.record(kind='download-request')
                return super().do_GET()
            with lock:
                transport = evidence.state['transport']
                if transport not in ('raw', 'tls', 'reality'):
                    self.transfer_json(503, {'error': 'owned configuration required'}, 'direct')
                    return
                evidence.state['pageCounts'][path] += 1
            self.record(kind='document')
            body = page.page_html(path, '').decode('utf-8')
            form = '<div><input id="network-file" type="file" aria-label="选择合成上传文件"><button id="network-upload" type="button" aria-label="上传合成文件" disabled>上传合成文件</button><button id="network-probe" type="button" aria-label="验证网页出口">验证网页出口</button><p id="network-state" role="status">合成文件尚未上传</p></div>'
            assert body.count('<div class="actions">') == 1 and body.count('return {path:config.path,') == 1
            body = body.replace('<div class="actions">', form + '<div class="actions">', 1)
            body = body.replace('return {path:config.path,', 'return {network:window.ownedNetwork||null,path:config.path,', 1)
            script = r'''<script>
window.ownedNetwork={selected:null,uploadRequests:0,upload:null,error:'',downloadOperation:null};
const ownedFile=document.getElementById('network-file'),ownedButton=document.getElementById('network-upload');
const ownedStatus=document.getElementById('network-state');
const ownedOperation=()=>__TRANSPORT__+'-https-gecko-'+crypto.randomUUID().replaceAll('-','');
ownedFile.addEventListener('change',async()=>{try{const f=ownedFile.files[0];if(!f)throw Error('missing selection');const digest=await crypto.subtle.digest('SHA-256',await f.arrayBuffer());window.ownedNetwork.selected={name:f.name,size:f.size,sha256:Array.from(new Uint8Array(digest),n=>n.toString(16).padStart(2,'0')).join('')};ownedButton.disabled=false;queue();}catch(error){window.ownedNetwork.error=error.name;queue();}});
ownedButton.addEventListener('click',async()=>{if(ownedButton.disabled)return;ownedButton.disabled=true;const operation=ownedOperation();window.ownedNetwork.uploadOperation=operation;window.ownedNetwork.uploadRequests++;ownedStatus.textContent='上传请求 '+operation;queue();try{const f=ownedFile.files[0],form=new FormData();form.append('file',f,f.name);form.append('identity',config.account);form.append('operation',operation);const response=await fetch('/gecko-network/'+operation+'/upload',{method:'POST',credentials:'same-origin',body:form});const result=await response.json();if(!response.ok)throw Error('upload rejected');window.ownedNetwork.upload={...result,headerRoute:response.headers.get('X-Fixture-Route')};ownedStatus.textContent='上传完成 '+operation;}catch(error){window.ownedNetwork.error=error.name;ownedStatus.textContent='上传已阻止 '+operation;}finally{ownedButton.disabled=false;queue();}});
document.getElementById('download').addEventListener('click',event=>{const operation=ownedOperation();window.ownedNetwork.downloadOperation=operation;ownedStatus.textContent='下载请求 '+operation;event.currentTarget.href='/transfer/'+operation+'/download';queue();});
document.getElementById('network-probe').addEventListener('click',async()=>{const operation=ownedOperation();window.ownedNetwork.probeOperation=operation;ownedStatus.textContent='网页请求 '+operation;queue();try{const response=await fetch('/gecko-network/'+operation+'/echo',{credentials:'same-origin',cache:'no-store'});const result=await response.json();if(!response.ok)throw Error('probe rejected');window.ownedNetwork.probe={...result,headerRoute:response.headers.get('X-Fixture-Route')};ownedStatus.textContent='网页完成 '+operation;}catch(error){window.ownedNetwork.error=error.name;ownedStatus.textContent='网页已阻止 '+operation;}finally{queue();}});
</script>'''.replace('__TRANSPORT__', json.dumps(transport))
            body = body.replace('</body>', script + '</body>', 1).encode('utf-8')
            self.send_response(200)
            self.send_header('Content-Type', 'text/html; charset=utf-8')
            self.send_header('Content-Length', str(len(body)))
            self.send_header('Cache-Control', 'no-store')
            self.send_header('X-Fixture-Route', route_name(self.client_address[0]))
            if self.joint_account() not in ('A', 'B', 'C'):
                self.send_header('Set-Cookie', 'ci_joint_account='+PAGES[path]+'; Path=/; Secure; HttpOnly; SameSite=Lax; Max-Age=86400')
            if 'env_login=' not in self.headers.get('Cookie', ''):
                self.send_header('Set-Cookie', 'env_login=environment-'+PAGES[path]+'; Path=/; Secure; HttpOnly; SameSite=Lax; Max-Age=86400')
            self.end_headers()
            self.wfile.write(body)
            self.wfile.flush()

        def do_POST(self):
            path = self.path.partition('?')[0]
            if path == '/gecko-network/control/configure':
                if self.server.server_port != 18080 or route_name(self.client_address[0]) != 'direct':
                    self.transfer_json(403, {'error': 'host-only control'}, 'direct');return
                value = json.loads(self.body(4096))
                if set(value) != {'transport', 'runId'} or value['transport'] not in ('raw', 'tls', 'reality') or str(uuid.UUID(value['runId'])) != value['runId']:
                    self.transfer_json(400, {'error': 'invalid owned configuration'}, 'direct');return
                with lock:
                    if evidence.state['runId'] is not None:
                        self.transfer_json(409, {'error': 'already configured'}, 'direct');return
                    evidence.state.update(transport=value['transport'], runId=value['runId'])
                self.transfer_json(200, {'ok': True}, 'direct');return
            if path == '/event':
                value = json.loads(self.body())
                target, document = value.get('path'), value.get('document')
                if target not in PAGES or value.get('account') != PAGES[target] or str(uuid.UUID(document)) != document:
                    self.transfer_json(400, {'error': 'invalid observation'}, 'direct');return
                value.update(receivedAt=int(time.time()*1000), route=route_name(self.client_address[0]),
                             acceptLanguage=self.headers.get('Accept-Language', ''), envLogin=page.env_cookie(self.headers.get('Cookie', ''))[1],
                             ciIdentity=self.joint_account())
                with lock:
                    prior = evidence.state['pages'][target].get(document)
                    if prior and value.get('eventSequence', 0) <= prior.get('eventSequence', 0):
                        self.transfer_json(409, {'error': 'stale observation'}, value['route']);return
                    evidence.state['pages'][target][document] = value
                    evidence.state['eventCount'] += 1
                self.record(kind='document-observation', document=document)
                self.transfer_json(200, {'ok': True}, value['route']);return
            match = upload_pattern.fullmatch(path)
            if match:
                return self.upload(match[1])
            if path == '/send':
                with lock:
                    evidence.state['sendCount'] += 1
                self.transfer_json(400, {'error': 'send forbidden in owned network fixture'}, route_name(self.client_address[0]));return
            return super().do_POST()

        def upload(self, operation):
            route = route_name(self.client_address[0])
            data = self.body()
            content_type = self.headers.get('Content-Type', '')
            if not content_type.lower().startswith('multipart/form-data;'):
                self.transfer_json(400, {'error': 'real multipart upload required'}, route);return
            message = email.parser.BytesParser(policy=email.policy.default).parsebytes(
                b'MIME-Version: 1.0\r\nContent-Type: '+content_type.encode('ascii')+b'\r\n\r\n'+data)
            files, fields = [], {}
            for part in message.iter_parts():
                name = part.get_param('name', header='content-disposition')
                if part.get_filename() is not None:
                    files.append((name, part.get_filename(), part.get_payload(decode=True)))
                else:
                    if name in fields:
                        raise ValueError('duplicate multipart field')
                    fields[name] = part.get_payload(decode=True).decode('utf-8')
            if len(files) != 1 or files[0][0:2] != ('file', 'gecko-upload.bin') or set(fields) != {'identity', 'operation'} or fields['operation'] != operation or fields['identity'] != self.joint_account():
                self.transfer_json(400, {'error': 'multipart ownership mismatch'}, route);return
            payload = files[0][2]
            if payload != UPLOAD_BYTES:
                self.transfer_json(400, {'error': 'actual selected bytes mismatch'}, route);return
            status, state = gate.start(operation, 'upload', 'https', route, len(data))
            if status != 200:
                self.transfer_json(status, state, route);return
            result = {'operation': operation, 'route': route, 'identity': self.joint_account(), 'fileBytes': len(payload),
                      'fileSha256': hashlib.sha256(payload).hexdigest(), 'wireBytes': len(data), 'multipart': True}
            gate.update(operation, syntheticAccount=self.joint_account(), prefix=True, bodyComplete=True, commitCount=1,
                        requestBytesRead=len(data), payloadSha256=result['fileSha256'], fileBytes=len(payload), multipart=True)
            self.record(kind='multipart-upload', **result)
            with lock:
                evidence.state['uploads'].append(result)
            try:
                if not gate.await_release(operation):
                    self.transfer_json(504, {'error': 'gate-timeout'}, route);return
                self.transfer_json(200, result, route)
                gate.update(operation, completed=True)
            finally:
                gate.summary(operation)

    return Handler
