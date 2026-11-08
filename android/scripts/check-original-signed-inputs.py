#!/usr/bin/env python3
"""Bind locally signed APKs to a successful cloud build before owned upgrade tests."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import zipfile

assert os.environ.get('GITHUB_ACTIONS') == 'true'
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--review', type=Path, required=True)
parser.add_argument('--companions', type=Path, required=True)
parser.add_argument('--bundle', type=Path, required=True)
parser.add_argument('--output', type=Path, required=True)
parser.add_argument('--source-run', type=Path, required=True)
args = parser.parse_args()
assert not args.output.exists()


def read(path):
    return json.loads(path.read_text())


def sha(path):
    value = hashlib.sha256()
    with path.open('rb') as stream:
        for part in iter(lambda: stream.read(65536), b''):
            value.update(part)
    return value.hexdigest()


def member(root, receipt):
    assert Path(receipt['file']).name == receipt['file']
    path = root / receipt['file']
    assert path.is_file() and not path.is_symlink()
    assert path.stat().st_size == receipt['bytes'] and sha(path) == receipt['sha256']
    return path


def payload(path):
    result = {}
    with zipfile.ZipFile(path) as archive:
        assert len(archive.namelist()) == len(set(archive.namelist()))
        for item in archive.infolist():
            if re.fullmatch(r'META-INF/(?:MANIFEST\.MF|[^/]+\.(?:SF|RSA|DSA|EC))', item.filename, re.I):
                continue
            digest = hashlib.sha256()
            with archive.open(item) as stream:
                for part in iter(lambda: stream.read(65536), b''):
                    digest.update(part)
            result[item.filename] = (item.file_size, digest.hexdigest())
    return result


run = read(args.source_run)
assert run['repository']['full_name'] == 'ForceMind/ChatGPT'
assert run['event'] == 'workflow_dispatch' and run['head_branch'] == 'dev'
assert run['path'] == '.github/workflows/android-regular-full-client.yml'
assert run['status'] == 'completed' and run['conclusion'] == 'success'
report = read(args.review / 'regular-full-validation.json')
assert report['schema'] == 1 and report['complete'] is True
assert report['scope'] == 'regular-feature-validation-before-original-signing'
for flag in ('networkFileSkillGatesComplete', 'navigationGatesComplete', 'compatibilityRecoveryGatesComplete'):
    assert report[flag] is True
assert report['originalSignatureUpgradeVerified'] is False and report['publicRelease'] is False
metadata = report['versionMetadata']
assert metadata['sourceSHA'] == run['head_sha']
human = report['humanUnsignedReceipt']
assert human == read(args.review / 'receipt.json') and human['versionMetadata'] == metadata
assert human['package'] == 'com.forcemind.chatgpt.profiles' and human['signing'] == 'UNSIGNED_REVIEW_ONLY'
companion = read(args.companions / 'human-x86-receipt.json')
test = read(args.companions / 'test-receipt.json')
for item in (companion, test):
    assert item['versionMetadata'] == metadata and item['sourceSHA'] == run['head_sha']
assert companion['sameDexAsHumanArm64'] is True and companion['arm64UnsignedSHA256'] == human['sha256']
inputs = {'arm64': member(args.review, human), 'humanX86': member(args.companions, companion),
          'instrumentation': member(args.companions, test)}
expected_bundle = os.environ['STUDIO_SIGNED_BUNDLE_SHA256']
assert re.fullmatch(r'[a-f0-9]{64}', expected_bundle) and sha(args.bundle) == expected_bundle
with zipfile.ZipFile(args.bundle) as archive:
    names = archive.namelist()
    assert len(names) == len(set(names)) and 'signed-receipt.json' in names
    assert archive.getinfo('signed-receipt.json').file_size < 1024 * 1024
    receipt = json.loads(archive.read('signed-receipt.json'))
    assert receipt['schema'] == 1 and receipt['package'] == human['package']
    assert receipt['certificateSHA256'] == human['productionCertificateExpectedSHA256']
    assert receipt['versionMetadata'] == metadata and receipt['sourceSHA'] == run['head_sha']
    assert receipt['privateKeyUploaded'] is False and receipt['publicRelease'] is False
    assert set(names) == {'signed-receipt.json'} | {receipt[role]['file'] for role in inputs}
    args.output.mkdir(parents=True, exist_ok=False)
    for name in names:
        assert Path(name).name == name and not archive.getinfo(name).is_dir()
        assert archive.getinfo(name).file_size <= 700 * 1024 * 1024
        with archive.open(name) as source, (args.output / name).open('xb') as target:
            shutil.copyfileobj(source, target, 65536)
payloads = {}
for role, source in inputs.items():
    signed = member(args.output, receipt[role])
    assert receipt[role]['inputSHA256'] == sha(source) and receipt[role]['nonSigningEntriesUnchanged'] is True
    before, after = payload(source), payload(signed)
    assert before and before == after, 'Signing changed application/test contents: ' + role
    payloads[role] = after
arm_dex = {name: value for name, value in payloads['arm64'].items() if name.endswith('.dex')}
x86_dex = {name: value for name, value in payloads['humanX86'].items() if name.endswith('.dex')}
assert arm_dex and arm_dex == x86_dex
proof = {'schema': 1, 'complete': True, 'sourceRunID': run['id'], 'sourceSHA': run['head_sha'],
         'harnessSourceSHA': os.environ['GITHUB_SHA'], 'versionMetadata': metadata,
         'signedBundleSHA256': expected_bundle, 'signedReceipt': receipt,
         'allNonSigningEntriesUnchanged': True, 'actualSignedArm64X86DexEqual': True,
         'originalKeyUploaded': False, 'productionSignatureUpgradeVerified': False}
(args.output / 'signed-input-verification.json').write_text(json.dumps(proof, indent=2) + '\n')
print(json.dumps({'complete': True, 'sourceRunID': run['id'], 'sourceSHA': run['head_sha']}))
