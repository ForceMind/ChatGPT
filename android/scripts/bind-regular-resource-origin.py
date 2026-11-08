"""Bind reused APKs to their successful parent without spoofing harness identity."""
import json,os,re,subprocess
from pathlib import Path
assert os.environ.get('GITHUB_ACTIONS')=='true' and os.environ.get('GITHUB_REPOSITORY')=='ForceMind/ChatGPT'
assert os.environ['GITHUB_REF']=='refs/heads/dev'
id=os.environ['STUDIO_RESOURCE_SOURCE_RUN'];assert id.isdecimal()
def api(s):return json.loads(subprocess.check_output(['gh','api','repos/ForceMind/ChatGPT/'+s]))
r=api('actions/runs/'+id)
assert r['status']=='completed' and r['conclusion']=='success'
assert r['path']=='.github/workflows/android-regular-optimization.yml' and r['event']=='workflow_dispatch' and r['head_branch']=='dev'
assert r['repository']['full_name']==r['head_repository']['full_name']=='ForceMind/ChatGPT'
art=api('actions/runs/'+id+'/artifacts?per_page=100')['artifacts']
a=[a for a in art if a['name']=='regular-environment-ci-input' and not a['expired']];assert len(a)==1
assert a[0]['workflow_run']['head_sha']==r['head_sha']
d=json.loads(Path('android/app/build/regular-environment-input/receipt.json').read_text())
assert d['sourceSHA']==r['head_sha'] and d['ciOnly'] is True and d['productionSignatureVerified'] is False
assert re.fullmatch(r'[a-f0-9]{64}',d['signerSHA256'])
o=list(Path('android/app/build/resource-build-diagnostics').rglob('overlay.json'));assert len(o)==1
mode=json.loads(o[0].read_text());assert mode['harnessSHA']==r['head_sha'] and mode['mode'] in ['baseline','r8','resources']
assert mode['versionName']==d['versionName']
origin={'schema':1,'complete':True,'parentRun':int(id),'parentFunctionalConclusion':r['conclusion'],'appSourceSHA':r['head_sha'],'harnessSHA':os.environ['GITHUB_SHA'],'mode':mode['mode'],'inputArtifactID':a[0]['id'],'inputArtifactDigest':a[0]['digest'],'ciSigner':d['signerSHA256']}
Path('android/app/build/resource-origin.json').write_text(json.dumps(origin,indent=2)+'\n')
Path('android/app/build/resource-android-version.json').write_text(json.dumps(d['versionMetadata'],indent=2)+'\n')
with open(os.environ['GITHUB_ENV'],'a') as f:
 f.write('STUDIO_REGULAR_CI_CERT_SHA256='+d['signerSHA256']+'\n')
 f.write('STUDIO_RESOURCE_APP_SOURCE_SHA='+r['head_sha']+'\n')
 f.write('STUDIO_ANDROID_VERSION_FILE='+str(Path('android/app/build/resource-android-version.json').resolve())+'\n')
print(json.dumps(origin))
