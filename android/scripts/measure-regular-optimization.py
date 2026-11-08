"""Synthetic regular-client foreground samples, only on fresh Actions emulators."""
import hashlib,importlib.util,json,os,re,statistics,subprocess,time,traceback
from pathlib import Path

ROOT=Path(__file__).resolve().parents[2]
OUT=ROOT/'android/app/build/regular-resource-evidence'
PACKAGE='com.forcemind.chatgpt.profiles'


def module(name,path):
    spec=importlib.util.spec_from_file_location(name,path);value=importlib.util.module_from_spec(spec);spec.loader.exec_module(value);return value


def write(path,value):
    path.parent.mkdir(parents=True,exist_ok=True);path.write_text(json.dumps(value,indent=2)+'\n')


def configure():
    assert os.environ.get('GITHUB_ACTIONS')=='true' and os.environ.get('GITHUB_REPOSITORY')=='ForceMind/ChatGPT'
    OUT.mkdir(parents=True,exist_ok=True)
    origin=json.loads(Path('android/app/build/resource-origin.json').read_text())
    assert origin['complete'] is True
    # Keep the real GITHUB_SHA as the harness identity. Bind only the APK source
    # guard to the separately API-verified successful parent workflow.
    path=Path('android/scripts/regular_environment_upgrade.py');old=path.read_bytes();text=old.decode()
    needle="assert receipt['sourceSHA'] == os.environ['GITHUB_SHA']"
    assert text.count(needle)==1
    text=text.replace(needle,"assert receipt['sourceSHA'] == os.environ['STUDIO_RESOURCE_APP_SOURCE_SHA']")
    path.write_text(text)
    edits=[{'path':str(path),'before':hashlib.sha256(old).hexdigest(),'after':hashlib.sha256(path.read_bytes()).hexdigest()}]
    path=Path('android/scripts/environment-fixture.py');old=path.read_bytes();text=old.decode()
    needle='const config = __CONFIG__;';assert text.count(needle)==1
    text=text.replace(needle,needle+"\nlet resourceRevisions=0;")
    needle='storageKey=STORE_KEY, fixedDate=FIXED_DATE)';assert text.count(needle)==1
    text=text.replace(needle,"storageKey=STORE_KEY, fixedDate=FIXED_DATE, resourceMode=os.environ['STUDIO_RESOURCE_LOAD'])")
    needle='eventSequence:++eventSequence';assert text.count(needle)==1
    text=text.replace(needle,'resourceMode:config.resourceMode,resourceRevisions,eventSequence:++eventSequence')
    needle='"eventSequence", "sentEvents", "capturedAt")';assert text.count(needle)==1
    text=text.replace(needle,'"eventSequence", "sentEvents", "capturedAt", "resourceMode", "resourceRevisions")')
    needle='    editor = (';assert text.count(needle)==1
    text=text.replace(needle,"    script += \"\\nif(config.resourceMode==='stream')setInterval(()=>{const p=reading.querySelector('article p');if(!p)throw Error('Resource paragraph missing');p.textContent='Synthetic streaming revision '+(++resourceRevisions)+' '+('x'.repeat(2048));queue();},500);\"\n"+needle)
    path.write_text(text)
    edits.append({'path':str(path),'before':hashlib.sha256(old).hexdigest(),'after':hashlib.sha256(path.read_bytes()).hexdigest()})
    write(OUT/'test-overlay.json',{'sourceOrigin':origin,'harnessSHA':os.environ['GITHUB_SHA'],'ciOnly':True,'edits':edits})
    os.environ['STUDIO_RESOURCE_APP_SOURCE_SHA']=origin['appSourceSHA']
    return origin


