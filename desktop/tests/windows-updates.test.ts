import test, { type TestContext } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, readFileSync, writeFileSync, existsSync, rmSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { createHash } from 'node:crypto';
import { createRequire } from 'node:module';
import http from 'node:http';
import { once } from 'node:events';
import { IdleUpdate } from '../src/main/idle-update';
import { WindowsUpdateManager, type WindowsUpdateBackend } from '../src/main/windows-update-manager';
import { windowsRelease } from '../src/shared/windows-updates';
import { registeredWindowsDirectory, windowsDifferentialEligible } from '../src/main/windows-update-platform';
const version = '3.0.0-alpha.22',
  exe = Buffer.from('owned installer fixture bytes'),
  zip = Buffer.from('owned ZIP fixture bytes');
const hash = (body: Buffer, algorithm = 'sha256', encoding: 'hex' | 'base64' = 'hex') =>
  createHash(algorithm).update(body).digest(encoding);
function info() {
  return {
    schema: 1,
    appId: 'com.forcemind.chatgpt.profiles',
    platform: 'win32',
    architecture: 'x64',
    version,
    releaseDate: '2026-10-27T04:00:00Z',
    releaseNotes: 'Windows fixture update',
    files: [
      {
        url: `https://github.com/ForceMind/ChatGPT/releases/download/v${version}/ChatGPT-Studio-${version}-windows-x64-setup.exe`,
        size: exe.length,
        sha256: hash(exe),
        sha512: hash(exe, 'sha512', 'base64')
      },
      {
        url: `https://github.com/ForceMind/ChatGPT/releases/download/v${version}/ChatGPT-Studio-${version}-windows-x64.zip`,
        size: zip.length,
        sha256: hash(zip),
        sha512: hash(zip, 'sha512', 'base64')
      }
    ]
  };
}
function setup(t: TestContext, mode: 'automatic' | 'manual' = 'automatic') {
  const root = mkdtempSync(path.join(os.tmpdir(), 'windows-update-')),
    data = path.join(root, 'data'),
    file = path.join(root, 'installer.exe');
  mkdirSync(data);
  const calls = {
    checks: 0,
    downloads: 0,
    fetches: 0,
    installs: [] as boolean[],
    cancelled: 0,
    verified: 0,
    accelerated: false
  };
  let value = info();
  let progress: (v: { percent: number; transferred?: number; total?: number }) => void = () => {},
    error: (e: Error) => void = () => {},
    checkGate: Promise<void> = Promise.resolve();
  const backend: WindowsUpdateBackend = {
    check: async () => {
      calls.checks++;
      await checkGate;
      return { info: structuredClone(value), available: true };
    },
    download: async (_artifact, accelerated) => {
      calls.downloads++;
      calls.accelerated = accelerated;
      writeFileSync(file, exe);
      progress({ percent: 100, transferred: exe.length, total: exe.length });
      return [file];
    },
    install: (restart) => {
      calls.installs.push(restart);
    },
    cancel: () => {
      calls.cancelled++;
    },
    progress: (listener) => {
      progress = listener;
      return () => {
        progress = () => {};
      };
    },
    error: (listener) => {
      error = listener;
      return () => {
        error = () => {};
      };
    }
  };
  const manager = new WindowsUpdateManager({
    dataDir: data,
    current: '3.0.0-alpha.21',
    arch: 'x64',
    mode,
    backend,
    fetch: async () => {
      calls.fetches++;
      return new Response(zip, { headers: { 'content-length': String(zip.length) } });
    },
    changed: () => {},
    verifyInstaller: async (_file, v) => {
      assert.equal(v, version);
      calls.verified++;
    },
    preflight: async () => {}
  });
  t.after(() => {
    manager.dispose();
    rmSync(root, { recursive: true, force: true });
  });
  return {
    root,
    data,
    file,
    calls,
    backend,
    manager,
    progress: (v: Parameters<typeof progress>[0]) => progress(v),
    error: (e: Error) => error(e),
    gate: (p: Promise<void>) => {
      checkGate = p;
    },
    change: (v: ReturnType<typeof info>) => {
      value = v;
    }
  };
}
test('Windows metadata pins app, architecture, repository, platform suffix, sizes and both digests', () => {
  assert.equal(windowsRelease(info()).version, version);
  for (const mutate of [
    (v: any) => (v.platform = 'darwin'),
    (v: any) => (v.appId = 'other.app'),
    (v: any) => (v.architecture = 'arm64'),
    (v: any) => (v.version = '3.0.0-alpha.022'),
    (v: any) => (v.files[0].url = v.files[0].url.replace('ForceMind', 'Other')),
    (v: any) => (v.files[0].url = 'https://gh-proxy.org/' + v.files[0].url),
    (v: any) => (v.files[0].sha512 = 'invalid'),
    (v: any) => (v.files[0].size = 0),
    (v: any) => (v.files[1] = v.files[0]),
    (v: any) => (v.packages = {})
  ]) {
    const value = info();
    mutate(value);
    assert.throws(() => windowsRelease(value), /清单/);
  }
});
test('Windows differential updates require an explicitly matching base on the original download path', () => {
  const base = '3.0.0-alpha.21';
  const plain = windowsRelease(info());
  assert.equal(windowsDifferentialEligible(plain.installer, base, false), false);
  const value = info();
  Object.assign(value.files[0], { differentialFrom: base });
  Object.assign(value.files[1], { differentialFrom: base });
  const selected = windowsRelease(value);
  assert.equal(windowsDifferentialEligible(selected.installer, base, false), true);
  assert.equal(windowsDifferentialEligible(selected.installer, '3.0.0-alpha.20', false), false);
  assert.equal(windowsDifferentialEligible(selected.installer, base, true), false);
  assert.equal(windowsDifferentialEligible(selected.installer, base, false, 'http://127.0.0.1:3333'), false);
  assert.equal(windowsDifferentialEligible(selected.zip, base, false), false);
});

