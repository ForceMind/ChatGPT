"""Pure parsing/attribution regressions; run in CI, never start adb or an emulator."""
import importlib.util
from pathlib import Path
import unittest

SPEC = importlib.util.spec_from_file_location("measure_resources", Path(__file__).resolve().parents[1] / "measure-resources.py")
RESOURCE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RESOURCE)


class ResourceParsingTests(unittest.TestCase):
    def test_process_comm_with_spaces_and_parentheses_preserves_field_indices(self):
        fields = ["S"] + ["0"] * 19
        fields[11], fields[12], fields[19] = "123", "45", "900"
        self.assertEqual({"cpuTicks": 168, "startTicks": 900},
                         RESOURCE.parse_proc_stat("52 (renderer (main)) " + " ".join(fields)))

    def test_missing_process_fields_fail_instead_of_zero(self):
        for raw in ["permission denied", "42 (app) S 1 2", "42 (app S 1"]:
            with self.subTest(raw=raw), self.assertRaises(ValueError):
                RESOURCE.parse_proc_stat(raw)

    def test_pss_api28_table_and_modern_summary(self):
        self.assertEqual(1234, RESOURCE.parse_pss("   TOTAL     1234    44     0     21\n"))
        self.assertEqual(60215, RESOURCE.parse_pss("    TOTAL: 60215      TOTAL SWAP PSS: 16733\n"))
        self.assertEqual(12345, RESOURCE.parse_pss(" TOTAL PSS: 12,345     TOTAL RSS: 20000\n"))
        self.assertEqual(0, RESOURCE.parse_pss("   TOTAL PSS: 0\n"))

    def test_missing_memory_does_not_become_complete_app_only_total(self):
        for raw in ["No process found for: 32", "Permission Denial", "TOTAL SWAP PSS: 32"]:
            with self.subTest(raw=raw), self.assertRaises(ValueError):
                RESOURCE.parse_pss(raw)
        with self.assertRaises(ValueError):
            RESOURCE.parse_rss("Name:\tapp\nVmSize:\t1234 kB\n")
        self.assertEqual(345, RESOURCE.parse_rss("VmRSS:\t345 kB\n"))

    def test_cpu_one_core_denominator_and_restart_rejection(self):
        before = {"cpuTicks": 200, "startTicks": 50, "counterTimeMonotonic": 10.0}
        after = {"cpuTicks": 350, "startTicks": 50, "counterTimeMonotonic": 12.0}
        interval = RESOURCE.cpu_interval(before, after, 100)
        self.assertEqual(1.5, interval["cpuCoreSeconds"])
        self.assertEqual(75, interval["cpuPercentOneCore"])
        for changes in [{"startTicks": 99}, {"cpuTicks": 199}, {"counterTimeMonotonic": 10.0}]:
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                RESOURCE.cpu_interval(before, {**after, **changes}, 100)

    def test_system_guest_ticks_not_double_counted(self):
        result = RESOURCE.parse_system_stat("cpu 10 2 3 20 1 1 2 1 5 1\ncpu0 0 0\ncpu1 0 0\n")
        self.assertEqual({"totalTicks": 40, "idleTicks": 21, "logicalCores": 2}, result)
        with self.assertRaises(ValueError):
            RESOURCE.parse_system_stat("cpu 10 2 3 20 1 1 2 1\n")

    def test_webview_identity_is_read_from_current_provider_not_hardcoded(self):
        self.assertEqual({"package": "com.android.webview", "version": "89.0.4389.105"},
                         RESOURCE.parse_webview_identity("  Current WebView package (name, version): (com.android.webview, 89.0.4389.105)\n"))
        self.assertEqual({"package": "com.google.android.webview", "version": "124.0.6367.54"},
                         RESOURCE.parse_webview_identity("Current WebView package(name, version): (com.google.android.webview, 124.0.6367.54)"))
        with self.assertRaises(ValueError):
            RESOURCE.parse_webview_identity("Current WebView package is null")

    def records(self, renderer_owner):
        return """ACTIVITY MANAGER RUNNING PROCESSES (dumpsys activity processes)
  *APP* UID 10123 ProcessRecord{123abc 301:%s/u0a123}
    packageList={%s}
  *APP* UID 99001 ProcessRecord{456abc 401:com.android.webview:sandboxed_process0/u0i1}
    mHostingRecord=HostingRecord{service %s/org.chromium.content.app.SandboxedProcessService0:0}
  *APP* UID 99002 ProcessRecord{789abc 402:com.android.chrome:sandboxed_process1/u0i2}
    Services:
      ServiceRecord{xyz u0 com.android.chrome/org.chromium.content.app.SandboxedProcessService1:0}
""" % (RESOURCE.PACKAGE, RESOURCE.PACKAGE, renderer_owner)

    def test_only_client_attributed_webview_renderer_is_counted(self):
        processes, renderers, excluded = RESOURCE.attributable_processes(self.records(RESOURCE.PACKAGE))
        self.assertEqual([301, 401], [process["pid"] for process in processes])
        self.assertEqual([401], renderers)
        self.assertEqual([402], [process["pid"] for process in excluded])
        self.assertEqual("app_and_jni_core", processes[0]["kind"])

    def test_unrelated_chrome_is_excluded_even_when_name_is_webview(self):
        processes, renderers, excluded = RESOURCE.attributable_processes(self.records("com.example.other"))
        self.assertEqual([301], [process["pid"] for process in processes])
        self.assertEqual([], renderers)
        self.assertEqual(2, len(excluded))

    def test_unknown_renderer_owner_fails_visible(self):
        raw = self.records(RESOURCE.PACKAGE).replace(
            RESOURCE.PACKAGE + "/org.chromium.content.app.SandboxedProcessService0:0", "unknown")
        with self.assertRaisesRegex(ValueError, "Cannot attribute renderer"):
            RESOURCE.attributable_processes(raw)
        with self.assertRaises(ValueError):
            RESOURCE.attributable_processes("Permission Denial")

    def test_multiple_owner_services_fail_instead_of_guessing(self):
        raw = self.records(RESOURCE.PACKAGE).replace(
            "  *APP* UID 99002", "    service com.example.other/org.chromium.content.app.SandboxedProcessService0:1\n  *APP* UID 99002")
        with self.assertRaisesRegex(ValueError, "Ambiguous renderer"):
            RESOURCE.attributable_processes(raw)


if __name__ == "__main__":
    unittest.main()
