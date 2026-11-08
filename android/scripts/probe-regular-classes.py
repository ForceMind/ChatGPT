"""Read named reflection contracts from one pinned CI APK, without app launch."""
from pathlib import Path
import json,os,re,shlex,subprocess
assert os.environ.get('GITHUB_ACTIONS')=='true' and os.environ.get('GITHUB_REPOSITORY')=='ForceMind/ChatGPT'
def text(args):return subprocess.check_output(['adb',*args],text=True).strip()
assert text(['shell','getprop','ro.kernel.qemu'])=='1'
assert not text(['shell','pm','list','packages','com.forcemind.chatgpt.profiles'])
inputs=Path(os.environ.get('STUDIO_CLASS_PROBE_INPUT','input'))
r=json.loads((inputs/'receipt.json').read_text());assert Path(r['file']).name==r['file']
subprocess.run(['adb','install','-t',str(inputs/r['file'])],check=True)
subprocess.run(['adb','push','probe/probe.jar','/data/local/tmp/studio-class-probe.jar'],check=True)
subprocess.run(['adb','shell','chmod','444','/data/local/tmp/studio-class-probe.jar'],check=True)
p=text(['shell','pm','path','com.forcemind.chatgpt.profiles']);assert p.startswith('package:') and '\n' not in p
p=p[len('package:'):];assert re.fullmatch(r'/data/app/[A-Za-z0-9_~./+=-]+/base\.apk',p)
command='CLASSPATH=/data/local/tmp/studio-class-probe.jar app_process / com.forcemind.chatgpt.pocinput.OptimizationClassProbe '+shlex.quote(p)
v=subprocess.run(['adb','shell',command],text=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
Path('probe/evidence/stdout.txt').write_text(v.stdout);Path('probe/evidence/stderr.txt').write_text(v.stderr)
assert v.returncode==0
value=json.loads(v.stdout);assert value['complete'] and value['webpagesOpened']==0
Path('probe/evidence/classes.json').write_text(json.dumps({'sourceSHA':r['sourceSHA'],'sourceRun':int(os.environ['SOURCE_RUN']),'apkSHA256':r['sha256'],**value},indent=2)+'\n')
print(json.dumps(value))