def owned_processes(raw,parsers):
    processes,_,excluded=parsers.attributable_processes(raw,PACKAGE)
    starts=list(re.finditer(r'^\s*\*(?:APP|PERS|ISOLATED)\*[^\n]*ProcessRecord\{[^\n]*',raw,re.M))
    gecko=[]
    for index,start in enumerate(starts):
        block=raw[start.start():starts[index+1].start() if index+1<len(starts) else len(raw)]
        identity=re.search(r'ProcessRecord\{\S+\s+(\d+):([^/\s}]+)',start.group())
        assert identity is not None
        pid,name=int(identity[1]),identity[2]
        service_owned=re.search(re.escape(PACKAGE)+r'/[^\s}\]]*GeckoChildProcessServices[^\s}\]]*',block)
        if (name.startswith(PACKAGE+':') and ('tab' in name or 'gecko' in name.lower())) or service_owned and name!=PACKAGE:
            gecko.append(pid)
            if pid not in {p['pid'] for p in processes}:processes.append({'pid':pid,'name':name,'kind':'gecko_renderer','clientPackage':PACKAGE})
    assert any(p['name']==PACKAGE for p in processes) and gecko,'Complete app and owned Gecko renderer attribution required'
    assert len({p['pid'] for p in processes})==len(processes)
    return sorted(processes,key=lambda p:p['pid']),gecko,excluded


