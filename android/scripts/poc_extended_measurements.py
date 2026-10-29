"""Synthetic CI-only warning controls and prolonged background measurements."""
import hashlib
import io
import json
import pathlib
import re
import time
import urllib.request
import zipfile

SOURCE_URL='https://maven.mozilla.org/maven2/org/mozilla/geckoview/geckoview/158.0.20261021001945/geckoview-158.0.20261021001945-sources.jar'
SOURCE_SHA='f78fd393bd774f46dbf55ea8e868b7607d5c4c133d9906eab000c339bbee02ac'

def collect_source(out):
    data=urllib.request.urlopen(SOURCE_URL,timeout=60).read()
    assert hashlib.sha256(data).hexdigest()==SOURCE_SHA
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        names=[n for n in archive.namelist() if n.endswith('/GeckoEditableChild.java')]
        assert len(names)==1
        source=archive.read(names[0]).decode()
    lines=source.splitlines();at=next(i for i,s in enumerate(lines) if '"No editable parent"' in s)
    report={'url':SOURCE_URL,'sourceJarSha256':SOURCE_SHA,'path':names[0],
            'javaSha256':hashlib.sha256(source.encode()).hexdigest(),
            'warningLine':at+1,'guardExcerpt':[{'line':i+1,'text':lines[i]} for i in range(at-5,at+3)],
            'parentBindingLines':[{'line':i+1,'text':s} for i,s in enumerate(lines) if 'mEditableParent =' in s or 'hasEditableParent()' in s],
            'condition':'hasEditableParent() logs and returns false when mEditableParent is null; several native callbacks use this guard. This does not identify the runtime caller or establish harmlessness.'}
    pathlib.Path(out,'input-warning-source.json').write_text(json.dumps(report,indent=2)+'\n')

def parse_proc_stat(raw):
    match=re.match(r'^(\d+) \(',raw);end=raw.rfind(')')
    if not match or end<0:raise ValueError('Invalid process stat')
    fields=raw[end+1:].split()
    if len(fields)<22:raise ValueError('Truncated process stat')
    return {'pid':int(match[1]),'startTicks':int(fields[19]),'cpuTicks':int(fields[11])+int(fields[12])}

def cpu_delta(before,after,hz):
    elapsed=after['deviceUptimeSeconds']-before['deviceUptimeSeconds']
    old={r['pid']:r for r in before['processes']};new={r['pid']:r for r in after['processes']}
    complete=bool(old) and old.keys()==new.keys() and all(old[k]['startTicks']==new[k]['startTicks'] and new[k]['cpuTicks']>=old[k]['cpuTicks'] for k in old)
    if elapsed<=0 or hz<=0:raise ValueError('Invalid CPU observation interval')
    return {'deviceSeconds':round(elapsed,3),'hostSeconds':round(after['hostMonotonic']-before['hostMonotonic'],3),
            'completeProcessCoverage':complete,'missingPids':sorted(old.keys()-new.keys()),'newPids':sorted(new.keys()-old.keys()),
            'oneCorePercent':round(sum(new[k]['cpuTicks']-old[k]['cpuTicks'] for k in old)/hz/elapsed*100,4) if complete else None}

