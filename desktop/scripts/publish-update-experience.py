"""Promote verified desktop packages without rebuilding them."""
import base64
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import time

REPO = 'ForceMind/ChatGPT'
assert os.environ.get('GITHUB_ACTIONS') == 'true'
assert os.environ.get('GITHUB_REPOSITORY') == REPO
assert os.environ.get('GITHUB_REF') == 'refs/heads/dev'
assert os.environ.get('PUBLISH_DESKTOP_UPDATES') == 'true'


def read(path):
    return json.loads(Path(path).read_text(encoding='utf-8-sig'))


def api(path):
    return json.loads(subprocess.check_output(['gh', 'api', 'repos/' + REPO + '/' + path]))


def unique(folder, name):
    files = list(Path(folder).rglob(name))
    assert len(files) == 1 and files[0].is_file() and not files[0].is_symlink(), name
    return files[0]


def digest(file, algorithm='sha256'):
    result = hashlib.new(algorithm)
    with file.open('rb') as stream:
        for part in iter(lambda: stream.read(1048576), b''):
            result.update(part)
    return result


tab_strip = os.environ.get('PUBLISH_TAB_STRIP') == 'true'
version = read('desktop/package.json')['version']
step_name = 'Approved narrow tab strip interaction' if tab_strip else 'Approved owned update interaction and installation regression'
if tab_strip:
    assert version in ('2026.11.11', '2026.11.12')
    source = os.environ['GITHUB_SHA']
    run = api('actions/runs/' + os.environ['GITHUB_RUN_ID'])
    assert run['head_sha'] == source and run['status'] == 'in_progress'
    available = api('actions/runs/' + str(run['id']) + '/artifacts?per_page=100')['artifacts']
    artifact_metadata = [
        row for row in available
        if row['name'] in ('ChatGPT-Studio-Update-Candidate-macOS', 'ChatGPT-Studio-Update-Candidate-Windows')
    ]
    assert len(artifact_metadata) == 2
    platform_reports = []
    for platform, folder, info_file, architecture, signing in (
        ('macOS', 'promotion/update-mac', 'build-info.json', 'arm64', 'ad-hoc, not notarized'),
        ('Windows', 'promotion/update-windows', 'windows-build-info.json', 'x64', 'unsigned'),
    ):
        info = read(unique(folder, info_file))
        expected = (
            {f'ChatGPT-Studio-{version}-mac-arm64.dmg', f'ChatGPT-Studio-{version}-mac-arm64.zip',
             f'ChatGPT-Profiles-{version}-mac-arm64.zip'}
            if platform == 'macOS'
            else {f'ChatGPT-Studio-{version}-windows-x64-setup.exe', f'ChatGPT-Studio-{version}-windows-x64.zip'}
        )
        rows = [item for item in info['artifacts'] if item['file'] in expected]
        assert len(rows) == len(expected) and {a['file'] for a in rows} == expected
        proof = Path('promotion/tab-strip-evidence') / ('update-experience-evidence-' + platform)
        assert all(f.suffix not in {'.png', '.jpg', '.webm', '.zip'} for f in proof.rglob('*') if f.is_file())
        result = read(unique(proof, 'tab-strip.json'))['stats']
        assert result['expected'] == 1 and result['unexpected'] == 0 and result['skipped'] == 0 and result['flaky'] == 0
        platform_reports.append({'platform': platform, 'architecture': architecture, 'signing': signing, 'artifacts': rows})
else:
    candidate = read('docs/releases/desktop-update-experience-2026.11.10-candidate.json')
    assert version == '2026.11.10' == candidate['version']
    source = candidate['sourceSHA']
    run = api('actions/runs/' + str(candidate['runID']))
    assert run['status'] == 'completed' and run['conclusion'] == 'success'
    artifact_metadata = candidate['artifactMetadata']
    platform_reports = candidate['platformReports']
assert run['head_sha'] == source and run['head_branch'] == 'dev'
assert run['event'] == 'workflow_dispatch' and run['path'] == '.github/workflows/desktop-ci.yml'
jobs = api('actions/runs/' + str(run['id']) + '/jobs?filter=latest&per_page=100')['jobs']
for platform in ('macos-15', 'windows-2025'):
    job, = [j for j in jobs if j['name'] == 'update-candidates (' + platform + ')']
    assert job['conclusion'] == 'success'
    for name in ('Full required check', step_name):
        step, = [s for s in job['steps'] if s['name'] == name]
        assert step['conclusion'] == 'success'

