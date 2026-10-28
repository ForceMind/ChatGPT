"""Restricted CI fixture bridge for a genuinely non-debuggable PoC APK.

The browser runs as its normal app UID. Only the fresh emulator's test tools
use root; private fixture commands drop to that same app UID. Never shipped.
"""
from pathlib import Path
import hashlib,json,os,re,shlex,stat,subprocess,sys,time

PACKAGE='com.forcemind.chatgpt.environmentpoc'
DATA='/data/user/0/'+PACKAGE
DIRS=('managed-recovery-records','managed-poc-a','managed-poc-b','recovery-records','recovery-poc-a')
CIPHER=r'files/managed-recovery-records/[ABC]-page-[01]\.bin(?:\.bak)?'
READ=r'(?:files/managed-native-[ABC]\.json|files/managed-events-[ABC]\.jsonl|'+CIPHER+r')'

def validate_receipt(c,run):
    if c.get('runId')!=run or c.get('package')!=PACKAGE or c.get('qemu')!='1' or type(c.get('adbUid')) is not int or c.get('adbUid')!=0 or type(c.get('sdk')) is not int or c.get('sdk') not in (28,35) or c.get('nonDebuggableRunAsRefused') is not True or c.get('installedApkSha256')!=c.get('signedApkSha256') or not re.fullmatch(r'[a-f0-9]{64}',c.get('installedApkSha256','')):raise ValueError('CI release identity not attested')
    if not re.fullmatch(r'emulator-\d+',c.get('serial','')) or type(c.get('appUid')) is not int or not 10000<=c.get('appUid',0)<=19999 or c.get('su') not in ('/system/bin/su','/system/xbin/su') or c.get('chcon') not in ('/system/bin/chcon','/system/xbin/chcon'):raise ValueError('Invalid CI fixture context')

