#!/usr/bin/env bash
set -euo pipefail
test "${GITHUB_ACTIONS:-}" = true
cd "$(dirname "$0")/../.."
sdkmanager 'ndk;28.2.13676358' 'platforms;android-37.0' 'build-tools;36.0.0'
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/28.2.13676358"
bash android/network-poc/scripts/build-core.sh
mkdir -p android/app/libs/licenses
cp android/network-poc/app/libs/networkcore.aar android/app/libs/networkcore.aar
cp android/network-poc/app/libs/dependencies.json android/app/libs/dependencies.json
python3 - <<'PY'
import hashlib,json,pathlib,os
root=pathlib.Path('android/app/libs');text=(root/'dependencies.json').read_text();decoder=json.JSONDecoder();copied=0
while text.strip():
 value,n=decoder.raw_decode(text.lstrip());text=text.lstrip()[n:];path=value.get('Dir')
 if not path:continue
 source=pathlib.Path(path)
 if not source.is_dir():
  cached=list((root/'licenses').glob(value['Path'].replace('/','_')+'_*'))
  if not cached:raise RuntimeError('Missing dependency source licenses: '+value['Path'])
  copied+=len(cached);continue
 for f in source.iterdir():
  if f.is_file() and f.name.lower().startswith(('license','copying','notice')):
   (root/'licenses'/(value['Path'].replace('/','_')+'_'+f.name)).write_bytes(f.read_bytes());copied+=1
assert copied>0
(root/'core-manifest.json').write_text(json.dumps({'sourceSHA':os.environ['GITHUB_SHA'],'coreRevision':'0afec66085a8','goVersion':'1.27.3','mobileRevision':'197770444867','ndk':'28.2.13676358','aarSHA256':hashlib.sha256((root/'networkcore.aar').read_bytes()).hexdigest(),'productionSigning':False,'licenses':copied},indent=2)+'\n')
PY