def warning_probe(h,report):
    rows=[]
    def snapshot(label):
        time.sleep(3)
        sample={'phase':label,'hostMonotonic':time.monotonic(),'pages':{a+':'+t:h.base.latest(a,t) for a,t in h.TABS},'native':h.native('A')}
        assert all(sample['pages'].values());rows.append(sample)
        (h.OUT/'input-warning-controls.json').write_text(json.dumps(rows,ensure_ascii=False,indent=2)+'\n')
        return sample
    h.mark_phase('warning-passive-open');h.launch('A',tabs=2)
    for a,t in h.TABS:h.select(a,t);h.wait(lambda:h.latest(a,t),label='passive document observation')
    passive=snapshot('warning-passive-open')
    assert all(not r['standard'] and not r['rich'] and not any(e['type']=='input' for e in r['inputEvents']) for r in passive['pages'].values())
    report['cases']['passiveControlsContainNoEditorInput']=True
    h.mark_phase('warning-passive-switch')
    for _ in range(3):
        for a,t in h.TABS:h.select(a,t)
    switched=snapshot('warning-passive-switch')
    assert all(switched['pages'][k]['document']==r['document'] for k,r in passive['pages'].items())
    h.mark_phase('warning-navigation-without-input')
    for a,t in h.TABS:
        h.select(a,t);h.point(a,t,'detail');h.wait(lambda:(lambda r:r if r and r['page']=='detail' else None)(h.latest(a,t)),label='blank detail navigation');h.healthy(a,t)
    navigated=snapshot('warning-navigation-without-input')
    assert all(not r['standard'] and not r['rich'] and not any(e['type']=='input' for e in r['inputEvents']) for r in navigated['pages'].values())
    h.mark_phase('warning-focus-without-commit')
    for a,t in h.TABS:
        h.select(a,t)
        for field in ('standard','rich'):
            h.point(a,t,field);h.wait(lambda:(lambda r:r if r and r['activeElement']==field else None)(h.latest(a,t)),label='focus-only '+field)
        h.launch(a,'hideKeyboard')
    focused=snapshot('warning-focus-without-commit')
    assert all(not r['standard'] and not r['rich'] for r in focused['pages'].values())
    h.mark_phase('warning-native-commit')
    for a,t in h.TABS:h.type_values(a,t,h.markers[a+':'+t]);h.checkpoint(a)
    edited=snapshot('warning-native-commit')
    h.mark_phase('warning-retained-input-switch')
    for _ in range(3):
        for a,t in h.TABS:
            h.select(a,t);r=h.same_values(a,t,h.markers[a+':'+t]);assert r['document']==edited['pages'][a+':'+t]['document']
    snapshot('warning-retained-input-switch')
    h.mark_phase('warning-activity-recreate');h.select('A','0');old=h.native('A')['elapsedMs'];h.launch('A','recreate')
    h.wait(lambda:(lambda r:r if r.get('elapsedMs',0)>old and r.get('windowFocused') else None)(h.native('A')),label='recreated diagnostic Activity')
    for a,t in h.TABS:
        h.select(a,t);r=h.same_values(a,t,h.markers[a+':'+t]);assert r['document']==edited['pages'][a+':'+t]['document']
    snapshot('warning-activity-recreate')
    h.mark_phase('warning-real-close');h.select('A','0');h.checkpoint('A',True)
    h.restored('warning-restored',h.markers,{k:r['document'] for k,r in edited['pages'].items()})
    snapshot('warning-restored')
    assert not h.base.submissions
    report['cases']['controlledInputNavigationAndRestoration']=True
    report['warningControls']={'samples':len(rows),'zeroSubmissions':True,'conditions':['no editor input','blank navigation','focus only','native commit','retained switch','Activity recreation','real process exit and restore'],'causalAttribution':False}
    report['experimentComplete']=True

