"""One immutable candidate per fresh VM, all three on the same scenario runner."""
from pathlib import Path
import argparse,json,os,subprocess
assert os.environ.get('GITHUB_ACTIONS')=='true'
mode=os.environ['STUDIO_RESOURCE_MODE'];assert mode in ('baseline','r8','resources')
p=argparse.ArgumentParser();p.add_argument('--api',choices=['28','35'],required=True);args=p.parse_args()
root=Path('android/app/build');source=root/'resource-cohort'/mode
input=root/'regular-environment-input';diagnostics=root/'resource-build-diagnostics'
assert not input.exists() and not diagnostics.exists()
original={p:p.read_bytes() for p in [Path('android/scripts/regular_environment_upgrade.py'),Path('android/scripts/environment-fixture.py')]}
try:
    (source/'input').rename(input);(source/'diagnostics').rename(diagnostics)
    subprocess.run(['python3','android/scripts/bind-regular-resource-origin.py'],check=True)
    origin=json.loads((root/'resource-origin.json').read_text());assert origin['mode']==mode
    env=dict(os.environ,STUDIO_REGULAR_CI_CERT_SHA256=origin['ciSigner'],STUDIO_RESOURCE_APP_SOURCE_SHA=origin['appSourceSHA'],
             STUDIO_ANDROID_VERSION_FILE=str((root/'resource-android-version.json').resolve()))
    subprocess.run(['bash','android/scripts/run-environment-api'+args.api+'.sh','android/scripts/measure-regular-optimization.py'],env=env,check=True)
finally:
    for p,data in original.items():p.write_bytes(data)
    if input.exists():input.rename(source/'input')
    if diagnostics.exists():diagnostics.rename(source/'diagnostics')
