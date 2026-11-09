"""Generate the standalone original-certificate verifier for the 3000035 upgrade."""
import argparse
import os
from pathlib import Path
import subprocess
import sys

assert os.environ.get('GITHUB_ACTIONS') == 'true'
assert os.environ.get('GITHUB_REPOSITORY') == 'ForceMind/ChatGPT'
assert os.environ.get('GITHUB_REF') == 'refs/heads/dev'
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--classes', type=Path, required=True)
parser.add_argument('--output', type=Path, required=True)
args = parser.parse_args()
assert args.classes.is_dir()
assert (args.classes / 'com/forcemind/chatgpt/profiles/WorkspaceState.class').is_file()
assert not args.output.exists()

subprocess.run([sys.executable, 'android/scripts/prepare-original-signature-tests.py'], check=True)
legacy = Path('android/app/build/generated/original-signature-tests/com/forcemind/chatgpt/profiles/OriginalSignatureUpgradeData.java').read_text()
versions = 'OLD_CODE=3000022L, NEW_CODE=3000035L'
assert legacy.count(versions) == 1
legacy = legacy.replace(versions, 'OLD_CODE=3000035L, NEW_CODE=3000036L')
legacy = legacy.replace('original-public-alpha22-original-signature', 'original-public-regular-original-signature')

reader = legacy.replace('OriginalSignatureUpgradeData', 'OriginalSignatureUpgradeVerifyTest')
for start, end in (
    ('    static JSONObject seed()', '    static void sameJson('),
    ('    static void verifyNative(', '    static String js('),
):
    i, j = reader.index(start), reader.index(end)
    assert i < j
    reader = reader[:i] + reader[j:]
reader = reader.replace('JSONObject expected=seed?seed():expected()', 'assertFalse(seed);JSONObject expected=expected()')
assert reader.count('verifyNative(expected,subset)') == 2
reader = reader.replace('verifyNative(expected,subset)', 'OptimizationUpgradeFiles.verify(expected,subset)')
old_test = reader.index('    @Test public void seedOrColdReadOriginalPublicApp()')
reader = reader[:old_test] + '''    @Test public void actualReleaseRetainsTheOriginalData() throws Exception {
        String phase = args().getString("phase");
        assertTrue("verify-new".equals(phase) || "verify-new-cold".equals(phase));
        execute(phase, NEW_CODE, false, false);
    }
}
'''
for symbol in ('WorkspaceState', 'StateStore', 'DevicePayloadKeys', 'AssetLedger', 'PageRecoveryStore'):
    assert symbol not in reader
oracle = Path('android/scripts/android/OptimizationUpgradeFiles.java').read_text()
oracle = oracle.replace('RegularEnvironmentUpgradeTest', 'OriginalSignatureUpgradeVerifyTest')
destination = args.output / 'src/main/java/com/forcemind/chatgpt/profiles'
destination.mkdir(parents=True)
for name, source in (
    ('OriginalSignatureUpgradeData', legacy),
    ('OriginalSignatureUpgradeVerifyTest', reader),
    ('OptimizationUpgradeFiles', oracle),
):
    (destination / (name + '.java')).write_text(source)

(args.output / 'settings.gradle').write_text('''pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositories { google(); mavenCentral() } }
rootProject.name = 'Studio original upgrade verification'
''')
(args.output / 'gradle.properties').write_text('android.useAndroidX=true\n')
(args.output / 'build.gradle').write_text('''plugins { id 'com.android.application' version '8.13.2' }
android {
    namespace 'com.forcemind.chatgpt.profiles.originalcheck'
    compileSdk 36
    defaultConfig {
        applicationId 'com.forcemind.chatgpt.profiles.originalcheck'
        minSdk 28
        targetSdk 35
    }
}
dependencies {
    compileOnly files('baseline-api.jar')
    implementation 'androidx.webkit:webkit:1.17.1'
    implementation 'androidx.test:runner:1.6.2'
    implementation 'androidx.test.ext:junit:1.2.1'
}
''')
(args.output / 'src/main/AndroidManifest.xml').write_text('''<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application android:allowBackup="false" android:label="Studio original upgrade verification" />
    <instrumentation android:name="androidx.test.runner.AndroidJUnitRunner"
        android:targetPackage="com.forcemind.chatgpt.profiles" />
</manifest>
''')
subprocess.run(['jar', '--create', '--file', str(args.output / 'baseline-api.jar'),
                '-C', str(args.classes), 'com/forcemind/chatgpt/profiles'], check=True)
