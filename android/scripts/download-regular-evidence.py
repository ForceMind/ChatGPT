#!/usr/bin/env python3
"""Select only successful job-window artifacts, then download their exact authenticated IDs."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import shutil
import stat
import subprocess
import tempfile
import zipfile

from regular_evidence_source import (ARTIFACTS, APPROVED_JOBS, REPOSITORY, SOURCE_FILE,
                                     _harness, timestamp, validate_artifact,
                                     validate_ci_receipts, validate_provenance, validate_run_jobs)


def api(endpoint):
    result = subprocess.run(['gh', 'api', '--method', 'GET', endpoint], check=True,
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=60)
    return json.loads(result.stdout)


def pages(endpoint, field):
    result = []
    number = 1
    while True:
        separator = '&' if '?' in endpoint else '?'
        page = api(endpoint + separator + 'per_page=100&page=' + str(number))
        rows = page[field]
        assert isinstance(rows, list)
        result.extend(rows)
        assert len(result) <= page['total_count'], 'API pagination changed during selection'
        if len(result) == page['total_count']:
            break
        assert rows, 'Incomplete API pagination'
        number += 1
    assert len({row['id'] for row in result}) == len(result), 'Duplicate API pagination rows'
    return result


def sha(path):
    value = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            value.update(chunk)
    return value.hexdigest()


def extract(archive_path, destination):
    destination.mkdir()
    with zipfile.ZipFile(archive_path) as archive:
        names = archive.namelist()
        assert names and len(names) == len(set(names)), 'Duplicate or empty ZIP members'
        normalized = set()
        for item in archive.infolist():
            name = item.filename
            path = PurePosixPath(name)
            parts = name.rstrip('/').split('/')
            assert name and not path.is_absolute() and all(part not in ('', '.', '..') for part in parts)
            assert '\\' not in name and '\x00' not in name and all(':' not in part for part in parts), 'Unsafe ZIP path'
            key = str(path)
            assert key not in normalized, 'Duplicate normalized ZIP path'
            normalized.add(key)
            mode = stat.S_IFMT(item.external_attr >> 16)
            assert mode in (0, stat.S_IFREG, stat.S_IFDIR), 'ZIP symlink or special member'
            target = destination.joinpath(*path.parts)
            if item.is_dir():
                assert mode != stat.S_IFREG
                target.mkdir(parents=True, exist_ok=True)
            else:
                assert mode != stat.S_IFDIR
                target.parent.mkdir(parents=True, exist_ok=True)
                with archive.open(item) as source, target.open('xb') as output:
                    shutil.copyfileobj(source, output, 1024 * 1024)


def read(path):
    assert path.is_file() and not path.is_symlink()
    return json.loads(path.read_text(encoding='utf-8'))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run-id', type=int, required=True)
    args = parser.parse_args()
    harness_sha, harness_run = _harness()
    assert args.run_id > 0
    assert os.environ.get('GH_TOKEN') or os.environ.get('GITHUB_TOKEN'), 'Read-only Actions API token required'
    env_file = Path(os.environ['GITHUB_ENV'])
    assert env_file.is_file() and not env_file.is_symlink()
    assert not SOURCE_FILE.exists(), 'Refuse to overwrite an evidence witness'
    for _, _, target in ARTIFACTS:
        assert not Path(target).exists(), 'Refuse to mix evidence in existing destination: ' + target
    prefix = 'repos/' + REPOSITORY + '/actions/runs/' + str(args.run_id)
    run = api(prefix)
    assert run['id'] == args.run_id
    jobs = pages(prefix + '/jobs?filter=latest', 'jobs')
    by_job, mode = validate_run_jobs(run, jobs, harness_sha, harness_run)
    artifacts = pages(prefix + '/artifacts', 'artifacts')
    selected = []
    for name, job_name, target in ARTIFACTS:
        job = by_job[job_name]
        candidates = [row for row in artifacts if row['name'] == name
                      and timestamp(job['started_at']) <= timestamp(row['created_at']) <= timestamp(job['completed_at'])]
        assert len(candidates) == 1, 'Require one artifact in successful job window: ' + name
        artifact = candidates[0]
        validate_artifact(artifact, run, job)
        selected.append({**artifact, 'jobName': job_name, 'jobId': job['id'], 'destination': target})
    SOURCE_FILE.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='regular-evidence-', dir=SOURCE_FILE.parent) as temporary:
        staging = Path(temporary)
        extracted = {}
        for row in selected:
            archive = staging / (str(row['id']) + '.zip')
            with archive.open('xb') as output:
                subprocess.run(['gh', 'api', '--method', 'GET',
                                'repos/' + REPOSITORY + '/actions/artifacts/' + str(row['id']) + '/zip'],
                               check=True, stdout=output, stderr=subprocess.PIPE, timeout=300)
            assert archive.stat().st_size == row['size_in_bytes'], 'Downloaded archive size mismatch'
            row['archiveSHA256'] = sha(archive)
            row['archiveBytes'] = archive.stat().st_size
            assert row['archiveSHA256'] == row['digest'][7:], 'Official artifact digest mismatch: ' + row['name']
            folder = staging / str(row['id'])
            extract(archive, folder)
            extracted[row['name']] = folder
        network = read(extracted['environment-client-input'] / 'gecko-network-receipt.json')
        base = read(extracted['environment-client-input'] / 'regular-base-ci-receipt.json')
        signer = validate_ci_receipts(network, base, run['head_sha'])
        witness = {
            'schema': 1, 'complete': True, 'sourceRunID': run['id'], 'sourceSHA': run['head_sha'],
            'sourceMode': mode, 'sourceRun': run, 'sourceJobs': jobs,
            'validatedJobs': [by_job[name] for name in sorted(APPROVED_JOBS)],
            'harnessSourceSHA': harness_sha, 'harnessRunID': harness_run,
            'artifacts': selected, 'ciSignerSHA256': signer,
            'ciReceipts': {'network': network, 'base': base},
            'apiValidation': {'runEndpoint': prefix, 'jobsEndpoint': prefix + '/jobs?filter=latest',
                              'artifactsEndpoint': prefix + '/artifacts', 'completePagination': True,
                              'checkedAt': datetime.now(timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ')},
        }
        validate_provenance(witness)
        # Nothing is merged into an existing directory. Publish only fully checked archives.
        for row in selected:
            target = Path(row['destination'])
            assert not target.exists()
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copytree(extracted[row['name']], target)
        temporary_witness = staging / 'source.json'
        temporary_witness.write_text(json.dumps(witness, indent=2) + '\n', encoding='utf-8')
        assert not SOURCE_FILE.exists()
        temporary_witness.rename(SOURCE_FILE)
    with env_file.open('a', encoding='utf-8') as output:
        output.write('STUDIO_REGULAR_EVIDENCE_SOURCE_FILE=' + str(SOURCE_FILE.resolve()) + '\n')
        output.write('STUDIO_REGULAR_CI_CERT_SHA256=' + signer + '\n')
    print(json.dumps({'complete': True, 'sourceRunID': run['id'], 'sourceSHA': run['head_sha'],
                      'validatedJobs': 23, 'artifacts': len(selected), 'sourceMode': mode,
                      'witness': str(SOURCE_FILE)}, sort_keys=True))


if __name__ == '__main__':
    main()
