#!/usr/bin/env python3
"""Reuse the accepted environment flow for the original-package compatibility target."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import sys
import traceback
import uuid

import regular_environment_upgrade as native


def dual_engine_round_trip(acceptance, draft):
    """Exercise real engine UI routes in a new instrumentation process, not across it."""
    before = acceptance.status()
    native._write_json(native.EVIDENCE / 'functional-server.json', before)
    args = ['adb', 'shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
            native.PACKAGE + '.RegularDualEngineSwitchTest', '-e', 'expectedApi', str(acceptance.api),
            '-e', 'baselineScope', native.SCOPE, '-e', 'signer', native._ci_signer(),
            '-e', 'expectedDraft', draft, native.RUNNER]
    output = acceptance.command(args, timeout=300)
    (native.EVIDENCE / 'dual-engine-instrument.txt').write_text(output, encoding='utf-8')
    print(output, flush=True)
    assert re.findall(r'(?m)^OK \((\d+) test\)\s*$', output) == ['1']
    assert not re.search(r'FAILED|FAILURES!!!|shortMsg=', output)
    entries = re.findall(r'(?m)^INSTRUMENTATION_RESULT: regularDualEngineSwitchProof=(\{[^\r\n]+\})\s*$', output)
    assert len(entries) == 1
    instrument = json.loads(entries[0])
    assert instrument['schema'] == 1 and instrument['complete'] is True and instrument['api'] == acceptance.api
    after = acceptance.status()
    # Instrumentation starts its own app process. One initial A0 load is expected;
    # repeated account-menu round trips inside that process must add none.
    counts = dict(before['pageCounts'])
    counts['/c/a1'] += 1
    assert after['pageCounts'] == counts, 'Engine round trips loaded extra Gecko documents'
    for path, documents in before['pages'].items():
        for document, previous in documents.items():
            current = after['pages'][path][document]
            for key in ('document', 'draft', 'modelDraft', 'scroll'):
                assert current[key] == previous[key], 'Earlier functional page state changed: ' + key
    old_documents = set(before['pages']['/c/a1'])
    new_documents = set(after['pages']['/c/a1']) - old_documents
    assert len(new_documents) == 1
    document = next(iter(new_documents))
    page = after['pages']['/c/a1'][document]
    acceptance.assert_environment(page, 'A')
    assert page['draft'] == page['modelDraft'] == draft and page['scroll'] > 200
    assert after['sendCount'] == after['rejectedEventCount'] == 0
    native._write_json(native.EVIDENCE / 'dual-engine-after-server.json', after)
    result = {'schema': 1, 'complete': True, 'api': acceptance.api, 'instrumentation': instrument,
              'instrumentationStartsNewProcess': True, 'initialGeckoLoads': 1,
              'extraGeckoReloadsDuringRoundTrips': 0, 'geckoDocument': document,
              'geckoDraftSHA256': hashlib.sha256(draft.encode()).hexdigest(),
              'earlierFunctionalDocumentsUnchanged': True, 'productionSignatureVerified': False,
              'originalBaselineMainActivityOpened': False, 'screenshots': False}
    native._write_json(native.EVIDENCE / 'dual-engine-round-trip.json', result)
    return result


def main():
    assert os.environ.get('GITHUB_ACTIONS') == 'true', 'Actions only'
    spec = importlib.util.spec_from_file_location('regular_owned_environment_flow', Path(__file__).with_name('run-environment-client.py'))
    shared = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(shared)
    # Configure a separate module instance; the Beta module and its default
    # package, signature, version and acceptance entry point remain unchanged.
    shared.native = native
    shared.PACKAGE = native.PACKAGE
    shared.FILES = '/data/user/0/' + native.PACKAGE + '/files/'
    shared.EVIDENCE = native.EVIDENCE
    native.EVIDENCE.mkdir(parents=True, exist_ok=True)
    acceptance = None
    try:
        devices = native._run(['adb', 'devices']).stdout.strip().splitlines()[1:]
        assert len(devices) == 1 and devices[0].split()[1:] == ['device']
        upgrade = native.run_upgrade('synthetic')
        ci_receipt = native._json(native.INPUT / 'receipt.json')
        native._write_json(native.EVIDENCE / 'ci-candidate-receipt.json', ci_receipt)
        acceptance = shared.Acceptance(upgrade)
        acceptance.tls_server()
        acceptance.prepare_profiles()
        _, initial = acceptance.open('A', 0)
        draft = 'OwnedRegularA1-' + uuid.uuid4().hex
        acceptance.type_draft('A', 0, draft)
        scrolled = acceptance.scroll()
        assert scrolled['draft'] == scrolled['modelDraft'] == draft
        saved_scroll = scrolled['scroll']
        first_record = acceptance.record(draft, saved_scroll - 2)
        original_document = scrolled['document']
        acceptance.header_switch('A', 1)
        second = acceptance.type_draft('A', 1, 'OwnedRegularA2-' + uuid.uuid4().hex)
        _, returned = acceptance.header_switch('A', 0)
        assert returned['document'] == original_document and returned['draft'] == returned['modelDraft'] == draft
        assert abs(returned['scroll'] - saved_scroll) <= 2 and returned['scroll'] > 200
        _, b = acceptance.open('B', 0)
        _, c = acceptance.open('C', 0)
        status, returned = acceptance.open('A', 0)
        assert returned['document'] == original_document and returned['draft'] == returned['modelDraft'] == draft
        assert status['pageCounts'] == {'/c/a1': 1, '/c/a2': 1, '/c/b1': 1, '/c/c1': 1, '/c/navigation': 0}
        assert second['localStorage'] == second['indexedDB'] == second['envLogin'] == 'environment-A'
        assert b['timeZone'] != returned['timeZone'] and c['timeZone'] == returned['timeZone']
        before_cold = acceptance.record(draft, saved_scroll - 2)
        restored = acceptance.cold(returned, draft, saved_scroll)
        cold_record = acceptance.record(draft, saved_scroll - 80)
        assert acceptance.http('/configure', {'path': '/c/a1', 'initialDraft': 'server-conflict'})['appliesTo'] == 'future documents only'
        conflict = acceptance.cold(restored, draft, restored['scroll'], conflict=True)
        conflict_record = acceptance.record(draft, saved_scroll - 80)
        final = acceptance.status()
        assert final['sendCount'] == 0 and final['rejectedEventCount'] == 0
        dual_engine = dual_engine_round_trip(acceptance, draft)
        retained = native.verify_after_environment()
        assert retained['complete'] and retained['legacyAccountPreserved']
        proof = {'schema': 1, 'complete': True, 'api': upgrade['api'], 'package': native.PACKAGE,
                 'sourceSHA': os.environ['GITHUB_SHA'], 'sameTemporaryCISignature': True,
                 'versionMetadata': ci_receipt['versionMetadata'], 'ciCandidateReceipt': ci_receipt,
                 'productionSignatureVerified': False, 'legacyUpgrade': upgrade,
                 'legacyAfterEnvironmentUse': retained, 'manifest': acceptance.manifest,
                 'dualEngineRoundTrip': dual_engine,
                 'languageTimeZoneAndWorkerVerified': True, 'httpAcceptLanguageVerified': True,
                 'cookieLocalStorageIndexedDBAccountIsolation': True,
                 'sameEnvironmentDifferentStorageContexts': True,
                 'trustedNativeTextareaAndContenteditableInput': True, 'realNativeReadingScroll': True,
                 'liveSwitchKeptDocumentAndDraftAndScroll': True, 'originalDocument': original_document,
                 'draftSHA256': hashlib.sha256(draft.encode()).hexdigest(),
                 'firstRecovery': first_record, 'beforeColdRecovery': before_cold,
                 'coldRecovery': {'document': restored['document'], 'native': cold_record},
                 'conflictRecovery': {'document': conflict['document'], 'native': conflict_record,
                                      'webDraftPreservedUntilNativeChoice': True, 'nativeRestoreChosen': True},
                 'nativeKeyboardReceipts': acceptance.keyboard_events, 'nativeTouchReceipts': acceptance.touch_events,
                 'coldProcessExitReceipts': acceptance.cold_process_exits,
                 'sendCount': 0, 'screenshots': False, 'realServicesAccessed': False,
                 'backgroundLongTestsRepeated': False}
        native._write_json(native.EVIDENCE / 'regular-functional.json', proof)
        print(json.dumps({'complete': True, 'api': upgrade['api'], 'package': native.PACKAGE,
                          'productionSignatureVerified': False}), flush=True)
    except Exception:
        native._record_failure()
        raise
    finally:
        native._logcat('regular-environment')
        if acceptance is not None:
            failing = sys.exc_info()[0] is not None
            try:
                acceptance.close()
            except Exception:
                (native.EVIDENCE / 'server-cleanup-error.txt').write_text(traceback.format_exc())
                if not failing:
                    raise


if __name__ == '__main__':
    main()
