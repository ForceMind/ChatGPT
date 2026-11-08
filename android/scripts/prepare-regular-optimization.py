"""Apply an explicit CI-only optimization overlay; never edit production locally."""
import argparse
from datetime import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess

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
    parser = argparse.ArgumentParser(); parser.add_argument('--phase', choices=['overlay', 'version'], required=True)
    args = parser.parse_args()
    os.chdir(ROOT)
    output = Path('android/app/build/optimization'); output.mkdir(parents=True, exist_ok=True)
    if args.phase == 'version':
        target = Path('android/app/build/regular-android-version.json')
        assert not target.exists()
        target.write_text(json.dumps({'schema': 1, 'versionCode': 3000035, 'versionName': stamp,
                                     'timeZone': 'Asia/Shanghai', 'sourceSHA': os.environ['GITHUB_SHA']}, indent=2) + '\n')
        return
    subprocess.run(['git', 'diff', '--exit-code', BASE, 'HEAD', '--', 'android/app',
                    'android/network-poc/corebridge', 'desktop/resources/capabilities'], check=True)
    edits = []
    path = Path('android/app/build.gradle'); old = path.read_bytes(); text = old.decode()
    needle = '            minifyEnabled false\n'
    assert text.count(needle) == 1
    replacement = ('            minifyEnabled ' + ('false' if mode == 'baseline' else 'true') + '\n'
                   '            shrinkResources ' + ('true' if mode == 'resources' else 'false') + '\n'
                   "            proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'optimization-keep.pro'\n")
    path.write_text(text.replace(needle, replacement))
    edits.append({'path': str(path), 'beforeSHA256': sha(old), 'afterSHA256': sha(path.read_bytes())})
    Path('android/app/optimization-keep.pro').write_text('-keep class go.** { *; }\n-keep class studio.networkcore.** { *; }\n# CI-only classes: named trust proof and manifest file provider; absent in human APK.\n-keep class com.forcemind.chatgpt.profiles.GeneratedNetworkTrust { public static java.lang.String PEM; }\n-keep class com.forcemind.chatgpt.profiles.EnvironmentNetworkFilesProvider { *; }\n')
    # Same strict evidence provenance checks, bound to this dedicated workflow.
    path = Path('android/scripts/regular_evidence_source.py'); old = path.read_bytes(); text = old.decode()
    needle = "WORKFLOW = '.github/workflows/android-regular-full-client.yml'"
    assert text.count(needle) == 1
    path.write_text(text.replace(needle, "WORKFLOW = '.github/workflows/android-regular-optimization.yml'"))
    edits.append({'path': str(path), 'beforeSHA256': sha(old), 'afterSHA256': sha(path.read_bytes())})
    receipt = {'schema': 1, 'mode': mode, 'versionName': stamp, 'harnessSHA': os.environ['GITHUB_SHA'],
               'productBaseline': BASE, 'ciOnlyOverlay': True, 'productionSigningUsed': False, 'edits': edits}
    (output / 'overlay.json').write_text(json.dumps(receipt, indent=2) + '\n')
    (output / 'overlay.diff').write_bytes(subprocess.check_output(['git', 'diff', '--',
                                                               'android/app/build.gradle', 'android/scripts/regular_evidence_source.py']))


if __name__ == '__main__':
    main()
