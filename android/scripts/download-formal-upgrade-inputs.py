"""Read the exact approved private draft after authenticating both CI sources."""
import json
import os
from pathlib import Path
import re
import subprocess

assert os.environ.get('GITHUB_ACTIONS') == 'true'
assert os.environ.get('GITHUB_REPOSITORY') == 'ForceMind/ChatGPT'
assert os.environ.get('GITHUB_REF') == 'refs/heads/dev'


def api(path):
    return json.loads(subprocess.check_output(['gh', 'api', 'repos/ForceMind/ChatGPT/' + path]))


def require_success(run_id, sha, workflow):
    assert re.fullmatch(r'[0-9]+', run_id)
    run = api('actions/runs/' + run_id)
    assert str(run['id']) == run_id
    assert run['repository']['full_name'] == run['head_repository']['full_name'] == 'ForceMind/ChatGPT'
    assert run['head_branch'] == 'dev' and run['head_sha'] == sha
    assert run['path'] == '.github/workflows/' + workflow
    assert run['event'] == 'workflow_dispatch' and run['status'] == 'completed' and run['conclusion'] == 'success'


source_sha = os.environ['STUDIO_FORMAL_SOURCE_SHA']
require_success(os.environ['STUDIO_FORMAL_RUN'], source_sha, 'android-regular-optimization.yml')
require_success(os.environ['STUDIO_FORMAL_HARNESS_RUN'], '446ff8013966111354cd502460317419b2939d83',
                'android-formal-upgrade-harness.yml')
draft_id = os.environ['STUDIO_STAGING_RELEASE']
digest = os.environ['STUDIO_SIGNED_BUNDLE_SHA256']
assert re.fullmatch(r'[0-9]+', draft_id) and re.fullmatch(r'[a-f0-9]{64}', digest)
release = api('releases/' + draft_id)
assert str(release['id']) == draft_id and release['draft'] is True and release['prerelease'] is True
assert release['target_commitish'] == source_sha
assert re.fullmatch(r'formal-upgrade-review-[0-9]{12}', release['tag_name'])
asset, = release['assets']
assert asset['name'] == 'formal-original-upgrade-inputs.zip' and asset['state'] == 'uploaded'
assert 0 < asset['size'] < 300 * 1024 * 1024
if asset.get('digest'):
    assert asset['digest'] == 'sha256:' + digest
output = Path('android/app/build') / asset['name']
output.parent.mkdir(parents=True, exist_ok=True)
with output.open('xb') as stream:
    subprocess.run(['gh', 'api', 'repos/ForceMind/ChatGPT/releases/assets/' + str(asset['id']),
                    '-H', 'Accept: application/octet-stream'], stdout=stream, check=True)
assert output.stat().st_size == asset['size']
