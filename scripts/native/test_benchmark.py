"""Protocol regressions: a failed child must never become a successful timing sample."""
import importlib.util
import json
import pathlib
import sys
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('benchmark', pathlib.Path(__file__).with_name('benchmark.py'))
benchmark = importlib.util.module_from_spec(spec)
spec.loader.exec_module(benchmark)


class BenchmarkTest(unittest.TestCase):
    def child(self, code, kind='editor', timeout=2):
        with tempfile.TemporaryDirectory() as temp:
            return benchmark.run([sys.executable, '-c', code], {}, pathlib.Path(temp) / 'run.log', kind, timeout)

    def test_exit_zero_without_assertion_protocol_is_failure(self):
        self.assertFalse(self.child("print('window shown')")['ok'])

    def test_pass_then_nonzero_exit_is_failure(self):
        self.assertFalse(self.child('print(\'{"kind":"result","name":"PASS","bytes":0,"value":0}\'); exit(2)')['ok'])

    def test_pass_with_zero_exit(self):
        self.assertTrue(self.child('print(\'{"kind":"result","name":"PASS","bytes":0,"value":0}\')')['ok'])

    def test_stderr_cannot_corrupt_editor_protocol(self):
        code = 'import sys; sys.stdout.write(\'{"kind":\'); sys.stdout.flush(); sys.stderr.write("warning\\n"); sys.stderr.flush(); print(\'"result","name":"PASS","bytes":0,"value":0}\')'
        self.assertTrue(self.child(code)['ok'])

    def test_malformed_protocol_is_retained_as_failure(self):
        result = self.child('print(\'{"kind":broken}\')')
        self.assertFalse(result['ok'])
        self.assertIsNotNone(result['protocol_error'])

    def test_stage_show_is_not_startup_success(self):
        self.assertFalse(self.child("print('[perf] window-shown 10 (+10)')", 'startup')['ok'])
        self.assertTrue(self.child("print('[perf] first-paint 20 (+10)')", 'startup')['ok'])

    def test_timeout_is_failure(self):
        result = self.child('import time; time.sleep(5)', timeout=.1)
        self.assertTrue(result['timeout'])
        self.assertFalse(result['ok'])

    def test_percentiles_and_even_median(self):
        self.assertEqual({'n': 4, 'median': 2.5, 'p95': 4, 'p99': 4}, benchmark.summarize([4, 1, 2, 3]))


class ApplicationDiscoveryTest(unittest.TestCase):
    def test_discovery_waits_for_complete_json(self):
        sys.path.insert(0, str(pathlib.Path(__file__).parent))
        spec = importlib.util.spec_from_file_location('app_probe', pathlib.Path(__file__).with_name('app-probe.py'))
        app_probe = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(app_probe)
        with tempfile.TemporaryDirectory() as temp:
            path = pathlib.Path(temp) / 'endpoint.json'
            self.assertIsNone(app_probe.published_json(path))
            for partial in ('', '{', '{"url":'):
                path.write_text(partial)
                self.assertIsNone(app_probe.published_json(path))
            expected = {'url': 'http://127.0.0.1:1234/mcp', 'token': 'fixture'}
            path.write_text(json.dumps(expected))
            self.assertEqual(expected, app_probe.published_json(path))


if __name__ == '__main__':
    unittest.main()
