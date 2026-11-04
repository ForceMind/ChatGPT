"""Paired old/new formal-client CPU/PSS on fresh CI emulators, reusing strict native counter attribution."""
import argparse,importlib.util,json,os,shlex,subprocess,time,traceback
from pathlib import Path
SPEC=importlib.util.spec_from_file_location('owned_sampler',Path(__file__).resolve().parents[1]/'network-poc/scripts/measure-resources.py')
base=importlib.util.module_from_spec(SPEC);SPEC.loader.exec_module(base)
TEST='com.forcemind.chatgpt.profiles.NetworkBenchmarkTest#syntheticThreeProfileResourceMatrix'
class FormalSampler(base.Sampler):
 def __init__(self,*args,mode,source,**kwargs):super().__init__(*args,**kwargs);self.mode=mode;self.source=source
 def preflight(self):
  try:metadata=super().preflight()
  except RuntimeError as failure:
   if 'adb root failed' not in str(failure) or 'closed' not in str(failure):raise
   self.adb('wait-for-device',timeout=30)
   if self.shell('id','-u').strip()!='0':raise
   metadata=super().preflight()
  self.guest_log=(self.output/'guest-logcat.txt').open('w');self.guest_reader=subprocess.Popen(['adb','logcat','-v','threadtime'],stdout=self.guest_log,stderr=subprocess.STDOUT)
  metadata['samplerSourceSha']=os.environ['GITHUB_SHA'];metadata['sourceSha']=self.source;metadata['mode']=self.mode;metadata['proxyTransport']={'configuredNode':'VLESS raw/none only for enabled control probe','activeDuringWindow':self.mode in ('enabled','background'),'webpageTraffic':'offline interceptor, no network stream'}
  (self.output/'environment.json').write_text(json.dumps(metadata,indent=2)+'\n');return metadata
 def launch(self):
  self.shell('run-as',base.PACKAGE,'rm','-f','files/resource-phase.json','files/resource-result.json','files/resource-ack')
  self.log=(self.output/'instrumentation.txt').open('w')
  args=['am','instrument','-w','-r','-e','class',TEST,'-e','resourceSampler','1','-e','resourceWindowSeconds',str(self.window),'-e','mode',self.mode,'-e','sourceSHA',self.source,'-e','nodeA',self.fixture['transports']['raw']['nodeA'],base.PACKAGE+'.test/androidx.test.runner.AndroidJUnitRunner']
  self.instrumentation=subprocess.Popen(['adb','shell',' '.join(shlex.quote(x) for x in args)],stdout=self.log,stderr=subprocess.STDOUT)
 def wait_phase(self,name):
  try:return super().wait_phase(name)
  except Exception:
   if self.log:self.log.flush()
   file=self.output/'instrumentation.txt'
   if file.exists():print(file.read_text()[-9000:],flush=True)
   raise
 def cleanup(self):
  for name in ['resource-load-evidence.json','resource-native-maps.txt','resource-background-heartbeats.json','resource-result.json','resource-task-preflight.json']:
   try:(self.output/name).write_text(self.phase_file(name))
   except Exception:pass
  super().cleanup()
  if hasattr(self,'guest_reader'):
   self.guest_reader.terminate();self.guest_reader.wait(timeout=10);self.guest_log.close()
 def finish(self):
  result=super().finish();assert result['mode']==self.mode and result['sourceSHA']==self.source and result['screenshots'] is False and result['realChatGPTRequests']==0
  assert all(p['liveDocuments']==4 and p['noReload'] for p in result['phases'])
  if self.mode!='background':assert result['phases'][1]['chunks']==600
  return result
