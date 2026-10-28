import importlib.util
import unittest
from pathlib import Path
spec=importlib.util.spec_from_file_location('package_size_poc',Path(__file__).resolve().parents[1]/'package-size-poc.py')
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)

class PackageManifestTests(unittest.TestCase):
    def text(self,sdk='minSdkVersion',value='28',package='com.forcemind.chatgpt.environmentpoc',abis="'arm64-v8a'",extra=''):
        return f"package: name='{package}' versionCode='1'\n{sdk}:'{value}'\nnative-code: {abis}\n{extra}"
    def test_current_and_legacy_sdk_labels(self):
        for label in ('minSdkVersion','sdkVersion'):
            self.assertEqual(module.validate_badging(self.text(sdk=label))['minSdk'],28)
    def test_wrong_minimum_is_not_accepted(self):
        with self.assertRaises(RuntimeError):module.validate_badging(self.text(value='29'))
    def test_formal_package_is_not_accepted(self):
        with self.assertRaises(RuntimeError):module.validate_badging(self.text(package='com.forcemind.chatgpt.profiles'))
    def test_extra_abi_or_debuggable_is_not_release_comparison(self):
        for text in (self.text(abis="'arm64-v8a' 'x86_64'"),self.text(extra='application-debuggable\n')):
            with self.assertRaises(RuntimeError):module.validate_badging(text)

if __name__=='__main__':unittest.main()
