#!/usr/bin/env python3
"""Actual public alpha.22 -> original-signed human-code companion, owned CI only."""
import argparse
import json
import os
from pathlib import Path
import re

import regular_environment_upgrade as common

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--input', type=Path, required=True)
parser.add_argument('--output', type=Path, required=True)
args = parser.parse_args()
assert os.environ.get('GITHUB_ACTIONS') == 'true'
common.EVIDENCE = args.output
args.output.mkdir(parents=True, exist_ok=False)
guarded = False


def phase(label, api, installed_sha):
    cls = 'OriginalSignatureUpgradeData' if label in ('seed', 'verify-old') else 'OriginalSignatureUpgradeVerifyTest'
    result = common._run(['adb','shell','am','instrument','-w','-r','-e','class',
                          'com.forcemind.chatgpt.profiles.'+cls,'-e','phase',label,
                          '-e','expectedApi',str(api),'-e','signer',common.PRODUCTION_SIGNER,
                          '-e','baselineScope','original-public-alpha22-original-signature',
                          '-e','installedSHA256',installed_sha,common.RUNNER],timeout=300,check=False)
    (args.output/(label+'-instrument.txt')).write_text(result.stdout)
    print(result.stdout,flush=True)
    assert result.returncode == 0 and re.findall(r'(?m)^OK \((\d+) test\)\s*$',result.stdout) == ['1']
    assert not re.search(r'FAILURES!!!|INSTRUMENTATION_FAILED|shortMsg=|AssumptionViolated',result.stdout)
    rows = re.findall(r'(?m)^INSTRUMENTATION_RESULT: originalSignatureUpgradeProof=(\{[^\r\n]+\})\s*$',result.stdout)
    assert len(rows) == 1
    proof = json.loads(rows[0])
    assert proof['schema'] == 1 and proof['complete'] is True and proof['api'] == api and proof['phase'] == label
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
    for role in ('arm64','humanX86','instrumentation'):
        item = receipt[role]
        assert Path(item['file']).name == item['file']
        path = args.input/item['file']
        assert path.stat().st_size == item['bytes'] and common._sha(path) == item['sha256']
        files[role] = path
    tools = common._tools()
    for role in ('arm64','humanX86'):
        path = files[role]
        common._inspect_apk(path,tools,common.PRODUCTION_SIGNER,common.PACKAGE,common.NEW_CODE)
        manifest = common._run([str(tools/'aapt'),'dump','xmltree',str(path),'AndroidManifest.xml']).stdout
        assert 'android:testOnly' not in manifest and 'EnvironmentAcceptanceActivity' not in manifest
        badging = common._run([str(tools/'aapt'),'dump','badging',str(path)]).stdout
        assert 'application-debuggable' not in badging and "versionName='"+metadata['versionName']+"'" in badging
    common._inspect_apk(files['instrumentation'],tools,common.PRODUCTION_SIGNER,common.TEST_PACKAGE)
    original = args.input/'alpha22-public.apk'
    assert original.stat().st_size == common.OLD_BYTES and common._sha(original) == common.OLD_HASH
    common._inspect_apk(original,tools,common.PRODUCTION_SIGNER,common.PACKAGE,common.OLD_CODE)
    common._root_and_block()
    common._run(['adb','install',str(original)])
    original_installed = common._installed(common.OLD_HASH,'original-signature-alpha22')
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
    common._write_json(args.output/'original-signature-upgrade.json',proof)
    print(json.dumps({'complete':True,'api':api,'productionSignatureUpgradeVerified':True}),flush=True)
except Exception:
    common._record_failure()
    raise
finally:
    if guarded:
        common._logcat('original-signature-upgrade')
