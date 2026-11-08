"""A checked Actions evidence source, independent of the current delivery harness SHA."""
from datetime import datetime
import json
import os
from pathlib import Path
import re

from regular_version import validate_version

REPOSITORY = 'ForceMind/ChatGPT'
WORKFLOW = '.github/workflows/android-regular-full-client.yml'
SOURCE_FILE = Path('android/app/build/regular-evidence-source.json')
PACKAGE = 'com.forcemind.chatgpt.profiles'
PRODUCTION_SIGNER = '8b6eef928fded91f291ad954e20976b2cc6011b55e8cbec0152695fe1f05cf19'
BETA_SIGNER = 'bd7b9c924795ec21d27c73f280b5fc71b83ffea950e36e05967b77f092056ac0'

# This is the existing full-workflow artifact layout, not a new test matrix.
ARTIFACTS = [
    ('regular-unsigned-review', 'build', 'android/app/build/regular-review'),
    ('environment-client-input', 'build', 'input'),
    ('original-signature-input', 'build', 'android/app/build/original-signature-input'),
]
for _api in (28, 35):
    ARTIFACTS.extend([
        (f'regular-environment-{_api}', f'accept ({_api})', f'android/app/build/regular-environment-evidence/api{_api}'),
        (f'regular-navigation-{_api}', f'navigation ({_api})', f'android/app/build/regular-navigation-evidence/api{_api}'),
        (f'gecko-local-exports-{_api}', f'gecko-local-exports ({_api})', f'acceptance/local-exports/gecko-local-exports-{_api}'),
        (f'gecko-handoff-{_api}', f'gecko-handoff ({_api})', f'acceptance/handoff/gecko-handoff-{_api}'),
        (f'regular-webview-workbench-{_api}', f'webview-workbench ({_api})', f'acceptance/workbench/regular-webview-workbench-{_api}'),
    ])
    for _transport in ('raw', 'tls', 'reality'):
        ARTIFACTS.extend([
            (f'gecko-network-{_api}-{_transport}', f'gecko-network ({_api}, {_transport})', f'acceptance/network/gecko-network-{_api}-{_transport}'),
            (f'regular-webview-network-{_api}-{_transport}', f'webview-network ({_api}, {_transport})', f'acceptance/webview-network/regular-webview-network-{_api}-{_transport}'),
        ])
APPROVED_JOBS = frozenset(row[1] for row in ARTIFACTS)
assert len(APPROVED_JOBS) == 23 and len(ARTIFACTS) == 25


def timestamp(value):
    assert isinstance(value, str) and value.endswith('Z'), 'Missing UTC API timestamp'
    return datetime.fromisoformat(value[:-1] + '+00:00')


def _harness():
    assert os.environ.get('GITHUB_ACTIONS') == 'true', 'Actions evidence only'
    assert os.environ.get('GITHUB_REPOSITORY') == REPOSITORY, 'Wrong delivery repository'
    sha = os.environ.get('GITHUB_SHA', '')
    run = os.environ.get('GITHUB_RUN_ID', '')
    assert re.fullmatch(r'[a-f0-9]{40}', sha), 'Missing actual harness SHA'
    assert re.fullmatch(r'[1-9][0-9]*', run), 'Missing actual harness run ID'
    return sha, int(run)


def validate_run_jobs(run, jobs, harness_sha, harness_run_id):
    assert run['repository']['full_name'] == REPOSITORY and run['head_repository']['full_name'] == REPOSITORY
    assert run['repository']['id'] == run['head_repository']['id']
    assert type(run['id']) is int and run['id'] > 0
    assert run['path'] == WORKFLOW and run['event'] == 'workflow_dispatch' and run['head_branch'] == 'dev'
    assert re.fullmatch(r'[a-f0-9]{40}', run['head_sha'])
    assert type(run['run_attempt']) is int and run['run_attempt'] > 0
    assert len(jobs) == 24, 'Require build, all 22 functional jobs, and delivery'
    by_name = {job['name']: job for job in jobs}
    assert len(by_name) == len(jobs) and set(by_name) == APPROVED_JOBS | {'deliver'}, 'Unexpected or duplicate latest jobs'
    assert len({job['id'] for job in jobs}) == len(jobs)
    for job in jobs:
        assert type(job['id']) is int and job['id'] > 0
        assert job['run_id'] == run['id'] and job['head_sha'] == run['head_sha'] and job['head_branch'] == 'dev'
        assert job['run_attempt'] == run['run_attempt'], 'Require the latest-attempt API job records'
    for name in APPROVED_JOBS:
        job = by_name[name]
        assert job['status'] == 'completed' and job['conclusion'] == 'success', 'Unsuccessful approved job: ' + name
        assert timestamp(job['started_at']) <= timestamp(job['completed_at'])
    deliver = by_name['deliver']
    if run['id'] == harness_run_id:
        assert run['head_sha'] == harness_sha
        assert run['status'] == 'in_progress' and run['conclusion'] is None
        assert deliver['status'] in ('queued', 'in_progress') and deliver['conclusion'] is None
        mode = 'current-run'
    else:
        assert run['status'] == 'completed' and run['conclusion'] == 'failure', 'External source may resume only failed delivery'
        assert deliver['status'] == 'completed' and deliver['conclusion'] == 'failure', 'Only delivery may have failed'
        mode = 'delivery-resume'
    return by_name, mode


