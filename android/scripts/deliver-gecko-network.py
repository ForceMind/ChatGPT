"""Add Gecko network proof only after every real transport and OS tuple passes."""
import hashlib, json, os, shutil
from pathlib import Path
from android_version import validate_receipt

assert os.environ.get('GITHUB_ACTIONS') == 'true'
root=Path('input');out=Path('candidate')
assert out.is_dir(), 'Existing upgrade and UI/recovery gate must finish first'
receipt=json.loads((root/'gecko-network-receipt.json').read_text())
apk=root/'gecko-network-x86.apk'
version_metadata=validate_receipt(receipt)
assert receipt['ciOnly'] is True
human=json.loads((root/'receipt.json').read_text());assert validate_receipt(human,require_human_filename=True)==version_metadata
assert receipt['signerSHA256']==human['signerSHA256']==json.loads(Path('docs/signing/android-network-beta.json').read_text())['certificateSHA256']
assert apk.stat().st_size==receipt['bytes'] and receipt['file']==apk.name
assert hashlib.sha256(apk.read_bytes()).hexdigest()==receipt['sha256']
proofs=[]
for api in (28,35):
    for transport in ('raw','tls','reality'):
        file=Path(f'acceptance/network/gecko-network-{api}-{transport}/gecko-network.json')
        proof=json.loads(file.read_text())
        assert proof['schema']==1 and proof['complete'] and proof['api']==api and proof['transport']==transport
        assert proof['engine']=='gecko' and proof['candidateInstalledSHA256']==receipt['sha256']
        assert proof['ciReceipt']==receipt and proof['applicationSourceSHA']==receipt['sourceSHA']
        assert proof['candidateAPK']['versionCode']==version_metadata['versionCode'] and proof['candidateAPK']['versionName']==version_metadata['versionName']
        assert proof['idleSwitchWaited'] is True and proof['idleSwitchSelectCalls']==1
        assert proof['idleSwitchState']['waitIdleRequested'] is True and proof['idleSwitchState']['networkSelectCalls']==1
        schedule=proof['nativeInputSchedule'];prefixes=proof['nativePrefixReceipts']
        assert schedule['mode']=='single-jvm-native-character-then-exact-prefix-ack' and schedule['totalDeadlineSeconds']==75
        assert schedule['commonBatchHelperUnchanged'] is True and schedule['claimsBatchOrderingFixed'] is False
        assert len(prefixes)==len(proof['nativeAsciiKeyboardReceipts'])==len(prefixes[-1]['expectedPrefix'])==50
        for index,prefix in enumerate(prefixes):
            assert prefix['index']==index and prefix['draft']==prefix['modelDraft']==prefixes[-1]['expectedPrefix'][:index+1]
        streams=proof['nativeInputStreams'];assert len(streams)==1
        stream=streams[0];assert stream['complete'] and stream['exitCode']==0 and len(stream['dispatches'])==50
        assert stream['ready']['qemu'] is True and stream['ready']['pid']==stream['devicePID']
        for index,dispatch in enumerate(stream['dispatches']):
            assert dispatch['validated'] and dispatch['dispatch']['dispatch']==dispatch['done']['dispatch']==index
            assert dispatch['dispatch']['pid']==dispatch['done']['pid']==prefixes[index]['streamPID']==stream['devicePID']
        for flag in ('busySelectRejected','idleSwitchNoReload','noDirect','noReplay','draftCookieIdentityPreserved'):
            assert proof[flag], (api,transport,flag)
        assert proof['sendCount']==0 and not proof['screenshots'] and not proof['realServicesAccessed'] and not proof['adbReverseOriginUsed']
        assert proof['picker']['realSystemPicker'] and proof['picker']['name']=='gecko-upload.bin'
        successful=[]
        for route in ('A','B'):
            result=proof['route'+route]
            for kind in ('upload','download'):
                task=result[kind];gate=task['gate']
                size=task['fileBytes'] if kind=='upload' else task['bytes']
                digest=task['fileSha256'] if kind=='upload' else task['sha256']
                assert task['route']==route and size>0 and len(digest)==64
                assert gate['completed'] and gate['opCount']==gate['attemptCount']==1
                assert gate['route']==route
                successful.append(task['operation'])
        assert len(set(successful))==4
        assert len(proof['failedOperations'])==3 and all(not task['originReached'] for task in proof['failedOperations'])
        assert len(proof['negatives'])==(0 if transport=='raw' else 2)
        proofs.append(proof)
(out/'gecko-vless-route-acceptance.json').write_text(json.dumps(proofs,indent=2)+'\n')
# The synthetic APK, test CA, server credentials, and test document provider stay out.
assert not any('gecko-network-x86' in p.name or 'ca.pem' in p.name for p in out.iterdir())
# Local streams are an additional mandatory gate after the unchanged route/client gates.
import importlib.util
local_spec=importlib.util.spec_from_file_location('owned_local_exports_delivery',Path(__file__).with_name('deliver-gecko-local-exports.py'))
local_delivery=importlib.util.module_from_spec(local_spec);local_spec.loader.exec_module(local_delivery)
local_delivery.deliver(root,out)
