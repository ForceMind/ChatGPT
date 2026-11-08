#!/usr/bin/env python3
"""Deliver only the unsigned ARM64 review after both owned regular-client CI proofs."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import zipfile

import regular_environment_upgrade as native
from regular_version import assert_apk_version, validate_version

HASH = re.compile(r'[a-f0-9]{64}')
PROOF_FLAGS = ('encryptedPayloadPreserved', 'assetPreserved', 'preferencesPreserved',
               'persistedRegistryVerified', 'httpOnlySecureCookiePreserved',
               'localStoragePreserved', 'indexedDbPreserved', 'inlineOfflineDocumentOnly',
               'sameTemporaryCISignature')
FLOW_FLAGS = ('languageTimeZoneAndWorkerVerified', 'httpAcceptLanguageVerified',
              'cookieLocalStorageIndexedDBAccountIsolation', 'sameEnvironmentDifferentStorageContexts',
              'trustedNativeTextareaAndContenteditableInput', 'realNativeReadingScroll',
              'liveSwitchKeptDocumentAndDraftAndScroll')


def sha(path):
    return native._sha(path)


def regular_file(path):
    assert path.is_file() and not path.is_symlink(), f'Missing or symlinked input: {path}'
    return path


def read(path):
    return json.loads(regular_file(path).read_text(encoding='utf-8'))


def digest(value):
    assert isinstance(value, str) and HASH.fullmatch(value), 'Missing SHA256'
    return value


def flags(value, names, expected=True):
    for name in names:
        assert value[name] is expected, name


def inspect_human(folder):
    receipt = read(folder / 'receipt.json')
    metadata = validate_version(read(folder / 'android-version.json'))
    assert receipt['schema'] == 1 and receipt['versionMetadata'] == metadata
    for field in ('versionCode', 'versionName', 'sourceSHA'):
        assert receipt[field] == metadata[field], field
    assert metadata['sourceSHA'] == os.environ['GITHUB_SHA']
    assert receipt['package'] == native.PACKAGE and receipt['versionCode'] == native.NEW_CODE
    assert receipt['file'] == 'ChatGPT-Studio-' + metadata['versionName'] + '-android-arm64-UNSIGNED-REVIEW.apk'
    assert receipt['signing'] == 'UNSIGNED_REVIEW_ONLY' and receipt['abi'] == ['arm64-v8a'] and receipt['minAndroid'] == 9
    flags(receipt, ('productionSignatureVerified', 'debuggable', 'testOnly', 'ciControlsIncluded',
                    'ciTrustIncluded', 'personalDeviceInstalled', 'publicRelease'), False)
    flags(receipt, ('environmentClient', 'alignment16KiB'))
    assert receipt['productionCertificateExpectedSHA256'] == native.PRODUCTION_SIGNER
    apk = regular_file(folder / receipt['file'])
    assert apk.stat().st_size == receipt['bytes'] and sha(apk) == digest(receipt['sha256'])
    assert regular_file(folder / 'SHA256SUMS.txt').read_text().strip() == receipt['sha256'] + '  ' + receipt['file']
    assert regular_file(folder / 'LICENSE').read_bytes() == regular_file(Path('LICENSE')).read_bytes()
    tools = native._tools()
    badging = native._run([str(tools / 'aapt'), 'dump', 'badging', str(apk)]).stdout
    manifest = native._run([str(tools / 'aapt'), 'dump', 'xmltree', str(apk), 'AndroidManifest.xml']).stdout
    assert re.search(r"(?m)^package: name='com\.forcemind\.chatgpt\.profiles' ", badging)
    assert "application-label:'ChatGPT Studio'" in badging and 'application-debuggable' not in badging
    assert re.search(r"(?:minSdkVersion|sdkVersion):'28'", badging)
    assert_apk_version(badging, metadata)
    assert 'android:testOnly' not in manifest and 'EnvironmentAcceptanceActivity' not in manifest
    unsigned = native._run([str(tools / 'apksigner'), 'verify', str(apk)], check=False)
    assert unsigned.returncode != 0, 'Human review APK must remain unsigned'
    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
        assert len(names) == len(set(names))
        assert not any(re.fullmatch(r'META-INF/[^/]+\.(SF|RSA|DSA|EC)', name, re.I) for name in names)
        # v2/v3 signing blocks live outside ZIP entries, immediately before the central directory.
        with apk.open('rb') as stream:
            assert archive.start_dir >= 16
            stream.seek(archive.start_dir - 16)
            assert stream.read(16) != b'APK Sig Block 42', 'APK signing block in unsigned review'
        libs = [name for name in names if name.endswith('.so')]
        assert libs and any(name.endswith('/libxul.so') for name in libs)
        assert all(name.startswith('lib/arm64-v8a/') for name in libs)
        forbidden = (b'EnvironmentAcceptanceActivity', b'RegularEnvironmentUpgradeTest', b'EnvironmentUpgradeSeedTest',
                     b'EnvironmentNetworkControl', b'EnvironmentNetworkFilesProvider', b'GeneratedNetworkTrust',
                     b'EnvironmentLocalExportProbe', b'EnvironmentHandoffProbe',
                     b'environment-fixture.test', b'network-fixture.test')
        for name in names:
            assert 'studio_environment_network_ci_ca' not in name and 'studio_environment_network_security' not in name
            if name.endswith('.dex'):
                assert all(marker not in archive.read(name) for marker in forbidden), 'CI fixture leaked into human APK'
        extension = json.loads(archive.read('assets/studio-environment/manifest.json'))
        assert set(extension['content_scripts'][0]['matches']) == {'https://chatgpt.com/*', 'https://chat.openai.com/*'}
    native._run([str(tools / 'zipalign'), '-c', '-P', '16', '4', str(apk)])
    return receipt, metadata


def instrument(directory, label, expected):
    path = regular_file(directory / (label + '.txt'))
    text = path.read_text(encoding='utf-8')
    assert re.findall(r'(?m)^OK \((\d+) test\)\s*$', text) == ['1'], label
    assert not re.search(r'FAILED|FAILURES!!!|shortMsg=', text), label
    entries = re.findall(r'(?m)^INSTRUMENTATION_RESULT: regularEnvironmentUpgradeProof=(\{[^\r\n]+\})\s*$', text)
    assert len(entries) == 1 and json.loads(entries[0]) == expected, label


def verify_api(directory, api, metadata, shared_flow):
    proof = read(directory / 'regular-functional.json')
    assert proof['schema'] == 1 and proof['complete'] is True and proof['api'] == api and proof['package'] == native.PACKAGE
    assert proof['sourceSHA'] == metadata['sourceSHA'] and proof['versionMetadata'] == metadata
    flags(proof, FLOW_FLAGS + ('sameTemporaryCISignature',))
    flags(proof, ('productionSignatureVerified', 'screenshots', 'realServicesAccessed', 'backgroundLongTestsRepeated'), False)
    assert proof['sendCount'] == 0
    ci = read(directory / 'ci-candidate-receipt.json')
    assert ci == proof['ciCandidateReceipt'] and ci['schema'] == 1 and ci['versionMetadata'] == metadata
    for key in ('versionCode', 'versionName', 'sourceSHA'):
        assert ci[key] == metadata[key], key
    assert ci['package'] == native.PACKAGE and ci['file'] == 'regular-ci-x86.apk'
    flags(ci, ('ciOnly', 'temporarySignatureNotForPersonalDevices'))
    flags(ci, ('productionSignatureVerified',), False)
    signer = digest(ci['signerSHA256'])
    assert signer not in (native.PRODUCTION_SIGNER, native.BETA_SIGNER)
    upgrade = read(directory / 'verified-synthetic.json')
    assert proof['legacyUpgrade'] == upgrade and upgrade['schema'] == 1 and upgrade['complete'] is True
    assert upgrade['api'] == api and upgrade['target'] == 'synthetic' and upgrade['sourceSHA'] == metadata['sourceSHA']
    assert upgrade['fromVersionCode'] == native.OLD_CODE and upgrade['toVersionCode'] == native.NEW_CODE
    assert upgrade['baselineScope'] == native.SCOPE and upgrade['signerSHA256'] == signer
    flags(upgrade, ('freshPackageAbsent', 'sameTemporaryCISignature', 'allEmulatorEgressRejected'))
    flags(upgrade, ('productionSignatureVerified', 'humanUnsignedCandidateInstalled',
                    'uninstallOrClearBetweenVersions', 'realWebsitesAccessed', 'screenshots'), False)
    candidate = upgrade['candidateAPK']
    assert candidate['package'] == native.PACKAGE and candidate['versionCode'] == native.NEW_CODE
    assert candidate['file'] == ci['file'] and candidate['signerSHA256'] == signer
    assert digest(candidate['sha256']) == ci['sha256'] == upgrade['candidateInstalledSHA256']
    test = upgrade['testAPK']
    assert test['package'] == native.TEST_PACKAGE and test['signerSHA256'] == signer and test['file'] == 'upgrade-test.apk'
    digest(test['sha256'])
    resign = read(directory / 'signature-only-baseline.json')
    assert resign == upgrade['signatureOnlyBaseline'] and resign['schema'] == 1 and resign['complete'] is True
    flags(resign, ('nonSigningEntriesUnchanged', 'signatureOnlyResigned', 'originalPackageManifestResourcesUnchanged',
                   'productionSignatureVerifiedOnOriginalOnly', 'temporaryCISignatureOnInstalledBaseline'))
    flags(resign, ('oldSourceRebuilt',), False)
    assert resign['originalBytes'] == native.OLD_BYTES and resign['nonSigningEntryCount'] > 0
    original, baseline = resign['original'], resign['resigned']
    assert original['sha256'] == native.OLD_HASH and original['signerSHA256'] == native.PRODUCTION_SIGNER
    for item in (original, baseline):
        assert item['package'] == native.PACKAGE and item['versionCode'] == native.OLD_CODE
    assert baseline['signerSHA256'] == signer and baseline == upgrade['baselineAPK']
    assert digest(baseline['sha256']) == upgrade['baselineInstalledSHA256']
    expected = read(directory / 'upgrade-expected.json')
    assert expected['schema'] == 1 and expected['fromVersionCode'] == native.OLD_CODE and expected['toVersionCode'] == native.NEW_CODE
    assert expected['signerSHA256'] == signer and expected['uid'] == upgrade['uid']
    assert expected['profile'] == 'profile-' + expected['storageId']
    assert expected['browserRegistry']['name'] == expected['profile'] and expected['browserRegistry']['path']
    for key, label in (('seed', 'seed'), ('oldCold', 'verify-old'), ('upgraded', 'verify-new'), ('upgradedCold', 'verify-new-cold')):
        phase = upgrade[key]
        native._preserved(phase, expected, upgrade['seed'])
        assert phase['schema'] == 1 and phase['complete'] is True and phase['phase'] == label and phase['api'] == api
        assert phase['fromVersionCode'] == native.OLD_CODE and phase['toVersionCode'] == native.NEW_CODE
        assert phase['versionCode'] == (native.OLD_CODE if label in ('seed', 'verify-old') else native.NEW_CODE)
        assert phase['signerSHA256'] == signer and phase['baselineScope'] == native.SCOPE
        flags(phase, PROOF_FLAGS)
        flags(phase, ('appActivityLaunched', 'productionSignatureVerified', 'screenshots', 'geckoOpened', 'afterEnvironmentUse'), False)
        assert phase['encryptedRecoveryRecords'] == 2 and phase['verifiedLegacyAccounts'] == 1 and phase['verifiedLegacyTabs'] == 2
        assert phase['fixtureHttpRequests'] == 0
        instrument(directory, label, phase)
    after = read(directory / 'native-after-environment.json')
    assert after == proof['legacyAfterEnvironmentUse'] and after['schema'] == 1 and after['complete'] is True and after['api'] == api
    assert after['candidateInstalledSHA256'] == candidate['sha256'] and after['target'] == 'synthetic'
    flags(after, ('afterEnvironmentUse', 'legacyAccountPreserved', 'sameTemporaryCISignature'))
    flags(after, ('productionSignatureVerified', 'reseeded', 'uninstallOrClear', 'activityOrServiceStoppedByHelper', 'screenshots'), False)
    phase = after['native']
    native._preserved(phase, expected, upgrade['seed'], subset=True)
    flags(phase, PROOF_FLAGS + ('afterEnvironmentUse', 'legacyAccountPreserved'))
    flags(phase, ('appActivityLaunched', 'productionSignatureVerified', 'screenshots', 'geckoOpened'), False)
    assert phase['schema'] == 1 and phase['complete'] is True
    assert phase['phase'] == 'verify-after-environment' and phase['api'] == api and phase['versionCode'] == native.NEW_CODE
    assert phase['fromVersionCode'] == native.OLD_CODE and phase['toVersionCode'] == native.NEW_CODE and phase['baselineScope'] == native.SCOPE
    assert phase['encryptedRecoveryRecords'] == 2 and phase['verifiedLegacyAccounts'] == 1 and phase['verifiedLegacyTabs'] == 2
    assert phase['signerSHA256'] == signer and phase['fixtureHttpRequests'] == 0
    instrument(directory, 'verify-after-environment', phase)
    manifest = proof['manifest']
    assert manifest == read(directory / 'acceptance-manifest.json') and manifest['schema'] == 1
    assert manifest['legacyAccounts'] == [{'id': expected['accountId'], 'storageId': expected['storageId']}]
    accounts = manifest['accounts']
    assert set(accounts) == {'A', 'B', 'C'} and len({a['storageId'] for a in accounts.values()}) == 3
    assert expected['storageId'] not in {a['storageId'] for a in accounts.values()}
    assert accounts['A']['environmentSlot'] == accounts['C']['environmentSlot'] != accounts['B']['environmentSlot']
    assert {a['environmentSlot'] for a in accounts.values()} == {0, 1}
    server = read(directory / 'server.json')
    assert server['schema'] == 1 and server['host'] == 'environment-fixture.test' and server['port'] == 18480
    assert server['pageCounts'] == {'/c/a1': 3, '/c/a2': 1, '/c/b1': 1, '/c/c1': 1, '/c/navigation': 0}
    assert server['sendCount'] == server['rejectedEventCount'] == 0 and server['sends'] == [] and server['downloads'] == []
    assert server['eventCount'] > 0 and server['configuredInitialDrafts']['/c/a1'] == 'server-conflict'
    for label, paths in (('A', ('/c/a1', '/c/a2')), ('B', ('/c/b1',)), ('C', ('/c/c1',))):
        account = accounts[label]
        language, zone = ('en-US', 'America/New_York') if label == 'B' else ('zh-CN', 'Asia/Shanghai')
        assert account['language'] == language and account['timeZone'] == zone
        assert [tab['url'] for tab in account['tabs']] == ['https://environment-fixture.test:18480' + p for p in paths]
        for path in paths:
            assert server['pages'][path]
            for document, page in server['pages'][path].items():
                assert document == page['document']
                shared_flow.Acceptance.assert_environment(None, page, label)
    documents = server['pages']['/c/a1']
    ordered = [proof['originalDocument'], proof['coldRecovery']['document'], proof['conflictRecovery']['document']]
    assert len(set(ordered)) == 3 and set(documents) == set(ordered)
    for document in ordered:
        page = documents[document]
        assert hashlib.sha256(page['draft'].encode()).hexdigest() == proof['draftSHA256']
        assert page['draft'] == page['modelDraft'] and page['scroll'] > 200
    for recovery in (proof['firstRecovery'], proof['beforeColdRecovery'], proof['coldRecovery']['native'], proof['conflictRecovery']['native']):
        assert recovery['schema'] == 1 and recovery['complete'] is True and recovery['account'] == 'A' and recovery['tab'] == 0
        flags(recovery, ('recordPresent', 'documentPresent', 'nativeStatePresent'))
        assert recovery['draftSHA256'] == proof['draftSHA256'] and recovery['draftLength'] > 0 and recovery['previewLength'] > 0
        assert recovery['scroll'] > 120 and 0 <= recovery['ratio'] <= 1 and recovery['capturedAt'] > 0
        digest(recovery['cipherSHA256'])
    flags(proof['conflictRecovery'], ('webDraftPreservedUntilNativeChoice', 'nativeRestoreChosen'))
    keyboards = proof['nativeKeyboardReceipts']
    assert len(keyboards) == 2
    for keyboard in keyboards:
        assert keyboard['provider'] == 'ci-paced-ascii-keyboard' and keyboard['api'] == api and keyboard['uid'] == 2000
        flags(keyboard, ('complete', 'qemu'))
        assert keyboard['injectionMode'] == 2 and keyboard['paceMillis'] == 40 and keyboard['events']
        assert all(event['accepted'] is True and event['waitForFinish'] is True for event in keyboard['events'])
    assert keyboards[0]['textSHA256'] == proof['draftSHA256']
    exits = proof['coldProcessExitReceipts']
    if api == 35:
        assert exits == read(directory / 'cold-process-exit-receipts.json') and len(exits) == 2
        for attempt in exits:
            assert attempt['api'] == api and attempt['packagePrefix'] == native.PACKAGE
            flags(attempt, ('complete', 'processExitVerified', 'openCommandReturned'))
            assert attempt['before'] and attempt['after'] == [] and attempt['exitObservations'][-1]['processes'] == []
            assert attempt['forceStopAttempts'] == attempt['openAttempts'] == 1
            assert attempt['deadlineSeconds'] == 60 and attempt['elapsedMillis'] > 0 and attempt['remainingMillisBeforeOpen'] > 0
    else:
        assert exits == [] and proof['nativeTouchReceipts']
        for touch in proof['nativeTouchReceipts']:
            assert touch['provider'] == 'ci-android9-shell-touchscreen' and len(touch['events']) == 33
    sources = ('regular-functional.json', 'verified-synthetic.json', 'signature-only-baseline.json',
               'upgrade-expected.json', 'native-after-environment.json', 'ci-candidate-receipt.json',
               'acceptance-manifest.json', 'server.json', 'seed.txt', 'verify-old.txt',
               'verify-new.txt', 'verify-new-cold.txt', 'verify-after-environment.txt')
    return {'api': api, 'functional': proof, 'independentFixture': server,
            'originalExpectedState': expected,
            'evidenceSHA256': {name: sha(regular_file(directory / name)) for name in sources}}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--review-dir', type=Path, default=Path('android/app/build/regular-review'))
    parser.add_argument('--evidence-dir', type=Path, default=native.EVIDENCE)
    parser.add_argument('--output-dir', type=Path, default=Path('android/app/build/regular-review-delivery'))
    args = parser.parse_args()
    assert os.environ.get('GITHUB_ACTIONS') == 'true', 'Actions delivery only'
    assert not args.output_dir.exists(), 'Refuse to overwrite an existing delivery'
    receipt, metadata = inspect_human(args.review_dir)
    paths = sorted(args.evidence_dir.rglob('regular-functional.json'))
    assert len(paths) == 2, 'Require exactly API28 and API35 regular functional proofs'
    locations = {}
    for path in paths:
        api = read(path)['api']
        assert api in (28, 35) and api not in locations, 'Duplicate or unexpected API proof'
        locations[api] = path.parent
    assert set(locations) == {28, 35}
    spec = importlib.util.spec_from_file_location('regular_delivery_shared_flow', Path(__file__).with_name('run-environment-client.py'))
    shared_flow = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(shared_flow)
    proofs = [verify_api(locations[api], api, metadata, shared_flow) for api in (28, 35)]
    ci_receipts = [entry['functional']['ciCandidateReceipt'] for entry in proofs]
    assert ci_receipts[0] == ci_receipts[1], 'API proofs must use the same CI candidate and temporary signature'
    assert ci_receipts[0]['sha256'] != receipt['sha256'], 'Never substitute the CI APK for human review'
    report = {'schema': 1, 'complete': True, 'package': native.PACKAGE, 'versionMetadata': metadata,
              'humanUnsignedReceipt': receipt, 'apiProofs': proofs,
              'sameSourceHumanAndCiCandidates': True, 'sameTemporaryCISignature': True,
              'productionCertificateUpgradeVerified': False, 'humanUnsignedApkInstalled': False,
              'legacyWebViewProfilePreserved': True, 'newGeckoEnvironmentHotSwitchVerified': True,
              'legacyMainToGeckoUiSwitchVerified': False, 'personalDeviceVerified': False,
              'realChatGPTVerified': False, 'publicRelease': False,
              'scope': 'Original alpha.22 non-signing payload, temporary-CI-key install -r preservation; new Gecko A/B/C environments on API28/API35 owned fixtures only.'}
    args.output_dir.mkdir(parents=True, exist_ok=False)
    allowed = (receipt['file'], 'receipt.json', 'android-version.json', 'SHA256SUMS.txt', 'LICENSE')
    for name in allowed:
        shutil.copyfile(regular_file(args.review_dir / name), args.output_dir / name)
    native._write_json(args.output_dir / 'regular-environment-acceptance.json', report)
    note = f'''# 常规 Android 第一批未签名审阅包

版本：{metadata['versionName']} / code {metadata['versionCode']}；包名：{native.PACKAGE}。
源码：{metadata['sourceSHA']}；APK SHA256：{receipt['sha256']}。

本目录 APK 是未签名 ARM64 审阅产物，不能安装或覆盖手机中的正式版。它未用于本次模拟器安装。
正式发布、正式私钥签名、个人手机安装与公开更新均未执行。

API28/35 的合成升级使用原公开 alpha.22 APK，仅替换签名元数据；非签名 ZIP 内容逐字节一致。
旧包与 CI 候选使用同一把一次性 CI 密钥，其证书与正式版、Beta 均不同。这是临时同签兼容验证，不能视为正式原证书覆盖升级验收。

已核对旧账号的 account/storage/profile 注册绑定、HttpOnly/Secure Cookie、localStorage、IndexedDB、加密恢复/草稿、密文、资产及偏好在覆盖安装与冷读后保留；新候选将旧账号识别为默认 WebView。
新 Gecko 环境 A/B/C 的两组语言与时区、Worker、HTTP Accept-Language、账号存储隔离、热切换不重载、冷恢复和草稿冲突保护已有合成 CI 证据。
旧档案通过无 Activity 的独立 WebView/Profile 核对；没有把实际旧 MainActivity 与新 Gecko 页面之间的 UI 切换作为已验收能力。
真实 ChatGPT、个人手机、真实登录/网络服务、后台长测和第二批下载/网络/Skill 门禁不在本批验收范围；本批未截图。

详细证据及输入哈希见 regular-environment-acceptance.json。交付目录仅包含本审阅 APK、版本/哈希/许可证和验收报告，不包含 CI APK、签名密钥或 fixture CA。
'''
    (args.output_dir / 'REVIEW.md').write_text(note, encoding='utf-8')
    assert {path.name for path in args.output_dir.iterdir()} == set(allowed) | {'regular-environment-acceptance.json', 'REVIEW.md'}
    assert sha(args.output_dir / receipt['file']) == receipt['sha256']
    print(json.dumps({'complete': True, 'directory': str(args.output_dir), 'file': receipt['file'],
                      'sha256': receipt['sha256'], 'unsignedReviewOnly': True,
                      'productionCertificateUpgradeVerified': False}, sort_keys=True))


if __name__ == '__main__':
    main()
