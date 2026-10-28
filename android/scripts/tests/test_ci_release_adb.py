import copy
import os
from pathlib import Path
import shlex
import sys
import unittest
from unittest.mock import patch
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from ci_release_adb import PACKAGE,DATA,adapt,paths,validate_receipt

class ReleaseFixtureBridgeTests(unittest.TestCase):
    def setUp(self):
        self.c={'runId':'123','package':PACKAGE,'qemu':'1','sdk':28,'adbUid':0,'appUid':10077,'serial':'emulator-5554','su':'/system/xbin/su','chcon':'/system/bin/chcon','nonDebuggableRunAsRefused':True,'installedApkSha256':'a'*64,'signedApkSha256':'a'*64}
    def test_valid_installed_identity(self):validate_receipt(self.c,'123')
    def test_physical_nonroot_wrong_run_or_signature_refused(self):
        for key,value in [('serial','physical-123'),('qemu','0'),('adbUid',2000),('runId','124'),('signedApkSha256','b'*64),('nonDebuggableRunAsRefused',False),('adbUid',False)]:
            c={**self.c,key:value}
            with self.assertRaises(ValueError):validate_receipt(c,'123')
    def test_only_normal_app_uid(self):
        for uid in (0,2000,99000,True):
            with self.assertRaises(ValueError):validate_receipt({**self.c,'appUid':uid},'123')
    def test_no_host_device_access_without_ci_mode(self):
        with patch.dict(os.environ,{},clear=True):
            with self.assertRaises(ValueError):paths()
    def test_binary_reader_is_bound_to_app_uid(self):
        args,kind=adapt(['exec-out','run-as',PACKAGE,'cat','files/managed-native-A.json'],self.c)
        self.assertEqual(kind,'fixture-read');self.assertEqual(args[:2],['shell','-T'])
        command=args[2];self.assertTrue(command.startswith('cd '+DATA+' && '));self.assertIn('10077',command);self.assertIn('managed-native-A.json',command)
    def test_writer_keeps_stdin_and_original_selinux_context(self):
        args,kind=adapt(['shell',"run-as "+PACKAGE+" sh -c 'cat > files/managed-recovery-records/A-page-0.bin.bak'"],self.c)
        self.assertEqual(args[:2],['shell','-T']);self.assertEqual(kind,'fixture-write');self.assertIn('umask 077',args[2]);self.assertIn('chcon',args[2]);self.assertIn('ls -Zd files/managed-recovery-records/A-page-0.bin ',args[2])
    def test_foreign_package_and_traversal_refused(self):
        commands=[['exec-out','run-as','com.forcemind.chatgpt.profiles','cat','files/managed-native-A.json'],['exec-out','run-as',PACKAGE,'cat','files/managed-recovery-records/../../Cookies'],['exec-out','run-as',PACKAGE,'cat','files/Partitions/Cookies']]
        for command in commands:
            with self.assertRaises(ValueError):adapt(command,self.c)
    def test_only_specific_atomic_fault_removal(self):
        args,kind=adapt(['shell','run-as',PACKAGE,'rm','files/managed-recovery-records/A-page-0.bin'],self.c);self.assertEqual(kind,'fixture-atomic-remove')
        with self.assertRaises(ValueError):adapt(['shell','run-as',PACKAGE,'rm','files/managed-native-A.json'],self.c)
    def test_fresh_guard_and_known_live_file(self):
        args,kind=adapt(['shell','run-as',PACKAGE,'sh','-c',"'if [ -e files/managed-poc-a ]; then echo PRESENT; else echo ABSENT; fi'"],self.c);self.assertEqual(kind,'fresh-directory-check')
    def test_unknown_shell_and_screenshot_refused(self):
        for command in (['exec-out','screencap','-p'],['shell','rm','-rf','/data'],['shell','am','start','-n'],['shell','am','start','-n','com.other/.Activity']):
            with self.assertRaises(ValueError):adapt(command,self.c)
    def test_legacy_touch_remains_shell_uid(self):
        command=['shell','env','CLASSPATH=/data/local/tmp/studio-poc-input.jar','app_process','/system/bin','com.forcemind.chatgpt.pocinput.TouchInput','540','1500','540','800','550']
        args,kind=adapt(command,self.c);self.assertIn('2000 env CLASSPATH=',args[2])
        with self.assertRaises(ValueError):adapt(command,{**self.c,'sdk':35})

if __name__=='__main__':unittest.main()
