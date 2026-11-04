import base64
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from sampling_evidence import capture_failure, finalize_report


class SamplingEvidenceTest(unittest.TestCase):
    def test_primary_failure_survives_overwrite_and_cleanup_exception(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            original = {'arguments': ['dumpsys', 'activity', 'processes'], 'returnCode': 255,
                        'stdout': '完整原始输出', 'stderr': 'transport closed'}
            (root / 'command-failure.json').write_text(json.dumps(original))
            report = {'complete': False}
            first = RuntimeError('primary dumpsys failure')
            def cleanup():
                (root / 'command-failure.json').write_text('{"secondary":true}')
                raise RuntimeError('cleanup device offline')
            with self.assertRaisesRegex(RuntimeError, '^primary dumpsys failure$') as raised:
                try:
                    raise first
                except Exception as error:
                    report['error'] = capture_failure(root, error)
                    raise
                finally:
                    finalize_report(root, report, cleanup, first)
            self.assertIs(first, raised.exception)
            self.assertEqual(original, json.loads((root / 'first-command-failure.json').read_text()))
            saved = json.loads((root / 'report.json').read_text())
            self.assertFalse(saved['complete'])
            self.assertEqual('primary dumpsys failure', saved['error']['message'])
            self.assertEqual('cleanup device offline', saved['cleanupError']['message'])
            capture_failure(root, RuntimeError('later error'))
            self.assertEqual('primary dumpsys failure', json.loads((root / 'first-terminal-error.json').read_text())['message'])

    def test_cleanup_only_failure_stays_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            def cleanup():
                raise RuntimeError('cleanup failed')
            with self.assertRaisesRegex(RuntimeError, 'cleanup failed'):
                finalize_report(Path(directory), {'complete': True}, cleanup, None)
            self.assertFalse(json.loads((Path(directory) / 'report.json').read_text())['complete'])

    def test_setup_command_preserves_binary_outputs_exactly(self):
        with tempfile.TemporaryDirectory() as directory:
            stdout, stderr = b'partial\xff\n', b'closed\x00\n'
            error = subprocess.CalledProcessError(255, ['adb', 'uninstall', 'synthetic.test'], stdout, stderr)
            capture_failure(Path(directory), error)
            saved = json.loads((Path(directory) / 'first-terminal-error.json').read_text())
            self.assertEqual(255, saved['returnCode'])
            self.assertEqual(stdout, base64.b64decode(saved['stdoutBase64']))
            self.assertEqual(stderr, base64.b64decode(saved['stderrBase64']))
