import importlib.util
from pathlib import Path
import tempfile
import stat
import unittest
import zipfile
spec=importlib.util.spec_from_file_location('sign_poc_release',Path(__file__).resolve().parents[1]/'sign-poc-release.py')
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)

class ReleaseSigningBoundaryTests(unittest.TestCase):
    def test_password_sources_are_independent_single_lines(self):
        with tempfile.TemporaryDirectory() as directory:
            store,key=module.password_files(Path(directory));self.assertNotEqual(store,key)
            # Emulate the SDK's cached per-file input streams, not two fresh opens.
            streams={p:p.open() for p in (store,key)}
            try:
                values=[streams[p].readline() for p in (store,key)]
                self.assertTrue(values[0].strip());self.assertEqual(values[0],values[1]);self.assertTrue(all(streams[p].readline()=='' for p in streams))
                self.assertTrue(all(stat.S_IMODE(p.stat().st_mode)==0o600 for p in streams))
            finally:
                for stream in streams.values():stream.close()
    def text(self,minimum=28,debug=False,package=module.PACKAGE,abi='x86_64'):
        return f"package: name='{package}' versionCode='1'\nminSdkVersion:'{minimum}'\nnative-code: '{abi}'\n"+('application-debuggable\n' if debug else '')
    def test_two_declared_minimums(self):
        for minimum in (28,29):module.validate_badging(self.text(minimum),minimum)
    def test_debug_formal_package_or_wrong_abi_cannot_be_signed(self):
        for text in (self.text(debug=True),self.text(package='com.forcemind.chatgpt.profiles'),self.text(abi='arm64-v8a'),self.text(minimum=29)):
            with self.assertRaises(RuntimeError):module.validate_badging(text,28)
    def zip(self,path,extra=None,metadata=b'original'):
        with zipfile.ZipFile(path,'w') as z:
            for name,body in {'classes.dex':b'unit-format-marker-not-a-real-dex','assets/managed-recovery/recovery.js':b'fixture-bridge','META-INF/library.version':metadata,**(extra or {})}.items():z.writestr(name,body)
    def test_only_signature_metadata_is_ignored(self):
        with tempfile.TemporaryDirectory() as directory:
            a,b=Path(directory)/'unsigned.zip',Path(directory)/'signed.zip';self.zip(a);self.zip(b,{'META-INF/MANIFEST.MF':b'signature marker','META-INF/CERT.RSA':b'signature marker'})
            self.assertEqual(module.payload(a),module.payload(b))
    def test_library_metadata_change_is_not_hidden(self):
        with tempfile.TemporaryDirectory() as directory:
            a,b=Path(directory)/'a.zip',Path(directory)/'b.zip';self.zip(a);self.zip(b,metadata=b'changed')
            self.assertNotEqual(module.payload(a),module.payload(b))

if __name__=='__main__':unittest.main()