test('the locked Windows differential downloader reads only changed bytes and rejects the wrong cached installer', async (t) => {
  const s = setup(t);
  const common = Buffer.alloc(32768, 0x41);
  const before = Buffer.alloc(32768, 0x42);
  const after = Buffer.alloc(32768, 0x43);
  const target = Buffer.concat([common, after]);
  writeFileSync(s.file, Buffer.concat([common, before]));
  const map = (second: Buffer) => ({
    version: '2',
    files: [
      {
        name: 'file',
        offset: 0,
        sizes: [common.length, second.length],
        checksums: [hash(common), hash(second)]
      }
    ]
  });
  const ranges: string[] = [];
  const server = http.createServer((req, res) => {
    const range = req.headers.range || '';
    ranges.push(range);
    if (range !== 'bytes=32768-65535') {
      res.writeHead(416).end();
      return;
    }
    res.writeHead(206, { 'content-range': 'bytes 32768-65535/65536', 'content-length': after.length }).end(after);
  });
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  t.after(() => server.close());
  const address = server.address();
  assert(address && typeof address === 'object');
  const { GenericDifferentialDownloader } = createRequire(import.meta.url)(
    'electron-updater/out/differentialDownloader/GenericDifferentialDownloader.js'
  );
  const executor = {
    createRequest: http.request,
    addErrorAndTimeoutHandlers: (request: http.ClientRequest, reject: (reason: Error) => void) => {
      request.on('error', reject);
      request.setTimeout(5000, () => request.destroy(Error('owned range timeout')));
    }
  };
  const rebuild = (file: string) =>
    new GenericDifferentialDownloader({ size: target.length, sha512: hash(target, 'sha512', 'base64') }, executor, {
      newUrl: new URL('http://127.0.0.1:' + address.port + '/owned.exe'),
      oldFile: s.file,
      newFile: file,
      isUseMultipleRangeRequest: false,
      logger: { info: () => {}, warn: () => {}, error: () => {}, debug: () => {} }
    }).download(map(before), map(after));
  const output = path.join(s.root, 'rebuilt.exe');
  await rebuild(output);
  assert.deepEqual(readFileSync(output), target);
  assert.deepEqual(ranges, ['bytes=32768-65535']);
  writeFileSync(s.file, Buffer.concat([Buffer.alloc(common.length, 0x11), before]));
  await assert.rejects(rebuild(path.join(s.root, 'wrong.exe')), /checksum|sha512/i);
});

