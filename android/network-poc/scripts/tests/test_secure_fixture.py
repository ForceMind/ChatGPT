"""CI-only fixture regressions; use fresh generated trust and owned loopback endpoints."""
import hashlib
import http.client
import importlib.util
import json
from pathlib import Path
import ssl
import tempfile
import threading
import time
import unittest
from unittest import mock
from urllib.parse import parse_qs, urlsplit

SPEC = importlib.util.spec_from_file_location(
    'network_fixture', Path(__file__).resolve().parents[1] / 'fixture.py')
fixture = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(fixture)


class SecureFixtureTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        cls.folder = Path(cls.temp.name)
        cls.manifest = fixture.prepare(cls.folder)

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def test_transport_contract_and_owned_destination_guard(self):
        manifest = self.manifest
        self.assertFalse(manifest['ready'])
        self.assertFalse(manifest['externalRequests'])
        self.assertEqual(manifest['payloadBytes'], 20)
        self.assertEqual(manifest['payloadSha256'], hashlib.sha256(fixture.PAYLOAD).hexdigest())
        self.assertEqual(manifest['nodePorts'], [18443, 18444])
        for mode, ports in fixture.TRANSPORT_PORTS.items():
            self.assertEqual(manifest['transports'][mode]['expectedSecurity'],
                             'none' if mode == 'raw' else mode)
            for tag, port, source in zip(('A', 'B'), ports, ('127.0.0.2', '127.0.0.3')):
                uri = urlsplit(manifest['transports'][mode]['node' + tag])
                query = parse_qs(uri.query)
                self.assertEqual(uri.username, fixture.IDENTITY)
                self.assertEqual(uri.hostname, '10.0.2.2')
                self.assertEqual(uri.port, port)
                self.assertNotIn('allowInsecure', query)
                self.assertEqual(query['security'], ['none' if mode == 'raw' else mode])
                filename = f'{tag}.json' if mode == 'raw' else f'{mode}-{tag}.json'
                config = json.loads((self.folder / filename).read_text())
                self.assertEqual(config['log']['loglevel'], 'none')
                self.assertEqual(config['inbounds'][0]['listen'], '0.0.0.0')
                self.assertEqual(config['outbounds'][0]['protocol'], 'blackhole')
                owned = config['outbounds'][1]
                self.assertEqual(owned['sendThrough'], source)
                self.assertEqual(owned['settings']['finalRules'], [
                    {'action': 'allow', 'ip': ['127.0.0.1/32'], 'port': '18080,18480'}])
                self.assertEqual(config['routing']['rules'], [
                    {'type': 'field', 'ip': ['127.0.0.1/32'], 'port': '18080,18480',
                     'outboundTag': 'owned-origin'}])
                if mode == 'reality':
                    reality = config['inbounds'][0]['streamSettings']['realitySettings']
                    self.assertEqual(reality['target'], '127.0.0.1:18481')
                    self.assertEqual(reality['shortIds'], ['1234'])
                    self.assertEqual(query['fp'], ['chrome'])
                    self.assertEqual(query['sid'], ['1234'])
                    self.assertEqual(query['sni'], [fixture.SERVER_NAME])
        wrong = parse_qs(urlsplit(manifest['realityTest']['wrongPublicKeyNodeA']).query)
        valid = parse_qs(urlsplit(manifest['transports']['reality']['nodeA']).query)
        self.assertNotEqual(wrong['pbk'], valid['pbk'])
        wrong_short = parse_qs(urlsplit(manifest['realityTest']['wrongShortIdNodeA']).query)
        self.assertEqual(wrong_short['pbk'], valid['pbk'])
        self.assertEqual(wrong_short['sid'], ['4321'])
        self.assertEqual({key: value for key, value in wrong_short.items() if key != 'sid'},
                         {key: value for key, value in valid.items() if key != 'sid'})
        untrusted = urlsplit(manifest['tlsTest']['untrustedAuthorityNodeA'])
        self.assertEqual(untrusted.port, 19543)
        self.assertEqual(parse_qs(untrusted.query)['security'], ['tls'])
        config = json.loads((self.folder / 'tls-untrusted.json').read_text())
        self.assertEqual(config['inbounds'][0]['port'], 19543)
        self.assertEqual(config['inbounds'][0]['streamSettings']['tlsSettings'][
            'certificates'][0]['certificate'], manifest['caCertificateLines'])
        self.assertEqual(config['outbounds'][0]['protocol'], 'blackhole')
        self.assertEqual(config['outbounds'][1]['settings']['finalRules'][0]['port'], '18080,18480')
        self.assertNotIn('PRIVATE KEY', json.dumps(manifest))
        self.assertNotIn('privateKey', json.dumps(manifest))

    def test_prepare_is_idempotent_and_keeps_embedded_trust(self):
        before = {path.name: path.read_bytes() for path in self.folder.iterdir()}
        self.assertEqual(fixture.prepare(self.folder), self.manifest)
        self.assertEqual(before, {path.name: path.read_bytes() for path in self.folder.iterdir()})
        self.assertEqual(self.manifest['caCertificateLines'],
                         (self.folder / 'ca.pem').read_text().splitlines())
        self.assertEqual(self.manifest['certificateSha256'], hashlib.sha256(
            fixture.openssl('x509', '-in', str(self.folder / 'ca.pem'), '-outform', 'DER')).hexdigest())

    def test_origin_uses_verified_tls_and_preserves_payload(self):
        server = fixture.http.server.ThreadingHTTPServer(('127.0.0.1', 0), fixture.Origin)
        server.socket = fixture.tls_context(self.folder).wrap_socket(server.socket, server_side=True)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            context = ssl.create_default_context(cafile=str(self.folder / 'ca.pem'))
            context.set_alpn_protocols(['h2', 'http/1.1'])
            client = http.client.HTTPSConnection('127.0.0.1', server.server_port,
                                                context=context, timeout=3)
            client.connect()
            self.assertEqual(client.sock.selected_alpn_protocol(), 'http/1.1')
            # Reuse the client object across alternating requests. Each finite
            # response must tell the client to establish a fresh TLS connection,
            # even when the request asks for HTTP/1.1 keep-alive.
            for attempt in range(3):
                for method, path, body, expected in (
                    ('GET', '/echo', None, b'{"route": "direct"}'),
                    ('GET', '/download', None, fixture.PAYLOAD),
                    ('GET', '/stability/tab-0/download', None, fixture.PAYLOAD),
                    ('POST', '/upload', f'owned upload {attempt}\n'.encode(),
                     f'owned upload {attempt}\n'.encode()),
                ):
                    client.request(method, path, body=body, headers={'Connection': 'keep-alive'})
                    response = client.getresponse()
                    self.assertEqual(response.version, 11)
                    self.assertEqual(response.status, 200)
                    self.assertEqual(response.getheader('Connection'), 'close')
                    self.assertTrue(response.will_close)
                    self.assertEqual(response.getheader('X-Fixture-Route'), 'direct')
                    self.assertEqual(int(response.getheader('Content-Length')), len(expected))
                    self.assertEqual(response.read(), expected)
                    self.assertIsNone(client.sock)
            client.close()
            untrusted = http.client.HTTPSConnection('127.0.0.1', server.server_port, timeout=3)
            with self.assertRaises(ssl.SSLCertVerificationError):
                untrusted.connect()
            untrusted.close()
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=2)

    def test_access_diagnostics_are_bounded_and_exclude_request_contents(self):
        handler = fixture.Origin.__new__(fixture.Origin)
        handler.command = 'POST'
        handler.path = '/upload?synthetic-private-query=hidden'
        handler.headers = {'Content-Length': '17'}
        handler.server = mock.Mock(server_port=18480)
        handler.client_address = ('127.0.0.2', 12345)
        with mock.patch.object(fixture.Origin, '_access_count', fixture.Origin._access_limit - 1):
            with mock.patch('builtins.print') as output:
                handler.access_diagnostic(200, 17, 17, 17)
                handler.access_diagnostic(200, 17, 17, 17)
        self.assertEqual(output.call_count, 1)
        entry = json.loads(output.call_args.args[0])
        self.assertEqual(entry['method'], 'POST')
        self.assertEqual(entry['route'], 'A')
        self.assertEqual(entry['path'], '/upload')
        self.assertEqual(entry['contentLength'], 17)
        self.assertEqual(entry['requestContentLength'], 17)
        self.assertEqual(entry['bodylen'], 17)
        self.assertEqual(entry['responseBytesWritten'], 17)
        self.assertNotIn('hidden', json.dumps(entry))

    def test_certificate_rejects_unknown_hostname(self):
        # OpenSSL performs hostname verification against the generated trust root.
        with self.assertRaises(fixture.subprocess.CalledProcessError):
            fixture.openssl('verify', '-CAfile', str(self.folder / 'ca.pem'),
                            '-verify_hostname', 'not-owned.invalid', str(self.folder / 'ca.pem'))
        for host in ('10.0.2.2', '127.0.0.1'):
            fixture.openssl('verify', '-CAfile', str(self.folder / 'ca.pem'),
                            '-verify_ip', host, str(self.folder / 'ca.pem'))

    def transfer_control(self, server, opid, action='state'):
        client = http.client.HTTPConnection('127.0.0.1', server.server_port, timeout=3)
        try:
            client.request('GET' if action == 'state' else 'POST',
                           f'/transfer-control/{opid}/{action}')
            response = client.getresponse()
            return response.status, json.loads(response.read())
        finally:
            client.close()

    def await_transfer(self, control, opid, field):
        deadline = time.monotonic() + 3
        while time.monotonic() < deadline:
            status, state = self.transfer_control(control, opid)
            if status == 200 and state[field]:
                return state
            threading.Event().wait(0.01)
        self.fail('transfer phase not observed: ' + field)

    def test_transfer_download_prefix_requires_release_for_http_and_https(self):
        gates = fixture.TransferGates(timeout=5)
        http_server = fixture.http.server.ThreadingHTTPServer(('127.0.0.1', 0), fixture.Origin)
        https_server = fixture.http.server.ThreadingHTTPServer(('127.0.0.1', 0), fixture.Origin)
        https_server.socket = fixture.tls_context(self.folder).wrap_socket(https_server.socket, server_side=True)
        servers = [http_server, https_server]
        threads = [threading.Thread(target=server.serve_forever, daemon=True) for server in servers]
        with mock.patch.object(fixture.Origin, '_transfer_gates', gates), \
                mock.patch.object(fixture.Origin, 'access_diagnostic') as ordinary:
            for thread in threads:
                thread.start()
            try:
                for origin, server in zip(('http', 'https'), servers):
                    opid = f'tls-{origin}-prefix-download'
                    options = {'timeout': 3}
                    if origin == 'https':
                        options['context'] = ssl.create_default_context(cafile=str(self.folder / 'ca.pem'))
                    client_type = http.client.HTTPConnection if origin == 'http' else http.client.HTTPSConnection
                    client = client_type('127.0.0.1', server.server_port, **options)
                    try:
                        self.assertEqual(self.transfer_control(http_server, opid)[0], 404)
                        client.request('GET', f'/transfer/{opid}/download')
                        response = client.getresponse()
                        self.assertEqual(response.status, 200)
                        self.assertEqual(response.read(fixture.TRANSFER_PREFIX_BYTES), b'D' * 4096)
                        state = self.await_transfer(http_server, opid, 'prefix')
                        self.assertFalse(state['released'])
                        self.assertFalse(state['completed'])
                        self.assertEqual(state['responseBytesWritten'], 4096)
                        self.assertEqual(state['totalBytes'], 262144)
                        self.assertEqual(self.transfer_control(http_server, opid, 'release')[0], 200)
                        self.assertEqual(response.read(), b'D' * (262144 - 4096))
                        state = self.await_transfer(http_server, opid, 'completed')
                        self.assertEqual(state['opCount'], 1)
                        self.assertEqual(state['commitCount'], 0)
                        self.assertEqual(state['payloadSha256'], hashlib.sha256(b'D' * 262144).hexdigest())
                        self.assertEqual(state['responseBytesWritten'], 262144)
                        self.assertIsNone(state['writeError'])
                        self.assertFalse(state['timedOut'])
                        self.assertEqual(self.transfer_control(http_server, opid, 'release')[0], 200)
                    finally:
                        self.transfer_control(http_server, opid, 'release')
                        client.close()
                ordinary.assert_not_called()
            finally:
                for server in servers:
                    server.shutdown()
                    server.server_close()
                for thread in threads:
                    thread.join(timeout=2)

    def test_transfer_upload_commits_once_before_response_and_rejects_duplicate(self):
        gates = fixture.TransferGates(timeout=5)
        server = fixture.http.server.ThreadingHTTPServer(('127.0.0.1', 0), fixture.Origin)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        result, failures = [], []
        opid = 'raw-http-committed-upload'
        body = b'owned-synthetic-content-not-for-logs' * 128

        def upload():
            client = http.client.HTTPConnection('127.0.0.1', server.server_port, timeout=4)
            try:
                client.request('POST', f'/transfer/{opid}/upload', body=body)
                response = client.getresponse()
                result.append((response.status, response.read()))
            except Exception as failure:
                failures.append(type(failure).__name__)
            finally:
                client.close()

        uploader = threading.Thread(target=upload, daemon=True)
        with mock.patch.object(fixture.Origin, '_transfer_gates', gates), \
                mock.patch.object(fixture.Origin, 'access_diagnostic') as ordinary, \
                mock.patch('builtins.print') as diagnostic:
            thread.start()
            uploader.start()
            try:
                state = self.await_transfer(server, opid, 'bodyComplete')
                self.assertEqual(state['requestBytesRead'], len(body))
                self.assertEqual(state['payloadSha256'], hashlib.sha256(body).hexdigest())
                self.assertEqual(state['commitCount'], 1)
                self.assertEqual(state['responseBytesWritten'], 0)
                self.assertFalse(state['completed'])
                self.assertTrue(uploader.is_alive())
                duplicate = http.client.HTTPConnection('127.0.0.1', server.server_port, timeout=3)
                try:
                    duplicate.request('POST', f'/transfer/{opid}/upload', body=b'duplicate')
                    response = duplicate.getresponse()
                    self.assertEqual(response.status, 409)
                    self.assertEqual(json.loads(response.read())['opCount'], 2)
                finally:
                    duplicate.close()
                self.assertEqual(self.transfer_control(server, opid, 'release')[0], 200)
                uploader.join(timeout=3)
                self.assertFalse(uploader.is_alive())
                self.assertFalse(failures)
                self.assertEqual(result, [(200, fixture.TRANSFER_UPLOAD_ACK)])
                state = self.await_transfer(server, opid, 'completed')
                self.assertEqual(state['commitCount'], 1)
                self.assertEqual(state['attemptCount'], 2)
                ordinary.assert_not_called()
                deadline = time.monotonic() + 2
                while diagnostic.call_count == 0 and time.monotonic() < deadline:
                    threading.Event().wait(0.01)
                self.assertEqual(diagnostic.call_count, 1)
                event = json.loads(diagnostic.call_args.args[0])
                self.assertEqual(event['event'], 'owned-transfer-summary')
                self.assertEqual(event['opid'], opid)
                self.assertEqual(event['commitCount'], 1)
                self.assertNotIn(body.decode(), json.dumps(event))
                self.assertNotIn('owned-synthetic-content-not-for-logs', json.dumps(event))
                self.assertNotIn('vless://', json.dumps(event))
                self.assertNotIn('PRIVATE KEY', json.dumps(event))
            finally:
                self.transfer_control(server, opid, 'release')
                uploader.join(timeout=3)
                server.shutdown()
                server.server_close()
                thread.join(timeout=2)

    def test_transfer_gate_timeout_and_operation_capacity_are_explicit(self):
        gates = fixture.TransferGates(timeout=0, limit=1)
        opid = 'raw-http-timeout-download'
        status, state = gates.start(opid, 'download', 'http', 'A', 262144)
        self.assertEqual(status, 200)
        self.assertFalse(gates.await_release(opid))
        state = gates.state(opid)
        self.assertTrue(state['timedOut'])
        self.assertEqual(state['failure'], 'gate-timeout')
        self.assertFalse(state['completed'])
        self.assertFalse(state['released'])
        self.assertEqual(gates.release(opid)[0], 409)
        self.assertEqual(gates.start('raw-http-capacity-upload', 'upload', 'http', 'A', 1)[0], 429)
        self.assertEqual(gates.release('raw-http-unknown-upload')[0], 404)
        with mock.patch('builtins.print') as output:
            gates.summary(opid)
            gates.summary(opid)
        self.assertEqual(output.call_count, 1)
        self.assertLess(len(output.call_args.args[0]), 1024)

    def test_transfer_download_timeout_closes_an_incomplete_response(self):
        gates = fixture.TransferGates(timeout=0)
        server = fixture.http.server.ThreadingHTTPServer(('127.0.0.1', 0), fixture.Origin)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        opid = 'raw-http-expired-download'
        with mock.patch.object(fixture.Origin, '_transfer_gates', gates):
            thread.start()
            client = http.client.HTTPConnection('127.0.0.1', server.server_port, timeout=3)
            try:
                client.request('GET', f'/transfer/{opid}/download')
                response = client.getresponse()
                self.assertEqual(response.status, 200)
                with self.assertRaises(http.client.IncompleteRead) as failure:
                    response.read()
                self.assertEqual(failure.exception.partial, b'D' * 4096)
                state = gates.state(opid)
                self.assertTrue(state['timedOut'])
                self.assertFalse(state['completed'])
                self.assertFalse(state['released'])
                self.assertEqual(state['responseBytesWritten'], 4096)
            finally:
                client.close()
                server.shutdown()
                server.server_close()
                thread.join(timeout=2)

    def test_transfer_rejects_bad_ids_mismatched_origin_and_unbounded_upload(self):
        server = fixture.http.server.ThreadingHTTPServer(('127.0.0.1', 0), fixture.Origin)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        with mock.patch.object(fixture.Origin, '_transfer_gates', fixture.TransferGates()), \
                mock.patch.object(fixture.Origin, 'access_diagnostic') as ordinary:
            thread.start()
            try:
                for method, path, headers, expected in (
                    ('GET', '/transfer/not-a-bounded-id/download', {}, 400),
                    ('GET', '/transfer/tls-https-wrong-origin/download', {}, 400),
                    ('POST', '/transfer/raw-http-over-limit/upload', {'Content-Length': '262145'}, 413),
                    ('POST', '/transfer/raw-http-chunked/upload', {'Transfer-Encoding': 'chunked'}, 400),
                    ('GET', '/transfer-control/raw-http-invalid-query/state?hidden=do-not-log', {}, 400),
                ):
                    client = http.client.HTTPConnection('127.0.0.1', server.server_port, timeout=3)
                    try:
                        client.request(method, path, headers=headers)
                        response = client.getresponse()
                        self.assertEqual(response.status, expected)
                        response.read()
                    finally:
                        client.close()
                ordinary.assert_not_called()
                self.assertFalse(fixture.Origin._transfer_gates.operations)
            finally:
                server.shutdown()
                server.server_close()
                thread.join(timeout=2)

    def test_rejects_unrecognized_x25519_der(self):
        with mock.patch.object(fixture, 'openssl', side_effect=[b'x' * 48, b'y' * 44]):
            with self.assertRaisesRegex(ValueError, 'DER structure'):
                fixture.x25519_pair()

    def test_startup_reports_child_failure_before_ready(self):
        child = mock.Mock()
        child.poll.return_value = 23
        with self.assertRaisesRegex(RuntimeError, 'exited during startup'):
            fixture.wait_ready([child], self.folder, timeout=1)
        self.assertFalse((self.folder / 'ready.json').exists())

    def test_readiness_includes_negative_tls_listener(self):
        seen = []

        def connection(address, timeout):
            seen.append(address[1])
            return mock.MagicMock()

        with mock.patch.object(fixture.socket, 'create_connection', side_effect=connection):
            with mock.patch.object(fixture.ssl, 'create_default_context'):
                ports = fixture.wait_ready([], self.folder, timeout=1)
        self.assertIn(19543, ports)
        self.assertIn(19543, seen)


if __name__ == '__main__':
    unittest.main()
