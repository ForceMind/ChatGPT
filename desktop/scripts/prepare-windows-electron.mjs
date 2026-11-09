import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { closeSync, existsSync, mkdirSync, openSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import path from 'node:path';

assert.equal(process.env.GITHUB_ACTIONS, 'true');
assert.equal(process.env.GITHUB_REPOSITORY, 'ForceMind/ChatGPT');
assert.equal(process.platform, 'win32');
assert.equal(process.arch, 'x64');
const read = (file) => JSON.parse(readFileSync(file, 'utf8'));
const pkg = read('package.json');
const lock = read('package-lock.json');
const electron = path.resolve('node_modules/electron');
const version = read(path.join(electron, 'package.json')).version;
assert.equal(version, pkg.devDependencies.electron);
assert.equal(version, lock.packages['node_modules/electron'].version);
assert.match(version, /^\d+\.\d+\.\d+$/);
const name = `electron-v${version}-win32-x64.zip`;
const expected = read(path.join(electron, 'checksums.json'))[name];
assert.match(expected, /^[a-f0-9]{64}$/);
const release = JSON.parse(
  execFileSync('gh', ['api', `repos/electron/electron/releases/tags/v${version}`], { encoding: 'utf8' })
);
assert.equal(release.tag_name, 'v' + version);
assert.equal(release.draft, false);
const assets = release.assets.filter((asset) => asset.name === name && asset.state === 'uploaded');
assert.equal(assets.length, 1);
const asset = assets[0];
if (asset.digest) assert.equal(asset.digest, 'sha256:' + expected);
const folder = path.join(process.env.RUNNER_TEMP, 'studio-electron-' + process.env.GITHUB_RUN_ID);
mkdirSync(folder, { recursive: false });
const archive = path.join(folder, name);
const output = openSync(archive, 'wx');
try {
  execFileSync(
    'gh',
    ['api', `repos/electron/electron/releases/assets/${asset.id}`, '-H', 'Accept: application/octet-stream'],
    {
      stdio: ['ignore', output, 'inherit'],
      timeout: 180000
    }
  );
} finally {
  closeSync(output);
}
const bytes = readFileSync(archive);
assert.equal(bytes.length, asset.size);
assert.equal(createHash('sha256').update(bytes).digest('hex'), expected);
const destination = path.join(electron, 'dist');
assert(!existsSync(destination), 'Only initialize an uninstalled CI dependency');
execFileSync(
  'powershell.exe',
  [
    '-NoLogo',
    '-NoProfile',
    '-NonInteractive',
    '-Command',
    '$ErrorActionPreference="Stop"; Expand-Archive -LiteralPath $env:STUDIO_ELECTRON_ARCHIVE -DestinationPath $env:STUDIO_ELECTRON_DESTINATION'
  ],
  {
    env: { ...process.env, STUDIO_ELECTRON_ARCHIVE: archive, STUDIO_ELECTRON_DESTINATION: destination },
    stdio: 'inherit'
  }
);
assert.equal(readFileSync(path.join(destination, 'version'), 'utf8').trim().replace(/^v/, ''), version);
writeFileSync(path.join(electron, 'path.txt'), 'electron.exe', { flag: 'wx' });
assert.equal(createRequire(import.meta.url)('electron'), path.join(destination, 'electron.exe'));
mkdirSync('release/windows', { recursive: true });
writeFileSync(
  'release/windows/dependency-download.json',
  JSON.stringify(
    {
      version,
      name,
      assetID: asset.id,
      bytes: bytes.length,
      sha256: expected,
      source: 'Official Electron Release API; locked npm package checksum',
      sourceSHA: process.env.GITHUB_SHA
    },
    null,
    2
  ) + '\n'
);
rmSync(folder, { recursive: true });
console.log(`Verified official Electron ${version} archive and installed CI binary`);