def adapt(args,c):
    if args==['devices'] or args==['emu','kill'] or args in (['reverse','tcp:8765','tcp:8765'],['reverse','--remove','tcp:8765'],['logcat','-v','threadtime','-T','1']):return args,None
    if not args or args[0] not in ('shell','exec-out'):raise ValueError('Unsupported CI command')
    t=shlex.split(' '.join(args[1:]));kind=None
    if not t:raise ValueError('Empty device command')
    if t[0]=='run-as':
        if len(t)<4 or t[1]!=PACKAGE:raise ValueError('Foreign package access refused')
        command=t[2:];script=None;write=None
        if len(command)==2 and command[0]=='cat' and re.fullmatch(READ,command[1]):script=shlex.join(command);kind='fixture-read'
        elif command==['rm','files/managed-recovery-records/A-page-0.bin']:script=shlex.join(command);kind='fixture-atomic-remove'
        elif len(command)==3 and command[:2]==['sh','-c']:
            body=command[2]
            if body in ['if [ -e files/'+d+' ]; then echo PRESENT; else echo ABSENT; fi' for d in DIRS]:script=body;kind='fresh-directory-check'
            elif re.fullmatch('cat > '+CIPHER,body):script='umask 077; '+body;write=body[6:];kind='fixture-write'
        if script is None:raise ValueError('Private fixture path or operation refused')
        inner=shlex.join([c['su'],str(c['appUid']),'/system/bin/sh','-c',script]);outer='cd '+shlex.quote(DATA)+' && '+inner
        if write:
            reference=write[:-4] if write.endswith('.bak') else write
            label='context=$(/system/bin/ls -Zd '+shlex.quote(reference)+" | /system/bin/cut -d ' ' -f1); case \"$context\" in u:object_r:app_data_file:s0*) ;; *) exit 84;; esac; "
            outer='cd '+shlex.quote(DATA)+' && '+label+inner+' && '+shlex.quote(c['chcon'])+' "$context" '+shlex.quote(write)+' && [ "$(/system/bin/ls -Zd '+shlex.quote(write)+" | /system/bin/cut -d ' ' -f1)\" = \"$context\" ]"
        # One non-PTY shell-v2 channel preserves binary input/output and remote
        # exit status. exec-out separately escapes arguments and must not receive
        # an already quoted nested sh -c script.
        return ['shell','-T',outer],kind
    if args[0]=='exec-out':
        if t!=['cat','/sdcard/managed-poc.xml']:raise ValueError('Unapproved exec-out operation')
        return ['exec-out',*t],None
    allowed=(t in (['getprop','ro.kernel.qemu'],['getprop','ro.build.version.sdk'],['dumpsys','activity','activities'],['ps','-A','-o','PID,S,NAME'],['am','force-stop',PACKAGE],['uiautomator','dump','--compressed','/sdcard/managed-poc.xml']))
    if t[:2]==['am','start']:
        allowed=t.count('-n')==1 and t.index('-n')+1<len(t) and re.fullmatch(re.escape(PACKAGE+'/'+PACKAGE)+r'\.Managed[ABC]Activity',t[t.index('-n')+1]) is not None
    if t[:2]==['input','tap']:allowed=len(t)==4 and all(re.fullmatch(r'\d{1,4}',x) for x in t[2:])
    if t[:2]==['input','swipe']:allowed=len(t)==7 and all(re.fullmatch(r'\d{1,4}',x) for x in t[2:])
    if t[:2]==['input','keyevent']:allowed=t==['input','keyevent','KEYCODE_HOME']
    if t[:2]==['log','-t']:allowed=len(t)==7 and t[:5]==['log','-t','StudioPocPhase','-p','i'] and all(re.fullmatch(r'(?:phase|step)=[a-z0-9-]+',x) for x in t[5:])
    if t[:2]==['env','CLASSPATH=/data/local/tmp/studio-poc-input.jar']:
        allowed=c['sdk']==28 and len(t)==10 and t[2:5]==['app_process','/system/bin','com.forcemind.chatgpt.pocinput.TouchInput'] and all(re.fullmatch(r'\d{1,4}',x) for x in t[5:])
        if allowed:t=[c['su'],'2000',*t]
    if not allowed:raise ValueError('Unapproved CI shell operation')
    return ['shell','-T',shlex.join(t)],None

def paths():
    if os.environ.get('GITHUB_ACTIONS')!='true' or os.environ.get('POC_RUNTIME_VARIANT')!='optimized-release':raise ValueError('Optimized-release CI only')
    real=Path(os.environ['POC_REAL_ADB']).resolve();expected=(Path(os.environ['ANDROID_HOME'])/'platform-tools/adb').resolve()
    if real!=expected or not real.is_file():raise ValueError('Unexpected adb binary')
    receipt=Path(os.environ['POC_RELEASE_ATTESTATION']);audit=Path(os.environ['POC_RELEASE_AUDIT'])
    if receipt.is_symlink() or not receipt.resolve().is_relative_to(Path(os.environ['RUNNER_TEMP']).resolve()):raise ValueError('Invalid attestation path')
    expected_audit=(Path(os.environ['GITHUB_WORKSPACE'])/'android/environment-poc/build/recovery-evidence/release-fixture-access.jsonl').resolve()
    if audit.is_symlink() or audit.resolve()!=expected_audit:raise ValueError('Invalid fixture audit path')
    return str(real),receipt,audit

