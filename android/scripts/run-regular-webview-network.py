#!/usr/bin/env python3
"""Bind the existing WebView page/file/system-route checks to the regular CI APK."""
import importlib.util
import os
from pathlib import Path
import re
import sys
import traceback
import urllib.parse

from environment_ci_target import native, PACKAGE, VERSION_CODE, REGULAR, expected_signer, validate_receipt, install

assert REGULAR and os.environ.get('GITHUB_ACTIONS') == 'true'
output = Path(os.environ['STUDIO_GECKO_OUTPUT'])
output.mkdir(parents=True, exist_ok=True)
native.EVIDENCE = output
guarded = False
try:
    api = native._guard()
    guarded = True
    assert len(native._run(['adb', 'devices']).stdout.strip().splitlines()[1:]) == 1
    assert not native._shell('pm', 'list', 'packages', PACKAGE).strip(), 'Fresh disposable emulator required'
    receipt = native._json(Path(os.environ['STUDIO_GECKO_RECEIPT']))
    metadata = validate_receipt(receipt)
    assert receipt['ciOnly'] is True and receipt['legacyWebviewFixture'] is True
    assert metadata['sourceSHA'] == os.environ['GITHUB_SHA']
    apk = Path(os.environ['STUDIO_GECKO_APK'])
    assert receipt['file'] == apk.name and receipt['sha256'] == native._sha(apk) and receipt['bytes'] == apk.stat().st_size
    signer = expected_signer()
    native._inspect_apk(apk, native._tools(), signer, PACKAGE, VERSION_CODE, test_only=True)
    test = apk.parent / 'network-test.apk'
    test_receipt = native._json(apk.parent / 'network-test-receipt.json')
    assert test_receipt['file'] == test.name and test_receipt['bytes'] == test.stat().st_size
    assert test_receipt['ciOnly'] is True and test_receipt['productionSignatureVerified'] is False
    assert test_receipt['package'] == PACKAGE+'.test' and test_receipt['signerSHA256'] == signer
    assert test_receipt['sourceSHA'] == metadata['sourceSHA'] and test_receipt['sha256'] == native._sha(test)
    native._inspect_apk(test, native._tools(), signer, PACKAGE+'.test')
    transport = os.environ['STUDIO_GECKO_TRANSPORT']
    assert transport in ('raw', 'tls', 'reality')
    fixture = native._json(Path(os.environ['STUDIO_GECKO_FIXTURE']))
    native._root_and_block()
    for name in ('nodeA', 'nodeB'):
        node = urllib.parse.urlsplit(fixture['transports'][transport][name])
        assert node.hostname == '10.0.2.2' and node.port in (18443,18444,19443,19444,20443,20444)
        native._shell('iptables','-I','OUTPUT','1','-p','tcp','-d','10.0.2.2','--dport',str(node.port),'-j','ACCEPT')
    for tool in ('iptables', 'ip6tables'):
        native._shell(tool,'-I','OUTPUT','1','-o','lo','-j','ACCEPT')
    install(apk)
    native._run(['adb','install','-t',str(test)])
    installed = native._installed(receipt['sha256'],'regular-webview-network')
    suite = os.environ.get('STUDIO_REGULAR_WEBVIEW_SUITE', 'network-files')
    assert suite in ('network-files', 'workbench')
    if suite == 'network-files':
        spec=importlib.util.spec_from_file_location('regular_owned_webview_route',Path(__file__).with_name('run-network-integration.py'))
        shared=importlib.util.module_from_spec(spec);spec.loader.exec_module(shared)
        sys.argv=[str(spec.origin),'--transport',transport,'--output',str(output),'--regular-files-only']
        shared.main()
        for name in ('verified.json','file-safety-verified.json','regular-webview-files-scope.json'):
            assert native._json(output/name)['complete'] is True
    else:
        assert transport == 'raw'
        methods = ('realDocumentUrisImportToTheInitiatingAccountAndPreviewAllThreeKinds',
                   'allProjectSkillsAreVisibleAndNativeSearchFiltersThem',
                   'nativeImageConversionsProduceRealCopiesAndKeepSourceBytes',
                   'nativeDocumentMenuProducesOwnedJsonAndRendersItsActualContent',
                   'markdownConvertsToRealStaticHtmlAndPreviewShowsItsBody',
                   'rendererFailureKeepsOriginalCleansTaskAndAllowsRetry',
                   'publicBrowserInputsCarryChooserMarkerActualFilesAndNativeText',
                   'assetDialogHandsActualFilesToTargetWebpageAndSendsOnce',
                   'originalRoleAloneUsesNoDefaultSkillAndSubmitsExactlyOnce')
        classes = ','.join('com.forcemind.chatgpt.profiles.WorkbenchTest#'+method for method in methods)
        result = native._run(['adb','shell','am','instrument','-w','-r','-e','class',classes,
                              '-e','fixtureMode','true',native.RUNNER], timeout=900, check=False)
        (output/'workbench-instrument.txt').write_text(result.stdout)
        print(result.stdout, flush=True)
        assert result.returncode == 0 and re.findall(r'(?m)^OK \((\d+) tests\)\s*$',result.stdout) == [str(len(methods))]
        assert not re.search(r'FAILURES!!!|INSTRUMENTATION_FAILED|shortMsg=|AssumptionViolated|Ignored',result.stdout)
        native._write_json(output/'regular-workbench.json',{
            'schema':1,'complete':True,'api':api,'package':PACKAGE,'sourceSHA':metadata['sourceSHA'],
            'methods':methods,'tests':len(methods),'failures':0,'skipped':0,'screenshots':False,
            'visualArtifactsDisabledByApprovedScope':True,'functionalAssertionsPreserved':True,
            'realServicesAccessed':False,'productionSignatureVerified':False})
    native._write_json(output/'regular-webview-network.json',{
        'schema':1,'complete':True,'api':api,'transport':transport,'package':PACKAGE,
        'sourceSHA':metadata['sourceSHA'],'versionMetadata':metadata,'ciReceipt':receipt,'suite':suite,
        'candidateInstalledSHA256':installed,'productionSignatureVerified':False,
        'screenshots':False,'realServicesAccessed':False,'backgroundLongTestsRepeated':False})
except Exception:
    native._record_failure()
    raise
finally:
    if guarded:
        native._logcat('regular-webview-network')
