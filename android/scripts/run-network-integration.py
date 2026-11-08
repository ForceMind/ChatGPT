"""Real formal activity on a fresh CI emulator; no screenshots or external service access."""
import argparse,json,os,re,shlex,subprocess
from pathlib import Path
REGULAR=os.environ.get('STUDIO_ANDROID_CI_TARGET')=='regular'
PACKAGE='com.forcemind.chatgpt.profiles' if REGULAR else 'com.forcemind.chatgpt.profiles.networkci'
def adb(*args,timeout=30):
 return subprocess.check_output(['adb',*args],text=True,timeout=timeout)
def report_command(name):
 assert name in ('formal-network-result.json','formal-network-files.json')
 return ['adb','exec-out','cat','/data/user/0/'+PACKAGE+'/files/'+name] if REGULAR else ['adb','exec-out','run-as',PACKAGE,'cat','files/'+name]
def main():
 p=argparse.ArgumentParser();p.add_argument('--transport',choices=['raw','tls','reality'],required=True);p.add_argument('--output',required=True);p.add_argument('--regular-files-only',action='store_true');a=p.parse_args()
 assert a.regular_files_only is REGULAR, 'Explicit regular scope required; legacy default remains unchanged'
 assert os.environ.get('GITHUB_ACTIONS')=='true' and adb('shell','getprop','ro.kernel.qemu').strip()=='1'
 output=Path(a.output);output.mkdir(parents=True,exist_ok=True)
 fixture=json.loads(Path('android/network-poc/secure-fixture/fixture.json').read_text());assert json.loads(Path('android/network-poc/secure-fixture/ready.json').read_text())['ready']
 node=fixture['transports'][a.transport]
 # Clearing is confined to this synthetic CI identity on the disposable emulator.
 if not REGULAR:
  assert PACKAGE.endswith('.networkci');assert 'Success' in adb('shell','pm','clear',PACKAGE)
 else:
  assert PACKAGE=='com.forcemind.chatgpt.profiles' and adb('shell','id','-u').strip()=='0'
 arguments={'class':'com.forcemind.chatgpt.profiles.NetworkIntegrationTest','nodeA':node['nodeA'],'nodeB':node['nodeB'],'transport':a.transport,'origin':'https://network-fixture.test:18480','sourceSHA':os.environ['GITHUB_SHA']}
 command=['am','instrument','-w','-r']
 for key,value in arguments.items():command+=['-e',key,value]
 command+=[PACKAGE+'.test/androidx.test.runner.AndroidJUnitRunner']
 result=subprocess.run(['adb','shell',' '.join(shlex.quote(x) for x in command)],text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=360)
 text=result.stdout
 for secret in (node['nodeA'],node['nodeB']):text=text.replace(secret,'<synthetic-node>')
 (output/'instrumentation.txt').write_text(text);print(text)
 read=subprocess.run(report_command('formal-network-result.json'),text=True,capture_output=True,timeout=15)
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
 subprocess.run(['adb','shell','am','force-stop',PACKAGE],check=True,capture_output=True,timeout=15)
 file_arguments=dict(arguments,**{'class':'com.forcemind.chatgpt.profiles.NetworkFileSafetyTest'})
 file_command=['am','instrument','-w','-r']
 for key,value in file_arguments.items():file_command+=['-e',key,value]
 file_command+=[PACKAGE+'.test/androidx.test.runner.AndroidJUnitRunner']
 result=subprocess.run(['adb','shell',' '.join(shlex.quote(x) for x in file_command)],text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=420)
 safe=result.stdout
 for secret in (node['nodeA'],node['nodeB']):safe=safe.replace(secret,'<synthetic-node>')
 (output/'file-safety-instrumentation.txt').write_text(safe);print(safe)
 read=subprocess.run(report_command('formal-network-files.json'),text=True,capture_output=True,timeout=15)
 if read.returncode==0:(output/'file-safety.json').write_text(read.stdout)
 assert result.returncode==0 and re.findall(r'(?m)^OK \((\d+) test\)\s*$',safe)==['1'] and not re.search(r'FAILURES!!!|INSTRUMENTATION_FAILED|shortMsg=',safe),'File and system routing test failed'
 assert read.returncode==0
 proof=json.loads(read.stdout);assert proof['complete'] and proof['sourceSHA']==os.environ['GITHUB_SHA']
 assert proof['systemPolicy']['restored'] and proof['systemPolicy']['roleRemoved'] and proof['systemPolicy']['restoredProxyInfo']==proof['systemPolicy']['originalProxy']
 assert proof['accountClear']['assetBytesRetained'] and proof['accountClear']['otherAccountStillTransferring'] and proof['accountClear']['oldCallbackInvalid']
 assert proof['realSystemPicker'] and proof['writePermissionDenied'] and proof['lateSelectionIgnored'] and proof['routeChangeBlockedWhileSelecting'] and proof['syntheticAutomationGuard']
 assert proof['publisherFailure']['actualPrefixBytes']==4096 and proof['publisherFailure']['newPartialRemoved'] and proof['publisherFailure']['existingDocumentPreserved']
 assert proof['cancelledDocumentMillis']<=10000 and proof['closedOwner']['elapsedMillis']<=10000 and proof['closedOwner']['partialRemoved'] and proof['closedOwner']['otherTransferPreserved'] and proof['closedOwner']['newRequestRejected']
 raw=Path(os.environ['RUNNER_TEMP'],'formal-network-fixture.log').read_text();events=[];gates=[]
 for line in raw.splitlines():
  try:e=json.loads(line)
  except json.JSONDecodeError:continue
  if not isinstance(e,dict):continue
  if e.get('event')=='owned-origin-access' and e.get('path','').startswith('/joint/formal-files-'+a.transport+'-'):events.append(e)
  if e.get('event')=='owned-transfer-summary' and e.get('opid','').startswith(a.transport+'-https-formal-'):gates.append(e)
 assert len(proof['expectedRequests'])==32 and len(events)==32
 for item in proof['expectedRequests']:assert len([e for e in events if all(e.get(k)==v for k,v in item.items())])==1,'File/UI/system route request mismatch: '+item['path']
 assert len(gates)==len(proof['gateFinal'])==5
 for state in proof['gateFinal']:
  assert state['opCount']==state['attemptCount']==1 and state['released'] is True and state['timedOut'] is False and state['route']=='A'
  assert len([e for e in gates if e['opid']==state['opid'] and e['opCount']==1 and e['syntheticAccount']==state['syntheticAccount']])==1
 (output/'file-safety-verified.json').write_text(json.dumps({'complete':True,'sourceSHA':os.environ['GITHUB_SHA'],'api':proof['api'],'transport':a.transport,'exactRequests':32,'gatedOperations':5,'realSystemPicker':True,'proxyRestored':True,'temporaryOwnerRemoved':True,'screenshots':False,'realChatGPT':False},indent=2)+'\n')
 print('FORMAL_FILE_SAFETY_VERIFIED '+json.dumps(proof))
 if REGULAR:
  (output/'regular-webview-files-scope.json').write_text(json.dumps({'complete':True,'package':PACKAGE,'scope':'network-and-file-safety','restartSuiteRepeated':False,'sourceSHA':os.environ['GITHUB_SHA'],'screenshots':False,'realChatGPT':False},indent=2)+'\n')
  return
 # Role/proxy restoration was verified above before ending this CI process.
 subprocess.run(['adb','shell','am','force-stop',PACKAGE],check=True,capture_output=True,timeout=15)
 restore=[]
 for phase in ['seed','verify']:
  command=['am','instrument','-w','-r','-e','class','com.forcemind.chatgpt.profiles.NetworkRestartTest','-e','phase',phase,'-e','sourceSHA',os.environ['GITHUB_SHA'],PACKAGE+'.test/androidx.test.runner.AndroidJUnitRunner']
  result=subprocess.run(['adb','shell',' '.join(shlex.quote(x) for x in command)],text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=180)
  (output/('restart-'+phase+'-instrumentation.txt')).write_text(result.stdout);print(result.stdout)
  read=subprocess.run(['adb','exec-out','run-as',PACKAGE,'cat','files/network-restart-'+phase+'.json'],text=True,capture_output=True,timeout=15)
  if read.returncode==0:(output/('restart-'+phase+'.json')).write_text(read.stdout)
  assert result.returncode==0 and re.findall(r'(?m)^OK \((\d+) test\)\s*$',result.stdout)==['1'] and not re.search(r'FAILURES!!!|INSTRUMENTATION_FAILED|shortMsg=',result.stdout),'Cold restart test failed'
  assert read.returncode==0
  result=json.loads(read.stdout);assert result['complete'] and result['sourceSHA']==os.environ['GITHUB_SHA'] and result['screenshots'] is False;restore.append(result)
  subprocess.run(['adb','shell','am','force-stop',PACKAGE],check=True,capture_output=True,timeout=15)
 assert restore[0]['pid']!=restore[1]['pid'] and restore[1]['draftActualDOMMatches'] and restore[1]['cookiePreserved'] and restore[1]['profilePreserved'] and restore[1]['deferredClearedProfileRemoved'] and restore[1]['selectedRouteRestored']
 (output/'restart-verified.json').write_text(json.dumps({'complete':True,'sourceSHA':os.environ['GITHUB_SHA'],'api':evidence['api'],'transport':a.transport,'newProcess':True,'sameRamDocumentClaim':False,'actualDOMDraft':True,'selectedRouteRestored':True,'realChatGPTRequests':0,'screenshots':False},indent=2)+'\n')

if __name__=='__main__':main()
