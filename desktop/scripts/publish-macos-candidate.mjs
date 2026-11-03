import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { createReadStream, lstatSync, mkdirSync, readFileSync, readdirSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

// This one-release promotion never invokes a packager and never overwrites assets.
const locked = {
  repository: 'ForceMind/ChatGPT',
  version: '3.0.0-alpha.23',
  sourceCommit: '1db8aa817971ae161737a99dff8f4a5ee1b7ab9a',
  runID: 41493373060,
  artifactID: 13166043620,
  releaseID: 432247054
};
const root = fileURLToPath(new URL('../..', import.meta.url));
const promotion = path.join(root, 'promotion');
mkdirSync(promotion, { recursive: true });
const verificationFile = path.join(promotion, 'verification.json');
const verification = {
  schema: 1,
  version: locked.version,
  sourceCommit: locked.sourceCommit,
  publisherCommit: process.env.GITHUB_SHA,
  status: 'verifying',
  publishedAt: null,
  publicationRequested: false,
  assets: [],
  installationPackagesRebuilt: false
};
const save = () => writeFileSync(verificationFile, JSON.stringify(verification, null, 2) + '\n');
const token = process.env.GITHUB_TOKEN;
const redact = (value) => (token ? String(value).replaceAll(token, '[REDACTED]') : String(value));
const tag = 'v' + locked.version;
const apiRoot = 'https://api.github.com/repos/' + locked.repository;
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
let release;

async function api(method, endpoint, options = {}) {
  const url = endpoint.startsWith('https:') ? endpoint : apiRoot + endpoint;
  assert(['api.github.com', 'uploads.github.com'].includes(new URL(url).hostname));
  const attempts = method === 'GET' ? 3 : 1;
  for (let attempt = 0; attempt < attempts; attempt++) {
    try {
      const response = await fetch(url, {
        method,
        redirect: 'error',
        signal: AbortSignal.timeout(options.raw ? 300000 : 30000),
        headers: {
          Authorization: 'Bearer ' + token,
          Accept: 'application/vnd.github+json',
          'X-GitHub-Api-Version': '2022-11-28',
          ...(options.body ? { 'Content-Type': options.raw ? 'application/octet-stream' : 'application/json' } : {}),
          ...(options.raw ? { 'Content-Length': String(options.bytes) } : {})
        },
        ...(options.body ? { body: options.raw ? options.body : JSON.stringify(options.body) } : {}),
        ...(options.raw ? { duplex: 'half' } : {})
      });
      if (response.status === 404 && options.allow404) return undefined;
      if (!response.ok) {
        const detail = await response.json().catch(() => ({}));
        const error = Error(
          'GitHub REST ' +
            method +
            ' ' +
            new URL(url).pathname +
            ': HTTP ' +
            response.status +
            ' ' +
            (detail.message || '')
        );
        error.retryable = response.status >= 500;
        throw error;
      }
      return await response.json();
    } catch (error) {
      // Only GETs retry. An uncertain write is inspected on the next run, never replayed blindly.
      if (
        method !== 'GET' ||
        attempt === attempts - 1 ||
        error.retryable === false ||
        /HTTP [34]\d\d/.test(error.message)
      )
        throw error;
      await sleep((attempt + 1) * 1000);
    }
  }
  throw Error('Unreachable REST retry state');
}

const git = (...args) => execFileSync('git', args, { cwd: root, encoding: 'utf8' }).trim();
async function digest(file) {
  const hash = createHash('sha256');
  for await (const chunk of createReadStream(file)) hash.update(chunk);
  return hash.digest('hex');
}
function walk(directory, files = []) {
  for (const entry of readdirSync(directory, { withFileTypes: true })) {
    assert(!entry.isSymbolicLink(), 'Original artifact contains a symlink');
    const file = path.join(directory, entry.name);
    if (entry.isDirectory()) walk(file, files);
    else {
      assert(entry.isFile(), 'Unexpected original artifact entry');
      files.push(file);
    }
  }
  return files;
}
async function allAssets(id) {
  const assets = [];
  for (let page = 1; page <= 20; page++) {
    const batch = await api('GET', '/releases/' + id + '/assets?per_page=100&page=' + page);
    assert(Array.isArray(batch));
    assets.push(...batch);
    if (batch.length < 100) return assets;
  }
  throw Error('Release assets pagination exceeded its bound');
}
async function tagSource(allowMissing = false) {
  const ref = await api('GET', '/git/ref/tags/' + encodeURIComponent(tag), { allow404: allowMissing });
  if (!ref) return undefined;
  let object = ref.object;
  for (let depth = 0; object.type === 'tag' && depth < 5; depth++) {
    assert.match(object.sha, /^[a-f0-9]{40}$/);
    object = (await api('GET', '/git/tags/' + object.sha)).object;
  }
  assert.equal(object.type, 'commit', 'Tag must resolve to a commit');
  assert.equal(object.sha, locked.sourceCommit, 'Release tag points at different source');
  return object.sha;
}
function checkRelease(value) {
  assert.equal(value.tag_name, tag);
  assert.equal(value.target_commitish, locked.sourceCommit);
  assert.equal(value.prerelease, true);
  assert(Number.isSafeInteger(value.id) && value.id > 0);
}
function verifyAssets(assets, expected, complete) {
  assert.equal(new Set(assets.map((asset) => asset.name)).size, assets.length, 'Duplicate release asset name');
  for (const asset of assets) {
    const item = expected.find((candidate) => candidate.file === asset.name);
    assert(item, 'Unexpected pre-existing release asset: ' + asset.name);
    assert.equal(asset.state, 'uploaded', 'Incomplete asset cannot be overwritten or ignored');
    assert.equal(asset.size, item.bytes, 'Remote asset size differs: ' + asset.name);
    assert.equal(asset.digest, 'sha256:' + item.sha256, 'GitHub server digest differs: ' + asset.name);
  }
  if (complete) assert.equal(assets.length, expected.length, 'Release is missing allowlisted assets');
}

try {
  assert.equal(process.env.GITHUB_ACTIONS, 'true');
  assert.equal(process.platform, 'linux');
  assert.equal(process.env.GITHUB_EVENT_NAME, 'workflow_dispatch');
  assert.equal(process.env.PROMOTE_RELEASE, 'true');
  assert.equal(process.env.GITHUB_REPOSITORY, locked.repository);
  assert(token, 'Promotion token missing');
  assert.match(process.env.GITHUB_SHA || '', /^[a-f0-9]{40}$/);
  assert.equal(git('rev-parse', 'HEAD'), process.env.GITHUB_SHA);
  git('merge-base', '--is-ancestor', locked.sourceCommit, 'HEAD');
  const manifest = JSON.parse(readFileSync(path.join(root, 'docs/releases/' + locked.version + '.json'), 'utf8'));
  const pkg = JSON.parse(readFileSync(path.join(root, 'desktop/package.json'), 'utf8'));
  assert.equal(pkg.version, locked.version);
  assert.deepEqual(
    pkg.build.files,
    ['dist/**/*', 'package.json', '!**/._*', '!**/.DS_Store'],
    'Promotion helper must remain outside the App allowlist'
  );
  assert.equal(manifest.version, locked.version);
  assert.equal(manifest.sourceCommit, locked.sourceCommit);
  assert.equal(manifest.runs.macos, locked.runID);
  assert.equal(manifest.artifactID, locked.artifactID);
  assert.equal(manifest.artifactName, 'macos-local-candidate-' + locked.sourceCommit);
  assert.equal(manifest.architecture, 'arm64');
  assert.equal(manifest.bundleID, 'com.forcemind.chatgpt.profiles');
  const expectedPackages = [
    'ChatGPT-Profiles-' + locked.version + '-mac-arm64.zip',
    'ChatGPT-Studio-' + locked.version + '-mac-arm64.dmg',
    'ChatGPT-Studio-' + locked.version + '-mac-arm64.dmg.blockmap',
    'ChatGPT-Studio-' + locked.version + '-mac-arm64.zip',
    'ChatGPT-Studio-' + locked.version + '-mac-arm64.zip.blockmap'
  ];
  assert.equal(manifest.artifacts.length, 5);
  assert.deepEqual(manifest.artifacts.map((item) => item.file).sort(), [...expectedPackages].sort());
  const frozenInputs = [
    'desktop/src',
    'desktop/assets',
    'desktop/resources',
    'desktop/package.json',
    'desktop/package-lock.json',
    'desktop/tsconfig.json',
    'desktop/scripts',
    'desktop/.npmrc',
    'LICENSE',
    ':(exclude)desktop/scripts/publish-macos-candidate.mjs'
  ];
  const changed = git('diff', '--name-only', locked.sourceCommit, 'HEAD', '--', ...frozenInputs);
  assert.equal(changed, '', 'Validated packaging inputs changed: ' + changed);
  verification.frozenInputs = { paths: frozenInputs, changedFiles: [], helperExcludedFromApp: true };

  const run = await api('GET', '/actions/runs/' + locked.runID);
  assert.equal(run.id, locked.runID);
  assert.equal(run.status, 'completed');
  assert.equal(run.conclusion, 'success');
  assert.equal(run.head_sha, locked.sourceCommit);
  assert.equal(run.repository.full_name, locked.repository);
  assert.equal(run.path, '.github/workflows/desktop-ci.yml');
  const jobs = (await api('GET', '/actions/runs/' + locked.runID + '/jobs?per_page=100')).jobs;
  const job = jobs.find((item) => item.name === 'Validate and package local macOS arm64 candidate');
  assert.equal(job?.conclusion, 'success', 'Original candidate packaging job failed/missing');
  const requiredSteps = [
    'Complete candidate checks and build',
    'Complete isolated macOS E2E including real synthetic update progress',
    'Package existing build without publishing or repeating checks',
    'Verify candidate identity, ad-hoc signature and artifact SHA256 receipts'
  ];
  for (const name of requiredSteps)
    assert(
      job.steps.some((step) => step.name === name && step.conclusion === 'success'),
      'Unverified original step: ' + name
    );
  const artifact = await api('GET', '/actions/artifacts/' + locked.artifactID);
  assert.equal(artifact.id, locked.artifactID);
  assert.equal(artifact.expired, false);
  assert.equal(artifact.name, manifest.artifactName);
  assert.equal(artifact.workflow_run.id, locked.runID);
  assert.equal(artifact.workflow_run.head_sha, locked.sourceCommit);
  verification.ci = {
    candidate: {
      runID: run.id,
      headSHA: run.head_sha,
      conclusion: run.conclusion,
      url: run.html_url,
      jobID: job.id,
      steps: requiredSteps
    },
    promotion: {
      runID: process.env.GITHUB_RUN_ID,
      headSHA: process.env.GITHUB_SHA,
      npmRunCheck: 'passed before this script'
    },
    artifact: { id: artifact.id, name: artifact.name, runID: artifact.workflow_run.id, expired: artifact.expired }
  };

  const files = walk(path.join(promotion, 'original'));
  const find = (name) => {
    const matches = files.filter((file) => path.basename(file) === name);
    assert.equal(matches.length, 1, 'Missing/duplicate original file: ' + name);
    assert(lstatSync(matches[0]).isFile());
    return matches[0];
  };
  const receipt = JSON.parse(readFileSync(find('candidate-receipt.json'), 'utf8'));
  const build = JSON.parse(readFileSync(find('build-info.json'), 'utf8'));
  assert.equal(receipt.sourceSha, locked.sourceCommit);
  for (const info of [receipt, build]) {
    assert.equal(info.version, locked.version);
    assert.equal(info.architecture, 'arm64');
  }
  assert.equal(receipt.bundleID, 'com.forcemind.chatgpt.profiles');
  assert.equal(receipt.signing, 'ad-hoc');
  assert.equal(receipt.notarized, false);
  assert.equal(receipt.published, false);
  assert.equal(receipt.buildInfoSHA256, await digest(find('build-info.json')));
  assert(receipt.checks.every((check) => check.exitCode === 0));
  assert(
    receipt.checks.some(
      (check) =>
        check.command === '/usr/bin/codesign' &&
        ['--verify', '--deep', '--strict'].every((arg) => check.args.includes(arg))
    )
  );
  assert.match(readFileSync(find('candidate-codesign-verify.txt'), 'utf8'), /Signature=adhoc/);
  assert.equal(receipt.artifacts.length, 5);
  assert.equal(build.artifacts.length, 5);
  const expected = [];
  for (const item of manifest.artifacts) {
    assert.equal(item.platform, 'macos');
    assert(Number.isSafeInteger(item.bytes) && item.bytes > 0);
    assert.match(item.sha256, /^[a-f0-9]{64}$/);
    const file = find(item.file);
    assert.equal(lstatSync(file).size, item.bytes);
    assert.equal(await digest(file), item.sha256, 'Original candidate bytes differ: ' + item.file);
    assert(
      receipt.artifacts.some(
        (entry) => entry.file === item.file && entry.bytes === item.bytes && entry.sha256 === item.sha256
      )
    );
    assert(build.artifacts.some((entry) => entry.file === item.file && entry.sha256 === item.sha256));
    expected.push({ file: item.file, bytes: item.bytes, sha256: item.sha256, path: file });
  }
  for (const name of ['candidate-receipt.json', 'build-info.json', 'candidate-codesign-verify.txt']) {
    const file = find(name);
    expected.push({ file: name, bytes: lstatSync(file).size, sha256: await digest(file), path: file });
  }
  const originalSums = new Map();
  for (const line of readFileSync(find('SHA256SUMS'), 'utf8').trim().split('\n')) {
    const match = /^([a-f0-9]{64})  ([A-Za-z0-9._-]+)$/.exec(line);
    assert(match && !originalSums.has(match[2]), 'Invalid/duplicate original SHA256SUMS entry');
    originalSums.set(match[2], match[1]);
  }
  assert.equal(originalSums.size, expected.length);
  for (const item of expected)
    assert.equal(originalSums.get(item.file), item.sha256, 'Original checksum receipt differs');
  const sumFile = path.join(promotion, 'SHA256SUMS.txt');
  writeFileSync(
    sumFile,
    [...expected]
      .sort((a, b) => a.file.localeCompare(b.file))
      .map((item) => item.sha256 + '  ' + item.file)
      .join('\n') + '\n'
  );
  expected.push({
    file: 'SHA256SUMS.txt',
    bytes: lstatSync(sumFile).size,
    sha256: await digest(sumFile),
    path: sumFile
  });
  verification.localFiles = expected.map(({ path: _path, ...item }) => item);
  verification.status = 'original-artifacts-verified';
  save();

  release = await api('GET', '/releases/' + locked.releaseID, { allow404: true });
  if (!release) {
    const matches = [];
    for (let page = 1; page <= 20; page++) {
      const batch = await api('GET', '/releases?per_page=100&page=' + page);
      matches.push(...batch.filter((item) => item.tag_name === tag));
      if (batch.length < 100) break;
      assert(page < 20, 'Release search pagination exceeded its bound');
    }
    assert(matches.length <= 1, 'Multiple releases already use this tag');
    release = matches[0];
    if (!release)
      release = await api('POST', '/releases', {
        body: {
          tag_name: tag,
          target_commitish: locked.sourceCommit,
          name: 'ChatGPT Studio ' + locked.version,
          body: readFileSync(path.join(root, 'docs/releases/' + locked.version + '-notes.md'), 'utf8'),
          draft: true,
          prerelease: true,
          make_latest: 'false'
        }
      });
  }
  checkRelease(release);
  await tagSource(true);
  verification.releaseID = release.id;
  verification.releaseURL = release.html_url;
  let assets = await allAssets(release.id);
  verifyAssets(assets, expected, !release.draft);
  if (release.draft) {
    for (const item of expected) {
      release = await api('GET', '/releases/' + release.id);
      checkRelease(release);
      assert.equal(release.draft, true, 'Release was published while promotion was uploading');
      assets = await allAssets(release.id);
      verifyAssets(assets, expected, false);
      if (assets.some((asset) => asset.name === item.file)) continue;
      const stream = createReadStream(item.path);
      try {
        await api(
          'POST',
          'https://uploads.github.com/repos/' +
            locked.repository +
            '/releases/' +
            release.id +
            '/assets?name=' +
            encodeURIComponent(item.file),
          {
            raw: true,
            bytes: item.bytes,
            body: stream
          }
        );
      } finally {
        stream.destroy();
      }
    }
    assets = await allAssets(release.id);
    verifyAssets(assets, expected, true);
    await tagSource(true);
    verification.status = 'draft-assets-server-digest-verified';
    verification.assets = assets.map((asset) => ({
      id: asset.id,
      file: asset.name,
      bytes: asset.size,
      digest: asset.digest
    }));
    save();
    release = await api('GET', '/releases/' + release.id);
    checkRelease(release);
    assert.equal(release.draft, true);
    verifyAssets(release.assets, expected, true);
    verification.publicationRequested = true;
    save();
    release = await api('PATCH', '/releases/' + release.id, {
      body: { draft: false, prerelease: true, make_latest: 'false' }
    });
  }
  // Publication/read-back only. Never fabricate a date or roll back a public release.
  release = await api('GET', '/releases/' + release.id);
  checkRelease(release);
  assert.equal(release.draft, false);
  assert(Number.isFinite(Date.parse(release.published_at)), 'Actual publication timestamp missing');
  const source = await tagSource();
  assets = await allAssets(release.id);
  verifyAssets(assets, expected, true);
  verification.status = 'published-and-read-back-verified';
  verification.releaseID = release.id;
  verification.releaseURL = release.html_url;
  verification.publishedAt = release.published_at;
  verification.tag = { name: tag, sourceCommit: source };
  verification.assets = assets.map((asset) => ({
    id: asset.id,
    file: asset.name,
    bytes: asset.size,
    sha256: asset.digest.slice(7),
    url: asset.browser_download_url
  }));
  verification.verifiedAt = new Date().toISOString();
  save();
  console.log(JSON.stringify(verification, null, 2));
} catch (error) {
  verification.status = verification.publicationRequested
    ? 'publication-requested-readback-unverified'
    : 'failed-before-publication-or-existing-release-verification';
  verification.error = redact(error.message);
  if (release)
    verification.lastObservedRelease = {
      id: release.id,
      draft: release.draft,
      prerelease: release.prerelease,
      publishedAt: release.published_at || null
    };
  save();
  console.error(verification.error);
  process.exitCode = 1;
}
