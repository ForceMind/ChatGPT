#!/usr/bin/env python3
"""Verify original CI packages and stage a normal release; never publish it here."""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess

assert os.environ.get('GITHUB_ACTIONS') == 'true'
REPO = 'ForceMind/ChatGPT'
assert os.environ['GITHUB_REPOSITORY'] == REPO and os.environ['GITHUB_REF'] == 'refs/heads/dev'


def read(path):
    return json.loads(Path(path).read_text(encoding='utf-8-sig'))


def api(path):
    return json.loads(subprocess.check_output(['gh', 'api', 'repos/' + REPO + '/' + path]))


def unique(folder, name):
    paths = [p for p in Path(folder).rglob(name) if p.is_file()]
    assert len(paths) == 1 and not paths[0].is_symlink(), 'Missing/duplicate input: ' + name
    return paths[0]


def digest(path, algorithm='sha256'):
    value = hashlib.new(algorithm)
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1048576), b''):
            value.update(chunk)
    return value.hexdigest()


version = read('desktop/package.json')['version']
assert re.fullmatch(r'[1-9][0-9]*\.[1-9][0-9]*\.[1-9][0-9]*', version)
manifest = read('docs/releases/' + version + '.json')
assert manifest['version'] == version and manifest['prerelease'] is False
tag = 'v' + version
assert re.fullmatch(r'[a-f0-9]{40}', manifest['sourceCommit'])
workflows = {'macos': '.github/workflows/macos-release.yml', 'windows': '.github/workflows/windows-formal-validation.yml',
             'android': '.github/workflows/android-regular-original-signature.yml'}
verified_runs = {}
for platform, workflow in workflows.items():
    expected = manifest['runs'][platform]
    run = api('actions/runs/' + str(expected['id']))
    assert run['status'] == 'completed' and run['conclusion'] == 'success', platform
    assert run['head_sha'] == expected['sourceCommit'] and run['path'] == workflow
    assert run['event'] == 'workflow_dispatch' and run['head_branch'] == 'dev'
    verified_runs[platform] = {'id': run['id'], 'sourceCommit': run['head_sha'], 'conclusion': 'success'}

desktop_inputs = ['desktop/src', 'desktop/resources', 'desktop/package.json',
                  'desktop/package-lock.json', 'desktop/tsconfig.json', 'desktop/scripts/build.mjs',
                  'desktop/scripts/icon.mjs', 'desktop/scripts/package-mac.mjs',
                  'desktop/scripts/package-win.mjs', 'desktop/scripts/windows-update-feed.mjs', 'LICENSE']
for platform in ('macos', 'windows'):
    # Windows migration/NSIS resources are not included in the macOS bundle.
    assets = ['desktop/assets'] if platform == 'windows' else [
        'desktop/assets/icon.png', 'desktop/assets/icon.svg', 'desktop/assets/menu-bar-template.svg']
    subprocess.run(['git', 'diff', '--exit-code', manifest['runs'][platform]['sourceCommit'], 'HEAD', '--',
                    *desktop_inputs, *assets], check=True)
subprocess.run(['git', 'diff', '--exit-code', manifest['androidSourceCommit'], 'HEAD', '--',
                'android/app/src', 'android/app/build.gradle', 'android/app/proguard-rules.pro',
                'android/network-poc/corebridge', 'desktop/resources/capabilities'], check=True)

mac = read(unique('promotion/mac-info', 'mac-release-info.json'))
win = read(unique('promotion/win-installer', 'windows-build-info.json'))
assert mac['version'] == win['version'] == version
assert mac['commit'] == manifest['runs']['macos']['sourceCommit']
assert win['sourceCommit'] == manifest['runs']['windows']['sourceCommit']
ui_mac = read(unique('promotion/mac-info', 'mac-ui-verification.json'))
ui_win = read(unique('promotion/win-evidence', 'windows-ui.json'))
for stats in (ui_mac['stats'], ui_win['stats']):
    assert stats['unexpected'] == stats['flaky'] == 0 and stats['expected'] > 0
upgrade = read(unique('promotion/win-evidence', 'windows-upgrade.json'))
assert upgrade['sourceCommit'] == manifest['runs']['windows']['sourceCommit']
assert all(upgrade[k] is True for k in ('installed', 'identityRetained', 'launch', 'temporaryCleanup'))
assert upgrade['prepared']['newVersion'] == version
smoke = read(unique('promotion/win-evidence', 'windows-smoke.json'))
assert all(smoke[k] is True for k in ('installed', 'launched', 'normalExit', 'uninstalled', 'accountDataRetained'))
brand_paths = [p for p in Path('promotion/win-evidence').rglob('verified.json') if p.parent.name == 'windows-branding']
assert len(brand_paths) == 1
branding = read(brand_paths[0])
assert branding['complete'] is True and branding['sourceSHA'] == manifest['runs']['windows']['sourceCommit']
assert len(branding['cases']) == 6 and all(p['complete'] is True for p in branding['cases'])
for name in ('windows-install-fixture.json', 'windows-upgrade-fixture.json'):
    owned = read(unique('promotion/win-evidence', name))
    assert owned['synthetic'] is True and len(owned['requests']) > 0
retention = read(unique('promotion/win-evidence', 'windows-native-retention.json'))['retention']
assert all(retention[k] is True for k in ('passed', 'stable', 'active')) and retention['durationMs'] >= 330000
assert len(retention['before']) == len(retention['after']) == 8
for old in retention['before']:
    current = next(p for p in retention['after'] if p['id'] == old['id'])
    assert current['state']['instance'] == old['state']['instance']
    assert current['state']['draft'] == old['state']['draft'] and current['state']['discarded'] is False

