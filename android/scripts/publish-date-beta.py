"""Promote the verified Android artifact in Actions; never rebuild or overwrite assets."""
import argparse
from datetime import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import time
import xml.etree.ElementTree as ET
import zipfile

REPO = 'ForceMind/ChatGPT'
TAG = 'android-beta-2026.11.07-1905'
APP_SOURCE = '364ddd14d2fb76f234c3284e1372e8de9b37c4f8'
HARNESS_SOURCE = 'a94185dbb39a9115b3b39d3d10dc27e8df223234'
APK = 'ChatGPT-Studio-2026.11.07-1905-android-arm64.apk'
APK_SHA = '8555c1b1e8ad64659bdccd8832c84302a2a8986f049235d9d9f77eae416d4125'
CERT_SHA = 'bd7b9c924795ec21d27c73f280b5fc71b83ffea950e36e05967b77f092056ac0'


def command(*args):
    return subprocess.check_output(args, text=True).strip()


def api(path):
    result = subprocess.run(['gh', 'api', 'repos/' + REPO + '/' + path], text=True,
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=90)
    if result.returncode:
        raise RuntimeError('GitHub read failed: ' + path + '\n' + result.stderr)
    return json.loads(result.stdout)


def read(path):
    return json.loads(path.read_text(encoding='utf-8'))


def find_release():
    matches = [row for row in api('releases?per_page=100') if row['tag_name'] == TAG]
    assert len(matches) <= 1
    return matches[0] if matches else None


def digest(path):
    value = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            value.update(chunk)
    return value.hexdigest()


