"""Apply an explicit CI-only optimization overlay; never edit production locally."""
import argparse
from datetime import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import shutil
from optimization_test_overlay import legacy_upgrade_overlay
from optimization_dex import classes_and_roots, business_members

ROOT = Path(__file__).resolve().parents[2]
MODES = ('baseline', 'r8', 'resources')
BASE = 'f9f4d4e35661ccc20fc15853ffef661c592c23c3'


def sha(data):
    return hashlib.sha256(data).hexdigest()


def main():
    assert os.environ.get('GITHUB_ACTIONS') == 'true'
    assert os.environ.get('GITHUB_REPOSITORY') == 'ForceMind/ChatGPT'
    assert os.environ.get('GITHUB_REF') == 'refs/heads/dev'
    mode = os.environ['STUDIO_OPT_MODE']; assert mode in MODES
    stamp = os.environ['STUDIO_OPT_VERSION']
    assert re.fullmatch(r'\d{4}\.\d{2}\.\d{2}-\d{4}', stamp)
    datetime.strptime(stamp, '%Y.%m.%d-%H%M')
    parser = argparse.ArgumentParser(); parser.add_argument('--phase', choices=['overlay', 'version', 'legacy-tests'], required=True)
    args = parser.parse_args()
    os.chdir(ROOT)
    output = Path('android/app/build/optimization'); output.mkdir(parents=True, exist_ok=True)
    if args.phase == 'version':
        target = Path('android/app/build/regular-android-version.json')
        assert not target.exists()
        target.write_text(json.dumps({'schema': 1, 'versionCode': 3000035, 'versionName': stamp,
                                     'timeZone': 'Asia/Shanghai', 'sourceSHA': os.environ['GITHUB_SHA']}, indent=2) + '\n')
        return
    if args.phase == 'legacy-tests':
        gradle = Path('android/app/build.gradle'); original = gradle.read_bytes(); text = original.decode()
        text, count = re.subn(r'            minifyEnabled (?:true|false)\n', '            minifyEnabled false\n', text)
        assert count == 1
        text, count = re.subn(r'            shrinkResources (?:true|false)\n', '            shrinkResources false\n', text)
        assert count == 1
        folder = Path('android/app/build/optimization-old-side-test'); folder.mkdir(exist_ok=False)
        try:
            gradle.write_text(text)
            subprocess.run(['bash', 'android/environment-poc/gradlew', '-p', 'android', '--no-daemon',
                            '-PenvironmentClient', '-PregularEnvironmentClient', '-PregularEnvironmentCi',
                            '-PenvironmentTest', '-PregularNavigationTest', '-PregularWorkbenchTest',
                            ':app:assembleRelease', ':app:assembleReleaseAndroidTest'], check=True)
            definitions, unused = classes_and_roots('android/app/build/outputs/apk/release/app-release.apk')
            (folder/'unminified-runtime-types.json').write_text(json.dumps(sorted(definitions),indent=2)+'\n')
            apk = folder / 'upgrade-baseline-test.apk'
            shutil.copyfile('android/app/build/outputs/apk/androidTest/release/app-release-androidTest.apk', apk)
            contracts=business_members(apk,definitions)
            (folder/'business-test-api.json').write_text(json.dumps(contracts,indent=2)+'\n')
            with Path('android/app/optimization-ci-keep.pro').open('a') as rules:
                rules.write('\n# Exact original member ABI directly linked by existing instrumented tests.\n')
                for descriptor,members in contracts.items():
                    cls=descriptor[1:-1].replace('/','.')
                    assert re.fullmatch(r'[A-Za-z0-9_.$]+',cls)
                    rules.write('-keep,allowoptimization class '+cls+'\n-keepclassmembers class '+cls+' {\n')
                    for member in members:rules.write('    '+member+'\n')
                    rules.write('}\n')

            tools = Path(os.environ['ANDROID_HOME']) / 'build-tools/35.0.0'
            certs = subprocess.check_output([str(tools/'apksigner'),'verify','--print-certs',str(apk)],text=True)
            signer = re.findall(r'certificate SHA-256 digest: ([a-f0-9]+)',certs); assert len(signer)==1
            (folder/'baseline-test-receipt.json').write_text(json.dumps({
                'schema':1,'file':apk.name,'bytes':apk.stat().st_size,'sha256':sha(apk.read_bytes()),
                'sourceSHA':os.environ['GITHUB_SHA'],'signerSHA256':signer[0],
                'compiledAgainstUnminified':True,'ciOnly':True,'productionSigningUsed':False},indent=2)+'\n')
        finally:
            gradle.write_bytes(original)
        return
    subprocess.run(['git', 'diff', '--exit-code', BASE, 'HEAD', '--', 'android/app',
                    'android/network-poc/corebridge', 'desktop/resources/capabilities'], check=True)
    edits = []
    path = Path('android/app/build.gradle'); old = path.read_bytes(); text = old.decode()
    needle = '            minifyEnabled false\n'
    assert text.count(needle) == 1
    replacement = ('            minifyEnabled ' + ('false' if mode == 'baseline' else 'true') + '\n'
                   '            shrinkResources ' + ('true' if mode == 'resources' else 'false') + '\n'
                   "            proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'optimization-keep.pro'\n"
                   "            if (environmentTest) { proguardFiles 'optimization-ci-keep.pro'; testProguardFiles 'optimization-instrumentation-keep.pro' }\n")
    path.write_text(text.replace(needle, replacement))
    edits.append({'path': str(path), 'beforeSHA256': sha(old), 'afterSHA256': sha(path.read_bytes())})
    Path('android/app/optimization-keep.pro').write_text('-keep class go.** { *; }\n-keep class studio.networkcore.** { *; }\n# Slot selection uses getSimpleName; retain both ends of the nested identity.\n-keepattributes InnerClasses,EnclosingMethod\n-keep,allowoptimization class com.forcemind.chatgpt.profiles.EnvironmentBrowserActivity { <init>(); }\n-keep,allowoptimization class com.forcemind.chatgpt.profiles.EnvironmentBrowserActivity$Slot* { <init>(); }\n# CI-only classes: named trust proof and manifest file provider; absent in human APK.\n-keep class com.forcemind.chatgpt.profiles.GeneratedNetworkTrust { public static java.lang.String PEM; }\n-keep class com.forcemind.chatgpt.profiles.EnvironmentNetworkFilesProvider { *; }\n')
    Path('android/app/optimization-ci-keep.pro').write_text('# AndroidJUnitRunner expects Trace via the unshrunk test runtime classpath.\n-keep class androidx.tracing.** { *; }\n# Test-visible DTO ABI: field shape stays fixed, callable bodies may optimize.\n-keep,allowoptimization class com.forcemind.chatgpt.profiles.WorkspaceState { <methods>; }\n-keepclassmembers class com.forcemind.chatgpt.profiles.WorkspaceState { <fields>; }\n-keep,allowoptimization class com.forcemind.chatgpt.profiles.WorkspaceState$Account { <methods>; }\n-keepclassmembers class com.forcemind.chatgpt.profiles.WorkspaceState$Account { <fields>; }\n-keep,allowoptimization class com.forcemind.chatgpt.profiles.WorkspaceState$Tab { <methods>; }\n-keepclassmembers class com.forcemind.chatgpt.profiles.WorkspaceState$Tab { <fields>; }\n')
    Path('android/app/optimization-instrumentation-keep.pro').write_text('-keepclasseswithmembers class * { @org.junit.Test <methods>; }\n')
    smoke = Path('android/app/src/regularEnvironmentTest/java/com/forcemind/chatgpt/profiles/OptimizationRunnerSmokeTest.java')
    assert not smoke.exists()
    smoke.write_text('package com.forcemind.chatgpt.profiles;\nimport org.junit.Test;\nimport androidx.test.platform.app.InstrumentationRegistry;\nimport static org.junit.Assert.assertEquals;\npublic final class OptimizationRunnerSmokeTest { @Test public void runtimeStarts() { assertEquals("com.forcemind.chatgpt.profiles", InstrumentationRegistry.getInstrumentation().getTargetContext().getPackageName()); } }\n')
    with Path('android/app/optimization-ci-keep.pro').open('a') as rules:
        rules.write('# Exact reflective members directly named by existing instrumented tests.\n-keepclassmembers class com.forcemind.chatgpt.profiles.MainActivity { *** change(...); *** showCurrent(...); *** downloads(...); *** finishAccountChange(...); }\n-keepclassmembers class com.forcemind.chatgpt.profiles.NetworkSession { *** jobs; }\n-keepclassmembers class com.forcemind.chatgpt.profiles.HandoffRuntime { *** controls; }\n')
    # Same strict evidence provenance checks, bound to this dedicated workflow.
    path = Path('android/scripts/regular_evidence_source.py'); old = path.read_bytes(); text = old.decode()
    needle = "WORKFLOW = '.github/workflows/android-regular-full-client.yml'"
    assert text.count(needle) == 1
    path.write_text(text.replace(needle, "WORKFLOW = '.github/workflows/android-regular-optimization.yml'"))
    edits.append({'path': str(path), 'beforeSHA256': sha(old), 'afterSHA256': sha(path.read_bytes())})
    path = Path('android/scripts/prepare-regular-ci-inputs.py'); old = path.read_bytes()
    appendix = '''
# Explicit unminified old-side instrumentation; never modify the original app.
old_side = Path('android/app/build/optimization-old-side-test')
old_test_receipt = json.loads((old_side/'baseline-test-receipt.json').read_text())
assert old_test_receipt['ciOnly'] is True and old_test_receipt['compiledAgainstUnminified'] is True
assert old_test_receipt['sourceSHA'] == os.environ['GITHUB_SHA'] and old_test_receipt['signerSHA256'] == ci_signer
assert sha(old_side/old_test_receipt['file']) == old_test_receipt['sha256']
shutil.copyfile(old_side/old_test_receipt['file'], inputs/old_test_receipt['file'])
shutil.copyfile(old_side/'baseline-test-receipt.json', inputs/'baseline-test-receipt.json')
'''
    path.write_text(old.decode()+appendix)
    edits.append({'path':str(path),'beforeSHA256':sha(old),'afterSHA256':sha(path.read_bytes())})
    path = Path('android/scripts/regular_environment_upgrade.py'); old = path.read_bytes(); text = old.decode()
    text = legacy_upgrade_overlay(text)
    path.write_text(text)
    edits.append({'path':str(path),'beforeSHA256':sha(old),'afterSHA256':sha(path.read_bytes())})
    receipt = {'schema': 1, 'mode': mode, 'versionName': stamp, 'harnessSHA': os.environ['GITHUB_SHA'],
               'productBaseline': BASE, 'ciOnlyOverlay': True, 'productionSigningUsed': False, 'edits': edits}
    (output / 'overlay.json').write_text(json.dumps(receipt, indent=2) + '\n')
    (output / 'overlay.diff').write_bytes(subprocess.check_output(['git', 'diff', '--',
                                                               'android/app/build.gradle', 'android/scripts/regular_evidence_source.py']))


if __name__ == '__main__':
    main()