out = Path('promotion/desktop-update-output')
out.mkdir(parents=True, exist_ok=False)
artifacts = []
for report in platform_reports:
    platform = report['platform']
    folder = Path('promotion/update-mac' if platform == 'macOS' else 'promotion/update-windows')
    artifact_name = 'ChatGPT-Studio-Update-Candidate-' + platform
    expected, = [a for a in artifact_metadata if a['name'] == artifact_name]
    actual = api('actions/artifacts/' + str(expected['id']))
    assert not actual['expired'] and actual['workflow_run']['head_sha'] == source
    assert actual['workflow_run']['id'] == run['id'] and actual['digest'] == expected['digest']
    info = read(unique(folder, 'build-info.json' if platform == 'macOS' else 'windows-build-info.json'))
    assert info['version'] == version and info['architecture'] == report['architecture']
    assert info['signing'] == report['signing']
    if platform == 'Windows':
        assert info['sourceCommit'] == source and info['platform'] == 'win32'
    for entry in report['artifacts']:
        name = entry['file']
        assert Path(name).name == name and name.startswith(('ChatGPT-Studio-' + version, 'ChatGPT-Profiles-' + version))
        file = unique(folder, name)
        original, = [a for a in info['artifacts'] if a['file'] == name]
        sha = digest(file).hexdigest()
        assert sha == entry['sha256'] == original['sha256']
        if 'bytes' in entry:
            assert file.stat().st_size == entry['bytes']
        if 'sha512' in entry:
            assert base64.b64encode(digest(file, 'sha512').digest()).decode() == entry['sha512']
        shutil.copyfile(file, out / name)
        purpose = 'legacy-updater' if name.startswith('ChatGPT-Profiles-') else 'installer' if name.endswith(('.dmg', '.exe')) else 'portable'
        artifacts.append({**entry, 'bytes': file.stat().st_size,
                          'platform': 'macos' if platform == 'macOS' else 'windows', 'purpose': purpose,
                          'url': 'https://github.com/' + REPO + '/releases/download/v' + version + '/' + name})
assert len(artifacts) == 5
manifest = {'schema': 1, 'version': version, 'sourceSHA': source, 'sourceRunID': run['id'],
            'originalCIBytesVerified': True, 'artifacts': artifacts,
            'signing': {r['platform']: r['signing'] for r in platform_reports}}
(out / 'release-info.json').write_text(json.dumps(manifest, indent=2) + '\n')
(out / 'SHA256SUMS.txt').write_text(''.join(a['sha256'] + '  ' + a['file'] + '\n' for a in artifacts))
notes = '''ChatGPT Studio 2026.11.10 · Mac / Windows

- 更新下载显示真实百分比、已下载 / 总大小和明确阶段，检查及下载失败可重试。
- Windows 识别并修复标准 Programs\\ChatGPT Studio\\ChatGPT Profiles 嵌套目录，更新快捷方式、卸载和自动更新记录，保留现有用户数据。
- 补齐 Windows 安装包中的更新配置及完整回退文件清单，合成失败回退后可直接重试。

GitHub Actions 两端完整 npm run check 通过。Mac 108 项单元、4 项适用更新 E2E 通过；Windows 107 项单元通过、1 项平台跳过，2 项适用更新 E2E 通过。Windows 目录迁移另有前批 11 场景证据，代码沿用；本批验证为自有合成更新及安装流程，未操作个人账号或设备。

Mac 为 Apple Silicon 临时签名包，未公证。Windows 尚未配置 Authenticode，仍可能显示未知发布者。完整应用更新需要退出或重启。Android 当前正式版本继续为 2026.11.09-1236，应用内更新器仍待下一批实现。

候选来源与更新验收 https://github.com/ForceMind/ChatGPT/actions/runs/42749099128
目录迁移验收 https://github.com/ForceMind/ChatGPT/actions/runs/42588467252
'''
if tab_strip:
    notes = f"""ChatGPT Studio {version} · Mac / Windows

- 标签随窗口缩小，放不下的收进「更多标签」。当前标签始终显示在标题栏。
- 从更多标签切换会复用已打开的页面，并沿用原有草稿保存流程。
- Mac 和 Windows 通过 GitHub Actions 窄窗、多标签、切换和草稿合成验收。测试不截图，不访问真实账号。

Mac 为 Apple Silicon 临时签名包，未公证。Windows 未配置 Authenticode。
验收记录 https://github.com/ForceMind/ChatGPT/actions/runs/{run['id']}
"""
if tab_strip and version == '2026.11.12':
    notes = f"""ChatGPT Studio {version} · Mac / Windows

- Mac 窄窗保留多标签横排，滚轮可横移。左、右下拉分别列出该侧被遮住的标签，切换会把目标标签滚入视野。
- Windows 窄窗显示当前标签和「更多标签」，列表显示全部标签。选中项有边框和对勾，列表顺序保持一致。
- 两端合成验收确认切换复用原网页并保留未发送草稿。Mac rc.2 的滚动、双侧下拉与草稿已由用户在本机验收。

Mac 为 Apple Silicon 临时签名包，未公证。Windows 尚未配置 Authenticode。Android 延续 2026.11.10-1309。
验收记录 https://github.com/ForceMind/ChatGPT/actions/runs/{run['id']}
"""
(out / 'notes.txt').write_text(notes)
tag = 'v' + version
releases = api('releases?per_page=100')
matches = [r for r in releases if r['tag_name'] == tag]
assert len(matches) <= 1
if matches:
    release, = matches