test('registered installers must match this executable directory and never claim a broad user folder or network share', () => {
  const dir = 'C:\\Users\\测试\\Programs\\ChatGPT Studio',
    exe = dir + '\\ChatGPT Profiles.exe',
    record = { InstallLocation: dir, UninstallString: '"' + dir + '\\Uninstall ChatGPT Studio.exe"' };
  assert.equal(registeredWindowsDirectory(exe, [record], ['C:\\Users\\测试']), dir);
  const studioExe = dir + '\\ChatGPT Studio.exe';
  assert.equal(registeredWindowsDirectory(studioExe, [record], []), dir);
  assert.equal(registeredWindowsDirectory(studioExe, [record], [dir]), undefined);
  assert.equal(registeredWindowsDirectory(dir + '\\Other.exe', [record], []), undefined);
  assert.equal(registeredWindowsDirectory(exe, [{ ...record, InstallLocation: 'C:\\other' }], []), undefined);
  assert.equal(registeredWindowsDirectory(exe, [record], [dir]), undefined);
  assert.equal(
    registeredWindowsDirectory(
      '\\\\server\\share\\ChatGPT Profiles.exe',
      [{ ...record, InstallLocation: '\\\\server\\share' }],
      []
    ),
    undefined
  );
  const actual = {
    InstallLocation: null,
    UninstallString: '"' + dir + '\\Uninstall ChatGPT Profiles.exe" /currentuser'
  };
  assert.equal(registeredWindowsDirectory(exe, [actual], []), dir);
  assert.equal(registeredWindowsDirectory(exe, [{ UninstallString: actual.UninstallString }], []), dir);
  for (const bad of [
    actual.UninstallString + ' /allusers',
    actual.UninstallString + ' & calc.exe',
    actual.UninstallString.replace(dir, 'C:\\Other'),
    actual.UninstallString.replace('Uninstall ChatGPT Profiles.exe', 'Other.exe'),
    actual.UninstallString.replaceAll('"', '')
  ])
    assert.equal(registeredWindowsDirectory(exe, [{ InstallLocation: null, UninstallString: bad }], []), undefined);
  assert.equal(registeredWindowsDirectory(exe, [{ ...actual, InstallLocation: 'C:\\Other' }], []), undefined);
});
test('Windows checks coalesce, save the Shanghai noon attempt and never download without approval', async (t) => {
  const s = setup(t);
  let release!: () => void;
  s.gate(
    new Promise<void>((resolve) => {
      release = resolve;
    })
  );
  const one = s.manager.check(Date.parse('2026-10-27T04:00:00Z')),
    two = s.manager.check();
  assert.equal(one, two);
  release();
  await one;
  assert.equal(s.calls.checks, 1);
  assert.equal(s.calls.downloads, 0);
  assert.equal(s.manager.status.phase, 'available');
  assert.equal(JSON.parse(readFileSync(path.join(s.manager.directory, 'state.json'), 'utf8')).noonDay, '2026-10-27');
});
test('installed updates verify bytes and package version before a quit-triggered installer, with restart only by explicit choice', async (t) => {
  const s = setup(t);
  await s.manager.check();
  await s.manager.download(true);
  assert.equal(s.calls.accelerated, true);
  assert.equal(s.calls.verified, 1);
  assert.equal(s.manager.status.phase, 'ready');
  assert.deepEqual(s.calls.installs, []);
  s.manager.restartAfterInstall();
  await s.manager.installOnQuit();
  assert.deepEqual(s.calls.installs, [true]);
  assert.equal(s.calls.verified, 2);
});
test('Windows idle install is opt-in, rechecks activity after byte verification and keeps a deferred package', async (t) => {
  const s = setup(t);
  await s.manager.check();
  await s.manager.download();
  assert.equal(s.manager.status.idleEnabled, false);
  assert.equal(s.manager.idleChoiceMade(), false);
  s.manager.setIdleAllowed(true);
  s.manager.setIdleDeadline(100_000);
  assert.equal(s.manager.status.idleDeadline, 100_000);
  const saved = JSON.parse(readFileSync(path.join(s.manager.directory, 'state.json'), 'utf8'));
  assert.equal(saved.idleAllowed, true);
  assert.match(saved.approved, /^[a-f0-9]{64}$/);
  await assert.rejects(
    s.manager.installOnQuit(true, async () => {
      assert.equal(s.calls.verified, 2);
      return false;
    }),
    /空闲安装已延后/
  );
  assert.deepEqual(s.calls.installs, []);
  assert.equal(s.manager.status.phase, 'ready');
  assert.equal(s.manager.status.idleEnabled, true);
  await s.manager.installOnQuit(true, async () => true);
  assert.deepEqual(s.calls.installs, [false]);
});

