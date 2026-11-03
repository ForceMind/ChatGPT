"""Real formal activity on a fresh CI emulator; no screenshots or external service access."""
import argparse,json,os,re,shlex,subprocess
from pathlib import Path
PACKAGE='com.forcemind.chatgpt.profiles.networkci'
def adb(*args,timeout=30):
 return subprocess.check_output(['adb',*args],text=True,timeout=timeout)
def main():
 p=argparse.ArgumentParser();p.add_argument('--transport',choices=['raw','tls','reality'],required=True);p.add_argument('--output',required=True);a=p.parse_args()
 assert os.environ.get('GITHUB_ACTIONS')=='true' and adb('shell','getprop','ro.kernel.qemu').strip()=='1'
 output=Path(a.output);output.mkdir(parents=True,exist_ok=True)
 fixture=json.loads(Path('android/network-poc/secure-fixture/fixture.json').read_text());assert json.loads(Path('android/network-poc/secure-fixture/ready.json').read_text())['ready']
 node=fixture['transports'][a.transport]
 # Clearing is confined to this synthetic CI identity on the disposable emulator.
 assert PACKAGE.endswith('.networkci');assert 'Success' in adb('shell','pm','clear',PACKAGE)
 arguments={'class':'com.forcemind.chatgpt.profiles.NetworkIntegrationTest','nodeA':node['nodeA'],'nodeB':node['nodeB'],'transport':a.transport,'origin':'https://network-fixture.test:18480','sourceSHA':os.environ['GITHUB_SHA']}
 command=['am','instrument','-w','-r']
 for key,value in arguments.items():command+=['-e',key,value]
 command+=[PACKAGE+'.test/androidx.test.runner.AndroidJUnitRunner']
 result=subprocess.run(['adb','shell',' '.join(shlex.quote(x) for x in command)],text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=360)
 text=result.stdout
 for secret in (node['nodeA'],node['nodeB']):text=text.replace(secret,'<synthetic-node>')
 (output/'instrumentation.txt').write_text(text);print(text)
 read=subprocess.run(['adb','exec-out','run-as',PACKAGE,'cat','files/formal-network-result.json'],text=True,capture_output=True,timeout=15)
 if read.returncode==0:(output/'result.json').write_text(read.stdout)
 assert result.returncode==0 and re.findall(r'(?m)^OK \((\d+) test\)\s*$',text)==['1'] and not re.search(r'FAILURES!!!|INSTRUMENTATION_FAILED|shortMsg=',text),'Formal client test failed; see actual report/log'
 assert read.returncode==0
 evidence=json.loads(read.stdout);assert evidence['complete'] is True and evidence['sourceSHA']==os.environ['GITHUB_SHA'] and evidence['transport']==a.transport
 assert evidence['disabledCoreUnloaded'] and evidence['recreatedSameViews'] and evidence['retryDidNotReload'] and evidence['failedCoreBlockedAllViews']
 assert evidence['screenshots'] is False and evidence['realChatGPT'] is False and evidence['api'] in (28,35)
 assert len(evidence['files'])==12 and len(set(evidence['profileNames'].values()))==3
 events=[]
 for line in Path(os.environ['RUNNER_TEMP'],'formal-network-fixture.log').read_text().splitlines():
  try:e=json.loads(line)
  except json.JSONDecodeError:continue
  if isinstance(e,dict) and e.get('event')=='owned-origin-access' and e.get('path','').startswith('/joint/formal-'+a.transport+'-'):events.append(e)
 expected=evidence['expectedRequests'];assert len(expected)==17 and len(events)==len(expected)
 for item in expected:assert len([e for e in events if all(e.get(k)==v for k,v in item.items())])==1,'Missing/replayed/mismatched formal request: '+item['path']
 (output/'verified.json').write_text(json.dumps({'complete':True,'sourceSHA':os.environ['GITHUB_SHA'],'api':evidence['api'],'transport':a.transport,'exactRequests':len(events),'successfulFiles':12,'screenshots':False,'realChatGPT':False},indent=2)+'\n')
 print('FORMAL_NETWORK_VERIFIED '+json.dumps(evidence))
if __name__=='__main__':main()
