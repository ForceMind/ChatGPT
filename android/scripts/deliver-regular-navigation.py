#!/usr/bin/env python3
"""Gate a navigation review, without presenting it as the final signed candidate."""
import importlib.util
import json
import os
from pathlib import Path
import shutil

assert os.environ.get('GITHUB_ACTIONS') == 'true'
spec = importlib.util.spec_from_file_location('regular_navigation_delivery', Path(__file__).with_name('deliver-regular-review.py'))
shared = importlib.util.module_from_spec(spec)
spec.loader.exec_module(shared)
review = Path('android/app/build/regular-review')
receipt, metadata = shared.inspect_human(review)
evidence = Path('android/app/build/regular-environment-evidence')
proofs = []
flags = ('nativeHeaderTitleSwitch', 'switchNoReload', 'disabledEmptyHistory', 'backForward',
         'normalAndForceRefresh', 'draftCookieStoragePreserved')
for api in (28, 35):
    folder = evidence / ('api' + str(api))
    proof = shared.read(folder / 'regular-navigation.json')
    assert proof['schema'] == 1 and proof['complete'] is True and proof['api'] == api
    assert proof['package'] == receipt['package'] and proof['sourceSHA'] == metadata['sourceSHA']
    assert proof['versionMetadata'] == metadata and proof['ciCandidateReceipt']['versionMetadata'] == metadata
    assert proof['sendCount'] == 0
    shared.flags(proof, ('productionSignatureVerified', 'screenshots', 'realServicesAccessed', 'backgroundLongTestsRepeated'), False)
    for key, filename in (('geckoNavigation', 'gecko-chrome-navigation.json'), ('webviewNavigation', 'browser-chrome-legacy.json')):
        result = proof[key]
        assert result == shared.read(folder / filename) and result['complete'] is True
        shared.flags(result, flags)
        shared.flags(result, ('screenshots', 'realServicesAccessed'), False)
    upgrade = proof['legacyUpgrade']
    assert upgrade == shared.read(folder / 'verified-synthetic.json') and upgrade['complete'] is True
    assert upgrade['sourceSHA'] == metadata['sourceSHA'] and upgrade['api'] == api
    assert upgrade['signerSHA256'] == proof['ciCandidateReceipt']['signerSHA256']
    assert upgrade['signatureOnlyBaseline']['nonSigningEntriesUnchanged'] is True
    assert upgrade['signatureOnlyBaseline']['original']['sha256'] == shared.native.OLD_HASH
    assert upgrade['productionSignatureVerified'] is False
    expected = shared.read(folder / 'upgrade-expected.json')
    for name, label in (('seed', 'seed'), ('oldCold', 'verify-old'), ('upgraded', 'verify-new'), ('upgradedCold', 'verify-new-cold')):
        shared.native._preserved(upgrade[name], expected, upgrade['seed'])
        shared.instrument(folder, label, upgrade[name])
    retained = proof['legacyAfterNavigation']
    assert retained == shared.read(folder / 'native-after-environment.json')
    assert retained['complete'] is True and retained['legacyAccountPreserved'] is True
    shared.native._preserved(retained['native'], expected, upgrade['seed'], subset=True)
    shared.instrument(folder, 'verify-after-environment', retained['native'])
    proofs.append(proof)
assert proofs[0]['ciCandidateReceipt'] == proofs[1]['ciCandidateReceipt']
output = Path('android/app/build/regular-review-delivery')
assert not output.exists()
output.mkdir()
for name in (receipt['file'], 'receipt.json', 'android-version.json', 'SHA256SUMS.txt', 'LICENSE'):
    shutil.copyfile(review / name, output / name)
assert shared.sha(output / receipt['file']) == receipt['sha256']
report = {'schema': 1, 'complete': True, 'scope': 'regular-navigation-only', 'versionMetadata': metadata,
          'humanUnsignedReceipt': receipt, 'apiProofs': proofs, 'formalSignedUpgradeVerified': False,
          'networkFileSkillGatesComplete': False, 'publicRelease': False}
(output / 'regular-navigation-acceptance.json').write_text(json.dumps(report, indent=2, ensure_ascii=False) + '\n')
(output / 'REVIEW.md').write_text('导航专项审阅：Android 9/15 双内核导航及原包临时同签升级验证。\n未签名 APK 不能安装或覆盖手机；网络/文件/Skill 总门禁和原证书覆盖候选仍待完成。\n')
print(json.dumps({'complete': True, 'scope': report['scope'], 'versionName': metadata['versionName'], 'sha256': receipt['sha256']}))
