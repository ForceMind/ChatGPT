#!/usr/bin/env python3
"""Fetch an immutable official AOSP provider in CI; inspect before installation."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import urllib.request
import zipfile


def run(args):
    return subprocess.check_output(args, text=True, stderr=subprocess.STDOUT, timeout=240)


def blob_id(data):
    return hashlib.sha1(b'blob ' + str(len(data)).encode() + b'\0' + data).hexdigest()


def parse_badging(badging, expected_package):
    package = re.search(r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", badging, re.M)
    sdk = re.search(r"^\s*(?:min)?sdkVersion:'(\d+)(?:\.(\d+))?'", badging, re.I | re.M)
    target = re.search(r"^\s*targetSdkVersion:'(\d+)(?:\.(\d+))?'", badging, re.M)
    if not package or not sdk or not target or package[1] != expected_package:
        raise RuntimeError('Unexpected official provider package metadata: ' + '\n'.join(badging.splitlines()[:12]))
    if int(sdk[2] or 0) != 0 or int(target[2] or 0) != 0:
        raise RuntimeError('Fractional SDK metadata is not supported by the API28 fixture')
    return {'package': package[1], 'versionCode': package[2], 'version': package[3],
            'minSdk': int(sdk[1]), 'targetSdk': int(target[1])}


def inspect(apk, catalog, pin, tools):
    data = apk.read_bytes()
    if blob_id(data) != pin['blob']:
        raise RuntimeError('Official provider Git blob verification failed')
    badging = run([str(tools/'aapt2'), 'dump', 'badging', str(apk)])
    signer = run([str(tools/'apksigner'), 'verify', '--verbose', '--print-certs', str(apk)])
    parsed = parse_badging(badging, catalog['package'])
    with zipfile.ZipFile(apk) as archive:
        abis = sorted({n.split('/')[1] for n in archive.namelist() if n.startswith('lib/') and n.endswith('.so')})
    meta = {**pin, 'repository': catalog['repository'], 'path': catalog['path'],
            **parsed, 'abis': abis,
            'bytes': len(data), 'sha256': hashlib.sha256(data).hexdigest(),
            'sourceUrl': catalog['repository'] + '/+/' + pin['commit'] + '/' + catalog['path'],
            'scope': 'CI emulator only; this historical provider is not a production security recommendation'}
    return meta, badging, signer


def fetch(catalog, pin, target):
    url = catalog['repository'] + '/+/' + pin['commit'] + '/' + catalog['path'] + '?format=TEXT'
    try:
        with urllib.request.urlopen(url, timeout=120) as response:
            target.write_bytes(base64.b64decode(response.read()))
    except Exception as failure:
        print('Gitiles transfer unavailable; fetching the same pinned official Git blob:', type(failure).__name__)
        with tempfile.TemporaryDirectory(prefix='aosp-webview-') as directory:
            repo = Path(directory)
            run(['git', 'init', '-q', str(repo)])
            run(['git', '-C', str(repo), 'remote', 'add', 'origin', catalog['repository']])
            run(['git', '-C', str(repo), 'fetch', '--depth=1', '--filter=blob:none', 'origin', pin['commit']])
            with target.open('wb') as out:
                subprocess.run(['git', '-C', str(repo), 'show', pin['commit'] + ':' + catalog['path']],
                               stdout=out, check=True, timeout=240)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', required=True)
    args = parser.parse_args()
    if os.environ.get('GITHUB_ACTIONS') != 'true':
        raise SystemExit('Provider acquisition runs only in GitHub Actions')
    output = Path(args.output)
    output.mkdir(parents=True, exist_ok=True)
    catalog = json.loads(Path(__file__).with_name('webview-providers.json').read_text())
    tools = Path(os.environ['ANDROID_HOME'])/'build-tools'/'36.0.0'
    rejected = []
    for pin in catalog['candidates']:
        apk = output/'webview.apk'
        cached = output/'provider.json'
        reuse = cached.exists() and apk.exists() and json.loads(cached.read_text()).get('blob') == pin['blob']
        if not reuse:
            fetch(catalog, pin, apk)
        meta, badging, signer = inspect(apk, catalog, pin, tools)
        if meta['minSdk'] > 28 or meta['targetSdk'] < 28 or 'x86_64' not in meta['abis']:
            rejected.append(meta)
            apk.unlink()
            continue
        meta['rejectedIncompatibleCandidates'] = rejected
        (output/'provider.json').write_text(json.dumps(meta, indent=2))
        (output/'badging.txt').write_text(badging)
        (output/'signer.txt').write_text(signer)
        print(json.dumps(meta, indent=2))
        return
    raise RuntimeError('No pinned official provider is compatible with API28/x86_64')


if __name__ == '__main__':
    main()
