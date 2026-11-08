#!/usr/bin/env python3
"""Reuse the data-preservation checks in a separate original-certificate-only suite."""
import os
from pathlib import Path

assert os.environ.get('GITHUB_ACTIONS') == 'true', 'Generate CI tests only in Actions'
root = Path('android/app')
source = (root / 'src/regularEnvironmentTest/java/com/forcemind/chatgpt/profiles/RegularEnvironmentUpgradeTest.java').read_text()
assert source.count('static int identity(long code)throws Exception{') == 1
start = source.index('    static int identity(long code)throws Exception{')
end = source.index('    static JSONObject expected()', start)
identity = '''    static int identity(long code)throws Exception{
        assertEquals(PACKAGE,context().getPackageName());
        int api=Integer.parseInt(args().getString("expectedApi"));assertTrue(api==28||api==35);assertEquals(api,android.os.Build.VERSION.SDK_INT);
        assertEquals(0,android.os.Process.myUid()/100000);
        try(android.os.ParcelFileDescriptor fd=InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand("getprop ro.kernel.qemu");java.io.InputStream input=new android.os.ParcelFileDescriptor.AutoCloseInputStream(fd)){
            byte[] value=new byte[32];int n=input.read(value);assertTrue(n>0);assertEquals("1",new String(value,0,n,StandardCharsets.UTF_8).trim());
        }
        assertEquals(code,context().getPackageManager().getPackageInfo(PACKAGE,0).getLongVersionCode());
        String expected="8b6eef928fded91f291ad954e20976b2cc6011b55e8cbec0152695fe1f05cf19";
        assertEquals(expected,args().getString("signer"));assertEquals(expected,signer());
        assertEquals("original-public-alpha22-original-signature",args().getString("baselineScope"));
        int flags=context().getApplicationInfo().flags;
        assertEquals(0,flags&(android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE|android.content.pm.ApplicationInfo.FLAG_TEST_ONLY));
        String expectedAPK=args().getString("installedSHA256");assertNotNull(expectedAPK);assertTrue(expectedAPK.matches("[a-f0-9]{64}"));
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(java.io.InputStream input=new java.io.FileInputStream(context().getApplicationInfo().sourceDir)){
            byte[] bytes=new byte[65536];int n;while((n=input.read(bytes))!=-1)digest.update(bytes,0,n);
        }
        StringBuilder actual=new StringBuilder();for(byte b:digest.digest())actual.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
        assertEquals(expectedAPK,actual.toString());return api;
    }
'''
source = source[:start] + identity + source[end:]
source = source.replace('RegularEnvironmentUpgradeTest', 'OriginalSignatureUpgradeData')
source = source.replace('regular-environment-upgrade-expected.json', 'original-signature-upgrade-expected.json')
source = source.replace('sameTemporaryCISignature', 'sameProductionSignature')
source = source.replace('.put("productionSignatureVerified",false)', '.put("productionSignatureVerified",true)')
source = source.replace('regularEnvironmentUpgradeProof', 'originalSignatureUpgradeProof')
destination = root / 'build/generated/original-signature-tests/com/forcemind/chatgpt/profiles'
destination.mkdir(parents=True, exist_ok=False)
(destination / 'OriginalSignatureUpgradeData.java').write_text(source)
print('Generated separate original-certificate data suite; no private key read')
