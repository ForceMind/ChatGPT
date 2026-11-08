"""Preserve only actual shared test runtime types, without changing human rules."""
from pathlib import Path
import json,os,re,subprocess,sys
from optimization_dex import classes_and_roots
assert os.environ.get('GITHUB_ACTIONS')=='true' and os.environ.get('GITHUB_REPOSITORY')=='ForceMind/ChatGPT'
assert os.environ['STUDIO_OPT_MODE'] in ['baseline','r8','resources']
args=sys.argv[1:];assert args and all(a.startswith('-P') or a in [':app:assembleRelease',':app:assembleReleaseAndroidTest'] for a in args)
command=['bash','android/environment-poc/gradlew','-p','android','--no-daemon',*args]
folder=Path('android/app/build/optimization');folder.mkdir(parents=True,exist_ok=True)
reference=set(json.loads(Path('android/app/build/optimization-old-side-test/unminified-runtime-types.json').read_text()))
keep=Path('android/app/optimization-ci-keep.pro');seen=set();passes=[]
for attempt in range(3):
    subprocess.run(command,check=True)
    app,unused=classes_and_roots('android/app/build/outputs/apk/release/app-release.apk')
    test,roots=classes_and_roots('android/app/build/outputs/apk/androidTest/release/app-release-androidTest.apk')
    mapping=Path('android/app/build/outputs/mapping/release/mapping.txt');reverse={}
    if os.environ['STUDIO_OPT_MODE']!='baseline':
        for line in mapping.read_text().splitlines():
            if line and not line[0].isspace() and ' -> ' in line and line.endswith(':'):
                original,renamed=line[:-1].split(' -> ');reverse['L'+renamed.replace('.','/')+';']='L'+original.replace('.','/')+';'
    missing={reverse.get(t,t) for t in roots-app-test if reverse.get(t,t) in reference}
    business={t for t in missing if t.startswith('Lcom/forcemind/chatgpt/profiles/')}
    assert not business,'Review production reachability instead of masking business classes: '+str(sorted(business))
    provided={reverse.get(t,t) for t in roots-test if reverse.get(t,t) in reference and not reverse.get(t,t).startswith('Lcom/forcemind/chatgpt/profiles/')}
    additions=provided-seen
    names=[t[1:-1].replace('/','.') for t in sorted(additions)]
    assert all(re.fullmatch(r'[A-Za-z0-9_.$]+',name) for name in names)
    passes.append({'pass':attempt,'missingSharedRuntimeTypes':sorted(missing),'exactSharedTestRootsAdded':names})
    (folder/'test-runtime-closure.json').write_text(json.dumps({'ciOnly':True,'humanRulesChanged':False,'passes':passes,'complete':not missing and not additions},indent=2)+'\n')
    if not missing and not additions:break
    assert additions,'Explicit CI roots did not resolve; no ignorewarnings or skipped runtime'
    seen|=provided
    with keep.open('a') as f:
        f.write('\n# Exact test-DEX roots supplied by the unshrunk application runtime.\n')
        for name in names:f.write('-keep class '+name+' { *; }\n')
else:raise RuntimeError('Shared test runtime closure did not converge')