android = read('promotion/android/original-signed-candidate-acceptance.json')
assert android['complete'] is True and android['installableCandidateDelivered'] is True
assert android['sourceSHA'] == manifest['androidSourceCommit']
assert android['certificateSHA256'] == '8b6eef928fded91f291ad954e20976b2cc6011b55e8cbec0152695fe1f05cf19'
assert android['originalKeyUploaded'] is False and android['arm64PhysicalDeviceVerified'] is False
assert [p['api'] for p in android['originalCertificateUpgrades']] == [28, 35]
for proof in android['originalCertificateUpgrades']:
    assert proof['productionSignatureUpgradeVerified'] and proof['originalPublicAPKUnchanged']
    assert proof['uninstallOrClearBetweenVersions'] is False
    assert proof['originalInstalledSHA256'] == '39a06004608162884c5d2a11277eee840f5311438376bd7541adc638b1e55e53'

artifacts = manifest['artifacts']
assert len(artifacts) == 6 and len({p['file'] for p in artifacts}) == 6
assert {p['platform'] for p in artifacts} == {'macos', 'windows', 'android'}
out = Path('promotion/normal-out');out.mkdir(exist_ok=False)
for item in artifacts:
    name = item['file'];assert Path(name).name == name
    platform = item['platform']
    if platform == 'macos':
        inputs = [p for folder in ('promotion/mac-dmg', 'promotion/mac-zip') for p in Path(folder).rglob(name)]
        expected = next(p for p in mac['artifacts'] if p['file'] == name)
    elif platform == 'windows':
        inputs = [p for folder in ('promotion/win-installer', 'promotion/win-zip') for p in Path(folder).rglob(name)]
        expected = next(p for p in win['artifacts'] if p['file'] == name)
    else:
        expected = android['humanSignedReceipt'];assert name == expected['file']
        inputs = [Path('promotion/android') / name]
    assert len(inputs) == 1 and inputs[0].is_file() and not inputs[0].is_symlink()
    assert item['bytes'] == expected['bytes'] == inputs[0].stat().st_size
    assert item['sha256'] == expected['sha256'] == digest(inputs[0])
    assert item['url'] == 'https://github.com/' + REPO + '/releases/download/' + tag + '/' + name
    shutil.copyfile(inputs[0], out / name)
exe = next(p for p in artifacts if p['file'].endswith('.exe'))
assert upgrade['prepared']['installerSHA256'] == exe['sha256']
release_info = {**manifest, 'publisherCommit': os.environ['GITHUB_SHA'], 'verifiedRuns': verified_runs,
                'packagesRebuilt': False, 'realServicesVerified': False, 'arm64AndroidDeviceVerified': False}
(out / 'release-info.json').write_text(json.dumps(release_info, indent=2) + '\n')
for folder, name in [('promotion/mac-info', 'mac-ui-verification.json'),
                     ('promotion/mac-info', 'mac-update-verification.json'),
                     ('promotion/mac-info', 'mac-recovery-verification.json'),
                     ('promotion/mac-info', 'menu-bar-package.json'),
                     ('promotion/win-evidence', 'windows-smoke.json'),
                     ('promotion/win-evidence', 'windows-install-fixture.json'),
                     ('promotion/win-evidence', 'windows-upgrade-fixture.json'),
                     ('promotion/win-evidence', 'windows-upgrade.json'),
                     ('promotion/win-evidence', 'windows-native-retention.json')]:
    shutil.copyfile(unique(folder, name), out / name)
shutil.copyfile(brand_paths[0], out / 'windows-branding-verification.json')
shutil.copyfile('promotion/android/original-signed-candidate-acceptance.json', out / 'android-original-upgrade.json')
checksums = ''.join(digest(p) + '  ' + p.name + '\n' for p in sorted(out.iterdir()))
(out / 'SHA256SUMS.txt').write_text(checksums)
expected_assets = [{'file': p.name, 'bytes': p.stat().st_size, 'sha256': digest(p)} for p in sorted(out.iterdir())]
releases = api('releases?per_page=100')
matches = [r for r in releases if r['tag_name'] == tag];assert len(matches) == 1
release_id = matches[0]['id']


def draft():
    value = api('releases/' + str(release_id))
    assert value['draft'] is True and value['prerelease'] is False
    assert value['tag_name'] == tag and value['target_commitish'] == manifest['sourceCommit']
    assert all(a['name'] in {p['file'] for p in expected_assets} for a in value['assets'])
    return value


for item in expected_assets:
    current = draft()
    existing = next((p for p in current['assets'] if p['name'] == item['file']), None)
    if existing:
        assert existing['size'] == item['bytes'] and existing['digest'] == 'sha256:' + item['sha256']
    else:
        subprocess.run(['gh', 'release', 'upload', tag, str(out / item['file']), '--repo', REPO], check=True)
final = draft()
assert len(final['assets']) == len(expected_assets)
for item in expected_assets:
    actual = next(p for p in final['assets'] if p['name'] == item['file'])
    assert actual['state'] == 'uploaded' and actual['size'] == item['bytes']
    assert actual['digest'] == 'sha256:' + item['sha256']
receipt = {'schema': 1, 'complete': True, 'releaseID': release_id, 'tag': tag,
           'sourceCommit': manifest['sourceCommit'], 'publisherCommit': os.environ['GITHUB_SHA'],
           'verifiedRuns': verified_runs, 'assets': expected_assets, 'draft': True, 'public': False}
Path('promotion/normal-staging-receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
print(json.dumps({'complete': True, 'releaseID': release_id, 'assets': len(expected_assets), 'draft': True}))
