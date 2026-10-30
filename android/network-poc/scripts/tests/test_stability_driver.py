"""Pure log contract checks; runtime and browser assertions run only in Actions."""
import importlib.util
import json
from pathlib import Path
import unittest

SPEC = importlib.util.spec_from_file_location('stability', Path(__file__).resolve().parents[1] / 'run-stability.py')
driver = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(driver)


class StabilityDriverTests(unittest.TestCase):
    def test_complete_exactly_once_and_no_direct(self):
        expected = [{'path': '/stability/tab-0/page', 'method': 'GET', 'route': 'A'}]
        event = dict(expected[0], event='owned-origin-access', status=200,
                     responseBytesWritten=20, contentLength=20)
        line = json.dumps(event)
        self.assertEqual(driver.verify_requests(expected, line)[0]['count'], 1)
        for bad in (line + '\n' + line, '', json.dumps(dict(event, route='direct')),
                    json.dumps(dict(event, responseBytesWritten=0)),
                    json.dumps(dict(event, path='/stability/outage-web/upload', method='POST'))):
            with self.assertRaises(AssertionError):
                driver.verify_requests(expected, bad)

    def test_firewall_rejects_unowned_ports(self):
        for ports in ([443, 80], [19443], [19443, 20443], [19443, 19443]):
            with self.assertRaises(AssertionError):
                driver.OwnedOutage(ports, Path('/unused'))