test('the shared app-idle countdown commits a verified Windows update without reopening it', async (t) => {
  const s = setup(t);
  await s.manager.check();
  await s.manager.download();
  s.manager.setIdleAllowed(true);
  let now = 0;
  let focused = false;
  let safe = false;
  let notice: number | undefined;
  let installation: Promise<void> | undefined;
  const idle = new IdleUpdate({
    now: () => now,
    eligible: () => s.manager.status.phase === 'ready' && s.manager.status.idleEnabled === true,
    focused: () => focused,
    safe: async () => safe,
    countdown: (deadline) => {
      notice = deadline;
      s.manager.setIdleDeadline(deadline);
    },
    visible: () => notice !== undefined,
    install: (epoch) => {
      installation = s.manager.installOnQuit(false, () => idle.permit(epoch));
    }
  });
  now = 1_800_000;
  await idle.tick();
  assert.equal(notice, undefined);
  safe = true;
  await idle.tick();
  assert.equal(notice, 1_860_000);
  focused = true;
  idle.activity();
  assert.equal(s.manager.status.idleDeadline, undefined);
  now = 1_860_000;
  await idle.tick();
  assert.deepEqual(s.calls.installs, []);
  focused = false;
  now = 3_600_000;
  await idle.tick();
  assert.equal(notice, 3_660_000);
  now = 3_660_000;
  await idle.tick();
  assert(installation);
  await installation;
  assert.deepEqual(s.calls.installs, [false]);
});

