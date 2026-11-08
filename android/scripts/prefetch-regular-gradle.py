#!/usr/bin/env python3
"""Retry the two existing, checksum-pinned Gradle downloads on owned CI runners."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import urllib.parse

assert os.environ.get('GITHUB_ACTIONS') == 'true', 'Runner-only dependency download'
home = Path(os.environ.get('GRADLE_USER_HOME', str(Path.home() / '.gradle')))
proofs = []
for relative in ('android/gradle/wrapper/gradle-wrapper.properties',
                 'android/environment-poc/gradle/wrapper/gradle-wrapper.properties'):
    properties = dict(row.split('=', 1) for row in Path(relative).read_text().splitlines()
                      if '=' in row and not row.startswith('#'))
    url = properties['distributionUrl'].replace('\\:', ':')
    parsed = urllib.parse.urlsplit(url)
    assert parsed.scheme == 'https' and parsed.hostname == 'services.gradle.org'
    assert not parsed.query and not parsed.fragment and not parsed.username
    name = Path(parsed.path).name
    assert name in ('gradle-8.13-bin.zip', 'gradle-9.3.1-bin.zip')
    expected = properties['distributionSha256Sum']
    assert re.fullmatch(r'[a-f0-9]{64}', expected)
    for key in ('distributionBase', 'zipStoreBase'):
        assert properties[key] == 'GRADLE_USER_HOME'
    for key in ('distributionPath', 'zipStorePath'):
        assert properties[key] == 'wrapper/dists'
    # Gradle PathAssembler uses the URL MD5 as a positive base-36 cache key.
    # This is only a directory key; SHA-256 below authenticates the executable ZIP.
    value = int.from_bytes(hashlib.md5(url.encode()).digest(), 'big')
    alphabet = '0123456789abcdefghijklmnopqrstuvwxyz'
    key = ''
    while value:
        value, remainder = divmod(value, 36)
        key = alphabet[remainder] + key
    destination = home / 'wrapper/dists' / name[:-4] / (key or '0') / name
    destination.parent.mkdir(parents=True, exist_ok=True)
    partial = destination.with_name(name + '.studio-prefetch.part')
    assert not partial.exists(), 'Refuse to reuse a partial dependency download'
    try:
        subprocess.run(['curl', '--fail', '--location', '--silent', '--show-error',
                        '--retry', '3', '--retry-all-errors', '--retry-delay', '2',
                        '--connect-timeout', '15', '--max-time', '120',
                        '--output', str(partial), url], check=True)
        digest = hashlib.sha256()
        with partial.open('rb') as stream:
            for part in iter(lambda: stream.read(65536), b''):
                digest.update(part)
        assert digest.hexdigest() == expected, 'Pinned Gradle SHA-256 mismatch: ' + name
        partial.replace(destination)
        proofs.append({'properties': relative, 'url': url, 'file': name,
                       'sha256': expected, 'bytes': destination.stat().st_size,
                       'checksumVerified': True, 'wrapperPropertiesChanged': False})
        print('Verified existing pinned distribution: ' + name, flush=True)
    finally:
        partial.unlink(missing_ok=True)
output = Path('android/app/build/regular-gradle-prefetch.json')
output.parent.mkdir(parents=True, exist_ok=True)
output.write_text(json.dumps({'schema': 1, 'complete': True, 'distributions': proofs}, indent=2) + '\n')
