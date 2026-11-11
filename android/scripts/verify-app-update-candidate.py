#!/usr/bin/env python3
"""Bind the two emulator reports and the ARM64 review bytes to one Actions run."""
import argparse
import hashlib
import json
import os
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--review', type=Path, required=True)
parser.add_argument('--evidence', type=Path, required=True)
parser.add_argument('--out', type=Path, required=True)
args = parser.parse_args()
assert os.environ.get('GITHUB_ACTIONS') == 'true'
assert os.environ.get('GITHUB_REPOSITORY') == 'ForceMind/ChatGPT'
assert os.environ.get('GITHUB_REF') == 'refs/heads/dev'

receipt = json.loads((args.review / 'receipt.json').read_text())
assert receipt['versionMetadata']['sourceSHA'] == os.environ['GITHUB_SHA']
assert receipt['package'] == 'com.forcemind.chatgpt.profiles'
auto = os.environ.get('STUDIO_APP_UPDATE_AUTO') == 'true'
private = os.environ.get('STUDIO_APP_UPDATE_ACCEPTANCE') == 'true'
source_code = 3000039 if private else 3000042 if auto else 3000037
assert receipt['versionMetadata']['versionCode'] == source_code
assert receipt['signing'] == 'UNSIGNED_REVIEW_ONLY' and receipt['abi'] == 'arm64-v8a'
assert receipt['r8'] and receipt['alignment16KiB'] and not receipt['ciEndpointIncluded']
assert Path(receipt['file']).name == receipt['file']
apk = args.review / receipt['file']
assert apk.stat().st_size == receipt['bytes']
with apk.open('rb') as stream:
    assert hashlib.file_digest(stream, 'sha256').hexdigest() == receipt['sha256']

if private:
    target = json.loads((args.review / 'target/receipt.json').read_text())
    assert target['versionMetadata']['versionCode'] == 3000040
    assert target['versionMetadata']['sourceSHA'] == os.environ['GITHUB_SHA'] and target['privateSourceEnabled']
    target_apk = args.review / 'target' / target['file']
    assert target_apk.stat().st_size == target['bytes']
    with target_apk.open('rb') as stream:
        assert hashlib.file_digest(stream, 'sha256').hexdigest() == target['sha256']

results = []
for api in (28, 35):
    evidence = args.evidence / f'android-app-update-api-{api}'
    paths = evidence.rglob('auto-report.json' if auto else 'report.json')
    reports = [json.loads(path.read_text()) for path in paths]
    report, = reports if auto else [row for row in reports if 'systemConfirmationTapped' in row]
    assert report['complete'] is True and report['api'] == api and report['sourceSHA'] == os.environ['GITHUB_SHA']
    assert report['source']['versionName'] == receipt['versionMetadata']['versionName']
    if auto:
        assert report['source']['versionCode'] == source_code and report['source']['package'] == receipt['package']
        assert report['syntheticOnly'] and not report['screenshots']
        if private:
            assert report['privateSourceControl'] is True
        assert set(report['steps']) == {'startup', 'engine-dedupe', 'noon-job', 'foreground-notice',
                                        'missed-slot', 'manual-check', 'explicit-download', 'fixed-tab-entry',
                                        'full-title', 'live-tab-draft', 'gecko-tab-list'}
        assert [row['path'] for row in report['requests']] == ['/android.json'] * 4 + ['/update.apk']
        assert all(row['throughSelectedLine'] and not row['credentialHeader'] for row in report['requests'])
        assert not any(path.suffix.lower() in {'.png','.jpg','.jpeg','.webm','.mp4'}
                       for path in evidence.rglob('*') if path.is_file())
        results.append(report)
        continue
    assert report['source']['versionCode'] == 3000037 and report['target']['versionCode'] == 3000038
    for key in ('source', 'target'):
        assert report[key]['package'] == receipt['package']
        assert report[key]['signerSHA256'] == report['temporarySigner']
    assert report['systemConfirmationTapped'] and report['coldCookieLocalStorageIndexedDb']
    assert report['coldDecryptedRecoveryAndPayload']
    assert set(report['steps']) == {'both-entries', 'progress', 'background', 'route-lease', 'cancel', 'bad-packages',
                                    'retry', 'permission', 'live-tabs', 'draft', 'system-confirmation'}
    assert sum(path.startswith('page-recovery/') for path in report['preservedFileSHA256']) == 2
    requests = report['requests']
    assert requests and all(row['throughSelectedLine'] and not row.get('credentialHeader', False) for row in requests)
    assert sum(row.get('mode') == 'valid' and row['path'] == '/update.apk' for row in requests) == 2
    results.append(report)

for key in (('source',) if auto else ('source', 'target', 'temporarySigner')):
    assert results[0][key] == results[1][key]
assert args.out.parent.is_dir() and not args.out.exists()
args.out.write_text(json.dumps({
    'schema': 1,
    'runId': os.environ['GITHUB_RUN_ID'],
    'review': receipt,
    'android9And15SyntheticAcceptance': True,
    'automaticChecks': auto,
    'privateSourceCandidate': private,
    'targetReview': target if private else None,
    'physicalDeviceTested': False,
    'productionSignatureUpgradeTested': False,
    'reports': results,
}, indent=2) + '\n')
