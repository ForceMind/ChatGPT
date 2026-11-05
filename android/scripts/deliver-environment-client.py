"""Deliver the unchanged human APK only after both native upgrades and full client acceptance."""
import hashlib,json,os,shutil
from pathlib import Path
assert os.environ.get('GITHUB_ACTIONS')=='true'
root=Path('input');receipt=json.loads((root/'receipt.json').read_text())
synthetic_hash=hashlib.sha256((root/'synthetic-x86.apk').read_bytes()).hexdigest()
test_hash=hashlib.sha256((root/'upgrade-test.apk').read_bytes()).hexdigest()
test35_hash=test_hash
if (root/'api35-test-receipt.json').exists():
    test35=json.loads((root/'api35-test-receipt.json').read_text())
    assert test35['file']=='api35-upgrade-test.apk' and test35['applicationSourceSHA']==receipt['sourceSHA']
    test35_hash=hashlib.sha256((root/test35['file']).read_bytes()).hexdigest()
    assert test35_hash==test35['sha256'] and test35['signerSHA256']==receipt['signerSHA256']
assert receipt['versionName']=='3.0.0-alpha.24-beta.6' and receipt['versionCode']==3000030
assert receipt['package']=='com.forcemind.chatgpt.profiles.networkbeta'
proofs=[]
for api in (28,35):
    human=Path(f'acceptance/human/environment-client-{api}-human/verified-human.json')
    engine=Path(f'acceptance/synthetic/environment-client-{api}-synthetic/functional.json')
    a,b=json.loads(human.read_text()),json.loads(engine.read_text())
    assert a['complete'] and b['complete'] and a['api']==b['api']==api
    assert b['candidateScope']=='synthetic-x86-ci' and not b['originalHumanCandidateBytesInstalled']
    assert b['candidateInstalledSHA256']==synthetic_hash and b['legacyUpgrade']['testAPK']['sha256']==(test35_hash if api==35 else test_hash)
    assert b['legacyUpgrade']['humanCandidateSHA256']==receipt['sha256']
    for flag in ('languageTimeZoneAndWorkerVerified','httpAcceptLanguageVerified','cookieLocalStorageIndexedDBAccountIsolation',
                 'sameEnvironmentDifferentStorageContexts','trustedNativeTextareaAndContenteditableInput','realNativeReadingScroll',
                 'liveSwitchKeptDocumentAndDraftAndScroll','rotationNoReload'):
        assert b[flag], flag
    assert b['sendCount']==0 and not b['screenshots'] and not b['realServicesAccessed']
    assert b['legacyAfterEnvironmentUse']['complete'] and b['legacyAfterEnvironmentUse']['legacyAccountPreserved']
    assert b['conflictRecovery']['webDraftPreservedUntilNativeChoice'] and b['conflictRecovery']['nativeRestoreChosen']
    if api==35:
        assert b['legacyChromeNavigation']['profileRegistryPersisted']
        assert b['legacyChromeNavigation']['profileRegistryBarrier']['complete']
        cold=b['coldProcessExitReceipts']
        assert len(cold)==2
        for attempt in cold:
            assert attempt['complete'] and attempt['processExitVerified'] and attempt['before'] and attempt['after']==[]
            assert attempt['forceStopAttempts']==attempt['openAttempts']==1 and attempt['openCommandReturned']
    for key,engine in (('geckoChromeNavigation','gecko'),('legacyChromeNavigation','webview')):
        navigation=b[key]
        assert navigation['complete'] and navigation['engine']==engine
        for flag in ('nativeHeaderTitleSwitch','switchNoReload','disabledEmptyHistory','backForward','normalAndForceRefresh','draftCookieStoragePreserved'):
            assert navigation[flag], (key,flag)
        assert not navigation['screenshots'] and not navigation['realServicesAccessed']
    proofs.append({'api':api,'human':a,'engine':b})
apk=root/receipt['file'];assert apk.stat().st_size==receipt['bytes'] and hashlib.sha256(apk.read_bytes()).hexdigest()==receipt['sha256']
assert proofs[1]['human']['originalHumanCandidateBytesInstalled']
assert proofs[1]['human']['candidateInstalledSHA256']==receipt['sha256']
out=Path('candidate');out.mkdir()
for name in (receipt['file'],'receipt.json','SHA256SUMS.txt','LICENSE','GECKO-NOTICE.txt','network-core-dependencies.json'):
    shutil.copyfile(root/name,out/name)
shutil.copytree(root/'licenses',out/'licenses')
shutil.copyfile('docs/android-environment-client-install.md',out/'INSTALL.md')
(out/'client-environment-and-recovery-acceptance.json').write_text(json.dumps(proofs,indent=2)+'\n')
