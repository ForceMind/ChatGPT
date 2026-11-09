"""Authenticate the locally signed 3000036 test inputs before installing them."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import zipfile

assert os.environ.get('GITHUB_ACTIONS') == 'true'
assert os.environ.get('GITHUB_REPOSITORY') == 'ForceMind/ChatGPT'
assert os.environ.get('GITHUB_REF') == 'refs/heads/dev'
parser = argparse.ArgumentParser(description=__doc__)
for name in ('bundle', 'companions', 'harness', 'output'):
    parser.add_argument('--' + name, type=Path, required=True)
args = parser.parse_args()


def sha(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()


def payload(path):
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        assert len(names) == len(set(names))
        return {name: hashlib.sha256(archive.read(name)).hexdigest() for name in names
                if not re.fullmatch(r'META-INF/(?:MANIFEST\.MF|[^/]+\.(?:SF|RSA|DSA|EC))', name, re.I)}


assert sha(args.bundle) == os.environ['STUDIO_SIGNED_BUNDLE_SHA256']
companion = json.loads((args.companions / 'human-x86-receipt.json').read_text())
harness = args.harness / 'formal-upgrade-harness-unsigned.apk'
assert sha(harness) == (args.harness / 'SHA256SUMS').read_text().split()[0]
assert companion['file'] == 'human-x86-unsigned.apk' and companion['sameDexAsHumanArm64'] is True
x86 = args.companions / companion['file']
assert sha(x86) == companion['sha256']
with zipfile.ZipFile(args.bundle) as archive:
    assert archive.getinfo('signed-receipt.json').file_size < 16 * 1024
    receipt = json.loads(archive.read('signed-receipt.json'))
    assert receipt['schema'] == 1 and receipt['package'] == 'com.forcemind.chatgpt.profiles'
    assert receipt['sourceRunID'] == int(os.environ['STUDIO_FORMAL_RUN'])
    assert receipt['harnessRunID'] == int(os.environ['STUDIO_FORMAL_HARNESS_RUN'])
    assert receipt['sourceSHA'] == companion['sourceSHA'] == os.environ['STUDIO_FORMAL_SOURCE_SHA']
    assert receipt['versionMetadata'] == companion['versionMetadata']
    assert receipt['versionMetadata']['versionCode'] == 3000036
    assert receipt['arm64']['inputSHA256'] == companion['arm64UnsignedSHA256']
    assert receipt['privateKeyUploaded'] is False and receipt['publicRelease'] is False
    names = ['signed-receipt.json', 'current-public-companion-x86.apk']
    for role in ('humanX86', 'instrumentation'):
        names.append(receipt[role]['file'])
    assert sorted(archive.namelist()) == sorted(names)
    args.output.mkdir(parents=True, exist_ok=False)
    for name in names:
        assert name and Path(name).name == name and archive.getinfo(name).file_size < 300 * 1024 * 1024
        with archive.open(name) as source, (args.output / name).open('xb') as target:
            shutil.copyfileobj(source, target)

for role, source in (('humanX86', x86), ('instrumentation', harness)):
    signed = args.output / receipt[role]['file']
    assert sha(signed) == receipt[role]['sha256'] and signed.stat().st_size == receipt[role]['bytes']
    assert sha(source) == receipt[role]['inputSHA256'] and payload(source) == payload(signed)
