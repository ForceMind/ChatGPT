import { spawn } from 'node:child_process';
import { createServer } from 'node:http';
import { createHash } from 'node:crypto';
import { existsSync, mkdirSync, mkdtempSync, readFileSync, realpathSync, rmSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import os from 'node:os';

// Only run in the authorized macOS Actions job. Performance launches have no
// Playwright, remote debugging, disable-backgrounding or timer-throttling flags.
if (process.env.GITHUB_ACTIONS !== 'true' || process.platform !== 'darwin' || process.arch !== 'arm64')
  throw Error('CPU validation requires the disposable macOS arm64 Actions runner');
const desktop = fileURLToPath(new URL('..', import.meta.url));
const args = process.argv.slice(2);
const option = (name, fallback) => {
  const i = args.indexOf(name);
  return i < 0 ? fallback : args[i + 1];
};
const label = option('--label', 'candidate');
if (!['baseline', 'candidate'].includes(label)) throw Error('Expected baseline or candidate label');
const out = path.resolve(option('--output', `test-results/cpu/${label}`));
if (existsSync(out)) throw Error('Refusing to overwrite existing CPU evidence');
mkdirSync(out, { recursive: true });
const root = realpathSync(mkdtempSync(path.join(os.tmpdir(), 'studio-cpu-')));
const sourceSha = option('--source-sha', '');
if (!/^[a-f0-9]{40}$/.test(sourceSha)) throw Error('A full source SHA is required');

function fixturePage() {
  const state = (window.fixture = {
    instance: window.crypto.randomUUID(),
    beats: 0,
    events: 0,
    connections: 0,
    connection: '',
    sent: 0,
    tokens: 0,
    ready: false
  });
  let stream;
  const messages = (count) => {
    const main = document.querySelector('main');
    main.replaceChildren();
    for (let i = 0; i < count; i++) {
      const article = document.createElement('article');
      article.dataset.messageId = 'local-' + i;
      article.dataset.messageAuthorRole = i % 2 ? 'assistant' : 'user';
      article.textContent = ('Synthetic local conversation message ' + i + ' — 固定本地测试文本。 ').repeat(16);
      main.append(article);
    }
  };
  messages(32);
  document.querySelector('textarea').value += ' ' + state.instance;
  setInterval(() => state.beats++, 1000);
  new window.EventSource('/events').onmessage = (event) => {
    const value = JSON.parse(event.data);
    if (state.connection !== value.connection) state.connections++;
    state.connection = value.connection;
    state.events++;
  };
  document.querySelector('form').addEventListener('submit', (event) => {
    event.preventDefault();
    state.sent++;
  });
  // Exactly 1040 deterministic DOM updates per tab, over 130 seconds. A delayed
  // renderer must finish this work; it cannot pass by skipping missing tokens.
  window.fixtureStartStream = () => {
    messages(240);
    const main = document.querySelector('main');
    main.scrollTop = main.scrollHeight;
    state.tokens = 0;
    stream = setInterval(() => {
      document.querySelector('main').lastElementChild.append(' token-' + state.tokens);
      main.scrollTop = main.scrollHeight;
      if (++state.tokens === 1040) clearInterval(stream);
    }, 125);
  };
  state.ready = true;
}
let connection = 0;
const server = createServer((request, response) => {
  if (request.url === '/events') {
    const id = ++connection;
    response.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-store' });
    const tick = () => response.write('data: ' + JSON.stringify({ connection: id }) + '\n\n');
    tick();
    const timer = setInterval(tick, 500);
    request.on('close', () => clearInterval(timer));
    return;
  }
  response.writeHead(200, { 'Content-Type': 'text/html;charset=utf-8', 'Cache-Control': 'no-store' });
  response.end(
    '<!doctype html><meta charset="utf-8"><title>CPU synthetic fixture</title>' +
      '<style>body{margin:0}textarea{width:90%;height:64px}main{height:600px;overflow:auto}article{padding:12px}</style>' +
      '<form><textarea id="prompt-textarea">Synthetic unsent draft — 不发送</textarea><button>Send fixture only</button></form>' +
      '<main></main><script>(' +
      fixturePage.toString() +
      ')()</script>'
  );
});
await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
const config = {
  label,
  out,
  sourceSha,
  appRoot: path.resolve(option('--app-root', desktop)),
  dataDir: path.join(root, 'data'),
  origin: 'http://127.0.0.1:' + server.address().port,
  windowMs: 120000,
  backgroundMs: label === 'candidate' ? 1800000 : 0,
  scriptSha256: createHash('sha256')
    .update(readFileSync(fileURLToPath(import.meta.url)))
    .digest('hex'),
  host: {
    runner: process.env.RUNNER_NAME,
    image: process.env.ImageOS,
    imageVersion: process.env.ImageVersion,
    platform: os.platform(),
    arch: os.arch(),
    processor: os.cpus()[0]?.model,
    logicalCores: os.cpus().length,
    kernel: os.release()
  }
};

function startupHook(config) {
  const { app, BrowserWindow } = require('electron');
  const fs = require('node:fs');
  const path = require('node:path');
  const { syncBuiltinESMExports } = require('node:module');
  const originalWrite = fs.writeFileSync.bind(fs);
  const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
  let enabled = true;
  const counts = {
    pageState: 0,
    readConversation: 0,
    readRecoveryChanges: 0,
    capturePage: 0,
    recoveryWrite: 0,
    recoveryFsync: 0,
    recoveryBytes: 0,
    hookCalls: 0,
    hookNanoseconds: 0
  };
  const classify = (code) => {
    if (typeof code !== 'string') return;
    for (const name of ['pageState', 'readConversation', 'readRecoveryChanges']) {
      if (new RegExp('\\bfunction\\s+' + name + '\\s*\\(').test(code)) counts[name]++;
    }
  };
  // Only the time spent observing is charged here, never business method time
  // or Promise latency. Also provide a counter-disabled control phase below.
  const observe = (fn) => {
    if (!enabled) return;
    const start = process.hrtime.bigint();
    try {
      fn();
    } finally {
      counts.hookCalls++;
      counts.hookNanoseconds += Number(process.hrtime.bigint() - start);
    }
  };
  const recoveryRoot = path.join(config.dataDir, 'recovery') + path.sep;
  const descriptors = new Set();
  const isRecovery = (file) =>
    typeof file === 'string' &&
    path.resolve(file).startsWith(recoveryRoot) &&
    /\.bin(?:\.[a-f0-9-]+\.tmp)?$/.test(file);
  const open = fs.openSync;
  fs.openSync = function (...args) {
    const fd = Reflect.apply(open, this, args);
    observe(() => {
      if (isRecovery(args[0])) descriptors.add(fd);
    });
    return fd;
  };
  const close = fs.closeSync;
  fs.closeSync = function (...args) {
    try {
      return Reflect.apply(close, this, args);
    } finally {
      descriptors.delete(args[0]);
    }
  };
  for (const method of ['writeFileSync', 'fsyncSync']) {
    const original = fs[method];
    fs[method] = function (...args) {
      observe(() => {
        if (!descriptors.has(args[0]) && !isRecovery(args[0])) return;
        counts[method === 'writeFileSync' ? 'recoveryWrite' : 'recoveryFsync']++;
        if (method === 'writeFileSync')
          counts.recoveryBytes += Buffer.isBuffer(args[1]) ? args[1].length : Buffer.byteLength(String(args[1]));
      });
      return Reflect.apply(original, this, args);
    };
  }
  syncBuiltinESMExports();
  const guardedSessions = new Set();
  app.on('web-contents-created', (_event, contents) => {
    // Scope the entire performance launch to localhost and bundled resources.
    if (!guardedSessions.has(contents.session)) {
      guardedSessions.add(contents.session);
      contents.session.webRequest.onBeforeRequest((details, callback) => {
        const local = details.url.startsWith(config.origin + '/') || details.url === config.origin;
        callback({ cancel: !local && !/^(app|file|data|blob|devtools):/.test(details.url) });
      });
    }
    for (const method of ['executeJavaScript', 'executeJavaScriptInIsolatedWorld', 'capturePage']) {
      const original = contents[method];
      contents[method] = function (...args) {
        observe(() => {
          if (method === 'capturePage') counts.capturePage++;
          else if (method === 'executeJavaScript') classify(args[0]);
          else for (const script of args[1] || []) classify(script.code);
        });
        return Reflect.apply(original, this, args);
      };
    }
  });
  const report = {
    schema: 1,
    sourceSha: config.sourceSha,
    scriptSha256: config.scriptSha256,
    label: config.label,
    host: config.host,
    versions: process.versions,
    nativeLaunch: true,
    isolatedTestData: false,
    argv: process.argv,
    fixture: {
      origin: config.origin,
      idleMessages: 32,
      streamingMessages: 240,
      tokensPerTab: 1040,
      tokenPeriodMs: 125,
      readingMode:
        'Streaming follows the tail after every token; this is not a manual reading-position performance test.',
      warmupMs: 5000,
      warmupPolicy:
        'Identical fixed startup warmup on both versions, recorded separately; never expand it adaptively to hide repeated work.'
    },
    counterScope:
      'Calls/attempts, not completions. Recovery writes/fsync only isolated data/recovery/*.bin and *.bin.UUID.tmp; exclude state/keychain and report files.',
    cpuDefinition:
      'Electron app.getAppMetrics percentCPUUsage, summed across processes without normalization; raw per-process values retained.',
    phases: [],
    passed: false
  };
  let activePhase;
  const persist = () => {
    if (activePhase) {
      activePhase.operations = Object.fromEntries(
        Object.keys(counts).map((key) => [key, counts[key] - activePhase.counterBefore[key]])
      );
      originalWrite(path.join(config.out, activePhase.name + '.json'), JSON.stringify(activePhase, null, 2));
      report.incompletePhase = activePhase.name;
    }
    originalWrite(path.join(config.out, 'report.json'), JSON.stringify(report, null, 2));
  };
  persist();
  const diff = (before) => Object.fromEntries(Object.keys(counts).map((key) => [key, counts[key] - before[key]]));
  const stats = (values) => {
    const sorted = [...values].sort((a, b) => a - b);
    return {
      mean: values.reduce((sum, value) => sum + value, 0) / values.length,
      p50: sorted[Math.floor((sorted.length - 1) * 0.5)],
      p95: sorted[Math.floor((sorted.length - 1) * 0.95)],
      max: sorted.at(-1)
    };
  };
  app.whenReady().then(async () => {
    let win;
    try {
      for (let i = 0; i < 600; i++) {
        win = BrowserWindow.getAllWindows().find((item) => item.webContents.getURL() === 'app://shell/index.html');
        if (win) {
          try {
            if (await win.webContents.executeJavaScript('!!window.profiles&&document.readyState!=="loading"')) break;
          } catch {}
        }
        await sleep(100);
      }
      if (!win) throw Error('Shell failed to initialize');
      const run = (code) => win.webContents.executeJavaScript(code);
      const initial = await run('window.profiles.snapshot()');
      if (!initial.testMode || fs.realpathSync(app.getPath('userData')) !== fs.realpathSync(config.dataDir))
        throw Error('Expected fresh isolated test data');
      report.isolatedTestData = true;
      const views = () =>
        win.contentView.children.filter((view) => view.webContents.getURL().startsWith(config.origin));
      const read = () =>
        Promise.all(
          views().map(async (view) => ({
            id: view.webContents.id,
            pid: view.webContents.getOSProcessId(),
            throttled: view.webContents.getBackgroundThrottling(),
            state: await view.webContents.executeJavaScript(
              '({...window.fixture,draft:document.querySelector("textarea").value,discarded:document.wasDiscarded,visibility:document.visibilityState,tailGap:document.querySelector("main").scrollHeight-document.querySelector("main").clientHeight-document.querySelector("main").scrollTop})'
            )
          }))
        );
      const verify = (pages, expected) => {
        if (
          pages.length !== expected ||
          pages.some((page) => !page.state.ready || page.throttled || page.state.discarded || page.state.sent !== 0)
        )
          throw Error('Native fixture lost a tab, was throttled/discarded, or sent unexpectedly');
      };
      const ready = async (expected) => {
        for (let i = 0; i < 200; i++) {
          try {
            const pages = await read();
            if (pages.length === expected && pages.every((page) => page.state.ready && page.state.connection)) {
              verify(pages, expected);
              return;
            }
          } catch {}
          await sleep(100);
        }
        throw Error('Local fixture did not initialize all tabs');
      };
      const phase = async (name, durationMs, expected, onLate) => {
        const pagesBefore = await read();
        verify(pagesBefore, expected);
        app.getAppMetrics();
        const before = { ...counts };
        const start = performance.now();
        const samples = [];
        const delay = [];
        activePhase = { name, expected, durationMs, before: pagesBefore, counterBefore: before, samples, delay };
        let observerNanoseconds = 0;
        let nextTick = performance.now() + 100;
        const loop = setInterval(() => {
          const begin = process.hrtime.bigint();
          const now = performance.now();
          delay.push({ elapsedMs: now - start, delayMs: Math.max(0, now - nextTick) });
          nextTick = now + 100;
          observerNanoseconds += Number(process.hrtime.bigint() - begin);
        }, 100);
        let late;
        try {
          while (performance.now() - start < durationMs) {
            await sleep(1000);
            const begin = process.hrtime.bigint();
            samples.push({
              elapsedMs: performance.now() - start,
              processes: app.getAppMetrics().map((metric) => ({
                pid: metric.pid,
                type: metric.type,
                name: metric.name,
                creationTime: metric.creationTime,
                percentCPUUsage: metric.cpu.percentCPUUsage,
                cumulativeCPUUsage: metric.cpu.cumulativeCPUUsage,
                idleWakeupsPerSecond: metric.cpu.idleWakeupsPerSecond,
                workingSetSizeKB: metric.memory.workingSetSize
              }))
            });
            observerNanoseconds += Number(process.hrtime.bigint() - begin);
            if (onLate && !late && performance.now() - start >= durationMs - 30000) late = await read();
            if (samples.length % 60 === 0)
              console.log(JSON.stringify({ phase: name, elapsedMs: performance.now() - start }));
          }
        } finally {
          clearInterval(loop);
        }
        const elapsedMs = performance.now() - start;
        const operations = diff(before);
        const pagesAfter = await read();
        verify(pagesAfter, expected);
        const processes = new Map();
        for (const sample of samples)
          for (const metric of sample.processes) {
            if (!processes.has(metric.pid))
              processes.set(metric.pid, { pid: metric.pid, type: metric.type, values: [] });
            processes.get(metric.pid).values.push(metric.percentCPUUsage);
          }
        const rawFile = name + '.json';
        const result = {
          name,
          elapsedMs,
          tabs: expected,
          countsEnabled: enabled,
          operations,
          perProcessCPU: [...processes.values()].map(({ values, ...identity }) => ({
            ...identity,
            samples: values.length,
            percentCPUUsage: stats(values)
          })),
          cpuPercent: stats(
            samples.map((sample) => sample.processes.reduce((sum, item) => sum + item.percentCPUUsage, 0))
          ),
          mainProcessDelayMs: stats(delay.map((sample) => sample.delayMs)),
          observer: {
            samplingNanoseconds: observerNanoseconds,
            hookNanoseconds: operations.hookNanoseconds,
            selfTimePercentOfWall: ((observerNanoseconds + operations.hookNanoseconds) / (elapsedMs * 1000000)) * 100,
            note: 'Direct observer self-time is a lower bound; excludes renderer-side effects. Counter-disabled control retains CPU/delay sampling.'
          },
          rawFile,
          before: pagesBefore,
          late,
          after: pagesAfter
        };
        originalWrite(path.join(config.out, rawFile), JSON.stringify({ ...result, samples, delay }, null, 2));
        activePhase = undefined;
        report.phases.push(result);
        persist();
        return result;
      };
      await ready(1);
      for (const count of [1, 4, 8]) {
        let state = await run('window.profiles.snapshot()');
        while (state.tabs.length < count) {
          await run('window.profiles.command({type:"tab:new"})');
          state = await run('window.profiles.snapshot()');
          await ready(state.tabs.length);
        }
        await phase('warmup-' + count, 5000, count);
        await phase('idle-' + count, config.windowMs, count);
      }
      if (config.label === 'candidate') {
        enabled = false;
        try {
          await phase('idle-8-counters-disabled', config.windowMs, 8);
        } finally {
          enabled = true;
        }
      }
      for (const view of views()) await view.webContents.executeJavaScript('window.fixtureStartStream()');
      const streaming = await phase('streaming-8', config.windowMs + 15000, 8);
      if (streaming.after.some((page) => page.state.tokens !== 1040 || Math.abs(page.state.tailGap) > 2))
        throw Error('Fixed streaming workload did not complete at the visible tail');
      if (config.backgroundMs) {
        // Boundary reads only. The single late read checks the last 30 seconds,
        // rather than inferring connection activity from a 30-minute total.
        win.hide();
        if (win.isVisible()) throw Error('Window did not hide');
        const background = await phase('hidden-8', config.backgroundMs, 8, true);
        if (win.isVisible()) throw Error('Window unexpectedly became visible while held');
        win.show();
        await sleep(2000);
        const restored = await read();
        verify(restored, 8);
        const stable = background.before.every((before) => {
          const late = background.late?.find((page) => page.id === before.id);
          const after = restored.find((page) => page.id === before.id);
          return (
            late &&
            after &&
            before.pid === after.pid &&
            before.state.instance === after.state.instance &&
            before.state.draft === after.state.draft &&
            before.state.connection === after.state.connection &&
            before.state.connections === after.state.connections &&
            after.state.beats - late.state.beats >= 20 &&
            after.state.events - late.state.events >= 35 &&
            after.state.sent === 0 &&
            after.state.tokens === 1040
          );
        });
        report.retention = {
          hiddenMs: background.elapsedMs,
          restored,
          stable,
          passed: stable && background.elapsedMs >= 1800000
        };
        if (!report.retention.passed)
          throw Error('Background restoration lost identity, draft, connection, counters or zero-send invariant');
      }
      report.totals = counts;
      report.passed = true;
    } catch (error) {
      report.error = { message: String(error), stack: error.stack };
      process.exitCode = 1;
      console.error(error);
    } finally {
      report.totals = counts;
      persist();
      app.exit(report.passed ? 0 : 1);
    }
  });
}

const hook = path.join(root, 'hook.cjs');
writeFileSync(hook, '(' + startupHook.toString() + ')(' + JSON.stringify(config) + ');');
const runtime = path.join(desktop, 'node_modules/electron/dist/Electron.app/Contents/MacOS/Electron');
let child;
let timer;
try {
  child = spawn(runtime, ['-r', hook, config.appRoot], {
    env: {
      ...process.env,
      PROFILES_E2E: '1',
      PROFILES_TEST_DATA: config.dataDir,
      PROFILES_FIXTURE_ORIGIN: config.origin
    },
    stdio: ['ignore', 'pipe', 'pipe']
  });
  const log = [];
  child.stdout.on('data', (bytes) => {
    log.push(bytes.toString());
    process.stdout.write(bytes);
  });
  child.stderr.on('data', (bytes) => log.push(bytes.toString()));
  timer = setTimeout(() => child.kill('SIGKILL'), config.backgroundMs + 900000);
  const code = await new Promise((resolve, reject) => {
    child.once('error', reject);
    child.once('exit', resolve);
  });
  writeFileSync(path.join(out, 'electron.log'), log.join(''));
  if (code !== 0 || !existsSync(path.join(out, 'report.json'))) throw Error('Native CPU measurement failed: ' + out);
  const report = JSON.parse(readFileSync(path.join(out, 'report.json'), 'utf8'));
  if (!report.passed) throw Error('CPU validation did not pass: ' + out);
} finally {
  clearTimeout(timer);
  if (child && child.exitCode === null && child.signalCode === null) child.kill('SIGKILL');
  server.closeAllConnections();
  await new Promise((resolve) => server.close(resolve));
  // Only this launch's freshly-created synthetic directory is removed.
  rmSync(root, { recursive: true, force: true, maxRetries: 5, retryDelay: 200 });
}
