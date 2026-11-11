"""CI-only byte-for-byte delta comparison of existing public packages."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import time

REPO = 'ForceMind/ChatGPT'
INPUTS = {
    'android': (
        ('2026.11.11-1517', 'ChatGPT-Studio-2026.11.11-1517-android-arm64.apk'),
        ('2026.11.11-2241', 'ChatGPT-Studio-2026.11.11-2241-android-arm64.apk'),
    ),
    'macos': (
        ('2026.11.11', 'ChatGPT-Profiles-2026.11.11-mac-arm64.zip'),
        ('2026.11.12', 'ChatGPT-Profiles-2026.11.12-mac-arm64.zip'),
    ),
    'windows': (
        ('2026.11.12', 'ChatGPT-Studio-2026.11.12-windows-x64-setup.exe'),
        ('2026.11.13', 'ChatGPT-Studio-2026.11.13-windows-x64-setup.exe'),
    ),
}


def sha256(file):
    with file.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def fetch(root, docs, version, filename):
    record = json.loads((docs / f'{version}.json').read_text())
    rows = record['artifacts'] if 'artifacts' in record else [record]
    expected, = (row for row in rows if row['file'] == filename)
    release = json.loads(subprocess.check_output(['gh', 'api', f'repos/{REPO}/releases/tags/v{version}']))
    assert not release['draft'] and not release['prerelease'] and release['tag_name'] == f'v{version}'
    asset, = (row for row in release['assets'] if row['name'] == filename)
    assert asset['size'] == expected['bytes'] and asset['digest'] == 'sha256:' + expected['sha256']
    file = root / filename
    with file.open('xb') as output:
        subprocess.run(['gh', 'api', f'repos/{REPO}/releases/assets/{asset["id"]}',
                        '-H', 'Accept: application/octet-stream'], stdout=output, check=True, timeout=240)
    assert file.stat().st_size == expected['bytes'] and sha256(file) == expected['sha256']
    print(f'Verified public package {filename}', flush=True)
    return file, expected


def main(platform):
    assert os.environ.get('GITHUB_ACTIONS') == 'true' and os.environ.get('GITHUB_REPOSITORY') == REPO
    root = Path(os.environ['RUNNER_TEMP']) / f'studio-delta-{platform}'
    root.mkdir()
    docs = Path(__file__).resolve().parents[2] / 'docs/releases'
    (base, previous), (target, current) = (fetch(root, docs, *row) for row in INPUTS[platform])
    delta, rebuilt = root / 'comparison.vcdiff', root / 'reconstructed-package'
    before = time.monotonic()
    subprocess.run(['xdelta3', '-e', '-D', '-s', str(base), str(target), str(delta)], check=True, timeout=240)
    encode_seconds = time.monotonic() - before
    before = time.monotonic()
    subprocess.run(['xdelta3', '-d', '-D', '-s', str(base), str(delta), str(rebuilt)], check=True, timeout=240)
    decode_seconds = time.monotonic() - before
    assert rebuilt.stat().st_size == current['bytes'] and sha256(rebuilt) == current['sha256']
    corrupted = root / 'truncated.vcdiff'
    with delta.open('rb') as stream:
        corrupted.write_bytes(stream.read(max(1, delta.stat().st_size // 2)))
    rejected = root / 'rejected-package'
    result = subprocess.run(['xdelta3', '-d', '-D', '-s', str(base), str(corrupted), str(rejected)],
                            capture_output=True, timeout=240)
    assert result.returncode != 0 or not rejected.is_file() or sha256(rejected) != current['sha256']
    report = {
        'complete': True, 'platform': platform, 'base': base.name, 'target': target.name,
        'baseSHA256': previous['sha256'], 'targetSHA256': current['sha256'],
        'targetBytes': current['bytes'], 'deltaBytes': delta.stat().st_size,
        'downloadSavingPercent': (1 - delta.stat().st_size / current['bytes']) * 100,
        'encodeSeconds': encode_seconds, 'decodeSeconds': decode_seconds,
        'reconstructedExactPublicBytes': True, 'truncatedDeltaRejected': True,
        'clientUpdaterIntegrated': False,
    }
    out = Path(os.environ['RUNNER_TEMP']) / 'studio-delta-report.json'
    out.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main(sys.argv[1])