def main():
    os.chdir(ROOT);origin=configure()
    native=module('regular_resource_native',ROOT/'android/scripts/regular_environment_upgrade.py')
    flow=module('regular_resource_flow',ROOT/'android/scripts/run-environment-client.py')
    flow.native=native;flow.PACKAGE=PACKAGE;flow.FILES='/data/user/0/'+PACKAGE+'/files/'
    parsers=module('regular_resource_parsers',ROOT/'android/network-poc/scripts/measure-resources.py')
    result={'schema':1,'complete':False,'sourceOrigin':origin,'harnessSHA':os.environ['GITHUB_SHA'],'mode':origin['mode'],'cases':[],
            'windowSeconds':120,'warmupSeconds':30,'windowsPerCase':2,'sampleIntervalSeconds':5,
            'scope':'Synthetic foreground Gecko pages with the unchanged 500ms observation transport; not physical-device or real ChatGPT performance',
            'syntheticCasesIndependent':True,'dataRetentionCoveredByParentFunctionalGates':True,'backgroundPolicyChanged':False,'screenshots':False,'proxyEnabled':False}
    sampler=parsers.Sampler(OUT,120,30,5)
    native._guard()
    auxv=subprocess.check_output(['adb','exec-out','cat','/proc/self/auxv']);hz,width=parsers.parse_auxv_clktck(auxv)
    result['environment']={'api':native._shell('getprop','ro.build.version.sdk'),'fingerprint':native._shell('getprop','ro.build.fingerprint'),'clockTicksPerSecond':hz,'auxvWordBytes':width}
    (OUT/'kernel-auxv.bin').write_bytes(auxv)
    active=None
    try:
        for pages,load in [(1,'idle'),(1,'stream'),(4,'idle'),(4,'stream')]:
            name=str(pages)+'-'+load;folder=OUT/name;folder.mkdir()
            native.EVIDENCE=folder/'setup';flow.EVIDENCE=native.EVIDENCE;native.EVIDENCE.mkdir()
            os.environ['STUDIO_RESOURCE_LOAD']=load
            upgrade=native.run_upgrade('synthetic')
            active=flow.Acceptance(upgrade);active.tls_server();active.prepare_profiles()
            targets=[('A',0)] if pages==1 else [('A',0),('A',1),('B',0),('C',0)]
            for account,tab in targets:active.open(account,tab)
            active.open('A',0)
            def observe():
                state=active.status();observed={}
                for account,tab in targets:
                    path=flow.PAGE[(account,tab)];row=active.latest(state,path)
                    assert row and row['resourceMode']==load and row['sendClicks']==0
                    observed[path]={k:row[k] for k in ['document','draft','modelDraft','resourceRevisions','eventSequence']}
                assert sum(state['pageCounts'].values())==pages,'Unexpected extra page loads'
                return observed
            initial=observe();case={'pages':pages,'load':load,'initialDocuments':initial,'windows':[]}
            for window in range(2):
                time.sleep(30);before=observe();samples=[];start=time.monotonic()
                for index in range(25):
                    time.sleep(max(0,start+index*5-time.monotonic()))
                    activity=sampler.service('activity','processes');prefix=f'{window}-{index}'
                    (folder/(prefix+'-processes.txt')).write_text(activity)
                    processes,gecko,excluded=owned_processes(activity,parsers)
                    for p in processes:
                        raw=sampler.shell('cat','/proc/'+str(p['pid'])+'/stat');(folder/(prefix+'-'+str(p['pid'])+'-stat.txt')).write_text(raw)
                        p.update(parsers.parse_proc_stat(raw));p['counterTimeMonotonic']=time.monotonic()
                        raw=sampler.service('meminfo','-s',str(p['pid']));(folder/(prefix+'-'+str(p['pid'])+'-meminfo.txt')).write_text(raw)
                        p['pssKiB']=parsers.parse_pss(raw)
                    samples.append({'timeMonotonic':time.monotonic(),'processes':processes,'geckoPIDs':gecko,'excludedUnrelated':excluded})
                    write(folder/(str(window)+'-samples.json'),samples)
                    if index==0:start=samples[0]['timeMonotonic']
                after=observe();elapsed=samples[-1]['timeMonotonic']-samples[0]['timeMonotonic'];assert elapsed>=120
                first={p['pid']:p for p in samples[0]['processes']};cpu={pid:0.0 for pid in first}
                for old,new in zip(samples,samples[1:]):
                    a={p['pid']:p for p in old['processes']};b={p['pid']:p for p in new['processes']}
                    assert a.keys()==b.keys()==first.keys(),'Process set changed; preserve raw samples, fail incomplete CPU sum'
                    for pid in a:cpu[pid]+=parsers.cpu_interval(a[pid],b[pid],hz)['cpuCoreSeconds']
                revisions={}
                for path,old in before.items():
                    new=after[path];assert old['document']==new['document']==initial[path]['document']
                    assert old['draft']==new['draft'] and old['modelDraft']==new['modelDraft']
                    assert new['eventSequence']>old['eventSequence'],'Loaded page no longer reporting'
                    revisions[path]=new['resourceRevisions']-old['resourceRevisions']
                    assert revisions[path]>0 if load=='stream' else revisions[path]==0
                case['windows'].append({'durationSeconds':elapsed,'cpuCoreSeconds':sum(cpu.values()),'cpuPercentOneCore':sum(cpu[pid]/(next(p['counterTimeMonotonic'] for p in samples[-1]['processes'] if p['pid']==pid)-first[pid]['counterTimeMonotonic'])*100 for pid in first),
                    'pssMeanMiB':statistics.mean(sum(p['pssKiB'] for p in s['processes'])/1024 for s in samples),
                    'pssPeakMiB':max(sum(p['pssKiB'] for p in s['processes'])/1024 for s in samples),'streamRevisions':revisions,'documentsRetained':True,'draftsRetained':True})
                write(folder/'case.json',case)
            result['cases'].append(case);write(OUT/'report.json',result)
            active.close();active=None
            # End a complete independent synthetic trial. Never clear data in an
            # upgrade path; the next trial starts with the pinned old APK again.
            receipt=json.loads(Path('android/app/build/regular-environment-input/receipt.json').read_text())
            location=native._shell('pm','path',PACKAGE).strip();assert location.startswith('package:') and '\n' not in location
            installed_sha=native._shell('sha256sum',location[len('package:'):]).split()[0]
            assert installed_sha==receipt['sha256'],'Refuse cleanup of a non-owned APK'
            for package in [PACKAGE+'.test',PACKAGE]:
                assert native._run(['adb','uninstall',package]).returncode==0
        result['complete']=True
    except BaseException:
        result['firstError']=traceback.format_exc();raise
    finally:
        if active is not None:active.close()
        write(OUT/'report.json',result)


if __name__=='__main__':main()
