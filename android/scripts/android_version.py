"""One shared Android candidate timestamp. Loading/reusing metadata never reads the clock."""
import argparse
from datetime import datetime
import json
import os
from pathlib import Path
import re
from zoneinfo import ZoneInfo

VERSION_CODE = 3000032
TIME_ZONE = 'Asia/Shanghai'
DEFAULT_FILE = Path('android/app/build/android-version.json')


def validate_version(value):
    assert isinstance(value, dict) and type(value.get('schema')) is int and value['schema'] == 1, 'Unknown Android version metadata'
    assert type(value.get('versionCode')) is int and value['versionCode'] == VERSION_CODE, 'Unexpected candidate version code'
    assert value.get('timeZone') == TIME_ZONE, 'Android candidate time zone differs'
    name = value.get('versionName', '')
    assert re.fullmatch(r'\d{4}\.\d{2}\.\d{2}-\d{4}', name), 'Expected YYYY.MM.DD-HHmm'
    assert datetime.strptime(name, '%Y.%m.%d-%H%M').strftime('%Y.%m.%d-%H%M') == name
    assert re.fullmatch(r'[a-f0-9]{40}', value.get('sourceSHA', '')), 'Missing immutable App source SHA'
    return value


def load_version(path=None):
    source = Path(path or os.environ.get('STUDIO_ANDROID_VERSION_FILE', str(DEFAULT_FILE)))
    return validate_version(json.loads(source.read_text(encoding='utf-8')))


def apk_filename(version_name, arch='arm64'):
    assert re.fullmatch(r'\d{4}\.\d{2}\.\d{2}-\d{4}', version_name)
    assert arch in ('arm64', 'x86_64')
    return 'ChatGPT-Studio-' + version_name + '-android-' + arch + '.apk'


def validate_receipt(receipt, require_human_filename=False):
    metadata = validate_version(receipt['versionMetadata'])
    for field in ('versionName', 'versionCode', 'sourceSHA'):
        assert receipt[field] == metadata[field], 'Receipt and version metadata differ: ' + field
    assert receipt['package'] == 'com.forcemind.chatgpt.profiles.networkbeta'
    assert re.fullmatch(r'[a-f0-9]{64}', receipt['sha256'])
    assert re.fullmatch(r'[a-f0-9]{64}', receipt['signerSHA256'])
    assert isinstance(receipt['bytes'], int) and receipt['bytes'] > 0
    assert Path(receipt['file']).name == receipt['file']
    if require_human_filename:
        assert receipt['file'] == apk_filename(metadata['versionName'])
        assert receipt.get('ciOnly') is not True, 'CI package cannot be a human delivery'
    return metadata


def assert_apk_version(badging, metadata, build_config=None):
    validate_version(metadata)
    code = re.search(r"versionCode='(\d+)'", badging)
    name = re.search(r"versionName='([^']+)'", badging)
    assert code and int(code[1]) == metadata['versionCode'] and name and name[1] == metadata['versionName'], 'APK version differs from shared metadata'
    if build_config is not None:
        generated_code = re.search(r'\bVERSION_CODE\s*=\s*(\d+)\s*;', build_config)
        generated_name = re.search(r'\bVERSION_NAME\s*=\s*("(?:[^"\\]|\\.)*")\s*;', build_config)
        assert generated_code and int(generated_code[1]) == metadata['versionCode']
        assert generated_name and json.loads(generated_name[1]) == metadata['versionName'], 'BuildConfig display version differs from PackageManager/APK'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    actions = parser.add_subparsers(dest='action', required=True)
    create = actions.add_parser('create')
    create.add_argument('--output', type=Path, default=DEFAULT_FILE)
    create.add_argument('--source-sha', default=os.environ.get('GITHUB_SHA'))
    reuse = actions.add_parser('from-receipt')
    reuse.add_argument('--receipt', type=Path, required=True)
    reuse.add_argument('--output', type=Path, default=DEFAULT_FILE)
    read = actions.add_parser('read')
    read.add_argument('--file', type=Path, default=DEFAULT_FILE)
    read.add_argument('--field', choices=('versionName', 'versionCode', 'sourceSHA'))
    args = parser.parse_args()
    if args.action == 'read':
        value = load_version(args.file)
        print(value[args.field] if args.field else json.dumps(value, sort_keys=True))
        return
    if args.action == 'create':
        assert os.environ.get('GITHUB_ACTIONS') == 'true', 'Candidate timestamp generation is Actions-only'
        value = validate_version({'schema': 1, 'versionCode': VERSION_CODE,
                                  'versionName': datetime.now(ZoneInfo(TIME_ZONE)).strftime('%Y.%m.%d-%H%M'),
                                  'timeZone': TIME_ZONE, 'sourceSHA': args.source_sha})
    else:
        receipt = json.loads(args.receipt.read_text(encoding='utf-8'))
        value = validate_receipt(receipt)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    if args.action == 'from-receipt' and args.output.exists():
        assert load_version(args.output) == value, 'Refuse to overwrite a different original build timestamp'
    else:
        with args.output.open('x', encoding='utf-8') as output:
            output.write(json.dumps(value, indent=2) + '\n')
    print(json.dumps(value, sort_keys=True))


if __name__ == '__main__':
    main()
