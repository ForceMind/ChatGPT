#!/usr/bin/env python3
"""Reuse the accepted environment flow for the original-package compatibility target."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import sys
import traceback
import uuid

import regular_environment_upgrade as native


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
        retained = native.verify_after_environment()
        assert retained['complete'] and retained['legacyAccountPreserved']
        proof = {'schema': 1, 'complete': True, 'api': upgrade['api'], 'package': native.PACKAGE,
                 'sourceSHA': os.environ['GITHUB_SHA'], 'sameTemporaryCISignature': True,
                 'versionMetadata': ci_receipt['versionMetadata'], 'ciCandidateReceipt': ci_receipt,
                 'productionSignatureVerified': False, 'legacyUpgrade': upgrade,
                 'legacyAfterEnvironmentUse': retained, 'manifest': acceptance.manifest,
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
