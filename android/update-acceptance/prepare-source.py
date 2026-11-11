"""Prepare the protected Pages assets from one locally signed acceptance target."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--receipt', type=Path, required=True)
parser.add_argument('--output', type=Path, required=True)
args = parser.parse_args()
receipt = json.loads(args.receipt.read_text())
metadata = receipt['versionMetadata']
assert metadata['versionCode'] == 3000040
assert receipt['package'] == 'com.forcemind.chatgpt.profiles'
assert receipt['certificateSHA256'] == '8b6eef928fded91f291ad954e20976b2cc6011b55e8cbec0152695fe1f05cf19'
assert receipt['privateKeyUploaded'] is False and receipt['publicRelease'] is False
name = receipt['file']
assert Path(name).name == name
apk = args.receipt.parent / name
assert apk.stat().st_size == receipt['bytes']
with apk.open('rb') as stream:
    sha = hashlib.sha256()
    for block in iter(lambda: stream.read(1048576), b''):
        sha.update(block)
assert sha.hexdigest() == receipt['sha256']
args.output.mkdir(exist_ok=False)
private = args.output / 'private'
private.mkdir()
parts = []
with apk.open('rb') as stream:
    index = 0
    while block := stream.read(20 * 1024 * 1024):
        file = private / f'part-{index:03}.bin'
        file.write_bytes(block)
        parts.append({'path': '/private/' + file.name, 'size': len(block)})
        index += 1
feed = {'schema': 1, 'packageName': receipt['package'], 'versionName': metadata['versionName'],
        'versionCode': metadata['versionCode'], 'size': receipt['bytes'], 'abi': 'arm64-v8a',
        'sha256': receipt['sha256'], 'certificateSHA256': receipt['certificateSHA256'],
        'url': 'https://studio-android-acceptance.pages.dev/update.apk'}
(private/'source.json').write_text(json.dumps({'feed':feed,'parts':parts})+'\n')
shutil.copyfile(Path(__file__).with_name('worker.mjs'), args.output/'_worker.js')
(args.output/'_routes.json').write_text(json.dumps({'version':1,'include':['/*'],'exclude':[]})+'\n')
print(f'Prepared {len(parts)} protected APK parts, {receipt["bytes"]} bytes')
