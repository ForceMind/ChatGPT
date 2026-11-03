"""Pure routing-driver contract tests. Runtime assertions remain emulator-only in Actions."""
import copy
import importlib.util
import json
from pathlib import Path
import unittest

SPEC = importlib.util.spec_from_file_location('routing_driver', Path(__file__).resolve().parents[1] / 'run-routing.py')
driver = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(driver)


def evidence():
    connected = {'state': 'CONNECTED', 'coreRunning': True, 'visibleStatus': '已连接 · 本地代理', 'webNetworkBlocked': False}
    failed = {'state': 'FAILED', 'coreRunning': False, 'visibleStatus': '连接失败 · 核心已停止',
              'webNetworkBlocked': True, 'error': '未切换直连，请手动重新连接线路'}
    document = {'url': 'https://network-fixture.test:18480/stability/core-health/page',
                'identity': 'owned-document', 'draft': 'core-health-unsent-draft', 'submissions': 0, 'fetchStarts': 0}
    documents = {'before': document}
    for phase, count in [('afterAutomaticFailure', 0), ('afterBlockedRequests', 1),
                         ('afterExplicitReconnect', 1), ('afterHomeResumeFailure', 2), ('afterExplicitDirect', 2)]:
        documents[phase] = dict(document, fetchStarts=count)
    return {'schema': 1, 'complete': True, 'test': 'unexpectedCoreStopFailsClosedAndRequiresExplicitRecovery',
            'security': 'tls', 'origin': 'https://network-fixture.test:18480', 'before': connected,
            'automaticFailure': failed, 'detectionMillis': 2200, 'externalStopAtMillis': 1000,
            'failureDetectedAtMillis': 3200, 'failedRequestsStartedAtMillis': 3300,
            'nativeFailure': 'IllegalStateException', 'webFailure': 'error', 'activeTransfersAfterFailure': 0,
            'reconnected': dict(connected), 'direct': {'state': 'DIRECT', 'coreRunning': False, 'webNetworkBlocked': False},
            'background': {'homeCommand': 'input keyevent KEYCODE_HOME',
                           'returnCommand': 'am start -W -n ' + driver.PACKAGE + '/com.forcemind.chatgpt.networkpreview.MainActivity',
                           'stoppedLifecycle': 'CREATED', 'resumedLifecycle': 'RESUMED', 'sameActivity': True,
                           'elapsedMillis': 1700, 'failure': dict(failed)},
            'documents': documents, 'closed': {'state': 'CLOSED', 'generation': 6, 'expectedGeneration': 6,
                                             'observedMillis': 4500, 'samples': 60, 'callbackInvocations': 0, 'coreRunning': False},
            'expectedRequests': copy.deepcopy(driver.HEALTH_REQUESTS)}


