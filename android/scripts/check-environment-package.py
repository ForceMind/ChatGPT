"""Human environment package constraints, in Actions only. Never starts an app."""
import json, os, subprocess, zipfile
from pathlib import Path
from android_version import load_version, assert_apk_version
assert os.environ.get('GITHUB_ACTIONS') == 'true'
root=Path('android/app/build')
source=(root/'generated/source/buildConfig/networkBeta/com/forcemind/chatgpt/profiles/BuildConfig.java').read_text()
assert 'ENVIRONMENT_CLIENT = true' in source
assert 'ENVIRONMENT_TEST = false' in source and 'ENVIRONMENT_HOME_URL = ""' in source
assert 'ENVIRONMENT_NETWORK_TEST = false' in source and 'NETWORK_TEST = false' in source
apk=root/'outputs/apk/networkBeta/app-networkBeta.apk'
metadata=load_version()
assert metadata['sourceSHA']==os.environ['GITHUB_SHA']
badging=subprocess.check_output([str(Path(os.environ['ANDROID_HOME'])/'build-tools/35.0.0/aapt2'),'dump','badging',str(apk)],text=True)
assert_apk_version(badging,metadata,source)
resources=subprocess.check_output([str(Path(os.environ['ANDROID_HOME'])/'build-tools/35.0.0/aapt2'),'dump','resources',str(apk)],text=True)
assert 'raw/studio_environment_network_ci_ca' not in resources and 'xml/studio_environment_network_security' not in resources
with zipfile.ZipFile(apk) as z:
    manifest=json.loads(z.read('assets/studio-environment/manifest.json'))
    assert set(manifest['content_scripts'][0]['matches'])=={'https://chatgpt.com/*','https://chat.openai.com/*'}
    for name in z.namelist():
        if name.endswith('.dex'):
            data=z.read(name)
            for forbidden in (b'EnvironmentAcceptanceActivity',b'EnvironmentUpgradeSeedTest',b'environment-fixture.test',b'EnvironmentNetworkFilesProvider',b'EnvironmentNetworkControl',b'GeneratedNetworkTrust',b'network-fixture.test'):
                assert forbidden not in data, 'Synthetic control leaked into human APK'
    assert not any('studio_environment_network_ci_ca' in name or 'studio_environment_network_security' in name for name in z.namelist())
    assert any(name.endswith('/libxul.so') for name in z.namelist())
folder=root/'network-beta'
(folder/'GECKO-NOTICE.txt').write_text('GeckoView 158.0.20261021001945, Mozilla Public License 2.0 and bundled third-party notices.\nExact SDK and source archive: https://maven.mozilla.org/maven2/org/mozilla/geckoview/geckoview/158.0.20261021001945/\nMozilla source and licensing: https://firefox-source-docs.mozilla.org/\nThe complete Gecko runtime license resources remain in the unmodified upstream runtime assets.\n')
