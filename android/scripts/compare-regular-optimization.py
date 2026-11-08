"""CI-only, immutable APK and functional-gate comparison; runtime results are separate."""
from pathlib import Path
import hashlib,json,os,subprocess,zipfile

assert os.environ.get('GITHUB_ACTIONS')=='true'
assert os.environ.get('GITHUB_REPOSITORY')=='ForceMind/ChatGPT' and os.environ['GITHUB_REF']=='refs/heads/dev'
root=Path('optimization-comparison');root.mkdir(exist_ok=False)
rows=[]
for mode in ['baseline','r8','resources']:
    run_id=os.environ['STUDIO_'+mode.upper()+'_RUN'];assert run_id.isdecimal()
    run=json.loads(subprocess.check_output(['gh','api','repos/ForceMind/ChatGPT/actions/runs/'+run_id]))
    assert run['path']=='.github/workflows/android-regular-optimization.yml' and run['event']=='workflow_dispatch' and run['head_branch']=='dev'
    assert run['status']=='completed', 'Wait for the actual complete run; do not infer success from uploaded APKs'
    folder=root/mode
    subprocess.run(['gh','run','download',run_id,'--repo','ForceMind/ChatGPT','--name','regular-unsigned-review','--dir',str(folder)],check=True)
    receipt=json.loads((folder/'receipt.json').read_text())
    assert receipt['sourceSHA']==run['head_sha'] and receipt['signing']=='UNSIGNED_REVIEW_ONLY'
    assert receipt['ciControlsIncluded'] is False and receipt['ciTrustIncluded'] is False and receipt['alignment16KiB'] is True
    apk=folder/receipt['file'];assert apk.name==receipt['file'] and apk.stat().st_size==receipt['bytes']
    h=hashlib.sha256()
    with apk.open('rb') as f:
        for chunk in iter(lambda:f.read(1048576),b''):h.update(chunk)
    assert h.hexdigest()==receipt['sha256']
    diagnostic=root/(mode+'-diagnostics')
    subprocess.run(['gh','run','download',run_id,'--repo','ForceMind/ChatGPT','--name','optimization-build-diagnostics','--dir',str(diagnostic)],check=True)
    overlays=list(diagnostic.rglob('overlay.json'));assert len(overlays)==1
    overlay=json.loads(overlays[0].read_text());assert overlay['mode']==mode and overlay['harnessSHA']==run['head_sha']
    assert overlay['versionName']==receipt['versionName']
    groups={};native={}
    with zipfile.ZipFile(apk) as z:
        for i in z.infolist():
            if i.is_dir():continue
            kind='native' if i.filename.startswith('lib/') else 'dex' if i.filename.endswith('.dex') else 'assets' if i.filename.startswith('assets/') else 'other'
            groups[kind]=groups.get(kind,0)+i.compress_size
            if kind=='native':native[i.filename]=hashlib.sha256(z.read(i)).hexdigest()
    rows.append({'mode':mode,'runID':int(run_id),'sourceSHA':run['head_sha'],'productBaseline':overlay['productBaseline'],'versionMetadata':receipt['versionMetadata'],'bytes':receipt['bytes'],'sha256':receipt['sha256'],'groupsStoredBytes':groups,'nativeEntrySHA256':native,'functionalConclusion':run['conclusion'],'fullFunctionalPass':run['conclusion']=='success','temporaryReviewOnly':True})
base=rows[0]
assert len({r['productBaseline'] for r in rows})==1
assert all(all(r['versionMetadata'][k]==base['versionMetadata'][k] for k in ('schema','versionName','versionCode','timeZone')) for r in rows)
assert len({next(e['beforeSHA256'] for e in json.loads(next((root/(r['mode']+'-diagnostics')).rglob('overlay.json')).read_text())['edits'] if e['path']=='android/app/build.gradle') for r in rows})==1
assert all(r['nativeEntrySHA256']==base['nativeEntrySHA256'] for r in rows),'Native libraries changed; not an isolated R8/resource comparison'
for r in rows:
    r['savedBytes']=base['bytes']-r['bytes'];r['savedPercent']=round(r['savedBytes']*100/base['bytes'],3)
report={'schema':1,'kind':'apk-and-functional-comparison','complete':True,'sourceInputsMatched':True,'sourceScope':'Same pinned product tree and pre-overlay Gradle hash; native library SHA equality; harness source metadata retained separately','allFunctionalPass':all(r['fullFunctionalPass'] for r in rows),'rows':rows,'runtimeMeasured':False,'productionAppChanged':False,'productionSigningUsed':False,'personalDevicesUpdated':False}
(root/'comparison.json').write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps({r['mode']:{'bytes':r['bytes'],'savedPercent':r['savedPercent'],'fullFunctionalPass':r['fullFunctionalPass']} for r in rows}))
assert report['allFunctionalPass'],'Do not accept a smaller package with failed functional gates'
