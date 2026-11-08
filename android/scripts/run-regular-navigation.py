#!/usr/bin/env python3
"""Run the existing two-engine navigation assertions under the regular CI identity."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import sys
import traceback
import uuid

import regular_environment_upgrade as native
from owned_ascii_input import AcknowledgedNativeInput


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


def main():
    assert os.environ.get('GITHUB_ACTIONS') == 'true'
    shared = module('regular_navigation_shared_flow', 'run-environment-client.py')
    shared.native, shared.PACKAGE, shared.EVIDENCE = native, native.PACKAGE, native.EVIDENCE
    shared.FILES = '/data/user/0/' + native.PACKAGE + '/files/'
    legacy = module('regular_navigation_shared_native', 'environment_upgrade.py')
    legacy.PACKAGE, legacy.RUNNER, legacy.EVIDENCE = native.PACKAGE, native.RUNNER, native.EVIDENCE
    legacy._installed, legacy._record_failure, legacy._logcat = native._installed, native._record_failure, native._logcat
    native.EVIDENCE.mkdir(parents=True, exist_ok=True)
    acceptance = None
    try:
        devices = native._run(['adb', 'devices']).stdout.strip().splitlines()[1:]
        assert len(devices) == 1 and devices[0].split()[1:] == ['device']
        upgrade = native.run_upgrade('synthetic')
        receipt = native._json(native.INPUT / 'receipt.json')
        class NavigationAcceptance(AcknowledgedNativeInput, shared.Acceptance):
            input_identity_field = 'url'
        acceptance = NavigationAcceptance(upgrade)
        acceptance.output = native.EVIDENCE
        acceptance._input_deadline = None
        acceptance.input_prefix_receipts = []
        acceptance.input_stream_receipts = []
        acceptance.tls_server()
        acceptance.prepare_profiles()
        _, first = acceptance.open('A', 0)
        draft = 'OwnedRegularNavigation-' + uuid.uuid4().hex
        acceptance.type_draft('A', 0, draft)
        acceptance.record(draft, 0)
        acceptance.header_switch('A', 1)
        _, current = acceptance.header_switch('A', 0)
        assert current['document'] == first['document'] and current['draft'] == draft
        gecko = acceptance.chrome_navigation(draft, current)
        native._write_json(native.EVIDENCE / 'navigation-server-final.json', acceptance.status())
        webview = legacy.verify_browser_chrome()
        retained = native.verify_after_environment()
        proof = {'schema': 1, 'complete': True, 'package': native.PACKAGE, 'api': upgrade['api'],
                 'sourceSHA': os.environ['GITHUB_SHA'], 'versionMetadata': receipt['versionMetadata'],
                 'ciCandidateReceipt': receipt, 'legacyUpgrade': upgrade,
                 'geckoNavigation': gecko, 'webviewNavigation': webview,
                 'legacyAfterNavigation': retained, 'draftSHA256': hashlib.sha256(draft.encode()).hexdigest(),
                 'nativePrefixReceipts': acceptance.input_prefix_receipts, 'nativeInputStreams': acceptance.input_stream_receipts,
                 'nativeKeyboardReceipts': acceptance.keyboard_events, 'inputDeadlineSeconds': 75,
                 'inputMode': 'acknowledged-native-stream', 'claimsBatchOrderingFixed': False,
                 'sendCount': acceptance.status()['sendCount'], 'productionSignatureVerified': False,
                 'screenshots': False, 'realServicesAccessed': False, 'backgroundLongTestsRepeated': False}
        assert proof['sendCount'] == 0
        native._write_json(native.EVIDENCE / 'regular-navigation.json', proof)
        print(json.dumps({'complete': True, 'api': upgrade['api'], 'engines': ['webview', 'gecko']}), flush=True)
    except Exception:
        native._record_failure()
        raise
    finally:
        native._logcat('regular-navigation')
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
