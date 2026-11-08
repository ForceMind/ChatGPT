"""Pair each scenario on one worker; do not invent ratios for unmatched work."""
from pathlib import Path
import json,math,os,statistics,subprocess
assert os.environ.get('GITHUB_ACTIONS')=='true' and os.environ.get('GITHUB_REPOSITORY')=='ForceMind/ChatGPT'
root=Path('optimization-comparison');static=json.loads((root/'comparison.json').read_text());assert static['allFunctionalPass']
id=os.environ['STUDIO_RESOURCE_RUN'];assert id.isdecimal()
run=json.loads(subprocess.check_output(['gh','api','repos/ForceMind/ChatGPT/actions/runs/'+id]))
assert run['path']=='.github/workflows/android-optimization-resources.yml' and run['event']=='workflow_dispatch' and run['head_branch']=='dev'
assert run['status']=='completed' and run['conclusion']=='success'
parents={r['mode']:r['runID'] for r in static['rows']};rows=[]
for api in [28,35]:
 for pages in [1,4]:
  for load in ['idle','stream']:
   folder=root/'resources'/f'{api}-{pages}-{load}'
   subprocess.run(['gh','run','download',id,'--repo','ForceMind/ChatGPT','--name',f'regular-optimization-resources-{api}-{pages}-{load}','--dir',str(folder)],check=True)
   reports={}
   for mode in ['baseline','r8','resources']:
    paths=[p for p in folder.rglob('report.json') if p.parent.name==mode];assert len(paths)==1
    d=json.loads(paths[0].read_text());assert d['complete'] and d['mode']==mode and d['sourceOrigin']['parentRun']==parents[mode]
    assert int(d['environment']['api'])==api and d['windowSeconds']==120 and d['warmupSeconds']==30 and d['windowsPerCase']==2
    assert len(d['cases'])==1 and d['cases'][0]['pages']==pages and d['cases'][0]['load']==load
    windows=d['cases'][0]['windows'];assert len(windows)==2
    for w in windows:
     assert w['durationSeconds']>=120 and w['documentsRetained'] and w['draftsRetained']
     assert all(math.isfinite(w[k]) and w[k]>=0 for k in ['cpuPercentOneCore','cpuCoreSeconds','pssMeanMiB','pssPeakMiB'])
     assert len(w['streamRevisions'])==pages
     assert all(n>0 if load=='stream' else n==0 for n in w['streamRevisions'].values())
    reports[mode]=d
   assert len({d['hostNode'] for d in reports.values()})==1,'Do not compare different machines'
   assert len({d['samplerSHA256'] for d in reports.values()})==1,'Do not compare different sampler recipes'
   assert len({d['environment']['fingerprint'] for d in reports.values()})==1,'Guest system image changed'
   assert len({d['environment']['clockTicksPerSecond'] for d in reports.values()})==1
   base=reports['baseline']['cases'][0]['windows']
   case={'api':api,'pages':pages,'load':load,'hostNode':reports['baseline']['hostNode'],'modes':{}}
   for mode,d in reports.items():
    w=d['cases'][0]['windows'];comparable=all(abs(a['durationSeconds']-b['durationSeconds'])<=5 for a,b in zip(base,w))
    if load=='stream':
     for a,b in zip(base,w):
      for page,count in a['streamRevisions'].items():
       comparable=comparable and abs(count-b['streamRevisions'][page])<=max(1,count*.05)
    cpu=statistics.mean(x['cpuPercentOneCore'] for x in w);memory=statistics.mean(x['pssMeanMiB'] for x in w)
    basecpu=statistics.mean(x['cpuPercentOneCore'] for x in base);basemem=statistics.mean(x['pssMeanMiB'] for x in base)
    case['modes'][mode]={'windows':w,'cpuMeanPercentOneCore':cpu,'pssMeanMiB':memory,'workAndTimingComparable':comparable,
     'cpuSavedPercent':(basecpu-cpu)/basecpu*100 if comparable and basecpu>0 else None,
     'pssSavedPercent':(basemem-memory)/basemem*100 if comparable and basemem>0 else None,
     'stableCpuReductionObserved':comparable and mode!='baseline' and max(x['cpuPercentOneCore'] for x in w)<min(x['cpuPercentOneCore'] for x in base),
     'stablePssReductionObserved':comparable and mode!='baseline' and max(x['pssMeanMiB'] for x in w)<min(x['pssMeanMiB'] for x in base)}
   rows.append(case)
report={'schema':1,'complete':True,'resourceRun':int(id),'harnessSourceSHA':run['head_sha'],'cases':rows,'samplingWindows':48,
 'limits':['Synthetic CI APKs and observer transport; not physical phones or real ChatGPT.','Two windows per mode/scenario; no broad statistical or battery-life guarantee.','Ratios withheld when work/timing differs; negative savings and raw windows retained.','Human unsigned APK reachability can differ from CI fixtures; production-signature and human runtime acceptance remain separate.'],
 'productionAppChanged':False,'personalDevicesUpdated':False}
(root/'resource-comparison.json').write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps({'complete':True,'scenarios':len(rows),'windows':48}))
