"""Allow exactly the user-approved resource driver in disposable bootstraps."""
from pathlib import Path
import hashlib,json,os
assert os.environ.get('GITHUB_ACTIONS')=='true'
assert os.environ.get('GITHUB_REPOSITORY')=='ForceMind/ChatGPT'
assert os.environ.get('GITHUB_WORKFLOW')=='Android regular optimization resource comparison' or os.environ.get('GITHUB_REF')=='refs/heads/validation/android-2241-resources'
out=Path('android/app/build/regular-resource-evidence');out.mkdir(parents=True,exist_ok=True)
changes=[]
for api,var in [(28,'studio_target'),(35,'studio_validation_script')]:
 p=Path('android/scripts/run-environment-api'+str(api)+'.sh');old=p.read_bytes();s=old.decode()
 marker='case "$'+var+'" in\n';assert s.count(marker)==1
 s=s.replace(marker,marker+'  android/scripts/measure-regular-optimization.py) studio_evidence=android/app/build/regular-resource-evidence ;;\n',1)
 if api == 28:
  condition='if [ "$studio_target" = synthetic ] || [ "$studio_target" = android/scripts/run-regular-environment-client.py ] || [ "$studio_target" = android/scripts/run-regular-navigation.py ]; then'
  assert s.count(condition)==1
  s=s.replace(condition,condition[:-len('; then')]+' || [ "$studio_target" = android/scripts/measure-regular-optimization.py ]; then',1)
 p.write_text(s);changes.append({'path':str(p),'beforeSHA256':hashlib.sha256(old).hexdigest(),'afterSHA256':hashlib.sha256(p.read_bytes()).hexdigest()})
(out/'driver-overlay.json').write_text(json.dumps({'ciOnly':True,'harnessSHA':os.environ['GITHUB_SHA'],'changes':changes},indent=2)+'\n')
