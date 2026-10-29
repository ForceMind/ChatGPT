import importlib.util
from pathlib import Path
import unittest

SPEC = importlib.util.spec_from_file_location('prepare_webview', Path(__file__).resolve().parents[1]/'prepare-webview.py')
PROVIDER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PROVIDER)
HEADER = "package: name='com.android.webview' versionCode='636771907' versionName='124.0.6367.219'\n"


class ProviderMetadataTests(unittest.TestCase):
    def test_minimum_sdk_formats_have_same_semantics(self):
        for field in ["minSdkVersion:'26'", "sdkVersion:'26'", "sdkVersion:'26.0'"]:
            with self.subTest(field=field):
                meta = PROVIDER.parse_badging(HEADER + field + "\ntargetSdkVersion:'34'\n", 'com.android.webview')
                self.assertEqual(26, meta['minSdk'])
                self.assertEqual(34, meta['targetSdk'])

    def test_target_cannot_be_mistaken_for_missing_minimum(self):
        with self.assertRaises(RuntimeError):
            PROVIDER.parse_badging(HEADER + "targetSdkVersion:'28'\n", 'com.android.webview')

    def test_wrong_provider_or_fractional_api_is_not_accepted(self):
        for raw, package in [(HEADER + "sdkVersion:'26'\ntargetSdkVersion:'34'", 'com.google.android.webview'),
                             (HEADER + "sdkVersion:'26.1'\ntargetSdkVersion:'34'", 'com.android.webview')]:
            with self.subTest(raw=raw), self.assertRaises(RuntimeError):
                PROVIDER.parse_badging(raw, package)


if __name__ == '__main__':
    unittest.main()
