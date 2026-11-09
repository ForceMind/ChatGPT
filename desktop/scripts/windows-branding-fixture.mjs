import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { createHash, createPrivateKey, randomUUID, X509Certificate } from 'node:crypto';
import { createServer } from 'node:https';
import { mkdirSync, mkdtempSync, readFileSync, writeFileSync, existsSync, readdirSync, rmSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { _electron as electron } from '@playwright/test';

const readJSON = (file) => JSON.parse(readFileSync(file, 'utf8').replace(/^\uFEFF/, ''));
const sha = (file) => createHash('sha256').update(readFileSync(file)).digest('hex');
const control = path.resolve('scripts/windows-branding-control.ps1');
const ps = (operation, values = {}) => {
  const args = [
    '-NoLogo',
    '-NoProfile',
    '-NonInteractive',
    '-ExecutionPolicy',
    'Bypass',
    '-File',
    control,
    '-Operation',
    operation
  ];
  for (const [key, value] of Object.entries(values)) {
    if (value === true) args.push('-' + key);
    else if (value !== undefined) args.push('-' + key, String(value));
  }
  execFileSync('powershell.exe', args, { stdio: 'inherit', timeout: 240000 });
};
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
function dataHashes(root) {
  const result = {};
  function walk(dir) {
    for (const file of readdirSync(dir, { withFileTypes: true })) {
      const name = path.join(dir, file.name);
      if (file.isDirectory()) walk(name);
      else if (file.isFile()) result[path.relative(root, name)] = sha(name);
      else throw Error('Unexpected link in synthetic data');
    }
  }
  walk(root);
  return result;
}
export async function runWindowsBrandingMatrix() {
  assert.equal(process.env.GITHUB_ACTIONS, 'true');
  assert.equal(process.platform, 'win32');
  ps('Guard');
  const root = mkdtempSync(path.join(process.env.RUNNER_TEMP || os.tmpdir(), 'studio-branding-'));
  const evidence = path.resolve('test-results/windows-branding');
  mkdirSync(evidence, { recursive: true });
  const data = path.join(process.env.APPDATA, 'ChatGPT Profiles');
  const programs = path.join(process.env.LOCALAPPDATA, 'Programs');
  const legacy = path.join(programs, 'ChatGPT Profiles');
  const modern = path.join(programs, 'ChatGPT Studio');
  const nested = path.join(modern, 'ChatGPT Profiles');
  assert(!existsSync(data) && !existsSync(legacy) && !existsSync(modern), 'Refuse existing data or programs');
  const build = readJSON('release/windows/windows-build-info.json');
  const installer = path.resolve('release/windows', build.artifacts.find((a) => a.file.endsWith('-setup.exe')).file);
  const baseline = path.join(root, 'baseline.exe');
  const old = readJSON('../docs/releases/3.0.0-alpha.21.json').artifacts.find((a) =>
    a.file.endsWith('-windows-x64-setup.exe')
  );
  const response = await fetch(old.url);
  assert(response.ok);
  writeFileSync(baseline, Buffer.from(await response.arrayBuffer()));
  assert.equal(sha(baseline), old.sha256);
  const publicRelease = readJSON('../docs/releases/2026.11.9.json').artifacts.find((a) =>
    a.file.endsWith('-windows-x64-setup.exe')
  );
  const publicInstaller = path.join(root, 'public-baseline.exe');
  const publicResponse = await fetch(publicRelease.url);
  assert(publicResponse.ok);
  writeFileSync(publicInstaller, Buffer.from(await publicResponse.arrayBuffer()));
  assert.equal(sha(publicInstaller), publicRelease.sha256);
  const tlsConfig = { key: path.join(root, 'fixture-key.json'), cer: path.join(root, 'fixture.cer') };
  writeFileSync(path.join(root, 'tls.json'), JSON.stringify(tlsConfig));
  ps('Tls', { Config: path.join(root, 'tls.json'), Output: path.join(root, 'thumbprint') });
  const thumbprint = readFileSync(path.join(root, 'thumbprint'), 'utf8').trim();
  const cert = new X509Certificate(readFileSync(tlsConfig.cer));
  const spki = createHash('sha256')
    .update(cert.publicKey.export({ type: 'spki', format: 'der' }))
    .digest('base64');
  const nonce = randomUUID();
  const requests = [];
  const server = createServer(
    {
      key: createPrivateKey({ key: readJSON(tlsConfig.key), format: 'jwk' }).export({ type: 'pkcs8', format: 'pem' }),
      cert: cert.toString()
    },
    (request, result) => {
      requests.push({ host: request.headers.host, url: request.url });
      result.setHeader('Cache-Control', 'no-store');
      if (request.url?.startsWith('/updates/windows/')) {
        result.setHeader('Content-Type', 'application/json');
        result.end(readFileSync('../site/updates/windows/alpha.yml'));
      } else {
        result.setHeader('Content-Type', 'text/html; charset=utf-8');
        result.end(
          `<!doctype html><title>Owned local branding fixture</title><p id="owned">${nonce}</p><textarea id="prompt-textarea"></textarea>`
        );
      }
    }
  );
  await new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(443, '127.0.0.1', resolve);
  });
  let app;
  const cases = [];
  async function launch(executable, seed) {
    console.log('Owned app launch', JSON.stringify({ executable, seed, exists: existsSync(executable) }));
    app = await electron.launch({
      executablePath: executable,
      args: [
        '--user-data-dir=' + data,
        '--host-resolver-rules=MAP * 127.0.0.1',
        '--no-proxy-server',
        '--ignore-certificate-errors-spki-list=' + spki
      ],
      timeout: 60000
    });
    const identity = await app.evaluate(({ app }) => ({
      name: app.getName(),
      data: app.getPath('userData'),
      version: app.getVersion()
    }));
    assert.equal(identity.name, 'ChatGPT Profiles');
    assert.equal(identity.data.toLowerCase(), data.toLowerCase());
    let pageId;
    for (let i = 0; i < 80; i++) {
      pageId = await app.evaluate(async ({ webContents }, expected) => {
        for (const page of webContents.getAllWebContents()) {
          if (!page.getURL().startsWith('https://chatgpt.com/')) continue;
          try {
            if ((await page.executeJavaScript('document.getElementById("owned")?.textContent')) === expected)
              return page.id;
          } catch {
            /* A target can navigate while its owned fixture is being discovered. */
          }
        }
        return undefined;
      }, nonce);
      if (pageId !== undefined) break;
      await sleep(250);
    }
    assert(pageId !== undefined, 'Installed app must render only owned loopback fixture');
    const token = 'owned-upgrade-' + nonce;
    if (seed)
      await app.evaluate(
        async ({ webContents }, values) => {
          const page = webContents.fromId(values.id);
          await page.session.cookies.set({
            url: 'https://chatgpt.com/',
            name: 'studio_branding_owned',
            value: values.token,
            httpOnly: true,
            secure: true,
            expirationDate: Date.now() / 1000 + 86400
          });
          const stored = await page.session.cookies.get({
            url: 'https://chatgpt.com/',
            name: 'studio_branding_owned'
          });
          if (stored[0]?.session !== false || stored[0]?.value !== values.token)
            throw Error('Persistent cookie fixture was not stored');
          await page.session.cookies.flushStore();
          await page.executeJavaScript(
            'localStorage.setItem("studio_branding_owned",' + JSON.stringify(values.token) + ')'
          );
          await page.session.flushStorageData();
        },
        { id: pageId, token }
      );
    else {
      const proof = await app.evaluate(async ({ webContents }, id) => {
        const page = webContents.fromId(id);
        return {
          cookies: await page.session.cookies.get({ url: 'https://chatgpt.com/', name: 'studio_branding_owned' }),
          storage: await page.executeJavaScript('localStorage.getItem("studio_branding_owned")')
        };
      }, pageId);
      assert.equal(proof.cookies[0]?.value, token);
      assert.equal(proof.storage, token);
    }
    if (!seed && path.basename(executable) === 'ChatGPT Studio.exe') {
      const shell = await app.firstWindow();
      await shell.waitForFunction(async () => (await window.profiles.snapshot()).update?.installMode === 'automatic');
    }
    await app.close();
    app = undefined;
    return identity;
  }
  function install(file, destination, fail = false) {
    const output = path.join(root, 'install-result.json');
    const proof = path.join(root, 'migration-' + randomUUID() + '.json');
    ps('Install', {
      File: file,
      Directory: destination,
      Output: output,
      Evidence: proof,
      FailCommit: fail || undefined
    });
    if (existsSync(proof)) writeFileSync(path.join(evidence, path.basename(proof)), readFileSync(proof));
    const result = { ...readJSON(output), proof: existsSync(proof) ? readJSON(proof) : undefined };
    if (result.exitCode !== 0) console.log(JSON.stringify(result));
    return result;
  }
  function inspect(directory) {
    const output = path.join(root, 'inspection.json');
    ps('Inspect', { Directory: directory, Output: output });
    return readJSON(output);
  }
  function clearOwned(directory) {
    const before = existsSync(data) ? dataHashes(data) : undefined;
    ps('Uninstall', { Directory: directory });
    if (before) assert.deepEqual(dataHashes(data), before, 'Uninstall must retain every synthetic user-data byte');
    if (existsSync(directory)) rmSync(directory, { recursive: true });
    if (directory === nested && existsSync(modern)) {
      assert.deepEqual(readdirSync(modern), [], 'Nested rollback must remove only the empty new program directories');
      rmSync(modern);
    }
    if (existsSync(data)) rmSync(data, { recursive: true });
  }
  try {
    const fresh = install(installer);
    assert.equal(fresh.exitCode, 0);
    const inspected = inspect(modern);
    assert.equal((await launch(path.join(modern, 'ChatGPT Studio.exe'), true)).version, build.version);
    cases.push({ name: 'new-default', complete: true, directory: modern, ...inspected });
    clearOwned(modern);
    for (const custom of [false, true]) {
      const source = custom ? path.join(root, 'custom-location') : legacy;
      assert.equal(install(baseline, custom ? source : undefined).exitCode, 0);
      await launch(path.join(source, 'ChatGPT Profiles.exe'), true);
      const before = dataHashes(data);
      const pinned = path.join(
        process.env.APPDATA,
        'Microsoft/Internet Explorer/Quick Launch/User Pinned/TaskBar',
        'Studio-Owned-' + nonce + '.lnk'
      );
      ps('Pin', { File: pinned, Directory: source });
      const result = install(installer, custom ? source : undefined);
      assert.equal(result.exitCode, 0);
      const target = custom ? source : modern;
      assert.deepEqual(dataHashes(data), before, 'Installer must not mutate any synthetic user-data bytes');
      const inspectedUpgrade = inspect(target);
      ps('CheckPin', { File: pinned, Directory: target });
      if (!custom) assert(!existsSync(path.join(legacy, 'ChatGPT Profiles.exe')));
      await launch(path.join(target, 'ChatGPT Studio.exe'), false);
      cases.push({
        name: custom ? 'custom-kept' : 'legacy-default-migrated',
        complete: true,
        source,
        target,
        cookieAndStoragePreserved: true,
        dataBytesUnchanged: true,
        pinnedLinkTargetUpdated: true,
        ...inspectedUpgrade
      });
      rmSync(pinned);
      clearOwned(target);
    }
    for (const currentProgram of [false, true]) {
      const oldInstaller = currentProgram ? publicInstaller : baseline;
      const oldName = currentProgram ? 'ChatGPT Studio.exe' : 'ChatGPT Profiles.exe';
      assert.equal(install(oldInstaller, nested).exitCode, 0);
      await launch(path.join(nested, oldName), true);
      const before = dataHashes(data);
      const pinned = path.join(
        process.env.APPDATA,
        'Microsoft/Internet Explorer/Quick Launch/User Pinned/TaskBar',
        'Studio-Nested-' + nonce + '.lnk'
      );
      ps('Pin', { File: pinned, Directory: nested, Executable: oldName });
      const result = install(installer);
      assert.equal(result.exitCode, 0);
      assert.equal(path.dirname(result.proof.backup), programs, 'Nested rollback backup must be outside the target');
      assert.deepEqual(dataHashes(data), before);
      assert(!existsSync(nested), 'Legacy child must be removed after flattening');
      const inspectedNested = inspect(modern);
      ps('CheckPin', { File: pinned, Directory: modern });
      await launch(path.join(modern, 'ChatGPT Studio.exe'), false);
      cases.push({
        name: currentProgram ? 'nested-studio-flattened' : 'nested-profiles-flattened',
        complete: true,
        source: nested,
        target: modern,
        dataBytesUnchanged: true,
        cookieAndStoragePreserved: true,
        automaticUpdateRegistered: true,
        pinnedLinkTargetUpdated: true,
        ...inspectedNested
      });
      rmSync(pinned);
      clearOwned(modern);
    }
    assert.equal(install(publicInstaller, nested).exitCode, 0);
    await launch(path.join(nested, 'ChatGPT Studio.exe'), true);
    const nestedData = dataHashes(data);
    const nestedExeSHA = sha(path.join(nested, 'ChatGPT Studio.exe'));
    const nestedFailure = install(installer, undefined, true);
    assert.notEqual(nestedFailure.exitCode, 0);
    assert(nestedFailure.proof?.rolledBack && !nestedFailure.proof?.committed);
    assert.equal(sha(path.join(nested, 'ChatGPT Studio.exe')), nestedExeSHA);
    assert.deepEqual(dataHashes(data), nestedData);
    assert.deepEqual(readdirSync(modern), ['ChatGPT Profiles']);
    inspect(nested);
    await launch(path.join(nested, 'ChatGPT Studio.exe'), false);
    cases.push({ name: 'nested-post-uninstall-rollback', complete: true, dataBytesUnchanged: true });
    const retry = install(installer);
    assert.equal(retry.exitCode, 0, 'Nested rollback must allow a direct retry');
    inspect(modern);
    await launch(path.join(modern, 'ChatGPT Studio.exe'), false);
    cases.push({ name: 'nested-retry-after-rollback', complete: true });
    clearOwned(modern);
    assert.equal(install(publicInstaller, nested).exitCode, 0);
    const foreign = path.join(modern, 'user-owned-note.txt');
    writeFileSync(foreign, nonce);
    const rejectedNested = install(installer);
    assert.notEqual(rejectedNested.exitCode, 0);
    assert.equal(readFileSync(foreign, 'utf8'), nonce);
    assert(existsSync(path.join(nested, 'ChatGPT Studio.exe')));
    rmSync(foreign);
    clearOwned(nested);
    cases.push({ name: 'nested-foreign-parent-file-refused', complete: true });
    assert.equal(install(baseline).exitCode, 0);
    await launch(path.join(legacy, 'ChatGPT Profiles.exe'), true);
    const beforeRollback = dataHashes(data);
    const oldExeSHA = sha(path.join(legacy, 'ChatGPT Profiles.exe'));
    const failed = install(installer, undefined, true);
    assert.notEqual(failed.exitCode, 0);
    assert(failed.proof?.rolledBack && !failed.proof?.committed);
    assert.equal(sha(path.join(legacy, 'ChatGPT Profiles.exe')), oldExeSHA);
    assert.deepEqual(dataHashes(data), beforeRollback);
    await launch(path.join(legacy, 'ChatGPT Profiles.exe'), false);
    cases.push({
      name: 'forced-post-uninstall-failure-rollback',
      complete: true,
      oldExeRestored: true,
      cookiesAndStoragePreserved: true
    });
    clearOwned(legacy);
    assert.equal(install(baseline).exitCode, 0);
    const unknown = path.join(legacy, 'user-owned-note.txt');
    writeFileSync(unknown, nonce);
    const rejected = install(installer);
    assert.notEqual(rejected.exitCode, 0);
    assert.equal(readFileSync(unknown, 'utf8'), nonce);
    assert(existsSync(path.join(legacy, 'ChatGPT Profiles.exe')));
    rmSync(unknown);
    clearOwned(legacy);
    cases.push({ name: 'unknown-old-file-refused', complete: true });
    mkdirSync(modern, { recursive: true });
    const occupied = path.join(modern, 'owned-by-another-app.txt');
    writeFileSync(occupied, nonce);
    assert.notEqual(install(installer).exitCode, 0);
    assert.equal(readFileSync(occupied, 'utf8'), nonce);
    rmSync(modern, { recursive: true });
    cases.push({ name: 'occupied-new-target-refused', complete: true });
    const proof = {
      complete: true,
      sourceSHA: process.env.GITHUB_SHA,
      version: build.version,
      cases,
      requests,
      screenshots: false,
      realChatGPT: false,
      isolatedWindowsRunner: true,
      browser:
        'Packaged old/new Electron apps; all Chromium DNS mapped to owned loopback TLS/SPKI, no proxy; no real account or service',
      fixtureCertificateThumbprint: thumbprint,
      pinnedScope: 'Synthetic pinned .lnk target, not Explorer taskbar visual cache'
    };
    writeFileSync(path.join(evidence, 'verified.json'), JSON.stringify(proof, null, 2));
    return proof;
  } finally {
    await app?.close().catch(() => {});
    await new Promise((resolve) => server.close(resolve));
    rmSync(tlsConfig.key, { force: true });
    writeFileSync(path.join(evidence, 'partial-cases.json'), JSON.stringify(cases, null, 2));
  }
}
