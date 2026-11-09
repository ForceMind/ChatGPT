"""Use old/new-specific instrumentation without changing the old application."""

def upgrade_file_oracle(shared, verifier):
    method = '    static void execute(String phase,long version,boolean seed,boolean subset)throws Exception{\n'
    assert shared.count(method) == 1
    start = shared.index(method) + len(method)
    before, body = shared[:start], shared[start:]
    assert body.count('verifyNative(expected,subset)') == 2
    shared = before + '''        execute(phase,version,seed,subset,RegularEnvironmentUpgradeTest::verifyNative);
    }
    interface PersistedVerifier {
        void verify(JSONObject expected,boolean subset)throws Exception;
    }
    static void execute(String phase,long version,boolean seed,boolean subset,PersistedVerifier verifier)throws Exception{
''' + body.replace('verifyNative(expected,subset)', 'verifier.verify(expected,subset)')

    start = verifier.index('        JSONObject expected=')
    end = verifier.index('\n    }', start)
    assert verifier[start:end].count('RegularEnvironmentUpgradeTest.execute(') == 1
    verifier = verifier[:start] + '''        RegularEnvironmentUpgradeTest.execute(phase,RegularEnvironmentUpgradeTest.NEW_CODE,false,
                "verify-after-environment".equals(phase),OptimizationUpgradeFiles::verify);''' + verifier[end:]
    verifier = verifier.replace('import org.json.JSONArray;\nimport org.json.JSONObject;\n', '')
    return shared, verifier


def legacy_upgrade_overlay(text):
    marker = "        _root_and_block()\n        _run(['adb', 'install', str(baseline)])"
    assert text.count(marker)==1
    replacement = '''        old_test = _local_file('upgrade-baseline-test.apk')
        old_test_receipt = _json(INPUT/'baseline-test-receipt.json')
        assert old_test_receipt['sourceSHA'] == receipt['sourceSHA'] and old_test_receipt['signerSHA256'] == signer
        assert old_test_receipt['compiledAgainstUnminified'] is True and old_test_receipt['ciOnly'] is True
        assert _sha(old_test) == old_test_receipt['sha256'] and old_test.stat().st_size == old_test_receipt['bytes']
        old_test_info = _inspect_apk(old_test, tools, signer, TEST_PACKAGE)
        _root_and_block()
        _run(['adb', 'install', str(baseline)])'''
    text=text.replace(marker,replacement,1)
    needle="        _run(['adb', 'install', '-t', str(test)])";assert text.count(needle)==1
    text=text.replace(needle,"        _run(['adb', 'install', '-t', str(old_test)])",1)
    needle="        upgraded = _phase('verify-new', api, signer)";assert text.count(needle)==1
    text=text.replace(needle,"        _run(['adb', 'install', '-r', '-t', str(test)])\n"+needle,1)
    needle="        expected = _expected()\n        for phase in (seed, old_cold, upgraded, upgraded_cold):";assert text.count(needle)==1
    text=text.replace(needle,"        _write_json(EVIDENCE/'old-side-test.json', old_test_info)\n"+needle,1)
    return text
