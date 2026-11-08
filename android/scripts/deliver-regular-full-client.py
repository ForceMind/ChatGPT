#!/usr/bin/env python3
"""All regular-client feature gates; this is still unsigned, not an installable release."""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
from environment_ci_target import assert_receipt_pair
from regular_evidence_source import source_sha, provenance

assert os.environ.get('GITHUB_ACTIONS') == 'true'
assert os.environ.get('STUDIO_ANDROID_CI_TARGET') == 'regular'


def read(path):
    return json.loads(Path(path).read_text())


def invoke(name, environment=None):
    subprocess.run(['python3','android/scripts/'+name],check=True,env={**os.environ,**(environment or {})})


def instrument(path, count):
    text = path.read_text()
    noun = 'test' if count == 1 else 'tests'
    assert re.findall(r'(?m)^OK \((\d+) '+noun+r'\)\s*$',text) == [str(count)]
    assert not re.search(r'FAILURES!!!|INSTRUMENTATION_FAILED|shortMsg=|AssumptionViolated|Ignored',text)


invoke('deliver-regular-review.py')
candidate = Path('candidate')
assert not candidate.exists()
shutil.move('android/app/build/regular-review-delivery',candidate)
invoke('deliver-regular-navigation.py',{
    'STUDIO_REGULAR_NAVIGATION_EVIDENCE':'android/app/build/regular-navigation-evidence',
    'STUDIO_REGULAR_NAVIGATION_OUTPUT':'android/app/build/regular-navigation-review'})
navigation = Path('android/app/build/regular-navigation-review/regular-navigation-acceptance.json')
shutil.copyfile(navigation,candidate/navigation.name)
human = read(candidate/'receipt.json')
metadata = human['versionMetadata']
network_receipt = read('input/gecko-network-receipt.json')
basic_receipt = read('input/regular-base-ci-receipt.json')
assert_receipt_pair(network_receipt,human)
assert_receipt_pair(basic_receipt,human)
navigation_report = read(navigation)
assert navigation_report['schema']==1 and navigation_report['complete'] is True
assert navigation_report['scope']=='regular-navigation-only'
assert navigation_report['versionMetadata']==metadata and navigation_report['humanUnsignedReceipt']==human
assert [proof['api'] for proof in navigation_report['apiProofs']]==[28,35]
for proof in navigation_report['apiProofs']:
    assert proof['ciCandidateReceipt']==basic_receipt
    assert proof['legacyUpgrade']['candidateInstalledSHA256']==basic_receipt['sha256']
assert metadata['sourceSHA'] == source_sha()
shutil.copyfile(candidate/'receipt.json','input/receipt.json')
invoke('deliver-gecko-network.py')  # Also enforces both local-export gates.
invoke('deliver-gecko-handoff.py')
legacy = []
workbench = []
for api in (28,35):
    for transport in ('raw','tls','reality'):
        root=Path(f'acceptance/webview-network/regular-webview-network-{api}-{transport}')
        proof=read(root/'regular-webview-network.json')
        assert proof['schema']==1 and proof['complete'] is True and proof['api']==api
        assert proof['transport']==transport and proof['suite']=='network-files'
        assert proof['package']==human['package'] and proof['versionMetadata']==metadata
        assert proof['sourceSHA']==metadata['sourceSHA']
        assert proof['ciReceipt']==network_receipt
        assert proof['candidateInstalledSHA256']==proof['ciReceipt']['sha256']
        assert proof['productionSignatureVerified'] is False and proof['screenshots'] is False
        pages,files=read(root/'result.json'),read(root/'file-safety.json')
        assert pages['complete'] and files['complete'] and pages['api']==files['api']==api
        assert pages['sourceSHA']==files['sourceSHA']==metadata['sourceSHA']
        assert len(pages['expectedRequests'])==17 and len(pages['files'])==12
        assert len(files['expectedRequests'])==32
        assert pages['disabledCoreUnloaded'] and pages['recreatedSameViews'] and pages['retryDidNotReload'] and pages['failedCoreBlockedAllViews']
        assert files['systemPolicy']['restored'] and files['systemPolicy']['roleRemoved']
        assert files['systemPolicy']['restoredProxyInfo']==files['systemPolicy']['originalProxy']
        assert files['publisherFailure']['newPartialRemoved'] and files['publisherFailure']['existingDocumentPreserved']
        assert read(root/'verified.json')['complete'] and read(root/'file-safety-verified.json')['complete']
        assert read(root/'regular-webview-files-scope.json')['restartSuiteRepeated'] is False
        instrument(root/'instrumentation.txt',1);instrument(root/'file-safety-instrumentation.txt',1)
        legacy.append({'proof':proof,'pages':pages,'files':files})
    root=Path(f'acceptance/workbench/regular-webview-workbench-{api}')
    proof=read(root/'regular-workbench.json')
    binding=read(root/'regular-webview-network.json')
    assert binding['complete'] is True and binding['suite']=='workbench'
    assert binding['ciReceipt']==basic_receipt and binding['candidateInstalledSHA256']==basic_receipt['sha256']
    assert proof['schema']==1 and proof['complete'] is True and proof['api']==api
    assert proof['sourceSHA']==metadata['sourceSHA'] and proof['package']==human['package']
    assert proof['tests']==9 and proof['failures']==proof['skipped']==0
    assert proof['screenshots'] is False and proof['visualArtifactsDisabledByApprovedScope'] is True
    assert proof['functionalAssertionsPreserved'] is True and proof['realServicesAccessed'] is False
    instrument(root/'workbench-instrument.txt',9)
    workbench.append(proof)
origin = provenance()
assert origin is not None and origin['sourceSHA'] == metadata['sourceSHA']
report={'schema':1,'complete':True,'scope':'regular-feature-validation-before-original-signing',
        'versionMetadata':metadata,'provenance':origin,'humanUnsignedReceipt':human,'legacyWebViewRoutes':legacy,
        'legacyWorkbench':workbench,'networkFileSkillGatesComplete':True,'navigationGatesComplete':True,
        'compatibilityRecoveryGatesComplete':True,'originalSignatureUpgradeVerified':False,
        'installableCandidateDelivered':False,'publicRelease':False}
(candidate/'regular-full-validation.json').write_text(json.dumps(report,indent=2,ensure_ascii=False)+'\n')
assert hashlib.sha256((candidate/human['file']).read_bytes()).hexdigest()==human['sha256']
print(json.dumps({'complete':True,'scope':report['scope'],'sourceSHA':metadata['sourceSHA'],
                  'apkSHA256':human['sha256'],'originalSignatureUpgradeVerified':False}))
