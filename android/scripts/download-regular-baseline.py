#!/usr/bin/env python3
"""Download the exact public alpha.22 baseline through its official asset API."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import time

assert os.environ.get('GITHUB_ACTIONS') == 'true'
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--output', type=Path, required=True)
args = parser.parse_args()
assert not args.output.exists()
expected = '39a06004608162884c5d2a11277eee840f5311438376bd7541adc638b1e55e53'
release = json.loads(subprocess.check_output([
    'gh', 'api', 'repos/ForceMind/ChatGPT/releases/tags/v3.0.0-alpha.22'], timeout=60))
assert release['tag_name'] == 'v3.0.0-alpha.22' and release['draft'] is False
assets = [item for item in release['assets'] if item['name'] == 'ChatGPT-Studio-3.0.0-alpha.22-android.apk']
assert len(assets) == 1
asset = assets[0]
assert asset['size'] == 7376521 and asset['state'] == 'uploaded'
if asset.get('digest') is not None:
    assert asset['digest'] == 'sha256:' + expected
args.output.parent.mkdir(parents=True, exist_ok=True)
partial = args.output.with_name(args.output.name + '.download-part')
assert not partial.exists()
try:
    for attempt in range(3):
        with partial.open('wb') as stream:
            result = subprocess.run([
                'gh', 'api', 'repos/ForceMind/ChatGPT/releases/assets/' + str(asset['id']),
                '-H', 'Accept: application/octet-stream', '-H', 'Cache-Control: no-cache',
                '--method', 'GET', '-f', 'studio_ci_request=' + os.environ['GITHUB_RUN_ID'] + '-' + str(attempt)
            ], stdout=stream, timeout=120)
        if result.returncode == 0:
            break
        if attempt < 2:
            time.sleep(2)
    assert result.returncode == 0 and partial.stat().st_size == 7376521
    assert hashlib.sha256(partial.read_bytes()).hexdigest() == expected, 'Public baseline byte mismatch'
    partial.replace(args.output)
finally:
    partial.unlink(missing_ok=True)
print(json.dumps({'complete': True, 'originalPublicBaseline': True, 'bytes': 7376521,
                  'sha256': expected, 'releaseAssetID': asset['id'], 'transport': 'official-asset-api'}))
