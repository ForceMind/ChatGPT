#!/usr/bin/env python3
"""Build the post-install reader independently of the R8 target's dependencies."""
import argparse
import os
from pathlib import Path

assert os.environ.get('GITHUB_ACTIONS') == 'true'
assert os.environ.get('GITHUB_REPOSITORY') == 'ForceMind/ChatGPT'
assert os.environ.get('GITHUB_REF') == 'refs/heads/dev'
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--output', type=Path, required=True)
output = parser.parse_args().output
assert not output.exists()

package = 'com.forcemind.chatgpt.profiles'
regular = Path('android/app/src/regularEnvironmentTest/java') / package.replace('.', '/')
source = (regular / 'RegularEnvironmentUpgradeTest.java').read_text()
# Retain only the platform-based browser and persisted JSON readers.
parts = [
    source[:source.index('    static int identity(')],
    source[source.index('    static JSONObject expected()'):source.index('    static void save(')],
    source[source.index('    static void sameJson('):source.index('    static void verifyNative(')],
    source[source.index('    static String js('):source.index('    static void execute(')],
]
reader = ''.join(parts).replace('RegularEnvironmentUpgradeTest', 'AppUpdateBrowserData') + '}\n'
for symbol in ('WorkspaceState', 'StateStore', 'DevicePayloadKeys', 'AssetLedger', 'PageRecoveryStore'):
    assert symbol not in reader
destination = output / 'src/main/java' / package.replace('.', '/')
destination.mkdir(parents=True)
(destination / 'AppUpdateBrowserData.java').write_text(reader)
helpers = Path('android/scripts/android')
(destination / 'OptimizationUpgradeFiles.java').write_text(
    (helpers / 'OptimizationUpgradeFiles.java').read_text().replace('RegularEnvironmentUpgradeTest', 'AppUpdateBrowserData'))
(destination / 'AppUpdateInstalledTest.java').write_bytes((helpers / 'AppUpdateInstalledTest.java').read_bytes())

(output / 'settings.gradle').write_text('''pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositories { google(); mavenCentral() } }
rootProject.name = 'Studio app update verification'
''')
(output / 'gradle.properties').write_text('android.useAndroidX=true\n')
(output / 'build.gradle').write_text('''plugins { id 'com.android.application' version '8.13.2' }
android {
    namespace 'com.forcemind.chatgpt.profiles.updatecheck'
    compileSdk 36
    defaultConfig {
        applicationId 'com.forcemind.chatgpt.profiles.updatecheck'
        minSdk 28
        targetSdk 35
        versionCode 1
        versionName '1'
    }
}
dependencies {
    implementation 'androidx.webkit:webkit:1.17.1'
    implementation 'androidx.test:runner:1.6.2'
    implementation 'androidx.test.ext:junit:1.2.1'
}
''')
(output / 'src/main/AndroidManifest.xml').write_text('''<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application android:allowBackup="false" android:label="Studio update verification" />
    <instrumentation android:name="androidx.test.runner.AndroidJUnitRunner"
        android:targetPackage="com.forcemind.chatgpt.profiles" />
</manifest>
''')
