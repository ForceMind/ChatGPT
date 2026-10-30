#!/usr/bin/env python3
"""Owned, ephemeral CI VLESS fixtures. Never accepts external node credentials."""
import argparse
import base64
import hashlib
import http.server
import json
import os
from pathlib import Path
import signal
import socket
import ssl
import subprocess
import threading
import time
from urllib.parse import urlencode

PAYLOAD = b'network-poc-fixture\n'
IDENTITY = '00000000-0000-4000-8000-000000000001'
SERVER_NAME = 'network-fixture.test'
ORIGIN_PORT = 18080
HTTPS_ORIGIN_PORT = 18480
REALITY_TARGET_PORT = 18481
UNTRUSTED_TLS_PORT = 19543
TRANSPORT_PORTS = {'raw': (18443, 18444), 'tls': (19443, 19444),
                   'reality': (20443, 20444)}
SCHEMA_VERSION = 2


class Origin(http.server.BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'
    _access_count = 0
    _access_limit = 256
    _access_lock = threading.Lock()

    def log_message(self, *_):
        pass

    def access_diagnostic(self, status, content_length, body_len, written, error=None):
        # All requests are synthetic. Bound both event count and string lengths;
        # never print bodies, headers, query strings, certificates, or keys.
        with Origin._access_lock:
            if Origin._access_count >= Origin._access_limit:
                return
            Origin._access_count += 1
            entry = {'event': 'owned-origin-access', 'method': self.command[:16],
                     'path': self.path.partition('?')[0][:96], 'status': status,
                     'contentLength': content_length, 'bodylen': body_len,
                     'responseBytesWritten': written, 'port': self.server.server_port,
                     'connection': 'close'}
            entry['route'] = {'127.0.0.2': 'A', '127.0.0.3': 'B'}.get(
                self.client_address[0], 'direct')
            try:
                entry['requestContentLength'] = int(self.headers.get('Content-Length', '0'))
            except (AttributeError, ValueError):
                entry['requestContentLength'] = None
            if error is not None:
                entry['error'] = type(error).__name__
            print(json.dumps(entry, sort_keys=True), flush=True)

    def finite_response(self, status, data, route, kind=None, body_len=0):
        self.close_connection = True
        written, error = 0, None
        try:
            self.send_response(status)
            if kind is not None:
                self.send_header('Content-Type', kind)
            self.send_header('X-Fixture-Route', route)
            self.send_header('Content-Length', str(len(data)))
            self.send_header('Cache-Control', 'no-store')
            self.send_header('Connection', 'close')
            self.end_headers()
            self.wfile.write(data)
            self.wfile.flush()
            written = len(data)
        except OSError as failure:
            error = failure
            raise
        finally:
            self.access_diagnostic(status, len(data), body_len, written, error)

    def do_GET(self):
        route = {'127.0.0.2': 'A', '127.0.0.3': 'B'}.get(self.client_address[0], 'direct')
        path = self.path.partition('?')[0]
        # Unique, synthetic operation paths let CI count reloads and request replay
        # without recording query strings, headers, credentials, or request bodies.
        if path.startswith('/stability/'):
            path = '/' + path.rsplit('/', 1)[-1]
        if path.startswith('/download'):
            data, kind = PAYLOAD, 'application/octet-stream'
        elif path.startswith('/page'):
            data, kind = ('<!doctype html><meta name="viewport" content="width=device-width">'
                          '<title>' + route + '</title><textarea id="draft"></textarea>'
                          '<p id="route">' + route + '</p>').encode(), 'text/html'
        else:
            data, kind = json.dumps({'route': route}).encode(), 'application/json'
        self.finite_response(200, data, route, kind)

    def do_POST(self):
        try:
            data = self.rfile.read(int(self.headers.get('Content-Length', '0')))
        except OSError as failure:
            self.access_diagnostic(0, 0, 0, 0, failure)
            raise
        route = {'127.0.0.2': 'A', '127.0.0.3': 'B'}.get(self.client_address[0], 'direct')
        self.finite_response(200, data, route, body_len=len(data))


def openssl(*arguments, data=None):
    return subprocess.run(['openssl', *arguments], input=data, check=True,
                          stdout=subprocess.PIPE, stderr=subprocess.PIPE).stdout


def x25519_pair():
    """Validate complete OpenSSL PKCS8/SPKI structures before extracting raw keys."""
    private_der = openssl('genpkey', '-algorithm', 'X25519', '-outform', 'DER')
    public_der = openssl('pkey', '-inform', 'DER', '-pubout', '-outform', 'DER', data=private_der)
    private_prefix = bytes.fromhex('302e020100300506032b656e04220420')
    public_prefix = bytes.fromhex('302a300506032b656e032100')
    if (len(private_der) != len(private_prefix) + 32 or not private_der.startswith(private_prefix)
            or len(public_der) != len(public_prefix) + 32 or not public_der.startswith(public_prefix)):
        raise ValueError('Unexpected X25519 DER structure')
    encode = lambda value: base64.urlsafe_b64encode(value).decode().rstrip('=')
    return encode(private_der[-32:]), encode(public_der[-32:])


def node_uri(mode, port, tag, public_key=None, short_id='1234'):
    query = {'encryption': 'none', 'type': 'tcp',
             'security': 'none' if mode == 'raw' else mode}
    if mode != 'raw':
        query['sni'] = SERVER_NAME
    if mode == 'tls':
        query['alpn'] = 'http/1.1'
    if mode == 'reality':
        query.update({'pbk': public_key, 'fp': 'chrome', 'sid': short_id, 'spx': '/'})
    return f'vless://{IDENTITY}@10.0.2.2:{port}?{urlencode(query)}#{mode}-{tag}'


def fixture_config(mode, port, source, certificate, key, private_key):
    stream = {'network': 'raw', 'security': 'none' if mode == 'raw' else mode}
    if mode == 'tls':
        stream['tlsSettings'] = {'alpn': ['http/1.1'], 'minVersion': '1.2',
                                 'certificates': [{'certificate': certificate, 'key': key}]}
    if mode == 'reality':
        stream['realitySettings'] = {'target': f'127.0.0.1:{REALITY_TARGET_PORT}',
                                     'serverNames': [SERVER_NAME], 'privateKey': private_key,
                                     'shortIds': ['1234']}
    return {
        'log': {'loglevel': 'none'},
        'dns': {'hosts': {SERVER_NAME: '127.0.0.1'}},
        'inbounds': [{'listen': '0.0.0.0', 'port': port, 'protocol': 'vless',
                      'settings': {'clients': [{'id': IDENTITY}], 'decryption': 'none'},
                      'streamSettings': stream}],
        # Unmatched destinations hit the first outbound. Only owned origins reach freedom.
        'outbounds': [{'tag': 'blocked', 'protocol': 'blackhole'},
                      {'tag': 'owned-origin', 'protocol': 'freedom', 'sendThrough': source,
                       'settings': {'domainStrategy': 'UseIP', 'finalRules': [
                           {'action': 'allow', 'ip': ['127.0.0.1/32'],
                            'port': f'{ORIGIN_PORT},{HTTPS_ORIGIN_PORT}'}]}}],
        'routing': {'domainStrategy': 'IPOnDemand', 'rules': [
            {'type': 'field', 'ip': ['127.0.0.1/32'],
             'port': f'{ORIGIN_PORT},{HTTPS_ORIGIN_PORT}', 'outboundTag': 'owned-origin'}]}}


def write_json(path, value):
    path.write_text(json.dumps(value, indent=2) + '\n')


def prepare(folder):
    manifest_path = folder / 'fixture.json'
    if manifest_path.exists():
        return load_prepared(folder)
    folder.mkdir(parents=True, exist_ok=True)
    cert_path, key_path = folder / 'ca.pem', folder / 'key.pem'
    openssl('req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-sha256', '-days', '2',
            '-keyout', str(key_path), '-out', str(cert_path), '-subj', f'/CN={SERVER_NAME}',
            '-addext', f'subjectAltName=DNS:{SERVER_NAME},IP:10.0.2.2,IP:127.0.0.1',
            '-addext', 'basicConstraints=critical,CA:TRUE',
            '-addext', 'keyUsage=critical,digitalSignature,keyEncipherment,keyCertSign',
            '-addext', 'extendedKeyUsage=serverAuth')
    key_path.chmod(0o600)
    openssl('verify', '-CAfile', str(cert_path), '-verify_hostname', SERVER_NAME, str(cert_path))
    certificate, key = cert_path.read_text().splitlines(), key_path.read_text().splitlines()
    certificate_der = openssl('x509', '-in', str(cert_path), '-outform', 'DER')
    manifest = {'schemaVersion': SCHEMA_VERSION, 'ready': False,
                'payloadSha256': hashlib.sha256(PAYLOAD).hexdigest(), 'payloadBytes': len(PAYLOAD),
                'nodePorts': list(TRANSPORT_PORTS['raw']), 'originPort': ORIGIN_PORT,
                'httpsOriginPort': HTTPS_ORIGIN_PORT, 'realityTargetPort': REALITY_TARGET_PORT,
                'externalRequests': False, 'caCertificateLines': certificate,
                'certificateSha256': hashlib.sha256(certificate_der).hexdigest(),
                'transports': {}}
    reality_a_public_key = None
    for mode, ports in TRANSPORT_PORTS.items():
        nodes = {'expectedSecurity': 'none' if mode == 'raw' else mode}
        for tag, port, source in zip(('A', 'B'), ports, ('127.0.0.2', '127.0.0.3')):
            private_key, public_key = x25519_pair() if mode == 'reality' else (None, None)
            if mode == 'reality' and tag == 'A':
                reality_a_public_key = public_key
            # Keep the historical raw A.json/B.json names for existing CI consumers.
            config_name = f'{tag}.json' if mode == 'raw' else f'{mode}-{tag}.json'
            config_file = folder / config_name
            write_json(config_file, fixture_config(mode, port, source, certificate, key, private_key))
            config_file.chmod(0o600)
            nodes[f'node{tag}'] = node_uri(mode, port, tag, public_key)
        manifest['transports'][mode] = nodes
    # The CI client trust hook is scoped to the two positive TLS ports. This
    # listener presents the same certificate outside that scope, proving that
    # ordinary certificate verification still rejects an unknown authority.
    untrusted_config = folder / 'tls-untrusted.json'
    write_json(untrusted_config, fixture_config(
        'tls', UNTRUSTED_TLS_PORT, '127.0.0.2', certificate, key, None))
    untrusted_config.chmod(0o600)
    manifest['tlsTest'] = {'untrustedAuthorityNodeA': node_uri(
        'tls', UNTRUSTED_TLS_PORT, 'untrusted-authority')}
    _, wrong_public_key = x25519_pair()
    manifest['realityTest'] = {'wrongPublicKeyNodeA': node_uri(
        'reality', TRANSPORT_PORTS['reality'][0], 'wrong-public-key', wrong_public_key),
        'wrongShortIdNodeA': node_uri('reality', TRANSPORT_PORTS['reality'][0],
                                    'wrong-short-id', reality_a_public_key, short_id='4321')}
    write_json(manifest_path, manifest)
    return manifest


def load_prepared(folder):
    manifest = json.loads((folder / 'fixture.json').read_text())
    if manifest.get('schemaVersion') != SCHEMA_VERSION:
        raise ValueError('Fixture schema mismatch; prepare a fresh CI output directory')
    cert_der = openssl('x509', '-in', str(folder / 'ca.pem'), '-outform', 'DER')
    if hashlib.sha256(cert_der).hexdigest() != manifest.get('certificateSha256'):
        raise ValueError('Prepared fixture certificate fingerprint mismatch')
    if (folder / 'ca.pem').read_text().splitlines() != manifest.get('caCertificateLines'):
        raise ValueError('Prepared fixture public certificate differs from manifest')
    if not (folder / 'key.pem').is_file():
        raise ValueError('Prepared fixture private key missing')
    for mode in TRANSPORT_PORTS:
        for tag in ('A', 'B', 'untrusted') if mode == 'tls' else ('A', 'B'):
            name = f'{tag}.json' if mode == 'raw' else f'{mode}-{tag}.json'
            config = json.loads((folder / name).read_text())
            if mode == 'tls' and config['inbounds'][0]['streamSettings']['tlsSettings'][
                    'certificates'][0]['certificate'] != manifest['caCertificateLines']:
                raise ValueError('Prepared VLESS certificate differs from manifest')
    return manifest


def tls_context(folder, camouflage=False):
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.minimum_version = ssl.TLSVersion.TLSv1_3 if camouflage else ssl.TLSVersion.TLSv1_2
    context.set_alpn_protocols(['h2', 'http/1.1'] if camouflage else ['http/1.1'])
    context.load_cert_chain(str(folder / 'ca.pem'), str(folder / 'key.pem'))
    return context


class CamouflageServer:
    """Owned TLS1.3 handshake target; HTTPS content lives on the separate origin."""
    def __init__(self, context):
        self.context = context
        self.socket = socket.create_server(('127.0.0.1', REALITY_TARGET_PORT))
        self.socket.settimeout(0.25)
        self.stopped = threading.Event()
        self.thread = threading.Thread(target=self.serve, daemon=True)
        self.thread.start()

    def serve(self):
        while not self.stopped.is_set():
            try:
                conn, _ = self.socket.accept()
            except socket.timeout:
                continue
            except OSError:
                return
            threading.Thread(target=self.handshake, args=(conn,), daemon=True).start()

    def handshake(self, conn):
        try:
            conn.settimeout(3)
            with self.context.wrap_socket(conn, server_side=True) as secure:
                # REALITY needs the authenticated TLS1.3 target handshake. The target
                # intentionally serves no HTTP content, including no fake HTTP/2 framing.
                secure.recv(1)
        except (OSError, ssl.SSLError):
            pass
        finally:
            conn.close()

    def shutdown(self):
        self.stopped.set()
        self.socket.close()
        self.thread.join(timeout=2)


def wait_ready(children, folder, timeout=20):
    ports = [ORIGIN_PORT, HTTPS_ORIGIN_PORT, REALITY_TARGET_PORT,
             UNTRUSTED_TLS_PORT,
             *(port for pair in TRANSPORT_PORTS.values() for port in pair)]
    context = ssl.create_default_context(cafile=str(folder / 'ca.pem'))
    context.set_alpn_protocols(['http/1.1'])
    deadline = time.monotonic() + timeout
    pending = set(ports)
    while pending and time.monotonic() < deadline:
        if any(child.poll() is not None for child in children):
            raise RuntimeError('VLESS fixture exited during startup; inspect fixture-*.log')
        for port in list(pending):
            try:
                with socket.create_connection(('127.0.0.1', port), timeout=0.25) as conn:
                    if port in (HTTPS_ORIGIN_PORT, REALITY_TARGET_PORT):
                        with context.wrap_socket(conn, server_hostname=SERVER_NAME):
                            pass
                pending.remove(port)
            except (OSError, ssl.SSLError):
                pass
        if pending:
            time.sleep(0.1)
    if pending:
        raise RuntimeError(f'Fixture startup deadline exceeded for ports {sorted(pending)}')
    return ports


def run(core, folder, manifest):
    marker = folder / 'ready.json'
    marker.unlink(missing_ok=True)
    manifest['ready'] = False
    write_json(folder / 'fixture.json', manifest)
    children, logs, servers = [], [], []
    stopped = threading.Event()
    old_handlers = {}
    try:
        for signum in (signal.SIGTERM, signal.SIGINT):
            old_handlers[signum] = signal.signal(signum, lambda *_: stopped.set())
        origin = http.server.ThreadingHTTPServer(('0.0.0.0', ORIGIN_PORT), Origin)
        servers.append(origin)
        threading.Thread(target=origin.serve_forever, daemon=True).start()
        https = http.server.ThreadingHTTPServer(('0.0.0.0', HTTPS_ORIGIN_PORT), Origin)
        https.socket = tls_context(folder).wrap_socket(https.socket, server_side=True)
        servers.append(https)
        threading.Thread(target=https.serve_forever, daemon=True).start()
        servers.append(CamouflageServer(tls_context(folder, camouflage=True)))
        for mode in TRANSPORT_PORTS:
            for tag in ('A', 'B', 'untrusted') if mode == 'tls' else ('A', 'B'):
                config_name = f'{tag}.json' if mode == 'raw' else f'{mode}-{tag}.json'
                log = (folder / f'fixture-{mode}-{tag}.log').open('ab')
                logs.append(log)
                children.append(subprocess.Popen([core, 'run', '-config', str(folder / config_name)],
                                                 stdout=log, stderr=subprocess.STDOUT))
        ports = wait_ready(children, folder)
        manifest['ready'] = True
        write_json(folder / 'fixture.json', manifest)
        write_json(marker, {'ready': True, 'ports': ports,
                            'certificateSha256': manifest['certificateSha256']})
        print('Owned raw/TLS/REALITY fixtures ready', flush=True)
        while not stopped.wait(0.5):
            if any(child.poll() is not None for child in children):
                raise RuntimeError('VLESS fixture exited; inspect fixture-*.log')
    finally:
        marker.unlink(missing_ok=True)
        manifest['ready'] = False
        write_json(folder / 'fixture.json', manifest)
        for child in children:
            if child.poll() is None:
                child.terminate()
        for child in children:
            try:
                child.wait(timeout=3)
            except subprocess.TimeoutExpired:
                child.kill()
                child.wait(timeout=3)
        for server in reversed(servers):
            server.shutdown()
            if isinstance(server, http.server.ThreadingHTTPServer):
                server.server_close()
        for log in logs:
            log.close()
        for signum, handler in old_handlers.items():
            signal.signal(signum, handler)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--core', help='Pinned Xray binary; required when starting fixtures')
    parser.add_argument('--output', required=True)
    parser.add_argument('--prepare-only', action='store_true')
    args = parser.parse_args()
    if os.environ.get('GITHUB_ACTIONS') != 'true':
        raise SystemExit('CI only')
    folder = Path(args.output)
    if args.prepare_only:
        prepare(folder)
    else:
        if not args.core:
            parser.error('--core is required when starting fixtures')
        # Never rotate the CA after the Android CI build has embedded its public trust.
        run(args.core, folder, load_prepared(folder))


if __name__ == '__main__':
    main()
