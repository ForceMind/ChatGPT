import assert from 'node:assert/strict';
import { execFileSync, spawn } from 'node:child_process';
import { once } from 'node:events';
import { mkdirSync, readFileSync, realpathSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { extractFile } from '@electron/asar';
import { stageUpdate } from '../src/main/update-install.ts';

assert.equal(process.env.GITHUB_ACTIONS, 'true');
assert.equal(process.platform, 'darwin');
assert.equal(process.arch, 'arm64');
const root = realpathSync(path.join(process.env.RUNNER_TEMP, 'studio-public-upgrade'));
const previous = '2026.11.10';
const version = '2026.11.11';
const target = path.join(root, 'ChatGPT Studio.app');
execFileSync('/usr/bin/ditto', ['-x', '-k', path.join(root, `ChatGPT-Studio-${previous}-mac-arm64.zip`), root]);
const manifest = JSON.parse(readFileSync(`../docs/releases/${version}.json`, 'utf8'));
const [artifact] = manifest.artifacts.filter((row) => row.purpose === 'legacy-updater');
assert.equal(artifact.file, `ChatGPT-Profiles-${version}-mac-arm64.zip`);
const installer = path.join(root, 'published-update-installer');
writeFileSync(installer, extractFile(path.join(target, 'Contents/Resources/app.asar'), 'dist/main/update-installer'), {
  mode: 0o700,
  flag: 'wx'
});
const data = path.join(root, 'synthetic-user-data');
mkdirSync(data);
const marker = path.join(data, 'state.json');
const original = JSON.stringify({ ownedFixture: 'profile-and-draft' });
writeFileSync(marker, original, { flag: 'wx' });
const staged = await stageUpdate(
  path.join(root, artifact.file),
  { url: artifact.url, sha256: artifact.sha256, size: artifact.bytes },
  version,
  target,
  path.join(root, 'stage'),
  previous
);
const exited = spawn('/usr/bin/true');
const [code] = await once(exited, 'exit');
assert.equal(code, 0);
const result = path.join(root, 'install-result.json');
execFileSync(installer, [String(exited.pid), target, staged.candidate, result, version, 'no', previous], {
  timeout: 15000
});
const readPlist = (bundle, field) =>
  execFileSync('/usr/libexec/PlistBuddy', ['-c', 'Print :' + field, path.join(bundle, 'Contents/Info.plist')], {
    encoding: 'utf8'
  }).trim();
assert.equal(readPlist(target, 'CFBundleIdentifier'), 'com.forcemind.chatgpt.profiles');
assert.equal(readPlist(target, 'CFBundleShortVersionString'), version);
assert.equal(readPlist(staged.candidate, 'CFBundleShortVersionString'), previous);
execFileSync('/usr/bin/codesign', ['--verify', '--deep', '--strict', target]);
assert.equal(readFileSync(marker, 'utf8'), original);
mkdirSync('test-results', { recursive: true });
writeFileSync(
  'test-results/public-macos-upgrade.json',
  JSON.stringify(
    {
      complete: true,
      previous,
      version,
      installedName: path.basename(target),
      priorAppRetained: true,
      shippedHelperUsed: true,
      dataBytesUnchanged: true,
      realAccountsUsed: false,
      applicationLaunched: false,
      helperResult: JSON.parse(readFileSync(result, 'utf8'))
    },
    null,
    2
  ) + '\n'
);