p=argparse.ArgumentParser();p.add_argument('--output',required=True);p.add_argument('--inputs',required=True);p.add_argument('--background',action='store_true');a=p.parse_args()
assert os.environ.get('GITHUB_ACTIONS')=='true'
root=Path(a.output);root.mkdir(parents=True,exist_ok=True)
assert subprocess.check_output(['adb','shell','getprop','ro.kernel.qemu'],text=True).strip()=='1'
fixture=json.loads(Path('android/network-poc/secure-fixture/fixture.json').read_text())
original_attribution=base.attributable_processes
modes=['background'] if a.background else ['baseline','system','enabled','warm-system']
reports=[]
for mode in modes:
 package='com.forcemind.chatgpt.profiles.debug' if mode=='baseline' else 'com.forcemind.chatgpt.profiles.networkci'
 other='com.forcemind.chatgpt.profiles.networkci.test' if mode=='baseline' else 'com.forcemind.chatgpt.profiles.debug.test'
 # Test-only providers use one legacy authority; install one test family at a time.
 present=subprocess.run(['adb','shell','pm','path',other],text=True,capture_output=True)
 if present.returncode==0 and 'package:' in present.stdout:subprocess.run(['adb','uninstall',other],check=True,capture_output=True)
 apk=Path(a.inputs)/('baseline-debug.apk' if mode=='baseline' else 'candidate-networkci.apk')
 test=Path(a.inputs)/('baseline-test.apk' if mode=='baseline' else 'candidate-networkci-test.apk')
 for path in (apk,test):subprocess.run(['adb','install','-r','-t',str(path)],check=True,capture_output=True)
 base.PACKAGE=package;base.TEST=TEST;base.PHASES=['background'] if a.background else ['idle','stream']
 base.attributable_processes=lambda raw:original_attribution(raw,package)
 folder=root/mode;folder.mkdir();source='b44e564deaed09efea7be3d68349d2198dede46b' if mode=='baseline' else os.environ.get('NETWORK_SOURCE_SHA',os.environ['GITHUB_SHA'])
 # Every case begins cold, with only synthetic data. No personal package/device is accepted.
 for target in ['com.forcemind.chatgpt.profiles.debug','com.forcemind.chatgpt.profiles.networkci']:subprocess.run(['adb','shell','am','force-stop',target],check=True,capture_output=True)
 subprocess.run(['adb','shell','pm','clear',package],check=True,capture_output=True)
 if a.background:subprocess.run(['adb','shell','pm','grant',package,'android.permission.POST_NOTIFICATIONS'],check=False,capture_output=True)
 sampler=FormalSampler(folder,1800 if a.background else 60,5,60 if a.background else 10,fixture=fixture,mode=mode,source=source)
 report={'complete':False,'mode':mode,'sourceSHA':source,'phases':[],'scope':'3 actual profiles / 4 offline documents; idle + fixed 600-chunk DOM stream and idle core. Not real ChatGPT or network streaming cost.'}
 try:
  report['environment']=sampler.preflight();sampler.launch()
  for name in base.PHASES:report['phases'].append(sampler.measure(name))
  report['deviceResult']=sampler.finish();report['complete']=True
 except Exception as failure:
  original=folder/'command-failure.json'
  if original.exists():(folder/'first-command-failure.json').write_bytes(original.read_bytes())
  report['error']={'message':str(failure),'traceback':traceback.format_exc()};raise
 finally:
  sampler.cleanup();(folder/'report.json').write_text(json.dumps(report,indent=2)+'\n')
 reports.append(report)
if len(reports)>1:
 identity=reports[0]['environment']['webViewIdentity'];assert all(r['environment']['webViewIdentity']==identity for r in reports)
 assert reports[0]['deviceResult']['goLoaded'] is False and reports[1]['deviceResult']['goLoaded'] is False and reports[2]['deviceResult']['goLoaded'] is True and reports[3]['deviceResult']['goLoaded'] is True
(root/'comparison.json').write_text(json.dumps({'complete':True,'sourceSHA':os.environ.get('NETWORK_SOURCE_SHA',os.environ['GITHUB_SHA']),'samplerSourceSHA':os.environ['GITHUB_SHA'],'reports':reports,'sameEmulatorSequential':True,'forcedGC':False,'screenshots':False,'realChatGPTRequests':0,'limitations':['Instrumentation, sampling and sequential caches influence values','Only app + attributable WebView renderer PSS/CPU, not shared system totals','Offline DOM workload measures shell/retention/core overhead, not real-service network cost','Warm stopped case retains the initialized Go runtime; cold disabled does not load it']},indent=2)+'\n')
