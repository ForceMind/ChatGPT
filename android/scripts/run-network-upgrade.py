"""Same temporary CI signer, old-code install then new-code replacement, synthetic private data only."""
import argparse,json,os,re,shlex,subprocess
from pathlib import Path
PACKAGE='com.forcemind.chatgpt.profiles.debug'
def run(*args,timeout=120):return subprocess.check_output(list(args),text=True,timeout=timeout)
def phase(name,source,output):
 command=['am','instrument','-w','-r','-e','class','com.forcemind.chatgpt.profiles.NetworkUpgradeCompatibilityTest','-e','phase',name,'-e','sourceSHA',source,PACKAGE+'.test/androidx.test.runner.AndroidJUnitRunner']
 text=run('adb','shell',' '.join(shlex.quote(v) for v in command),timeout=180);(output/(name+'-instrumentation.txt')).write_text(text);print(text)
 raw=run('adb','exec-out','run-as',PACKAGE,'cat','files/upgrade-result-'+name+'.json',timeout=20);(output/(name+'-result.json')).write_text(raw)
 assert re.findall(r'(?m)^OK \((\d+) test\)\s*$',text)==['1'] and not re.search(r'FAILURES!!!|INSTRUMENTATION_FAILED|shortMsg=',text)
 proof=json.loads(raw);assert proof['complete'] and proof['phase']==name and proof['sourceSHA']==source and proof['screenshots'] is False and proof['networkRequests']==0 and proof['inlineProfileDocuments']==(6 if name=='seed' else 3)
 return proof
p=argparse.ArgumentParser();p.add_argument('--inputs',required=True);p.add_argument('--output',required=True);a=p.parse_args()
assert os.environ.get('GITHUB_ACTIONS')=='true' and run('adb','shell','getprop','ro.kernel.qemu').strip()=='1'
folder=Path(a.inputs);output=Path(a.output);output.mkdir(parents=True,exist_ok=True)
run('adb','install','-r','-t',str(folder/'baseline-debug.apk'));run('adb','install','-r','-t',str(folder/'baseline-test.apk'))
assert 'Success' in run('adb','shell','pm','clear',PACKAGE)
before=phase('seed','b44e564deaed09efea7be3d68349d2198dede46b',output)
run('adb','shell','am','force-stop',PACKAGE)
# No uninstall or data clearing between these steps.
run('adb','install','-r','-t',str(folder/'candidate-debug.apk'))
after=phase('verify',os.environ['GITHUB_SHA'],output)
assert before['signingSHA256']==after['signingSHA256'] and before['api']==after['api']
assert before['versionCode']==3000022 and after['versionCode']==3000024 and after['versionCode']>before['versionCode']
assert after['accounts']==3 and after['tabs']==4
(output/'verified.json').write_text(json.dumps({'complete':True,'sourceSHA':os.environ['GITHUB_SHA'],'api':after['api'],'sameTemporaryCISignature':True,'productionSignatureVerified':False,'preserved':['workspace','profile names','cookies','encrypted drafts and handoff identity','asset bytes','background preferences'],'networkRequests':0,'inlineProfileDocuments':3,'screenshots':False,'personalData':False},indent=2)+'\n')
