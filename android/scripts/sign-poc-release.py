#!/usr/bin/env python3
"""Sign only the independent, non-debuggable CI release with a disposable key."""
from pathlib import Path
import hashlib,json,os,re,secrets,shutil,subprocess,tempfile,zipfile

PACKAGE='com.forcemind.chatgpt.environmentpoc'

def digest(path):
    h=hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda:stream.read(1024*1024),b''):h.update(chunk)
    return h.hexdigest()

def payload(path):
    result={}
    with zipfile.ZipFile(path) as z:
        names=z.namelist()
        if len(names)!=len(set(names)):raise RuntimeError('Duplicate APK entries')
        for name in names:
            if re.fullmatch(r'META-INF/(?:MANIFEST\.MF|[^/]+\.(?:SF|RSA|DSA|EC))',name):continue
            h=hashlib.sha256()
            with z.open(name) as stream:
                for chunk in iter(lambda:stream.read(1024*1024),b''):h.update(chunk)
            result[name]=h.hexdigest()
    return result

def validate_badging(badging,minimum):
    if minimum not in (28,29) or not re.search(r"^package: name='"+re.escape(PACKAGE)+"'",badging,re.M) or re.findall(r"^(?:minSdkVersion|sdkVersion):'(\d+)'$",badging,re.M)!=[str(minimum)] or not re.search(r"^native-code: 'x86_64'\s*$",badging,re.M) or re.search(r'^application-debuggable\b',badging,re.M):raise RuntimeError('Not the expected non-debuggable release')

def password_files(directory):
    # apksigner consumes successive password lines when the same file is reused.
    # Separate one-line sources keep both PKCS12 passwords identical without
    # depending on a shared reader cursor or exposing them in CLI arguments.
    value=secrets.token_urlsafe(32)+'\n';files=[]
    for name in ('store-password','key-password'):
        path=directory/name;fd=os.open(path,os.O_CREAT|os.O_EXCL|os.O_WRONLY,0o600)
        with os.fdopen(fd,'w') as stream:stream.write(value)
        files.append(path)
    return files

def main():
    if os.environ.get('GITHUB_ACTIONS')!='true':raise SystemExit('Signing is CI-only')
    root=Path(__file__).resolve().parents[1]/'environment-poc';evidence=root/'build/recovery-evidence';evidence.mkdir(parents=True,exist_ok=True)
    minimum=int(os.environ['POC_MIN_API'])
    if minimum not in (28,29):raise SystemExit('Unexpected isolated minimum')
    files=list((root/'build/outputs/apk/release').glob('*x86_64*unsigned.apk'))
    if len(files)!=1:raise RuntimeError('Expected one independent x86_64 release')
    apk=files[0];tools=Path(os.environ['ANDROID_HOME'])/'build-tools/36.0.0'
    badging=subprocess.check_output([str(tools/'aapt2'),'dump','badging',str(apk)],text=True)
    validate_badging(badging,minimum)
    mapping=root/'build/outputs/mapping/release/mapping.txt'
    if not mapping.is_file() or not mapping.stat().st_size:raise RuntimeError('R8 mapping missing')
    target=root/'build/runtime-release/environment-poc-x86_64-ci-release.apk';target.parent.mkdir(parents=True,exist_ok=True)
    temporary=Path(tempfile.mkdtemp(prefix='studio-ci-key-',dir=os.environ['RUNNER_TEMP']))
    def tool(command):
        result=subprocess.run(command,capture_output=True,text=True)
        # Commands contain only password-file paths. The tools' diagnostic
        # output is needed on failure; no password or private-key bytes are logged.
        with (evidence/'runtime-release-signing-tools.txt').open('a') as log:
            log.write(Path(command[0]).name+' exit='+str(result.returncode)+'\n'+result.stdout+result.stderr+'\n')
        if result.returncode:raise RuntimeError(Path(command[0]).name+' failed: '+(result.stderr or result.stdout)[-2500:])
        return result.stdout
    try:
        store_password,key_password=password_files(temporary)
        key=temporary/'temporary.p12'
        tool(['keytool','-genkeypair','-noprompt','-storetype','PKCS12','-keystore',str(key),'-storepass:file',str(store_password),'-keypass:file',str(key_password),'-alias','poc-runtime','-keyalg','RSA','-keysize','2048','-validity','2','-dname','CN=Independent CI PoC'])
        tool([str(tools/'apksigner'),'sign','--ks',str(key),'--ks-key-alias','poc-runtime','--ks-pass','file:'+str(store_password),'--key-pass','file:'+str(key_password),'--out',str(target),str(apk)])
        verification=tool([str(tools/'apksigner'),'verify','--verbose','--print-certs',str(target)])
        cert=re.findall(r'Signer #1 certificate SHA-256 digest: ([a-fA-F0-9]{64})',verification)
        if len(cert)!=1 or payload(apk)!=payload(target):raise RuntimeError('Signing altered payload or certificate not verified')
        identity={'schema':1,'sourceSha':os.environ['GITHUB_SHA'],'runId':os.environ['GITHUB_RUN_ID'],'package':PACKAGE,'runtimeVariant':'optimized-release','optimization':'resources','abi':'x86_64','minSdk':minimum,'debuggable':False,'unsignedApkSha256':digest(apk),'signedApkSha256':digest(target),'signedApkBytes':target.stat().st_size,'certificateSha256':cert[0].lower(),'temporaryCiCertificate':True,'productionKeyUsed':False,'payloadUnchangedBySigning':True,'r8MappingSha256':digest(mapping)}
        (evidence/'runtime-release-badging.txt').write_text(badging)
        (evidence/'runtime-release-signature.txt').write_text(verification)
        (evidence/'runtime-release-identity.json').write_text(json.dumps(identity,indent=2)+'\n')
        print('Non-debuggable optimized independent release signed with temporary CI certificate; original payload preserved')
    finally:shutil.rmtree(temporary)

if __name__=='__main__':main()
