#!/usr/bin/env python3
"""Owned CI endpoints and two local VLESS exits. No external node credentials."""
import argparse
import hashlib
import http.server
import json
import os
from pathlib import Path
import signal
import subprocess
import threading
import time

PAYLOAD = b'network-poc-fixture\n'
IDENTITY = '00000000-0000-4000-8000-000000000001'

class Origin(http.server.BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def do_GET(self):
        route = {'127.0.0.2': 'A', '127.0.0.3': 'B'}.get(self.client_address[0], 'direct')
        if self.path.startswith('/download'):
            data, kind = PAYLOAD, 'application/octet-stream'
        elif self.path.startswith('/page'):
            data, kind = ('<!doctype html><meta name="viewport" content="width=device-width">'
                          '<title>' + route + '</title><textarea id="draft"></textarea>'
                          '<p id="route">' + route + '</p>').encode(), 'text/html'
        else:
            data, kind = json.dumps({'route': route}).encode(), 'application/json'
        self.send_response(200)
        self.send_header('Content-Type', kind)
        self.send_header('Content-Length', str(len(data)))
        self.send_header('Cache-Control', 'no-store')
        self.end_headers()
        self.wfile.write(data)

    def do_POST(self):
        data = self.rfile.read(int(self.headers.get('Content-Length', '0')))
        route = {'127.0.0.2': 'A', '127.0.0.3': 'B'}.get(self.client_address[0], 'direct')
        self.send_response(200)
        self.send_header('X-Fixture-Route', route)
        self.send_header('Content-Length', str(len(data)))
        self.end_headers()
        self.wfile.write(data)

def main():
    p = argparse.ArgumentParser()
    p.add_argument('--core', required=True)
    p.add_argument('--output', required=True)
    args = p.parse_args()
    if os.environ.get('GITHUB_ACTIONS') != 'true':
        raise SystemExit('CI only')
    folder = Path(args.output)
    folder.mkdir(parents=True, exist_ok=True)
    origin = http.server.ThreadingHTTPServer(('0.0.0.0', 18080), Origin)
    threading.Thread(target=origin.serve_forever, daemon=True).start()
    children = []
    for tag, port, source in [('A', 18443, '127.0.0.2'), ('B', 18444, '127.0.0.3')]:
        config = {'log': {'loglevel': 'none'},
                  'dns': {'hosts': {'network-fixture.test': '127.0.0.1'}},
                  'inbounds': [{'listen': '0.0.0.0', 'port': port, 'protocol': 'vless',
                                'settings': {'clients': [{'id': IDENTITY}], 'decryption': 'none'}}],
                  'outbounds': [{'protocol': 'freedom', 'sendThrough': source,
                                 'settings': {'domainStrategy': 'UseIP'}}]}
        config_file = folder / (tag + '.json')
        config_file.write_text(json.dumps(config))
        children.append(subprocess.Popen([args.core, 'run', '-config', str(config_file)],
                                         stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL))
    def stop(*_):
        for child in children:
            child.terminate()
        origin.shutdown()
        raise SystemExit(0)
    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    (folder / 'fixture.json').write_text(json.dumps({'payloadSha256': hashlib.sha256(PAYLOAD).hexdigest(),
        'nodePorts': [18443, 18444], 'originPort': 18080, 'externalRequests': False}))
    while True:
        time.sleep(1)
        if any(c.poll() is not None for c in children):
            stop()

if __name__ == '__main__':
    main()