def validate_artifact(artifact, run, job):
    assert type(artifact['id']) is int and artifact['id'] > 0
    assert artifact['expired'] is False, 'Expired artifact'
    assert type(artifact['size_in_bytes']) is int and artifact['size_in_bytes'] > 0
    assert re.fullmatch(r'sha256:[a-f0-9]{64}', artifact['digest']), 'Missing official artifact digest'
    origin = artifact['workflow_run']
    assert origin['id'] == run['id'] and origin['head_sha'] == run['head_sha'] and origin['head_branch'] == 'dev'
    assert origin['repository_id'] == run['repository']['id'] and origin['head_repository_id'] == run['head_repository']['id']
    created = timestamp(artifact['created_at'])
    assert timestamp(job['started_at']) <= created <= timestamp(job['completed_at']), 'Artifact is outside its successful job window'
    assert created <= timestamp(artifact['updated_at'])


def validate_ci_receipts(network, base, source):
    metadata = validate_version(network['versionMetadata'])
    assert base['versionMetadata'] == metadata and metadata['sourceSHA'] == source
    for receipt, filename in ((network, 'gecko-network-x86.apk'), (base, 'regular-ci-x86.apk')):
        assert receipt['schema'] == 1 and receipt['file'] == filename and receipt['package'] == PACKAGE
        assert receipt['ciOnly'] is True and receipt['productionSignatureVerified'] is False
        assert type(receipt['bytes']) is int and receipt['bytes'] > 0
        assert re.fullmatch(r'[a-f0-9]{64}', receipt['sha256'])
        for field in ('sourceSHA', 'versionCode', 'versionName'):
            assert receipt[field] == metadata[field], field
        assert re.fullmatch(r'[a-f0-9]{64}', receipt['signerSHA256'])
    signer = network['signerSHA256']
    assert signer == base['signerSHA256'] and signer not in (PRODUCTION_SIGNER, BETA_SIGNER)
    configured = os.environ.get('STUDIO_REGULAR_CI_CERT_SHA256')
    if configured is not None:
        assert configured == signer, 'Configured CI signer differs from authenticated build artifacts'
    return signer


def validate_provenance(value):
    harness_sha, harness_run = _harness()
    assert value['schema'] == 1 and value['complete'] is True
    assert value['harnessSourceSHA'] == harness_sha and value['harnessRunID'] == harness_run
    run = value['sourceRun']
    by_name, mode = validate_run_jobs(run, value['sourceJobs'], harness_sha, harness_run)
    assert value['sourceRunID'] == run['id'] and value['sourceSHA'] == run['head_sha'] and value['sourceMode'] == mode
    validated = value['validatedJobs']
    assert len(validated) == 23 and {job['name'] for job in validated} == APPROVED_JOBS
    for job in validated:
        assert job == by_name[job['name']]
    api = value['apiValidation']
    prefix = 'repos/' + REPOSITORY + '/actions/runs/' + str(run['id'])
    assert api['runEndpoint'] == prefix and api['jobsEndpoint'] == prefix + '/jobs?filter=latest'
    assert api['artifactsEndpoint'] == prefix + '/artifacts' and api['completePagination'] is True
    timestamp(api['checkedAt'])
    entries = value['artifacts']
    assert len(entries) == len(ARTIFACTS) and len({entry['id'] for entry in entries}) == len(entries)
    by_artifact = {entry['name']: entry for entry in entries}
    assert len(by_artifact) == len(entries) and set(by_artifact) == {row[0] for row in ARTIFACTS}
    for name, job_name, destination in ARTIFACTS:
        entry = by_artifact[name]
        assert entry['jobName'] == job_name and entry['jobId'] == by_name[job_name]['id']
        assert entry['destination'] == destination
        validate_artifact(entry, run, by_name[job_name])
        assert entry['archiveSHA256'] == entry['digest'][7:] and entry['archiveBytes'] == entry['size_in_bytes']
    receipts = value['ciReceipts']
    signer = validate_ci_receipts(receipts['network'], receipts['base'], run['head_sha'])
    assert value['ciSignerSHA256'] == signer
    return value


def provenance():
    _harness()
    source = os.environ.get('STUDIO_REGULAR_EVIDENCE_SOURCE_FILE')
    if source is None:
        return None
    path = Path(source)
    assert path.is_file() and not path.is_symlink(), 'Evidence source witness missing'
    return validate_provenance(json.loads(path.read_text(encoding='utf-8')))


def source_sha():
    value = provenance()
    return value['sourceSHA'] if value is not None else os.environ['GITHUB_SHA']
