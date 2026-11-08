#!/usr/bin/env python3
"""Gate a navigation review, without presenting it as the final signed candidate."""
import importlib.util
import hashlib
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
evidence = Path(os.environ.get('STUDIO_REGULAR_NAVIGATION_EVIDENCE','android/app/build/regular-environment-evidence'))
proofs = []
flags = ('nativeHeaderTitleSwitch', 'switchNoReload', 'disabledEmptyHistory', 'backForward',
         'normalAndForceRefresh', 'draftCookieStoragePreserved')
for api in (28, 35):
    folder = evidence / ('api' + str(api))
    proof = shared.read(folder / 'regular-navigation.json')
    assert proof['schema'] == 1 and proof['complete'] is True and proof['api'] == api
    assert proof['package'] == receipt['package'] and proof['sourceSHA'] == metadata['sourceSHA']
    assert proof['versionMetadata'] == metadata and proof['ciCandidateReceipt']['versionMetadata'] == metadata
    ci = proof['ciCandidateReceipt']
    assert ci['schema'] == 1 and ci['package'] == receipt['package'] and ci['file'] == 'regular-ci-x86.apk'
    for field in ('versionCode', 'versionName', 'sourceSHA'):
        assert ci[field] == metadata[field], field
    assert ci['ciOnly'] is True and ci['productionSignatureVerified'] is False
    assert ci['temporarySignatureNotForPersonalDevices'] is True
    assert type(ci['bytes']) is int and ci['bytes'] > 0
    shared.digest(ci['sha256'])
    signer = shared.digest(ci['signerSHA256'])
    assert signer not in (shared.native.PRODUCTION_SIGNER, shared.native.BETA_SIGNER)
    if os.environ.get('STUDIO_REGULAR_CI_CERT_SHA256'):
        assert signer == shared.native._ci_signer()
    assert proof['sendCount'] == 0
    assert proof['inputDeadlineSeconds'] == 75 and proof['inputMode'] == 'acknowledged-native-stream'
    assert proof['claimsBatchOrderingFixed'] is False
    prefixes, keys = proof['nativePrefixReceipts'], proof['nativeKeyboardReceipts']
    assert prefixes and len(prefixes) == len(keys)
    draft = prefixes[-1]['expectedPrefix']
    assert hashlib.sha256(draft.encode()).hexdigest() == proof['draftSHA256'] and len(draft) == len(prefixes)
    for index, (prefix, keyboard) in enumerate(zip(prefixes, keys)):
        assert prefix['index'] == index and prefix['complete'] is True
        assert prefix['expectedPrefix'] == prefix['draft'] == prefix['modelDraft'] == draft[:index+1]
        assert prefix['inputIdentityField'] == 'url' and prefix['inputIdentity'] == 'https://environment-fixture.test:18480/c/a1'
        assert prefix['inputCount'] == prefix['trustedInputCount'] == index+1
        assert keyboard['textLength'] == 1 and keyboard['textSHA256'] == hashlib.sha256(draft[index].encode()).hexdigest()
        assert keyboard['provider'] == 'ci-paced-ascii-keyboard' and keyboard['complete'] is True
        assert keyboard['api'] == api and keyboard['uid'] == 2000 and keyboard['qemu'] is True
        assert keyboard['paceMillis'] == 40 and keyboard['injectionMode'] == 2
        assert keyboard['events'] and all(e['accepted'] is True and e['waitForFinish'] is True for e in keyboard['events'])
    streams = proof['nativeInputStreams']
    assert len(streams) == 1 and streams[0]['complete'] is True and streams[0]['exitCode'] == 0
    stream = streams[0]
    assert type(stream['devicePID']) is int and stream['devicePID'] > 0
    assert len(stream['dispatches']) == len(prefixes)
    for phase, state in (('ready', stream['ready']), ('closed', stream['closed'])):
        assert state['provider'] == 'ci-native-ascii-stream' and state['phase'] == phase
        assert state['api'] == api and state['uid'] == 2000 and state['pid'] == stream['devicePID']
    assert stream['ready']['qemu'] is True and stream['ready']['dispatch'] == 0
    assert stream['closed']['complete'] is True and stream['closed']['dispatch'] == len(prefixes)
    stream_files = list(folder.glob('*-native-ascii-stream.json'))
    assert len(stream_files) == 1 and shared.read(stream_files[0]) == stream
    for index, dispatch in enumerate(stream['dispatches']):
        prefix, keyboard = prefixes[index], keys[index]
        assert dispatch['validated'] is True
        assert prefix['streamPID'] == stream['devicePID'] and prefix['streamDispatch'] == index
        assert prefix['keyboardReceiptIndex'] == index and prefix['document'] == prefixes[0]['document']
        assert shared.read(folder / ('native-prefix-' + str(index + 1) + '.json')) == prefix
        for phase, state in (('dispatch', dispatch['dispatch']), ('done', dispatch['done'])):
            assert state['provider'] == 'ci-native-ascii-stream' and state['phase'] == phase
            assert state['api'] == api and state['uid'] == 2000
            assert state['pid'] == stream['devicePID'] and state['dispatch'] == index
        assert dispatch['dispatch']['unicodeChar'] == ord(draft[index])
        name = dispatch['nativeReceipt']
        assert Path(name).name == name and name.startswith('ascii-keyboard-') and name.endswith('.json')
        assert shared.read(folder / name) == keyboard
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
    assert upgrade['candidateInstalledSHA256'] == ci['sha256']
    for field in ('file', 'sha256', 'package', 'versionCode', 'signerSHA256'):
        assert upgrade['candidateAPK'][field] == ci[field], field
    assert upgrade['fromVersionCode'] == shared.native.OLD_CODE and upgrade['toVersionCode'] == metadata['versionCode']
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
    assert retained['api'] == api and retained['candidateInstalledSHA256'] == ci['sha256']
    shared.native._preserved(retained['native'], expected, upgrade['seed'], subset=True)
    shared.instrument(folder, 'verify-after-environment', retained['native'])
    proofs.append(proof)
assert proofs[0]['ciCandidateReceipt'] == proofs[1]['ciCandidateReceipt']
output = Path(os.environ.get('STUDIO_REGULAR_NAVIGATION_OUTPUT','android/app/build/regular-review-delivery'))
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