def long_background(h,report):
    """30 minutes: Home, screen off, forced Doze; 10 real minutes each."""
    assert h.report_variant=='optimized-release'
    hz=int(h.text('shell','getconf','CLK_TCK'));cores=int(h.text('shell','getconf','_NPROCESSORS_ONLN'))
    assert 1<=hz<=10000 and 1<=cores<=128
    samples=[];stages=[]
    destination=h.OUT/'long-background-measurements.json'
    def save():destination.write_text(json.dumps({'cpuClockTicksPerSecond':hz,'logicalCPUs':cores,'samples':samples,'stages':stages},ensure_ascii=False,indent=2)+'\n')
    def sample(label):
        now=time.monotonic();uptime=float(h.text('shell','cat','/proc/uptime').split()[0]);_,rows=h.base.process_rows();processes=[]
        for row in rows:
            pid=row['pid'];raw=h.text('shell','cat','/proc/'+pid+'/stat');r=parse_proc_stat(raw);r['name']=row['name'];assert r['pid']==int(pid);processes.append(r)
        result={'stage':label,'hostMonotonic':now,'deviceUptimeSeconds':uptime,'processes':processes}
        if samples:result['cpuFromPrevious']=cpu_delta(samples[-1],result,hz)
        if not samples or label!=samples[-1]['stage'] or now-samples[-1].get('lastMemoryAt',0)>=60:
            memory=[]
            for row in rows:
                raw=h.text('shell','dumpsys','meminfo',row['pid']);(h.OUT/('resource-'+str(len(samples))+'-'+row['pid']+'.txt')).write_text(raw)
                m=re.search(r'TOTAL PSS:\s*(\d+)',raw) or re.search(r'^\s*TOTAL\s+(\d+)',raw,re.M)
                if not m:raise RuntimeError('Missing measured PSS')
                memory.append({'pid':int(row['pid']),'name':row['name'],'pssKiB':int(m[1])})
            result.update(memory=memory,totalPssMiB=round(sum(r['pssKiB'] for r in memory)/1024,3),lastMemoryAt=now)
        else:result['lastMemoryAt']=samples[-1]['lastMemoryAt']
        result['pageObservations']={a+':'+t:({k:r[k] for k in ('document','ticks','received','visibility','standard','rich','standardModel','richModel')} if (r:=h.base.latest(a,t)) else None) for a,t in h.TABS}
        result['deviceIdle']=h.text('shell','dumpsys','deviceidle','get','deep')
        power=h.text('shell','dumpsys','power');match=re.search(r'\bmWakefulness=(\w+)',power)
        assert match,'Actual screen power state unavailable'
        result['wakefulness']=match[1]
        if label in ('screen-off','forced-doze'):assert match[1] in ('Asleep','Dozing'),'Screen-off condition not observed'
        if label=='forced-doze':assert result['deviceIdle']=='IDLE','Forced Doze ended before the requested interval'
        samples.append(result);save();return result
    def window(label,seconds):
        h.mark_phase('prolonged-'+label);start=time.monotonic();sample(label);deadline=start+seconds
        while time.monotonic()<deadline:
            time.sleep(min(30,max(0,deadline-time.monotonic())));sample(label)
            print('Background measurement '+label+': '+str(round(time.monotonic()-start))+'/'+str(seconds)+' seconds',flush=True)
        return round(time.monotonic()-start,3)
    def resume(label,refs,pids,started):
        h.text('shell','dumpsys','deviceidle','unforce');h.text('shell','dumpsys','battery','reset');h.text('shell','input','keyevent','KEYCODE_WAKEUP');h.text('shell','wm','dismiss-keyguard')
        h.mark_phase('prolonged-'+label+'-resume')
        after={}
        for a,t in h.TABS:
            h.select(a,t);r=h.same_values(a,t,h.markers[a+':'+t]);assert r['document']==refs[a+':'+t]['document'],'Page reloaded after '+label
            assert abs(r['scrollY']-refs[a+':'+t]['scrollY'])<80,'Reading position changed after '+label
            assert r['received']>started and r['ticks']>refs[a+':'+t]['ticks'];after[a+':'+t]={'sameDocument':True,'tickDelta':r['ticks']-refs[a+':'+t]['ticks'],'scrollY':r['scrollY']}
        assert all(h.native(a)['pid']==pid for a,pid in pids.items()),'Host process replaced'
        assert not [r for r in h.base.requests if r['received']>=started],'Unexpected document request during retention interval'
        return after
    h.select('A','0');window('foreground-baseline',60)
    try:
        for label in ('home','screen-off','forced-doze'):
            refs={a+':'+t:h.base.latest(a,t) for a,t in h.TABS};pids={a:h.native(a)['pid'] for a in ('A','B','C')};started=time.monotonic()
            h.text('shell','input','keyevent','KEYCODE_HOME');h.wait(lambda:not h.foreground('A'),label='real Home before long sample')
            if label in ('screen-off','forced-doze'):h.text('shell','input','keyevent','KEYCODE_SLEEP')
            if label=='forced-doze':
                h.text('shell','dumpsys','battery','unplug');h.text('shell','dumpsys','deviceidle','force-idle')
                assert h.text('shell','dumpsys','deviceidle','get','deep')=='IDLE','Forced Doze not observed'
            seconds=window(label,600)
            # Record suspended/stale observations before waking; do not claim
            # heartbeats continued if the OS blocked execution/network access.
            before_resume={a+':'+t:h.base.latest(a,t) for a,t in h.TABS}
            after=resume(label,refs,pids,started)
            stage={'stage':label,'requestedSeconds':600,'measuredSeconds':seconds,'retainedPages':after,'backgroundObservationAgeSeconds':{k:round(time.monotonic()-r['received'],3) for k,r in before_resume.items()},'sameHostPids':True,'noNewDocumentRequests':True}
            stages.append(stage);save();report['cases']['prolonged-'+label]=True
    finally:
        h.text('shell','dumpsys','deviceidle','unforce',check=False);h.text('shell','dumpsys','battery','reset',check=False);h.text('shell','input','keyevent','KEYCODE_WAKEUP',check=False)
    summary=[]
    for label in ('foreground-baseline','home','screen-off','forced-doze'):
        rows=[r for r in samples if r['stage']==label];mem=[r['totalPssMiB'] for r in rows if 'totalPssMiB' in r]
        intervals=[r['cpuFromPrevious'] for r in rows[1:] if r.get('cpuFromPrevious',{}).get('completeProcessCoverage')];cpu=[r['oneCorePercent'] for r in intervals];duration=sum(r['deviceSeconds'] for r in intervals)
        assert intervals,'No attributable CPU interval for '+label
        summary.append({'stage':label,'samples':len(rows),'memorySamples':len(mem),'pssMiBFirst':mem[0],'pssMiBLast':mem[-1],'pssMiBPeak':max(mem),'cpuValidIntervals':len(cpu),'cpuMeasuredDeviceSeconds':round(duration,3),'cpuMeanOneCorePercent':round(sum(r['oneCorePercent']*r['deviceSeconds'] for r in intervals)/duration,4) if duration else None,'cpuPeakOneCorePercent':max(cpu) if cpu else None})
    report['longBackground']={'summary':summary,'stages':stages,'cpuClockTicksPerSecond':hz,'logicalCPUs':cores,'requestedBackgroundSeconds':1800,'scope':'Synthetic four-page workload; 500ms HTTP observations and recovery bookkeeping included. CPU is aggregate per one logical core, may exceed 100%. PSS is summed across currently attributable package processes. Sampling overhead included; emulator measurements are not physical-device or real ChatGPT predictions.'}

if __name__=='__main__':
    import sys
    assert len(sys.argv)==3 and sys.argv[1]=='--collect-source'
    collect_source(sys.argv[2])
