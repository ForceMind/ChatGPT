#!/usr/bin/env python3
"""Original-signed human-code upgrade on owned CI emulators only."""
import argparse
import json
import os
from pathlib import Path
import re

import regular_environment_upgrade as common

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--input', type=Path, required=True)
parser.add_argument('--output', type=Path, required=True)
parser.add_argument('--current-regular', action='store_true')
args = parser.parse_args()
assert os.environ.get('GITHUB_ACTIONS') == 'true'
if args.current_regular:
    common.OLD_CODE, common.NEW_CODE = 3000035, 3000036
    common.OLD_HASH = '6177afcc1001aad8603847b34e87857f633304518360611af365e0fa0416a4ff'
    common.OLD_BYTES = 263766864
    common.TEST_PACKAGE = common.PACKAGE + '.originalcheck'
    common.RUNNER = common.TEST_PACKAGE + '/androidx.test.runner.AndroidJUnitRunner'
    baseline_file = 'current-public-companion-x86.apk'
    baseline_scope = 'original-public-regular-original-signature'
else:
    baseline_file = 'alpha22-public.apk'
    baseline_scope = 'original-public-alpha22-original-signature'
common.EVIDENCE = args.output
args.output.mkdir(parents=True, exist_ok=False)
guarded = False


def phase(label, api, installed_sha):
    cls = 'OriginalSignatureUpgradeData' if label in ('seed', 'verify-old') else 'OriginalSignatureUpgradeVerifyTest'
    result = common._run(['adb','shell','am','instrument','-w','-r','-e','class',
                          'com.forcemind.chatgpt.profiles.'+cls,'-e','phase',label,
                          '-e','expectedApi',str(api),'-e','signer',common.PRODUCTION_SIGNER,
                          '-e','baselineScope',baseline_scope,
                          '-e','installedSHA256',installed_sha,common.RUNNER],timeout=300,check=False)
    (args.output/(label+'-instrument.txt')).write_text(result.stdout)
    print(result.stdout,flush=True)
    assert result.returncode == 0 and re.findall(r'(?m)^OK \((\d+) test\)\s*$',result.stdout) == ['1']
    assert not re.search(r'FAILURES!!!|INSTRUMENTATION_FAILED|shortMsg=|AssumptionViolated',result.stdout)
    rows = re.findall(r'(?m)^INSTRUMENTATION_RESULT: originalSignatureUpgradeProof=(\{[^\r\n]+\})\s*$',result.stdout)
    assert len(rows) == 1
    proof = json.loads(rows[0])
    assert proof['schema'] == 1 and proof['complete'] is True and proof['api'] == api and proof['phase'] == label
    assert proof['fromVersionCode'] == common.OLD_CODE and proof['toVersionCode'] == common.NEW_CODE
    assert proof['versionCode'] == (common.OLD_CODE if label in ('seed', 'verify-old') else common.NEW_CODE)
    assert proof['baselineScope'] == baseline_scope
    assert proof['signerSHA256'] == common.PRODUCTION_SIGNER and proof['productionSignatureVerified'] is True
    assert proof['sameProductionSignature'] is True and proof['encryptedRecoveryRecords'] == 2
    assert proof['fixtureHttpRequests'] == 0
    for flag in ('screenshots','geckoOpened','appActivityLaunched'):
        assert proof[flag] is False
    return proof