else:
    release = json.loads(subprocess.check_output(
        ['gh', 'api', '-X', 'POST', 'repos/' + REPO + '/releases', '--input', '-'],
        input=json.dumps({
            'tag_name': tag, 'target_commitish': source, 'name': 'ChatGPT Studio ' + version,
            'body': notes, 'draft': True, 'prerelease': False
        }).encode()
    ))
    assert release['draft']
    # The tag lookup used by the uploader may lag the creation response.
    for _ in range(12):
        visible = subprocess.run(
            ['gh', 'release', 'view', tag, '--repo', REPO, '--json', 'isDraft,targetCommitish'],
            capture_output=True
        )
        if visible.returncode == 0:
            current = json.loads(visible.stdout)
            assert current['isDraft'] and current['targetCommitish'] == source
            break
        time.sleep(2)
    else:
        raise RuntimeError('The new release draft is not yet visible to the uploader')
assert release['target_commitish'] == source and not release['prerelease']
files = [out / a['file'] for a in artifacts] + [out / 'release-info.json', out / 'SHA256SUMS.txt']
existing = {a['name']: a for a in release['assets']}
assert set(existing) <= {f.name for f in files}
for file in files:
    if file.name in existing:
        assert existing[file.name]['digest'] == 'sha256:' + digest(file).hexdigest()
    else:
        assert release['draft'], 'Never add unverified assets to an already public release'
        subprocess.run(['gh', 'release', 'upload', tag, str(file), '--repo', REPO], check=True)
release = api('releases/' + str(release['id']))
assert {a['name'] for a in release['assets']} == {f.name for f in files}
for file in files:
    asset, = [a for a in release['assets'] if a['name'] == file.name]
    assert asset['state'] == 'uploaded' and asset['size'] == file.stat().st_size
    assert asset['digest'] == 'sha256:' + digest(file).hexdigest()
if release['draft']:
    subprocess.run(['gh', 'api', '-X', 'PATCH', 'repos/' + REPO + '/releases/' + str(release['id']),
                    '-F', 'draft=false', '-f', 'make_latest=true'], stdout=subprocess.DEVNULL, check=True)
release = api('releases/' + str(release['id']))
assert not release['draft'] and not release['prerelease']
metadata = Path('promotion/desktop-update-metadata')
metadata.mkdir()
date = datetime.now(timezone.utc).isoformat()
compat, = [a for a in artifacts if a['purpose'] == 'legacy-updater']
feed = {'schema': 1, 'version': version, 'publishedAt': date,
        'notes': 'Mac 标签栏支持滚轮横移与两侧下拉，Windows 窄窗支持全部标签列表。' if version == '2026.11.12' else
                 '标签过多时收进「更多标签」，当前标签始终显示。' if tab_strip else
                 '2026.11.10：Mac / Windows 更新下载显示百分比、文件大小、阶段及失败重试；Windows 补齐更新配置并修复嵌套程序目录迁移。',
        'files': {'arm64': {'url': compat['url'], 'size': compat['bytes'], 'sha256': compat['sha256']}}}
(metadata / 'macos.json').write_text(json.dumps(feed, indent=2, ensure_ascii=False) + '\n')
subprocess.run(['node', 'desktop/scripts/windows-update-feed.mjs', str(unique('promotion/update-windows', 'windows-build-info.json')),
                str(metadata / 'alpha.yml'), str(out / 'notes.txt')], check=True)
(metadata / (version + '.json')).write_text(json.dumps(manifest, indent=2) + '\n')
(metadata / 'publication.json').write_text(json.dumps({'sourceSHA': source, 'sourceRunID': run['id'],
    'promotionRunID': int(os.environ['GITHUB_RUN_ID']), 'release': release, 'bytesRebuilt': False}, indent=2) + '\n')
print(json.dumps({'published': True, 'tag': tag, 'assets': len(files), 'originalCIBytesVerified': True}))