test('tampered cached installers cannot execute and leave a recoverable update error', async (t) => {
  const s = setup(t);
  await s.manager.check();
  await s.manager.download();
  writeFileSync(s.file, 'damaged');
  await assert.rejects(s.manager.installOnQuit(), /校验/);
  assert.deepEqual(s.calls.installs, []);
  assert.equal(s.manager.status.phase, 'error');
  assert.equal(JSON.parse(readFileSync(path.join(s.manager.directory, 'state.json'), 'utf8')).approved, undefined);
});
test('OS session end defers installation even for a fully prepared update', async (t) => {
  const s = setup(t);
  await s.manager.check();
  await s.manager.download();
  s.manager.sessionEnd();
  await s.manager.installOnQuit();
  assert.deepEqual(s.calls.installs, []);
  assert.equal(s.manager.status.phase, 'ready');
});
test('portable downloads are verified and saved as a new file without running an installer or replacing the app folder', async (t) => {
  const s = setup(t, 'manual'),
    original = path.join(s.root, 'original.txt'),
    output = path.join(s.root, 'ChatGPT-Studio-update.zip');
  writeFileSync(original, 'keep');
  await s.manager.check();
  await s.manager.download();
  s.manager.setIdleAllowed(true);
  s.manager.setIdleDeadline(100_000);
  assert.equal(s.manager.status.idleEnabled, undefined);
  assert.equal(s.manager.status.idleDeadline, undefined);
  await s.manager.installOnQuit();
  assert.deepEqual(s.calls.installs, []);
  assert.equal(s.calls.fetches, 1);
  assert.equal(s.calls.downloads, 0);
  await s.manager.exportPackage(output);
  assert.deepEqual(readFileSync(output), zip);
  assert.equal(readFileSync(original, 'utf8'), 'keep');
  assert.equal(s.manager.status.phase, 'available');
  assert.equal(JSON.parse(readFileSync(path.join(s.manager.directory, 'state.json'), 'utf8')).zip, undefined);
  assert.equal(existsSync(s.file), false);
});
test('a downloaded approval is never reused for different bytes advertised under the same version', async (t) => {
  const s = setup(t);
  await s.manager.check();
  await s.manager.download();
  s.manager.dispose();
  const changed = info();
  changed.files[0].sha256 = 'a'.repeat(64);
  s.change(changed);
  const restored = new WindowsUpdateManager({
    dataDir: s.data,
    current: '3.0.0-alpha.21',
    arch: 'x64',
    mode: 'automatic',
    backend: s.backend,
    fetch: async () => {
      throw Error('unused');
    },
    changed: () => {},
    verifyInstaller: async () => {},
    preflight: async () => {}
  });
  t.after(() => restored.dispose());
  await restored.check();
  assert.equal(restored.status.phase, 'available');
  assert.equal(s.calls.downloads, 1);
  assert.deepEqual(s.calls.installs, []);
});
test('oversized transfer progress cancels the download and never reaches install readiness', async (t) => {
  const s = setup(t);
  s.backend.download = async () => {
    s.progress({ percent: 50, transferred: exe.length + 1, total: exe.length + 1 });
    writeFileSync(s.file, exe);
    return [s.file];
  };
  await s.manager.check();
  await assert.rejects(s.manager.download());
  assert.equal(s.manager.status.phase, 'error');
  assert(s.calls.cancelled > 0);
  assert.deepEqual(s.calls.installs, []);
});
test('Windows progress uses advertised bytes and rejects late regression while verification runs', async (t) => {
  const s = setup(t);
  await s.manager.check();
  s.backend.download = async () => {
    s.progress({ percent: 99, transferred: 5, total: exe.length });
    assert.equal(s.manager.status.progress, Math.floor(500 / exe.length));
    assert.equal(s.manager.status.transferredBytes, 5);
    assert.equal(s.manager.status.totalBytes, exe.length);
    s.progress({ percent: 100, transferred: exe.length, total: exe.length });
    assert.equal(s.manager.status.stage, 'verify');
    s.progress({ percent: 50, transferred: 5, total: exe.length });
    assert.equal(s.manager.status.stage, 'verify');
    assert.equal(s.manager.status.progress, 100);
    writeFileSync(s.file, exe);
    return [s.file];
  };
  await s.manager.download();
  assert.equal(s.manager.status.phase, 'ready');
});
test('check failures retain their stage and can be checked again without exposing backend details', async (t) => {
  const s = setup(t);
  const check = s.backend.check;
  s.backend.check = async () => {
    throw Error('https://secret.invalid/?token=private');
  };
  await s.manager.check();
  assert.equal(s.manager.status.stage, 'check');
  assert.equal(s.manager.status.phase, 'error');
  assert(!s.manager.status.message?.includes('private'));
  s.backend.check = check;
  await s.manager.check();
  assert.equal(s.manager.status.phase, 'available');
});
