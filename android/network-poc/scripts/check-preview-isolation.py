"""Inspect original built APKs in CI; never install or build a preview locally."""
import json
import os
from pathlib import Path
import re
import subprocess
import zipfile


def boolean_attribute(line):
    if line is None:
        return False
    value = line.rsplit('=', 1)[-1].strip().lower()
    if value in ('true', '"true"'):
        return True
    return re.fullmatch(r'(?:\(type\s+0x12\)\s*)?0x(?:1|ffffffff)', value) is not None


def main():
    assert os.environ.get('GITHUB_ACTIONS') == 'true'
    root = Path(__file__).resolve().parents[1]
    tool = Path(os.environ['ANDROID_HOME']) / 'build-tools/36.0.0/aapt2'
    report = {'scope': 'compiled preview/ci APK isolation', 'builtLocally': False}
    failure = None
    try:
        for value in ['true', '"true"', '(type 0x12)0xffffffff', '(type 0x12)0x1']:
            assert boolean_attribute('android:testOnly=' + value)
        for value in ['false', '0x0', '(type 0x12)0x0', 'unknown', '1']:
            assert not boolean_attribute('android:testOnly=' + value)
        for flavor in ['preview', 'ci']:
            apk = root / f'app/build/outputs/apk/{flavor}/debug/app-{flavor}-debug.apk'
            assert apk.is_file(), 'Missing exact built APK: ' + flavor
            manifest = subprocess.check_output([str(tool), 'dump', 'xmltree', str(apk),
                                                '--file', 'AndroidManifest.xml'], text=True)
            admin = 'JointProxyAdminReceiver' in manifest and 'android.permission.BIND_DEVICE_ADMIN' in manifest
            provider = 'OwnedDocumentsProvider' in manifest
            document_permission = 'android.permission.MANAGE_DOCUMENTS' in manifest
            test_only = re.search(r'android:testOnly[^\n]*', manifest)
            raw_test_only = test_only[0] if test_only is not None else None
            enabled = boolean_attribute(raw_test_only)
            evidence_folder = root / 'evidence'
            evidence_folder.mkdir(exist_ok=True)
            (evidence_folder / ('joint-manifest-' + flavor + '.txt')).write_text(manifest)
            with zipfile.ZipFile(apk) as archive:
                names = archive.namelist()
                tokens = ['JointProxyAdminReceiver', 'OwnedDocumentsProvider', 'GeneratedFixtureCa']
                dex = [n for n in names if n.endswith('.dex')]
                found = {t: any(t.encode() in archive.read(n) for n in dex) for t in tokens}
                admin_resource = any('joint_device_admin' in n for n in names)
                ca_resource = any('network_fixture_ca' in n for n in names)
            report[flavor] = {'adminReceiverAndPermission': admin, 'ownedProvider': provider,
                              'managedDocumentPermission': document_permission,
                              'testOnly': enabled, 'testOnlyRawAttribute': raw_test_only, 'adminResource': admin_resource,
                              'fixtureCAResource': ca_resource, 'dexCIClasses': found}
            if flavor == 'preview':
                assert not admin and not provider and not document_permission and not enabled and not admin_resource and not ca_resource
                assert not any(found.values()), 'CI-only implementation leaked into preview APK'
            else:
                assert admin and provider and document_permission and enabled and admin_resource and ca_resource
                assert all(found.values()), 'CI implementation missing from isolated test APK'
        report['passed'] = True
    except Exception as error:
        report['passed'] = False
        report['error'] = str(error)
        failure = error
    folder = root / 'evidence'
    folder.mkdir(exist_ok=True)
    (folder / 'joint-preview-isolation.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report))
    if failure is not None:
        raise RuntimeError('Preview/CI package isolation failed') from failure


if __name__ == '__main__':
    main()