def verify_and_stage(root, reports, stage):
    assert os.environ.get('GITHUB_ACTIONS') == 'true' and os.environ['GITHUB_REPOSITORY'] == REPO
    assert command('git', 'rev-parse', 'HEAD') == os.environ['GITHUB_SHA']
    final = api('actions/runs/42370754805')
    assert final['status'] == 'completed' and final['conclusion'] == 'success' and final['head_sha'] == HARNESS_SOURCE
    artifact = api('actions/artifacts/13574370247')
    assert not artifact['expired'] and artifact['workflow_run']['id'] == 42370754805
    assert artifact['name'] == 'ChatGPT-Studio-Android-2026.11.07-1905-Candidate'
    assert artifact['size_in_bytes'] == 111828723
    subprocess.run(['git', 'cat-file', '-e', APP_SOURCE + '^{commit}'], check=True)
    changed = command('git', 'diff', '--name-only', APP_SOURCE, 'HEAD', '--', 'android').splitlines()
    assert set(changed) <= {'android/scripts/run-gecko-handoff.py', 'android/scripts/publish-date-beta.py'}, changed
    subprocess.run(['git', 'diff', '--exit-code', APP_SOURCE, 'HEAD', '--',
                    'desktop/resources/capabilities', '.github/workflows/android-environment-client.yml'], check=True)
    receipt = read(root / 'receipt.json')
    assert receipt['sourceSHA'] == APP_SOURCE and receipt['versionName'] == '2026.11.07-1905' and receipt['versionCode'] == 3000034
    assert receipt['file'] == APK and receipt['bytes'] == 241907607 and receipt['sha256'] == APK_SHA
    assert receipt['signerSHA256'] == CERT_SHA and receipt['package'] == 'com.forcemind.chatgpt.profiles.networkbeta'
    assert receipt['abi'] == ['arm64-v8a'] and receipt['minAndroid'] == 9 and receipt['alignment16KiB']
    assert all(receipt[key] is False for key in ('debuggable', 'testOnly', 'ciTrustIncluded', 'syntheticNodesIncluded'))
    assert read(root / 'android-version.json') == receipt['versionMetadata']
    assert [path.name for path in root.rglob('*.apk')] == [APK]
    assert (root / APK).stat().st_size == receipt['bytes'] and digest(root / APK) == APK_SHA
    clients = read(root / 'client-environment-and-recovery-acceptance.json')
    assert {row['api'] for row in clients} == {28, 35} and len(clients) == 2
    assert all(row['human']['complete'] and row['engine']['complete'] and
               row['human']['fromVersionCode'] == 3000033 and row['human']['toVersionCode'] == 3000034 for row in clients)
    network = read(root / 'gecko-vless-route-acceptance.json')
    assert len(network) == 6 and all(row['complete'] for row in network)
    assert {(row['api'], row['transport']) for row in network} == {(api, mode) for api in (28, 35) for mode in ('raw', 'tls', 'reality')}
    for filename in ('gecko-local-exports-summary.json', 'gecko-handoff-summary.json'):
        summary = read(root / 'evidence' / filename)
        assert summary['complete'] and summary['applicationSourceSHA'] == APP_SOURCE
        assert len(summary['apis']) == 2 and all(row['complete'] for row in summary['apis'])
    tasks = read(root / 'evidence/gecko-handoff-summary.json')
    assert tasks['onceScope'] == 'application-task' and tasks['transportLevelReplayGuarantee'] is False
    assert tasks['knownTransportReplayFailure']['includedInCurrentPass'] is False
    assert all(row['ownedAppFatalCount'] == 0 and row['uncertainReopenNoReplay'] for row in tasks['apis'])
    timing = read(root / 'evidence/navigation-timing-cases.json')
    assert timing['complete'] and timing['sourceSHA'] == APP_SOURCE
    assert {key: row['classification'] for key, row in timing['cases'].items()} == {
        'baseline-late-poll': 'expected-original-assertion-failure', 'fixed-late-poll': 'fixed-original-assertions-pass',
        'fixed-early-poll': 'safe-refusal-pass'}
    recovery = read(root / 'evidence/candidate-recovery-source.json')
    assert recovery['applicationSourceSHA'] == APP_SOURCE and recovery['harnessSourceSHA'] == HARNESS_SOURCE
    assert recovery['applicationAndOtherDriversUnchanged'] and len(recovery['reusedSuccessfulJobs']) == 14
    assert recovery['originalFailure']['includedInPass'] is False
    assert all(row['includedInPass'] is False for row in recovery['additionalFailedHarnessRuns'])
    unit_files = [path for path in reports.rglob('*.xml') if 'testDebugUnitTest' in path.parts]
    totals = [0, 0, 0, 0]
    for path in unit_files:
        suite = ET.fromstring(path.read_bytes())
        for index, key in enumerate(('tests', 'failures', 'errors', 'skipped')):
            totals[index] += int(suite.get(key, 0))
    assert len(unit_files) == 17 and totals == [85, 0, 0, 0], totals
    stage.mkdir(parents=True, exist_ok=False)
    shutil.copyfile(root / APK, stage / APK)
    shutil.copyfile(root / 'INSTALL.md', stage / 'INSTALL.android-beta.md')
    archive = stage / 'ChatGPT-Studio-2026.11.07-1905-android-evidence.zip'
    stamp = datetime.strptime(receipt['versionName'], '%Y.%m.%d-%H%M')
    with zipfile.ZipFile(archive, 'w', compression=zipfile.ZIP_DEFLATED) as output:
        for path in sorted(root.rglob('*')):
            if not path.is_file() or path.name == APK:
                continue
            assert not path.is_symlink() and not any(part.startswith('.') for part in path.relative_to(root).parts)
            body = path.read_bytes()
            assert not re.search(rb'-----BEGIN [A-Z ]*PRIVATE KEY-----|vless://|vmess://', body, re.IGNORECASE), str(path)
            info = zipfile.ZipInfo(path.relative_to(root).as_posix(), (stamp.year, stamp.month, stamp.day, stamp.hour, stamp.minute, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o100644 << 16
            output.writestr(info, body)
    manifest = {'schema': 1, 'tag': TAG, 'versionName': receipt['versionName'], 'versionCode': receipt['versionCode'],
                'releaseSourceSHA': os.environ['GITHUB_SHA'], 'applicationSourceSHA': APP_SOURCE,
                'harnessSourceSHA': HARNESS_SOURCE, 'candidateRun': 42370754805, 'candidateArtifact': 13574370247,
                'originalBuildRun': 42366675306, 'apk': receipt, 'unitTests': totals,
                'recoverySource': recovery, 'rebuilt': False, 'resigned': False,
                'knownTLSAndHistoricalBackRootCausesResolved': False,
                'files': [{'file': path.name, 'bytes': path.stat().st_size, 'sha256': digest(path)} for path in sorted(stage.iterdir())]}
    (stage / 'android-beta-release-manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    sums = [digest(path) + '  ' + path.name for path in sorted(stage.iterdir())]
    (stage / 'SHA256SUMS.android-beta.txt').write_text('\n'.join(sums) + '\n')
    assert digest(stage / APK) == APK_SHA
    return manifest


def publish(stage, manifest):
    sha = os.environ['GITHUB_SHA']
    assert api('branches/dev')['commit']['sha'] == sha, 'dev advanced; preserve concurrent work'
    subprocess.run(['git', 'fetch', 'origin', 'main', 'dev', '--no-tags'], check=True)
    main = command('git', 'rev-parse', 'origin/main')
    subprocess.run(['git', 'merge-base', '--is-ancestor', main, sha], check=True)
    # checkout credentials are this job's GITHUB_TOKEN, so the approved fast-forward
    # does not recursively start old branch/tag build workflows.
    subprocess.run(['git', 'push', 'origin', sha + ':refs/heads/main'], check=True)
    assert api('branches/main')['commit']['sha'] == api('branches/dev')['commit']['sha'] == sha
    release = find_release()
    if release is None:
        subprocess.run(['gh', 'release', 'create', TAG, '--target', sha, '--title',
                        'ChatGPT Studio Android Beta · 2026.11.07-1905', '--notes-file',
                        'docs/releases/android-beta-2026.11.07-1905-notes.md', '--draft', '--prerelease', '--latest=false'], check=True)
        release = find_release()
        assert release is not None
    assert release['prerelease'] and release['target_commitish'] == sha
    files = {path.name: path for path in stage.iterdir()}
    existing = {row['name']: row for row in release['assets']}
    assert set(existing) <= set(files), 'Refuse unreviewed assets'
    for name, asset in existing.items():
        assert asset['size'] == files[name].stat().st_size and asset['digest'] == 'sha256:' + digest(files[name]), name
    if not release['draft']:
        assert set(existing) == set(files), 'Published release is immutable'
    else:
        for name in sorted(set(files) - set(existing)):
            subprocess.run(['gh', 'release', 'upload', TAG, str(files[name])], check=True)
        for attempt in range(12):
            release = api('releases/' + str(release['id']))
            existing = {row['name']: row for row in release['assets']}
            if set(existing) == set(files) and all(row.get('digest') for row in existing.values()):
                break
            time.sleep(2)
        assert set(existing) == set(files)
        for name, asset in existing.items():
            assert asset['size'] == files[name].stat().st_size and asset['digest'] == 'sha256:' + digest(files[name]), name
        subprocess.run(['gh', 'release', 'edit', TAG, '--draft=false', '--prerelease', '--latest=false'], check=True)
        release = api('releases/' + str(release['id']))
    assert release['draft'] is False and release['prerelease'] is True
    ref = api('git/ref/tags/' + TAG)['object']
    if ref['type'] == 'tag':
        ref = api('git/tags/' + ref['sha'])['object']
    assert ref['type'] == 'commit' and ref['sha'] == sha
    result = {'schema': 1, 'releaseURL': release['html_url'], 'tag': TAG, 'releaseId': release['id'],
              'publishedAt': release['published_at'], 'isDraft': release['draft'], 'isPrerelease': release['prerelease'],
              'mergedMainAndDevSHA': sha, 'manifest': manifest,
              'assets': [{key: row[key] for key in ('id', 'name', 'size', 'digest', 'browser_download_url')}
                         for row in release['assets']]}
    Path('android-beta-publication-receipt.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps({'releaseURL': result['releaseURL'], 'tag': TAG, 'assets': len(result['assets']),
                      'mainAndDevSHA': sha, 'apkSHA256': APK_SHA, 'rebuilt': False}))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--candidate', type=Path, required=True)
    parser.add_argument('--reports', type=Path, required=True)
    parser.add_argument('--stage', type=Path, required=True)
    args = parser.parse_args()
    checked = verify_and_stage(args.candidate, args.reports, args.stage)
    publish(args.stage, checked)
