"""CI-only fixture regressions; use fresh generated trust and owned loopback endpoints."""
import hashlib
import http.client
import importlib.util
import json
from pathlib import Path
import ssl
import tempfile
import threading
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
