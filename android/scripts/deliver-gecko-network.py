"""Add Gecko network proof only after every real transport and OS tuple passes."""
import hashlib, json, os, shutil
from pathlib import Path

assert os.environ.get('GITHUB_ACTIONS') == 'true'
root=Path('input');out=Path('candidate')
assert out.is_dir(), 'Existing upgrade and UI/recovery gate must finish first'
receipt=json.loads((root/'gecko-network-receipt.json').read_text())
apk=root/'gecko-network-x86.apk'
assert receipt['ciOnly'] and receipt['versionCode']==3000031
assert hashlib.sha256(apk.read_bytes()).hexdigest()==receipt['sha256']
proofs=[]
for api in (28,35):
    for transport in ('raw','tls','reality'):
        file=Path(f'acceptance/network/gecko-network-{api}-{transport}/gecko-network.json')
        proof=json.loads(file.read_text())
        assert proof['schema']==1 and proof['complete'] and proof['api']==api and proof['transport']==transport
        assert proof['engine']=='gecko' and proof['candidateInstalledSHA256']==receipt['sha256']
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