try:
    api = common._guard()
    guarded = True
    devices = common._run(['adb','devices']).stdout.strip().splitlines()[1:]
    assert len(devices) == 1 and devices[0].split()[1:] == ['device']
    assert not common._shell('pm','list','packages',common.PACKAGE).strip()
    assert not common._shell('pm','list','packages',common.TEST_PACKAGE).strip()
    receipt = common._json(args.input/'signed-receipt.json')
    metadata = receipt['versionMetadata']
    assert receipt['package'] == common.PACKAGE and receipt['certificateSHA256'] == common.PRODUCTION_SIGNER
    assert metadata['versionCode'] == common.NEW_CODE and metadata['sourceSHA'] == receipt['sourceSHA']
    files = {}
    human_roles = ('humanX86',) if args.current_regular else ('arm64', 'humanX86')
    for role in (*human_roles, 'instrumentation'):
        item = receipt[role]
        assert Path(item['file']).name == item['file']
        path = args.input/item['file']
        assert path.stat().st_size == item['bytes'] and common._sha(path) == item['sha256']
        files[role] = path
    tools = common._tools()
    for role in human_roles:
        path = files[role]
        common._inspect_apk(path,tools,common.PRODUCTION_SIGNER,common.PACKAGE,common.NEW_CODE)
        manifest = common._run([str(tools/'aapt'),'dump','xmltree',str(path),'AndroidManifest.xml']).stdout
        assert 'android:testOnly' not in manifest and 'EnvironmentAcceptanceActivity' not in manifest
        badging = common._run([str(tools/'aapt'),'dump','badging',str(path)]).stdout
        assert 'application-debuggable' not in badging and "versionName='"+metadata['versionName']+"'" in badging
    common._inspect_apk(files['instrumentation'],tools,common.PRODUCTION_SIGNER,common.TEST_PACKAGE)
    original = args.input/baseline_file
    assert original.stat().st_size == common.OLD_BYTES and common._sha(original) == common.OLD_HASH
    common._inspect_apk(original,tools,common.PRODUCTION_SIGNER,common.PACKAGE,common.OLD_CODE)
    common._root_and_block()
    common._run(['adb','install',str(original)])
    original_installed = common._installed(common.OLD_HASH,'original-signature-baseline')
    common._run(['adb','install','-t',str(files['instrumentation'])])
    seed = phase('seed',api,original_installed)
    common._shell('am','force-stop',common.PACKAGE)
    cold = phase('verify-old',api,original_installed)
    common._shell('am','force-stop',common.PACKAGE)
    # No -t, uninstall, clear, rebuild or re-sign of the original baseline.
    common._run(['adb','install','-r',str(files['humanX86'])])
    installed = common._installed(receipt['humanX86']['sha256'],'original-signature-candidate')
    upgraded = phase('verify-new',api,installed)
    common._shell('am','force-stop',common.PACKAGE)
    upgraded_cold = phase('verify-new-cold',api,installed)
    expected = json.loads(common._shell('cat','/data/user/0/'+common.PACKAGE+'/files/original-signature-upgrade-expected.json'))
    for value in (seed,cold,upgraded,upgraded_cold):
        common._preserved(value,expected,seed)
    proof = {'schema':1,'complete':True,'api':api,'package':common.PACKAGE,'versionMetadata':metadata,
             'sourceSHA':receipt['sourceSHA'],'harnessSourceSHA':os.environ['GITHUB_SHA'],
             'certificateSHA256':common.PRODUCTION_SIGNER,'originalPublicAPKUnchanged':True,
             'productionSignatureUpgradeVerified':True,'originalInstalledSHA256':original_installed,
             'x86HumanCodeInstalledSHA256':installed,'arm64SignedReceipt':receipt['arm64'],
             'arm64PhysicalDeviceInstalled':False,'seed':seed,'oldCold':cold,'upgraded':upgraded,
             'upgradedCold':upgraded_cold,'expected':expected,'uninstallOrClearBetweenVersions':False,
             'screenshots':False,'realServicesAccessed':False,'personalDeviceModified':False}
    if args.current_regular:
        proof['originalPublicAPKUnchanged'] = False
        proof['baselineIsVerifiedPublicReleaseX86Companion'] = True
        proof['publicArm64BaselineSHA256'] = '177ef874c6c904a653aecb3ffc61a76ef45ebc8e69cb80c6addbdb9e74806259'
    common._write_json(args.output/'original-signature-upgrade.json',proof)
    print(json.dumps({'complete':True,'api':api,'productionSignatureUpgradeVerified':True}),flush=True)
except Exception:
    common._record_failure()
    raise
finally:
    if guarded:
        common._logcat('original-signature-upgrade')
