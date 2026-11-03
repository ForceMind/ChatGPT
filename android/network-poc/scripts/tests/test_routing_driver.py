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

    def test_exact_three_tests_and_failure_markers(self):
        driver.verify_instrumentation(0, 'Time: 42\n\nOK (3 tests)\n')
        for code, output in [(1, 'OK (3 tests)'), (0, 'OK (2 tests)'), (0, 'OK (4 tests)'),
                             (0, 'INSTRUMENTATION_FAILED\nOK (3 tests)'), (0, 'FAILURES!!!\nOK (3 tests)'),
                             (0, 'shortMsg=crash\nOK (3 tests)'), (0, 'OK (3 tests)\nOK (3 tests)')]:
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


if __name__ == '__main__':
    unittest.main()
