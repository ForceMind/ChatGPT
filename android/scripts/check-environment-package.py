"""Human environment package constraints, in Actions only. Never starts an app."""
import json, os, zipfile
from pathlib import Path
assert os.environ.get('GITHUB_ACTIONS') == 'true'
root=Path('android/app/build')
source=(root/'generated/source/buildConfig/networkBeta/com/forcemind/chatgpt/profiles/BuildConfig.java').read_text()
assert 'ENVIRONMENT_CLIENT = true' in source
assert 'ENVIRONMENT_TEST = false' in source and 'ENVIRONMENT_HOME_URL = ""' in source
apk=root/'outputs/apk/networkBeta/app-networkBeta.apk'
with zipfile.ZipFile(apk) as z:
    manifest=json.loads(z.read('assets/studio-environment/manifest.json'))
    assert set(manifest['content_scripts'][0]['matches'])=={'https://chatgpt.com/*','https://chat.openai.com/*'}
    for name in z.namelist():
        if name.endswith('.dex'):
            data=z.read(name)
            for forbidden in (b'EnvironmentAcceptanceActivity',b'EnvironmentUpgradeSeedTest',b'environment-fixture.test'):
                assert forbidden not in data, 'Synthetic control leaked into human APK'
    assert any(name.endswith('/libxul.so') for name in z.namelist())
folder=root/'network-beta'
(folder/'GECKO-NOTICE.txt').write_text('GeckoView 158.0.20261021001945, Mozilla Public License 2.0 and bundled third-party notices.\nExact SDK and source archive: https://maven.mozilla.org/maven2/org/mozilla/geckoview/geckoview/158.0.20261021001945/\nMozilla source and licensing: https://firefox-source-docs.mozilla.org/\nThe complete Gecko runtime license resources remain in the unmodified upstream runtime assets.\n')