def attest():
    real,receipt,audit=paths()
    def raw(*a,check=True):return subprocess.run([real,*a],check=check,capture_output=True,text=True)
    devices=[line.split()[0] for line in raw('devices').stdout.splitlines()[1:] if line.endswith('\tdevice')]
    if len(devices)!=1 or not re.fullmatch(r'emulator-\d+',devices[0]):raise ValueError('One fresh CI emulator required')
    serial=devices[0]
    def shell(*a,check=True):return raw('-s',serial,'shell',*a,check=check)
    qemu=shell('getprop','ro.kernel.qemu').stdout.strip();sdk=int(shell('getprop','ro.build.version.sdk').stdout.strip());uid=int(shell('id','-u').stdout.strip())
    if qemu!='1' or sdk not in (28,35) or uid!=0:raise ValueError('Root fixture access requires the verified CI emulator')
    su=shell('which','su').stdout.strip();chcon=shell('which','chcon').stdout.strip()
    app_uid=int(shell('stat','-c','%u',DATA).stdout.strip());actual_uid=int(shell(su,str(app_uid),'id','-u').stdout.strip())
    if actual_uid!=app_uid:raise ValueError('App UID drop failed')
    identity=json.loads((Path(os.environ['GITHUB_WORKSPACE'])/'android/environment-poc/build/recovery-evidence/runtime-release-identity.json').read_text())
    if identity.get('package')!=PACKAGE or identity.get('debuggable') is not False or not identity.get('payloadUnchangedBySigning') or not identity.get('temporaryCiCertificate'):raise ValueError('Expected signed non-debuggable CI release')
    package_paths=shell('pm','path',PACKAGE).stdout.strip().splitlines()
    if len(package_paths)!=1 or not package_paths[0].startswith('package:'):raise ValueError('Unexpected installed split set')
    apk=package_paths[0][8:]
    if not re.fullmatch(r'/data/app/[a-zA-Z0-9_~+./=-]+/base\.apk',apk) or '..' in apk.split('/'):raise ValueError('Unexpected installed APK path')
    installed=shell('sha256sum',apk).stdout.split()[0]
    refused=shell(su,'2000','/system/bin/run-as',PACKAGE,'id',check=False)
    denied=refused.returncode!=0 and 'not debuggable' in (refused.stdout+refused.stderr).lower()
    c={'schema':1,'runId':os.environ['GITHUB_RUN_ID'],'package':PACKAGE,'serial':serial,'qemu':qemu,'sdk':sdk,'adbUid':uid,'appUid':app_uid,'su':su,'chcon':chcon,'nonDebuggableRunAsRefused':denied,'installedApkSha256':installed,'signedApkSha256':identity['signedApkSha256']}
    validate_receipt(c,os.environ['GITHUB_RUN_ID'])
    fd=os.open(receipt,os.O_CREAT|os.O_EXCL|os.O_WRONLY,0o600)
    with os.fdopen(fd,'w') as stream:json.dump(c,stream)
    (audit.parent/'release-device-attestation.json').write_text(json.dumps(c,indent=2)+'\n')
    print('CI emulator, installed release hash, non-debuggable run-as refusal and normal app UID attested')

def main():
    if sys.argv[1:]==['--attest']:attest();return
    real,receipt,audit=paths();s=receipt.stat()
    if not stat.S_ISREG(s.st_mode) or s.st_uid!=os.getuid() or stat.S_IMODE(s.st_mode)!=0o600:raise ValueError('Unsafe CI attestation file')
    c=json.loads(receipt.read_text());validate_receipt(c,os.environ['GITHUB_RUN_ID'])
    args,kind=adapt(sys.argv[1:],c);command=[real,*([] if args==['devices'] else ['-s',c['serial']]),*args]
    if kind:
        r=subprocess.run(command,stderr=subprocess.PIPE)
        event={'kind':kind,'hostMonotonic':time.monotonic(),'appUid':c['appUid'],'returncode':r.returncode,'stderr':r.stderr.decode(errors='replace')[:1200]}
        fd=os.open(audit,os.O_WRONLY|os.O_CREAT|os.O_APPEND|os.O_NOFOLLOW,0o600)
        with os.fdopen(fd,'w') as stream:stream.write(json.dumps(event)+'\n')
        sys.stderr.buffer.write(r.stderr);raise SystemExit(r.returncode)
    os.execv(real,command)

if __name__=='__main__':
    try:main()
    except Exception as error:print('CI fixture bridge refused: '+str(error),file=sys.stderr);raise SystemExit(2)
