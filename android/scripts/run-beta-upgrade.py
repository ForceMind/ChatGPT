"""Fresh CI emulator only; replace fixed-signer synthetic seed with higher-code beta, no uninstall/clear."""
import json,os,re,subprocess
from pathlib import Path
assert os.environ.get('GITHUB_ACTIONS')=='true'
def run(args):return subprocess.check_output(args,text=True,timeout=120)
assert run(['adb','shell','getprop','ro.kernel.qemu']).strip()=='1'
root=Path('android/app/build/beta-upgrade-evidence');root.mkdir(parents=True,exist_ok=True)
inputs=Path('android/app/build/beta-upgrade-inputs');signer=json.loads(Path('docs/signing/android-network-beta.json').read_text())['certificateSHA256'];package='com.forcemind.chatgpt.profiles.networkbeta'
for name in ['seed-x86_64.apk','candidate-test.apk']:run(['adb','install','-r','-t',str(inputs/name)])
def phase(name):
 text=run(['adb','shell','am','instrument','-w','-r','-e','class','com.forcemind.chatgpt.profiles.BetaUpgradeTest','-e','phase',name,'-e','signer',signer,package+'.test/androidx.test.runner.AndroidJUnitRunner']);(root/(name+'.txt')).write_text(text);print(text)
 assert re.findall(r'(?m)^OK \((\d+) test\)\s*$',text)==['1'];assert not re.search(r'FAILURES!!!|INSTRUMENTATION_FAILED|shortMsg=',text)
 proof=json.loads(re.search(r'BETA_UPGRADE_RESULT (\{[^\n]+\})',text)[1]);assert proof['complete'] and proof['phase']==name and proof['signerSHA256']==signer and proof['browserStarted'] is False and proof['realNetworkRequests']==0;return proof
before=phase('seed');run(['adb','shell','am','force-stop',package]);run(['adb','install','-r',str(inputs/'candidate-x86_64.apk')]);after=phase('verify');assert before['uid']==after['uid'] and before['versionCode']==3000024 and after['versionCode']==3000025
(root/'verified.json').write_text(json.dumps({'complete':True,'sourceSHA':os.environ['GITHUB_SHA'],'before':before,'after':after,'uninstallOrClearBetweenVersions':False,'nativeDataOnly':True,'webViewCookiesExercised':False,'personalData':False},indent=2)+'\n')
