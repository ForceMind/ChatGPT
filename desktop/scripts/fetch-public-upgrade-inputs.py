"""Fetch exact public desktop release bytes for disposable Actions verification."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys

assert os.environ.get('GITHUB_ACTIONS') == 'true'
assert os.environ.get('GITHUB_REPOSITORY') == 'ForceMind/ChatGPT'
root = Path(os.environ['RUNNER_TEMP']) / 'studio-public-upgrade'
root.mkdir(exist_ok=False)
platform = sys.argv[1]
if platform == 'Windows':
    files = [
        (version, f'ChatGPT-Studio-{version}-windows-x64-setup.exe')
        for version in ('2026.11.9', '2026.11.10', '2026.11.11')
    ]
else:
    assert platform == 'macOS'
    files = [
        ('2026.11.10', 'ChatGPT-Studio-2026.11.10-mac-arm64.zip'),
        ('2026.11.11', 'ChatGPT-Profiles-2026.11.11-mac-arm64.zip'),
    ]
for version, name in files:
    record = json.loads(Path(f'../docs/releases/{version}.json').read_text())
    expected, = [row for row in record['artifacts'] if row['file'] == name]
    release = json.loads(subprocess.check_output([
        'gh', 'api', f'repos/ForceMind/ChatGPT/releases/tags/v{version}'
    ]))
    assert release['tag_name'] == f'v{version}' and not release['draft'] and not release['prerelease']
    asset, = [row for row in release['assets'] if row['name'] == name]
    assert asset['size'] == expected['bytes'] and asset['digest'] == 'sha256:' + expected['sha256']
    with (root / name).open('xb') as output:
        subprocess.run([
            'gh', 'api', f"repos/ForceMind/ChatGPT/releases/assets/{asset['id']}",
            '-H', 'Accept: application/octet-stream'
        ], stdout=output, check=True)
    with (root / name).open('rb') as downloaded:
        assert hashlib.file_digest(downloaded, 'sha256').hexdigest() == expected['sha256']
    assert (root / name).stat().st_size == expected['bytes']
    print(f'Verified public asset: {name}', flush=True)
