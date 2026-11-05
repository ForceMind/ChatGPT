"""Deliver the unchanged human APK only after both native upgrades and full client acceptance."""
import hashlib,json,os,shutil
from pathlib import Path
assert os.environ.get('GITHUB_ACTIONS')=='true'
root=Path('input');receipt=json.loads((root/'receipt.json').read_text())
assert receipt['versionName']=='3.0.0-alpha.24-beta.5' and receipt['versionCode']==3000029
assert receipt['package']=='com.forcemind.chatgpt.profiles.networkbeta'
proofs=[]
for api in (28,35):
    human=Path(f'acceptance/human/environment-client-{api}-human/verified-human.json')
    engine=Path(f'acceptance/synthetic/environment-client-{api}-synthetic/functional.json')
    a,b=json.loads(human.read_text()),json.loads(engine.read_text())
    assert a['complete'] and b['complete'] and a['api']==b['api']==api
    assert b['candidateScope']=='synthetic-x86-ci' and not b['originalHumanCandidateBytesInstalled']
    for flag in ('languageTimeZoneAndWorkerVerified','httpAcceptLanguageVerified','cookieLocalStorageIndexedDBAccountIsolation',
                 'sameEnvironmentDifferentStorageContexts','trustedNativeTextareaAndContenteditableInput','realNativeReadingScroll',
                 'liveSwitchKeptDocumentAndDraftAndScroll'):
        assert b[flag], flag
    assert b['sendCount']==0 and not b['screenshots'] and not b['realServicesAccessed']
    assert b['legacyAfterEnvironmentUse']['complete'] and b['legacyAfterEnvironmentUse']['legacyAccountPreserved']
    assert b['conflictRecovery']['webDraftPreservedUntilNativeChoice'] and b['conflictRecovery']['nativeRestoreChosen']
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
