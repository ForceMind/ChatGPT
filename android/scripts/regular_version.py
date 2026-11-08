"""Shared date metadata for the original-package Android compatibility release."""
import argparse
from datetime import datetime
import json
import os
from pathlib import Path
from zoneinfo import ZoneInfo

import android_version as shared

VERSION_CODE = 3000035
TIME_ZONE = shared.TIME_ZONE
DEFAULT_FILE = Path('android/app/build/regular-android-version.json')


def validate_version(value):
    return shared.validate_version(value, expected_version_code=VERSION_CODE)


def load_version(path=None):
    source = path or os.environ.get('STUDIO_ANDROID_VERSION_FILE', str(DEFAULT_FILE))
    return shared.load_version(source, expected_version_code=VERSION_CODE)


def apk_filename(version_name, arch='arm64'):
    return shared.apk_filename(version_name, arch)


def assert_apk_version(badging, metadata, build_config=None):
    return shared.assert_apk_version(badging, metadata, build_config,
                                     expected_version_code=VERSION_CODE)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    actions = parser.add_subparsers(dest='action', required=True)
    create = actions.add_parser('create')
    create.add_argument('--output', type=Path, default=DEFAULT_FILE)
    create.add_argument('--source-sha', default=os.environ.get('GITHUB_SHA'))
    read = actions.add_parser('read')
    read.add_argument('--file', type=Path, default=DEFAULT_FILE)
    read.add_argument('--field', choices=('versionName', 'versionCode', 'sourceSHA'))
    args = parser.parse_args()
    if args.action == 'read':
        value = load_version(args.file)
        print(value[args.field] if args.field else json.dumps(value, sort_keys=True))
        return
    assert os.environ.get('GITHUB_ACTIONS') == 'true', 'Candidate timestamp generation is Actions-only'
    value = validate_version({'schema': 1, 'versionCode': VERSION_CODE,
                              'versionName': datetime.now(ZoneInfo(TIME_ZONE)).strftime('%Y.%m.%d-%H%M'),
                              'timeZone': TIME_ZONE, 'sourceSHA': args.source_sha})
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open('x', encoding='utf-8') as output:
        output.write(json.dumps(value, indent=2) + '\n')
    print(json.dumps(value, sort_keys=True))


if __name__ == '__main__':
    main()
