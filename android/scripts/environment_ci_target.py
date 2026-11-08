"""Explicit identities for the two owned synthetic environment test targets."""
import os
from pathlib import Path
import re

import android_version as beta_version

TARGET = os.environ.get('STUDIO_ANDROID_CI_TARGET', 'networkbeta')
assert TARGET in ('networkbeta', 'regular'), 'Unknown owned CI target'
REGULAR = TARGET == 'regular'
if REGULAR:
    assert os.environ.get('GITHUB_ACTIONS') == 'true', 'Regular fixture target is Actions-only'
    import regular_environment_upgrade as native
    import regular_version as version
else:
    import environment_upgrade as native
    version = beta_version

PACKAGE = native.PACKAGE
VERSION_CODE = version.VERSION_CODE
load_version = version.load_version
assert_apk_version = version.assert_apk_version


def expected_signer():
    if REGULAR:
        return native._ci_signer()
    return native._json(Path('docs/signing/android-network-beta.json'))['certificateSHA256']


def validate_receipt(receipt, require_human_filename=False):
    if not REGULAR:
        return beta_version.validate_receipt(receipt, require_human_filename)
    metadata = version.validate_version(receipt['versionMetadata'])
    for field in ('versionCode', 'versionName', 'sourceSHA'):
        assert receipt[field] == metadata[field], field
    assert receipt['package'] == PACKAGE
    assert re.fullmatch(r'[a-f0-9]{64}', receipt['sha256'])
    assert type(receipt['bytes']) is int and receipt['bytes'] > 0
    assert Path(receipt['file']).name == receipt['file']
    if receipt.get('ciOnly') is True:
        assert not require_human_filename
        assert receipt['signerSHA256'] == expected_signer()
        assert receipt['productionSignatureVerified'] is False
    else:
        assert receipt['signing'] == 'UNSIGNED_REVIEW_ONLY'
        assert receipt['productionSignatureVerified'] is False
        assert receipt['debuggable'] is False and receipt['testOnly'] is False
        assert receipt['ciControlsIncluded'] is False and receipt['ciTrustIncluded'] is False
        assert receipt['abi'] == ['arm64-v8a'] and receipt['alignment16KiB'] is True
        assert receipt['file'] == 'ChatGPT-Studio-' + metadata['versionName'] + '-android-arm64-UNSIGNED-REVIEW.apk'
    return metadata


def assert_receipt_pair(ci, human):
    validate_receipt(ci)
    validate_receipt(human, require_human_filename=True)
    assert ci['sourceSHA'] == human['sourceSHA'] and ci['versionMetadata'] == human['versionMetadata']
    assert ci['ciOnly'] is True and ci['signerSHA256'] == expected_signer()
    if not REGULAR:
        assert human['signerSHA256'] == expected_signer()


def install(apk):
    return native._run(['adb', 'install', *(['-t'] if REGULAR else []), str(apk)])
