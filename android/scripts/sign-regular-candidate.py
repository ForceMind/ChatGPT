#!/usr/bin/env python3
"""Sign only fully validated cloud inputs locally, without exporting the release key."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import zipfile

assert os.environ.get('GITHUB_ACTIONS') != 'true', 'The original key stays local'
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--candidate',type=Path,required=True)
parser.add_argument('--companions',type=Path,required=True)
parser.add_argument('--output',type=Path,required=True)
args=parser.parse_args()
assert not args.output.exists(), 'Refuse to overwrite existing signed files'
expected='8b6eef928fded91f291ad954e20976b2cc6011b55e8cbec0152695fe1f05cf19'
root=Path(__file__).resolve().parents[1]
report=json.loads((args.candidate/'regular-full-validation.json').read_text())
assert report['complete'] is True and report['networkFileSkillGatesComplete'] is True
assert report['navigationGatesComplete'] is True and report['compatibilityRecoveryGatesComplete'] is True
metadata=report['versionMetadata'];human=report['humanUnsignedReceipt']
assert human['versionMetadata']==metadata and human['signing']=='UNSIGNED_REVIEW_ONLY'
assert metadata['versionCode']>3000022 and re.fullmatch(r'\d{4}\.\d{2}\.\d{2}-\d{4}',metadata['versionName'])
assert re.fullmatch(r'[a-f0-9]{40}',metadata['sourceSHA'])
properties=dict(line.split('=',1) for line in (root/'keystore.properties').read_text().splitlines() if '=' in line and not line.startswith('#'))
key=(root/properties['storeFile']).resolve()
assert key==(root/'.signing/android-release.p12').resolve() and key.is_file()
certificate=subprocess.run(['openssl','pkcs12','-in',str(key),'-clcerts','-nokeys','-passin','stdin'],
                           input=(properties['storePassword']+'\n').encode(),capture_output=True)
assert certificate.returncode==0,'Cannot authenticate original public certificate; private key output is disabled'
certificates=re.findall(b'-----BEGIN CERTIFICATE-----.*?-----END CERTIFICATE-----',certificate.stdout,re.S)
assert len(certificates)==1
der=subprocess.run(['openssl','x509','-outform','DER'],input=certificates[0],capture_output=True,check=True).stdout
assert hashlib.sha256(der).hexdigest()==expected,'Original certificate mismatch; refuse signing'
java=str(Path(os.environ['JAVA_HOME'])/'bin/java')
jar=Path(os.environ['APKSIGNER_JAR']);aapt=Path(os.environ['AAPT2_BIN'])
assert jar.is_file() and aapt.is_file()


def sha(path):
    digest=hashlib.sha256()
    with path.open('rb') as stream:
        for part in iter(lambda:stream.read(65536),b''):digest.update(part)
    return digest.hexdigest()


def entries(path):
    result={}
    with zipfile.ZipFile(path) as archive:
        assert len(archive.namelist())==len(set(archive.namelist()))
        for item in archive.infolist():
            if re.fullmatch(r'META-INF/(?:MANIFEST\.MF|[^/]+\.(?:SF|RSA|DSA|EC))',item.filename,re.I):continue
            digest=hashlib.sha256()
            with archive.open(item) as stream:
                for part in iter(lambda:stream.read(65536),b''):digest.update(part)
            result[item.filename]=(item.file_size,digest.hexdigest())
    return result


def input_file(folder,receipt):
    assert Path(receipt['file']).name==receipt['file']
    path=folder/receipt['file']
    assert path.is_file() and not path.is_symlink() and path.stat().st_size==receipt['bytes'] and sha(path)==receipt['sha256']
    return path


companion=json.loads((args.companions/'human-x86-receipt.json').read_text())
test=json.loads((args.companions/'test-receipt.json').read_text())
for row in (companion,test):assert row['versionMetadata']==metadata and row['sourceSHA']==metadata['sourceSHA']
assert companion['sameDexAsHumanArm64'] is True and companion['arm64UnsignedSHA256']==human['sha256']
inputs={'arm64':input_file(args.candidate,human),'humanX86':input_file(args.companions,companion),
        'instrumentation':input_file(args.companions,test)}
with zipfile.ZipFile(inputs['instrumentation']) as archive:
    dex=b''.join(archive.read(n) for n in archive.namelist() if n.endswith('.dex'))
    assert b'OriginalSignatureUpgradeData' in dex and b'OriginalSignatureUpgradeVerifyTest' in dex
    for name in (b'WorkbenchTest',b'NetworkIntegrationTest',b'BrowserChromeNavigationTest',b'RegularDualEngineSwitchTest'):
        assert name not in dex, 'Unexpected privileged instrumentation code'
names={'arm64':f"ChatGPT-Studio-{metadata['versionName']}-android-arm64.apk",
       'humanX86':f"ChatGPT-Studio-{metadata['versionName']}-ORIGINAL-CERT-CI-x86_64.apk",
       'instrumentation':f"ChatGPT-Studio-{metadata['versionName']}-GUARDED-UPGRADE-TEST.apk"}
args.output.mkdir(parents=True,exist_ok=False)
receipt={'schema':1,'package':'com.forcemind.chatgpt.profiles','certificateSHA256':expected,
         'versionMetadata':metadata,'sourceSHA':metadata['sourceSHA'],'privateKeyUploaded':False,
         'productionSignatureUpgradeVerified':False,'publicRelease':False}
for role,source in inputs.items():
    badging=subprocess.run([str(aapt),'dump','badging',str(source)],capture_output=True,text=True,check=True).stdout
    package='com.forcemind.chatgpt.profiles'+('.test' if role=='instrumentation' else '')
    assert "name='"+package+"'" in badging
    if role!='instrumentation':
        assert "versionName='"+metadata['versionName']+"'" in badging and "versionCode='"+str(metadata['versionCode'])+"'" in badging
        assert 'application-debuggable' not in badging
    before=entries(source);target=args.output/names[role]
    command=[java,'-jar',str(jar),'sign','--ks',str(key),'--ks-type','PKCS12',
             '--ks-key-alias',properties['keyAlias'],'--ks-pass','stdin','--out',str(target),str(source)]
    result=subprocess.run(command,input=(properties['storePassword']+'\n').encode(),capture_output=True)
    assert result.returncode==0, 'Original signing failed; password and tool output withheld'
    verified=subprocess.run([java,'-jar',str(jar),'verify','--verbose','--print-certs',str(target)],capture_output=True,text=True)
    assert verified.returncode==0
    assert re.findall(r'certificate SHA-256 digest: ([a-f0-9]{64})',verified.stdout)==[expected]
    after=entries(target);assert before==after,'Signing changed application contents'
    receipt[role]={'file':target.name,'bytes':target.stat().st_size,'sha256':sha(target),
                   'inputSHA256':sha(source),'nonSigningEntriesUnchanged':True}
(args.output/'signed-receipt.json').write_text(json.dumps(receipt,indent=2)+'\n')
print(json.dumps({'signed':True,'sourceSHA':metadata['sourceSHA'],'certificateSHA256':expected,
                  'originalSignatureUpgradeVerified':False,'privateKeyUploaded':False}))
