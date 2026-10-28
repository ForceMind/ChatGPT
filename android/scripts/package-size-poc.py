#!/usr/bin/env python3
"""Build and measure three unsigned isolated release APKs, on CI only."""
from pathlib import Path
import hashlib
import json
import os
import re
import subprocess
import sys
import zipfile

MODES=('baseline','r8','resources')
BRIDGE={'assets/managed-recovery/manifest.json','assets/managed-recovery/recovery.js'}

def digest_file(path):
    h=hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda:stream.read(1024*1024),b''):h.update(chunk)
    return h.hexdigest()

def group(name):
    if name.startswith('lib/'):return 'nativeLibraries'
    if re.fullmatch(r'classes\d*\.dex',name):return 'dex'
    if name=='resources.arsc':return 'compiledResources'
    if name.startswith('res/'):return 'resources'
    if name.startswith('assets/'):return 'assets'
    if name=='AndroidManifest.xml':return 'manifest'
    return 'other'

def validate_badging(badging):
    package=re.search(r"^package: name='([^']+)'",badging,re.M)
    minimum=re.findall(r"^(?:minSdkVersion|sdkVersion):'(\d+)'$",badging,re.M)
    native=re.search(r'^native-code:\s*(.*)$',badging,re.M)
    abis=re.findall(r"'([^']+)'",native.group(1)) if native else []
    if not package or package.group(1)!='com.forcemind.chatgpt.environmentpoc' or minimum!=['28'] or abis!=['arm64-v8a'] or re.search(r'^application-debuggable\b',badging,re.M):
        raise RuntimeError('Unexpected release manifest or ABI')
    return {'package':package.group(1),'minSdk':28,'abis':abis,'debuggable':False}

def measure(apk,badging):
    manifest=validate_badging(badging)
    categories={};libraries={};bridge={}
    with zipfile.ZipFile(apk) as archive:
        for entry in archive.infolist():
            category=categories.setdefault(group(entry.filename),{'entries':0,'rawBytes':0,'zipPayloadBytes':0})
            category['entries']+=1;category['rawBytes']+=entry.file_size;category['zipPayloadBytes']+=entry.compress_size
            if entry.filename.startswith('lib/') or entry.filename in BRIDGE:
                h=hashlib.sha256()
                with archive.open(entry) as stream:
                    for chunk in iter(lambda:stream.read(1024*1024),b''):h.update(chunk)
                item={'rawBytes':entry.file_size,'zipPayloadBytes':entry.compress_size,'compression':entry.compress_type,'sha256':h.hexdigest()}
                (libraries if entry.filename.startswith('lib/') else bridge)[entry.filename]=item
        if not libraries or set(bridge)!=BRIDGE or any(not name.startswith('lib/arm64-v8a/') for name in libraries):raise RuntimeError('Native engine or bridge missing')
    return {'apkBytes':apk.stat().st_size,'apkSha256':digest_file(apk),'manifest':manifest,'categories':categories,'nativeLibraries':libraries,'managedBridge':bridge,'zipHeadersAlignmentAndSigningBytes':apk.stat().st_size-sum(c['zipPayloadBytes'] for c in categories.values())}

def main():
    if os.environ.get('GITHUB_ACTIONS')!='true':raise SystemExit('Builds and measurements run in GitHub Actions only')
    android=Path(__file__).resolve().parents[1];out=android/'environment-poc/build/package-size-evidence';out.mkdir(parents=True,exist_ok=True)
    report={'schema':1,'sourceSha':os.environ['GITHUB_SHA'],'runId':os.environ['GITHUB_RUN_ID'],'scope':'Unsigned arm64 isolated release APK composition','engineVersion':'158.0.20261021001945','agpVersion':'9.1.1','minSdk':28,'formalAppOrDataUsed':False,'releaseRuntimeValidated':False,'installedBytesMeasured':False,'results':[],'complete':False}
    try:
        for mode in MODES:
            command=['./environment-poc/gradlew','-p','environment-poc','--no-daemon','-PpocMinSdk=28','-PpocAbis=arm64-v8a','-PpocOptimization='+mode,'assembleRelease']
            if mode=='resources':command.append('lintRelease')
            with (out/(mode+'-build.txt')).open('w') as log:
                result=subprocess.run(command,cwd=android,stdout=log,stderr=subprocess.STDOUT)
            if result.returncode:raise RuntimeError(mode+' build failed; inspect '+mode+'-build.txt')
            candidates=list((android/'environment-poc/build/outputs/apk/release').glob('*arm64-v8a*unsigned.apk'))
            if len(candidates)!=1:raise RuntimeError('Expected one unsigned arm64 release APK')
            badging=subprocess.check_output([os.environ['ANDROID_HOME']+'/build-tools/36.0.0/aapt2','dump','badging',str(candidates[0])],text=True)
            (out/(mode+'-badging.txt')).write_text(badging)
            item=measure(candidates[0],badging);item['mode']=mode;report['results'].append(item)
            print(mode,item['apkBytes'],'bytes',flush=True)
        baseline=report['results'][0]
        for item in report['results']:
            item['nativeEngineBytesPreserved']=item['nativeLibraries']==baseline['nativeLibraries']
            item['managedBridgeBytesPreserved']=item['managedBridge']==baseline['managedBridge']
            if not item['nativeEngineBytesPreserved'] or not item['managedBridgeBytesPreserved']:raise RuntimeError('Native engine or managed bridge changed between release modes')
            item['savedBytesVsBaseline']=baseline['apkBytes']-item['apkBytes']
            item['savedPercentVsBaseline']=round(100*item['savedBytesVsBaseline']/baseline['apkBytes'],4)
        report['complete']=True
    except Exception as error:
        report['failure']=str(error);raise
    finally:
        report['limits']=['Release APKs are unsigned, not installed, and not runtime-validated. Device lifecycle/recovery tests use the independent debug build.','APK file size is not installed disk usage, RAM, CPU or store-compressed download size.','No engine update, native stripping, formal signing key, user data, real ChatGPT or public release.']
        (out/'package-size-report.json').write_text(json.dumps(report,indent=2)+'\n')

if __name__=='__main__':main()