class RoutingDriverTests(unittest.TestCase):
    def health(self):
        return evidence()

    def test_exact_four_tests_and_failure_markers(self):
        driver.verify_instrumentation(0, 'Time: 42\n\nOK (4 tests)\n')
        for code, output in [(1, 'OK (4 tests)'), (0, 'OK (2 tests)'), (0, 'OK (3 tests)'),
                             (0, 'INSTRUMENTATION_FAILED\nOK (4 tests)'), (0, 'FAILURES!!!\nOK (4 tests)'),
                             (0, 'shortMsg=crash\nOK (4 tests)'), (0, 'OK (4 tests)\nOK (4 tests)')]:
            with self.subTest(code=code, output=output), self.assertRaises(RuntimeError):
                driver.verify_instrumentation(code, output)

    def test_runtime_evidence_must_prove_real_failure_and_explicit_recovery(self):
        valid = self.health()
        self.assertIs(valid, driver.verify_core_health(valid, 'tls', valid['origin']))
        for section, key, value in [('automaticFailure', 'state', 'CONNECTED'),
                                    ('automaticFailure', 'webNetworkBlocked', False),
                                    ('automaticFailure', 'coreRunning', True),
                                    ('automaticFailure', 'visibleStatus', '已连接 · 本地代理'),
                                    ('automaticFailure', 'error', 'unknown'),
                                    ('reconnected', 'coreRunning', False), ('closed', 'callbackInvocations', 1),
                                    ('closed', 'observedMillis', 4499), ('closed', 'coreRunning', True),
                                    ('background', 'sameActivity', False)]:
            candidate = self.health(); candidate[section][key] = value
            with self.subTest(section=section, key=key), self.assertRaises(AssertionError):
                driver.verify_core_health(candidate, 'tls', candidate['origin'])
        for section in ['documents', 'automaticFailure', 'closed']:
            candidate = self.health(); del candidate[section]
            with self.subTest(missing=section), self.assertRaises((AssertionError, KeyError)):
                driver.verify_core_health(candidate, 'tls', candidate['origin'])

    def test_reloaded_document_or_request_replay_cannot_be_complete(self):
        for key, value in [('identity', 'reloaded'), ('draft', ''), ('fetchStarts', 2), ('submissions', 1)]:
            candidate = self.health(); candidate['documents']['afterBlockedRequests'][key] = value
            with self.subTest(key=key), self.assertRaises(AssertionError):
                driver.verify_core_health(candidate, 'tls', candidate['origin'])

    def test_fixture_logs_require_exact_explicit_requests_and_no_failed_arrivals(self):
        events = [dict(item, event='owned-origin-access', port=18480, status=200,
                       contentLength=20, responseBytesWritten=20) for item in driver.HEALTH_REQUESTS]
        encode = lambda values: '\n'.join(json.dumps(item) for item in values)
        self.assertEqual([1] * 5, [item['count'] for item in driver.verify_health_requests(encode(events), self.health()['origin'])])
        failed = dict(events[0], path='/stability/core-health-failed-web/upload', method='POST', route='direct')
        for candidate in [events[:-1], events + [events[0]], events + [failed],
                          [dict(item, route='direct') for item in events],
                          [dict(item, responseBytesWritten=0) for item in events]]:
            with self.subTest(events=candidate), self.assertRaises(AssertionError):
                driver.verify_health_requests(encode(candidate), self.health()['origin'])
        # Raw HTTP's earlier suite must not be confused with HTTPS evidence.
        mixed = events + [dict(item, port=18080) for item in events]
        self.assertEqual(5, len(driver.verify_health_requests(encode(mixed), self.health()['origin'])))

    def test_failure_logs_do_not_print_node_credentials(self):
        uri = 'vless://00000000-0000-4000-8000-000000000001@10.0.2.2:19443?security=tls'
        result = driver.redact_output('failed ' + uri + '\nid=00000000-0000-4000-8000-000000000001', {'nodeA': uri})
        self.assertNotIn('00000000-0000-4000-8000-000000000001', result)
        self.assertNotIn('@10.0.2.2', result)
        self.assertIn('REDACTED', result)

    def transfer(self):
        original = {'identity': 'owned-document', 'draft': 'transfer-fault-draft', 'url': 'https://network-fixture.test:18480/stability/owned/page', 'submissions': 0}
        phases, final, callbacks, controls = [], [], [], []
        for suffix, kind, action in driver.TRANSFER_CASES:
            opid = 'tls-https-' + suffix
            upload = kind != 'download'; success = action == 'explicit-new-success'
            size = 65536 if kind == 'native-upload' else 33000 if upload else 262144
            started = {'opid': opid, 'transport': 'tls', 'origin': 'https', 'kind': 'upload' if upload else 'download',
                       'route': 'A', 'started': True, 'prefix': True, 'bodyComplete': upload, 'commitCount': int(upload),
                       'released': False, 'completed': False, 'writeError': None, 'readError': None, 'timedOut': False,
                       'opCount': 1, 'attemptCount': 1, 'requestBytesRead': size if upload else 0,
                       'responseBytesWritten': 0 if upload else 4096, 'totalBytes': size}
            terminal = dict(started, released=True, completed=True,
                            responseBytesWritten=len(b'network-poc-upload-committed\n') if upload else size)
            phase = {'opid': opid, 'kind': kind, 'action': action, 'started': started, 'terminal': terminal,
                     'activeTransfers': 0, 'routeState': 'FAILED' if action == 'core-stop' else 'CONNECTED',
                     'coreRunning': action != 'core-stop', 'document': dict(original), 'terminationMillis': 900}
            if not upload:
                phase.update(callbackCount=1, successCount=int(success), baselineFiles=['download-owned-baseline'],
                             afterFiles=['download-owned-baseline'], deliveredBytes=size, partialBytes=4096, error='cancelled or failed')
                callbacks.append({'opid': opid, 'callbackCount': 1, 'successCount': int(success)})
            elif kind == 'native-upload':
                phase.update(completionCount=1, successCount=int(success), outcomeUncertain=not success,
                             responseBytes=len(b'network-poc-upload-committed\n') if success else 0,
                             error=None if success else '上传结果不明；未自动重发')
            else:
                phase.update(outcomeUncertain=not success, clientBefore={'state': 'pending', 'completions': 0},
                             client={'state': 'success' if success else 'error', 'completions': 1, 'route': 'A',
                                     'body': 'network-poc-upload-committed\n', 'error': 'Failed to fetch'})
            phases.append(phase); final.append(dict(terminal))
            controls.extend({'opid': opid, 'method': method, 'status': 200, 'ownedDirectControl': True} for method in ['GET', 'POST'])
        rejected = [{'opid': 'tls-https-closed-rejected-download', 'kind': 'download', 'callbackCount': 1, 'successCount': 0, 'serverAbsent': True},
                    {'opid': 'tls-https-closed-rejected-upload', 'kind': 'upload', 'rejectedBeforeTransfer': True, 'serverAbsent': True}]
        callbacks.append({'opid': rejected[0]['opid'], 'callbackCount': 1, 'successCount': 0})
        controls.extend({'opid': item['opid'], 'method': 'GET', 'status': 404, 'ownedDirectControl': True} for item in rejected)
        return {'schema': 1, 'complete': True, 'transport': 'tls', 'origin': 'https://network-fixture.test:18480',
                'controlOrigin': 'http://10.0.2.2:18080', 'controlPlane': 'androidTest-only direct owned control; not production fallback',
                'originalDocument': original, 'finalDocument': dict(original), 'phases': phases, 'finalOperations': final,
                'closedRejections': rejected, 'finalDownloadCallbacks': callbacks, 'controlRequests': controls, 'activeTransfers': 0}

    def test_transfer_fault_missing_duplicate_callback_residue_and_unknown_are_rejected(self):
        complete = self.transfer()
        self.assertEqual(12, len(complete['phases']))
        driver.verify_transfer_fault(complete, 'tls', complete['origin'])
        broken = []
        value = self.transfer(); value['phases'].pop(); broken.append(value)
        value = self.transfer(); value['phases'][0]['callbackCount'] = 2; broken.append(value)
        value = self.transfer(); value['finalDownloadCallbacks'][0]['callbackCount'] = 2; broken.append(value)
        value = self.transfer(); value['phases'][0]['afterFiles'].append('download-leaked-partial'); broken.append(value)
        value = self.transfer(); value['finalOperations'][0]['opCount'] = 2; broken.append(value)
        value = self.transfer(); value['phases'][2]['outcomeUncertain'] = False; broken.append(value)
        value = self.transfer(); value['phases'][6]['terminationMillis'] = 10001; broken.append(value)
        value = self.transfer(); value['phases'][10]['terminationMillis'] = 10001; broken.append(value)
        value = self.transfer(); del value['finalOperations']; broken.append(value)
        value = self.transfer(); value['finalDocument']['identity'] = 'reloaded'; broken.append(value)
        for candidate in broken:
            with self.subTest(candidate=candidate), self.assertRaises((AssertionError, KeyError)):
                driver.verify_transfer_fault(candidate, 'tls', candidate['origin'])

    def test_transfer_terminal_event_is_once_and_server_commit_cannot_replay(self):
        complete = self.transfer()
        events = [dict(item, event='owned-transfer-summary') for item in complete['finalOperations']]
        encode = lambda values: '\n'.join(json.dumps(value) for value in values)
        self.assertEqual(12, len(driver.verify_transfer_requests(complete, encode(events))))
        for candidate in [events[:-1], events + [events[0]],
                          [dict(item, opCount=2) for item in events],
                          [dict(item, route='direct') for item in events]]:
            with self.subTest(events=candidate), self.assertRaises(AssertionError):
                driver.verify_transfer_requests(complete, encode(candidate))
        # A server can complete write/flush after a disconnect; this alone is not client success.
        self.assertTrue(complete['phases'][0]['terminal']['completed'])
        self.assertEqual(0, complete['phases'][0]['successCount'])


if __name__ == '__main__':
    unittest.main()
