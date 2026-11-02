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
const profile = option('--profile', 'full');
const instrumentation = option('--instrumentation', 'on');
if (!['full', 'stream-only'].includes(profile) || !['on', 'off'].includes(instrumentation))
  throw Error('Expected --profile full|stream-only and --instrumentation on|off');
if ((profile === 'full') !== (instrumentation === 'on'))
  throw Error('Full regression requires instrumentation on; stream-only control requires off');
const out = path.resolve(option('--output', `test-results/cpu/${profile}-${label}`));
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
    connectionOpen: false,
    connectionCloses: 0,
    connectionErrors: 0,
    connectionIdentityChanges: 0,
    sent: 0,
    tokens: 0,
    streamStarted: null,
    streamFinished: null,
    streamDurationMs: null,
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
  const socket = new window.WebSocket('ws://' + window.location.host + '/events');
  socket.onopen = () => {
    state.connections++;
    state.connectionOpen = true;
  };
  socket.onclose = () => {
    state.connectionCloses++;
    state.connectionOpen = false;
  };
  socket.onerror = () => state.connectionErrors++;
  // Deliberately no send() or reconnect loop: every page keeps one independent
  // synthetic server-push connection for the entire measurement launch.
  socket.onmessage = (event) => {
    const value = JSON.parse(event.data);
    if (state.connection && state.connection !== value.connection) state.connectionIdentityChanges++;
    state.connection = value.connection;
    state.events++;
  };
  document.querySelector('form').addEventListener('submit', (event) => {
    event.preventDefault();
    state.sent++;
  });
  // Exactly 1040 deterministic DOM updates per tab, over 130 seconds. A delayed
  // renderer catches up from the monotonic clock, performing every real append.
  // A callback does at most 16 appends; slow work still fails the fixed window.
  window.fixturePrepareStream = () => {
    messages(240);
    const main = document.querySelector('main');
    const marker = document.createElement('span');
    marker.textContent = ' Synthetic visible snapshot-phase preparation';
    main.lastElementChild.append(marker);
    main.scrollTop = main.scrollHeight;
  };
  window.fixtureStartStream = () => {
    const main = document.querySelector('main');
    state.tokens = 0;
    state.streamStarted = window.performance.now();
    state.streamFinished = null;
    state.streamDurationMs = null;
    stream = setInterval(() => {
      const due = Math.min(1040, Math.floor((window.performance.now() - state.streamStarted) / 125));
      const end = Math.min(due, state.tokens + 16);
      while (state.tokens < end) {
        main.lastElementChild.append(' token-' + state.tokens);
        main.scrollTop = main.scrollHeight;
        state.tokens++;
      }
      if (state.tokens === 1040) {
        state.streamFinished = window.performance.now();
        state.streamDurationMs = state.streamFinished - state.streamStarted;
        clearInterval(stream);
      }
    }, 125);
  };
  state.ready = true;
}
let connection = 0;
const upgradedSockets = new Set();
const connectionAudit = [];
const server = createServer((request, response) => {
  if (request.url === '/events') {
    response.writeHead(426, { Upgrade: 'websocket' });
    response.end();
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
// RFC 6455 sections 4.2.2 and 5: minimal server for this owned fixture only.
// Outgoing text/control payloads are bounded to 125 bytes; client application
// data is unsupported. No package dependency or Chromium connection-limit flag.
const websocketFrame = (opcode, payload) => {
  if (payload.length > 125) throw Error('Synthetic WebSocket frame exceeds its fixed small-payload contract');
  return Buffer.concat([Buffer.from([0x80 | opcode, payload.length]), payload]);
};
server.on('upgrade', (request, socket, head) => {
  const origin = 'http://127.0.0.1:' + server.address().port;
  const key = request.headers['sec-websocket-key'];
  if (
    request.url !== '/events' ||
    request.method !== 'GET' ||
    request.headers.origin !== origin ||
    request.headers.upgrade?.toLowerCase() !== 'websocket' ||
    request.headers['sec-websocket-version'] !== '13' ||
    typeof key !== 'string' ||
    !/^[A-Za-z0-9+/]{22}==$/.test(key)
  ) {
    socket.destroy();
    return;
  }
  const accept = createHash('sha1')
    .update(key + '258EAFA5-E914-47DA-95CA-C5AB0DC85B11')
    .digest('base64');
  socket.write(
    'HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: ' +
      accept +
      '\r\n\r\n'
  );
  socket.setNoDelay(true);
  upgradedSockets.add(socket);
  const audit = { id: ++connection, openedAt: Date.now(), frames: 0, closed: false, errors: 0 };
  connectionAudit.push(audit);
  let pending = Buffer.alloc(0);
  const consume = (bytes) => {
    pending = Buffer.concat([pending, bytes]);
    if (pending.length > 1024) return socket.destroy();
    while (pending.length >= 2) {
      const opcode = pending[0] & 0x0f;
      const length = pending[1] & 0x7f;
      // Browsers mask all client frames. Only final close/ping/pong controls
      // are valid for this receive-only fixture, including split TCP chunks.
      if (
        (pending[0] & 0xf0) !== 0x80 ||
        !(pending[1] & 0x80) ||
        length > 125 ||
        ![8, 9, 10].includes(opcode) ||
        (opcode === 8 && length === 1)
      )
        return socket.destroy();
      if (pending.length < length + 6) return;
      const payload = Buffer.from(pending.subarray(6, length + 6));
      for (let i = 0; i < payload.length; i++) payload[i] ^= pending[2 + (i % 4)];
      pending = pending.subarray(length + 6);
      if (opcode === 8) {
        socket.end(websocketFrame(8, payload));
        return;
      }
      if (opcode === 9) socket.write(websocketFrame(10, payload));
    }
  };
  const tick = () => {
    if (socket.destroyed || socket.writableEnded) return;
    socket.write(websocketFrame(1, Buffer.from(JSON.stringify({ connection: audit.id }))));
    audit.frames++;
  };
  const timer = setInterval(tick, 500);
  socket.on('data', consume);
  socket.on('error', () => audit.errors++);
  socket.on('close', () => {
    audit.closed = true;
    audit.closedAt = Date.now();
    upgradedSockets.delete(socket);
    clearInterval(timer);
  });
  if (head.length) consume(head);
  tick();
});
await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
const config = {
  label,
  profile,
  instrumentation,
  launchStartedAt: Date.now(),
  out,
  sourceSha,
  appRoot: path.resolve(option('--app-root', desktop)),
  dataDir: path.join(root, 'data'),
  origin: 'http://127.0.0.1:' + server.address().port,
  windowMs: 120000,
  backgroundMs: profile === 'full' && label === 'candidate' ? 1800000 : 0,
  dependenciesSHA256: createHash('sha256')
    .update(readFileSync(path.join(desktop, 'package-lock.json')))
    .digest('hex'),
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
if (
  createHash('sha256')
    .update(readFileSync(path.join(config.appRoot, 'package-lock.json')))
    .digest('hex') !== config.dependenciesSHA256
)
  throw Error('App dependency lock differs from the shared measurement runtime');

function startupHook(config) {
  const { app, BrowserWindow } = require('electron');
  const fs = require('node:fs');
  const path = require('node:path');
  const { syncBuiltinESMExports } = require('node:module');
  const originalWrite = fs.writeFileSync.bind(fs);
  const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
  let enabled = config.instrumentation === 'on';
  const hookOrigin = performance.now();
  const time = () => performance.now() - hookOrigin;
  let currentPhase = 'startup';
  const timeline = [];
  const probes = new Map();
  const safeProbe = (value) => {
    if (
      !value ||
      typeof value.document !== 'string' ||
      value.document.length > 100 ||
      typeof value.known !== 'boolean' ||
      !['draft', 'viewport', 'conversation'].every((key) => Number.isSafeInteger(value[key]) && value[key] >= 0)
    )
      return undefined;
    return {
      document: value.document,
      draft: value.draft,
      viewport: value.viewport,
      conversation: value.conversation,
      known: value.known
    };
  };
  const captures = new Map();
  const renames = new Map();
  const pendingCaptures = new Set();
  let captureSerial = 0;
  const emit = (type, fields) => {
    if (enabled) timeline.push({ atMs: time(), phase: currentPhase, type, ...fields });
  };
  const counts = {
    pageState: 0,
    readConversation: 0,
    readRecoveryChanges: 0,
    capturePage: 0,
    captureCompleted: 0,
    recoveryWrite: 0,
    recoveryWriteCompleted: 0,
    recoveryRename: 0,
    recoveryFsync: 0,
    recoveryBytes: 0,
    hookCalls: 0,
    hookNanoseconds: 0
  };
  const classify = (code) => {
    if (typeof code !== 'string') return;
    for (const name of ['pageState', 'readConversation', 'readRecoveryChanges']) {
      if (new RegExp('\\bfunction\\s+' + name + '\\s*\\(').test(code)) {
        if (enabled) counts[name]++;
        return name;
      }
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
  const descriptors = new Map();
  const isRecovery = (file) =>
    typeof file === 'string' &&
    path.resolve(file).startsWith(recoveryRoot) &&
    /\.bin(?:\.[a-f0-9-]+\.tmp)?$/.test(file);
  const open = fs.openSync;
  fs.openSync = function (...args) {
    const fd = Reflect.apply(open, this, args);
    observe(() => {
      if (isRecovery(args[0])) descriptors.set(fd, args[0]);
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
      const value = Reflect.apply(original, this, args);
      observe(() => {
        const file = descriptors.get(args[0]) || args[0];
        if (!isRecovery(file)) return;
        if (method === 'writeFileSync') counts.recoveryWriteCompleted++;
        emit(method === 'writeFileSync' ? 'recovery-write-complete' : 'recovery-fsync-complete', {
          file: path.relative(recoveryRoot, file),
          bytes:
            method === 'writeFileSync'
              ? Buffer.isBuffer(args[1])
                ? args[1].length
                : Buffer.byteLength(String(args[1]))
              : undefined
        });
      });
      return value;
    };
  }
  const rename = fs.renameSync;
  fs.renameSync = function (...args) {
    const value = Reflect.apply(rename, this, args);
    if (isRecovery(args[0]) && isRecovery(args[1]) && args[1].endsWith('.bin')) {
      const event = { atMs: time(), file: path.relative(recoveryRoot, args[1]) };
      // Constant-size completion sentinels also remain in controls. They are
      // required to prove preparation/final-save completion, not operation logs.
      renames.set(path.basename(args[1]), event);
      observe(() => {
        counts.recoveryRename++;
        emit('recovery-rename-complete', event);
      });
    }
    return value;
  };
  syncBuiltinESMExports();
  const guardedSessions = new Set();
  app.on('web-contents-created', (_event, contents) => {
    const frameIdentity = () => {
      try {
        return { processId: contents.mainFrame.processId, routingId: contents.mainFrame.routingId };
      } catch {
        return undefined;
      }
    };
    contents.on('did-frame-navigate', () => emit('frame-navigation', { wc: contents.id, frame: frameIdentity() }));
    contents.on('dom-ready', () => emit('document-ready', { wc: contents.id, frame: frameIdentity() }));
    // Scope the entire performance launch to localhost and bundled resources.
    if (!guardedSessions.has(contents.session)) {
      guardedSessions.add(contents.session);
      contents.session.webRequest.onBeforeRequest((details, callback) => {
        const local = details.url.startsWith(config.origin + '/') || details.url === config.origin;
        const ownedSocket = details.url === config.origin.replace(/^http:/, 'ws:') + '/events';
        callback({ cancel: !local && !ownedSocket && !/^(app|file|data|blob|devtools):/.test(details.url) });
      });
    }
    for (const method of ['executeJavaScript', 'executeJavaScriptInIsolatedWorld', 'capturePage']) {
      const original = contents[method];
      contents[method] = function (...args) {
        let name;
        observe(() => {
          if (method === 'capturePage') counts.capturePage++;
          else if (method === 'executeJavaScript') name = classify(args[0]);
          else for (const script of args[1] || []) name = classify(script.code) || name;
        });
        // Controls retain a single latest safe probe result, but do not count
        // or log the call. No draft/body values from a script result are read.
        if (
          !enabled &&
          method === 'executeJavaScriptInIsolatedWorld' &&
          args[0] === 1017 &&
          (args[1] || []).some((script) => /\bfunction\s+readRecoveryChanges\s*\(/.test(script.code))
        )
          name = 'readRecoveryChanges';
        const capture =
          method === 'capturePage'
            ? {
                id: ++captureSerial,
                wc: contents.id,
                frame: frameIdentity(),
                startedAtMs: time(),
                probe: probes.get(contents.id)
              }
            : undefined;
        if (capture) {
          pendingCaptures.add(capture.id);
          emit('capture-start', capture);
        }
        let result;
        try {
          result = Reflect.apply(original, this, args);
        } catch (error) {
          if (capture) {
            pendingCaptures.delete(capture.id);
            captures.set(contents.id, { ...capture, completedAtMs: time(), success: false });
            emit('capture-failed', { id: capture.id, wc: contents.id });
          }
          throw error;
        }
        if (!capture && name !== 'readRecoveryChanges') return result;
        return result.then(
          (value) => {
            if (capture) {
              pendingCaptures.delete(capture.id);
              const completed = { ...capture, completedAtMs: time(), success: !value.isEmpty() };
              captures.set(contents.id, completed);
              observe(() => {
                if (completed.success) counts.captureCompleted++;
                emit('capture-complete', completed);
              });
            } else if (safeProbe(value)) {
              const safe = safeProbe(value);
              const previous = probes.get(contents.id);
              probes.set(contents.id, safe);
              observe(() =>
                emit(previous?.document !== safe.document ? 'probe-document-change' : 'probe-counters', {
                  wc: contents.id,
                  frame: frameIdentity(),
                  ...safe
                })
              );
            }
            return value;
          },
          (error) => {
            if (capture) {
              pendingCaptures.delete(capture.id);
              captures.set(contents.id, { ...capture, completedAtMs: time(), success: false });
              emit('capture-failed', { id: capture.id, wc: contents.id });
            }
            throw error;
          }
        );
      };
    }
  });
  const report = {
    schema: 2,
    sourceSha: config.sourceSha,
    scriptSha256: config.scriptSha256,
    dependenciesSHA256: config.dependenciesSHA256,
    label: config.label,
    profile: config.profile,
    instrumentation: config.instrumentation,
    launchStartedAt: config.launchStartedAt,
    fullRegression: config.profile === 'full',
    host: config.host,
    versions: process.versions,
    nativeLaunch: true,
    isolatedTestData: false,
    argv: process.argv,
    fixture: {
      origin: config.origin,
      connectionProtocol: 'websocket',
      connectionPeriodMs: 500,
      connectionPolicy: 'One independent receive-only connection per tab; no reconnect and no application send.',
      previousProtocol: 'HTTP/1.1 SSE; old incomplete SSE evidence is not comparable to this workload.',
      idleMessages: 32,
      streamingMessages: 240,
      tokensPerTab: 1040,
      tokenPeriodMs: 125,
      streamTargetDurationMs: 130000,
      streamMaximumCatchUpPerCallback: 16,
      streamScheduler: 'Monotonic desired count; every due token is appended and scrolled individually, never skipped.',
      previousStreamScheduler:
        'Callback-count interval; incomplete older streaming reports are excluded by script hash.',
      readingMode:
        'Streaming follows the tail after every token; this is not a manual reading-position performance test.',
      warmupMs: 5000,
      warmupPolicy:
        'Identical fixed startup warmup on both versions, recorded separately; never expand it adaptively to hide repeated work.'
    },
    counterScope:
      'Calls/attempts, not completions. Recovery writes/fsync only isolated data/recovery/*.bin and *.bin.UUID.tmp; exclude state/keychain and report files.',
    cpuDefinition:
      'Electron app.getAppMetrics percentCPUUsage summed across processes. Integrals use Electron percentage-seconds, not core-seconds.',
    controlObservation:
      'Off disables operation counters and timeline; CPU/delay sampling and thin completion/probe/egress wrappers remain. Preparation/final-save sentinels retain only latest identifiers, monotonic times and safe document/counters.',
    phases: [],
    passed: false
  };
  let activePhase;
  const persist = () => {
    if (activePhase) {
      activePhase.operations = activePhase.countsEnabled
        ? Object.fromEntries(Object.keys(counts).map((key) => [key, counts[key] - activePhase.counterBefore[key]]))
        : null;
      originalWrite(path.join(config.out, activePhase.name + '.json'), JSON.stringify(activePhase, null, 2));
      report.incompletePhase = activePhase.name;
    }
    if (config.instrumentation === 'on')
      originalWrite(
        path.join(config.out, 'timeline.json'),
        JSON.stringify(
          {
            clock: 'Milliseconds since startup hook; shared with phase startedAtMs/endedAtMs and alignment sentinels.',
            events: timeline
          },
          null,
          2
        )
      );
    originalWrite(path.join(config.out, 'report.json'), JSON.stringify(report, null, 2));
  };
  persist();
  const difference = (after, before) =>
    enabled ? Object.fromEntries(Object.keys(counts).map((key) => [key, after[key] - before[key]])) : null;
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
      // Only stage/setup boundaries call this. Validate real tail DOM content
      // independently of the generator's counter and progress timestamps.
      function readFixtureBoundary() {
        const main = document.querySelector('main');
        const tail = main.lastElementChild?.textContent || '';
        return {
          ...window.fixture,
          draft: document.querySelector('textarea').value,
          discarded: document.wasDiscarded,
          visibility: document.visibilityState,
          tailGap: main.scrollHeight - main.clientHeight - main.scrollTop,
          tailTokens: [...tail.matchAll(/ token-(\d+)/g)].map((match) => Number(match[1]))
        };
      }
      const read = () =>
        Promise.all(
          views().map(async (view) => ({
            id: view.webContents.id,
            pid: view.webContents.getOSProcessId(),
            throttled: view.webContents.getBackgroundThrottling(),
            state: await view.webContents.executeJavaScript('(' + readFixtureBoundary.toString() + ')()')
          }))
        );
      const verify = (pages, expected) => {
        if (
          pages.length !== expected ||
          new Set(pages.map((page) => page.state.connection)).size !== expected ||
          pages.some(
            (page) =>
              !page.state.ready ||
              page.throttled ||
              page.state.discarded ||
              page.state.sent !== 0 ||
              !page.state.connection ||
              !page.state.connectionOpen ||
              page.state.connections !== 1 ||
              page.state.connectionCloses !== 0 ||
              page.state.connectionErrors !== 0 ||
              page.state.connectionIdentityChanges !== 0
          )
        )
          throw Error(
            'Native fixture lost a tab or unique open connection, was throttled/discarded, reconnected/closed/errored, or sent unexpectedly'
          );
      };
      const ready = async (expected) => {
        let lastPages;
        let lastError;
        for (let i = 0; i < 200; i++) {
          try {
            const pages = await read();
            lastPages = pages;
            if (pages.length === expected && pages.every((page) => page.state.ready && page.state.connection)) {
              verify(pages, expected);
              return;
            }
          } catch (error) {
            lastError = String(error);
          }
          await sleep(100);
        }
        report.fixtureReadinessFailure = { expected, lastPages, lastError };
        throw Error('Local fixture did not initialize all tabs and independent WebSocket connections');
      };
      const phase = async (name, durationMs, expected, options = {}) => {
        const pagesBefore = await read();
        verify(pagesBefore, expected);
        app.getAppMetrics();
        const before = { ...counts };
        const start = performance.now();
        const startedAtMs = time();
        currentPhase = name;
        const samples = [];
        const delay = [];
        activePhase = {
          name,
          expected,
          durationMs,
          countsEnabled: enabled,
          before: pagesBefore,
          counterBefore: enabled ? before : null,
          samples,
          delay
        };
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
        let split;
        try {
          if (options.begin) await options.begin();
          while (performance.now() - start < (split ? split.elapsedMs + options.settleMs : durationMs)) {
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
            if (options.splitMs && !split && performance.now() - start >= options.splitMs) {
              split = { elapsedMs: performance.now() - start, counts: { ...counts }, pages: await read() };
              verify(split.pages, expected);
              if (options.onSplit) options.onSplit(split.pages);
              currentPhase = 'settle-8';
              emit('settle-start', { lifecycleStartedAtMs: startedAtMs });
            }
            if (options.late && !late && performance.now() - start >= durationMs - 30000) late = await read();
            if (options.until?.()) break;
            if (samples.length % 60 === 0)
              console.log(JSON.stringify({ phase: name, elapsedMs: performance.now() - start }));
          }
        } finally {
          clearInterval(loop);
        }
        if (options.until && !options.until()) {
          report.preparationFailure = options.failureEvidence?.();
          throw Error('Preparation did not observe fresh capture success and matching recovery replacement');
        }
        const elapsedMs = performance.now() - start;
        const endedCounts = { ...counts };
        const operations = difference(endedCounts, before);
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
          startedAtMs,
          endedAtMs: startedAtMs + elapsedMs,
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
            hookNanoseconds: operations?.hookNanoseconds ?? null,
            selfTimePercentOfWall:
              ((observerNanoseconds + (operations?.hookNanoseconds || 0)) / (elapsedMs * 1000000)) * 100,
            note: 'Direct observer self-time is a lower bound; excludes renderer-side effects. Counter-disabled control retains CPU/delay sampling.'
          },
          rawFile,
          before: pagesBefore,
          late,
          after: pagesAfter
        };
        const aggregate = (from, to) => {
          let previous = 0;
          let integral = 0;
          const cpu = [];
          const perProcess = new Map();
          for (const sample of samples) {
            const overlap = Math.max(0, Math.min(to, sample.elapsedMs) - Math.max(from, previous));
            const total = sample.processes.reduce((sum, item) => sum + item.percentCPUUsage, 0);
            if (overlap) {
              integral += (total * overlap) / 1000;
              cpu.push(total);
              for (const metric of sample.processes) {
                if (!perProcess.has(metric.pid))
                  perProcess.set(metric.pid, { pid: metric.pid, type: metric.type, values: [], integral: 0 });
                const item = perProcess.get(metric.pid);
                item.values.push(metric.percentCPUUsage);
                item.integral += (metric.percentCPUUsage * overlap) / 1000;
              }
            }
            previous = sample.elapsedMs;
          }
          return {
            cpuPercent: stats(cpu),
            cpuPercentageSeconds: integral,
            cpuTimeWeightedMeanPercent: integral / ((to - from) / 1000),
            perProcessCPU: [...perProcess.values()].map((item) => ({
              pid: item.pid,
              type: item.type,
              samples: item.values.length,
              percentCPUUsage: stats(item.values),
              cpuPercentageSeconds: item.integral,
              cpuTimeWeightedMeanPercent: item.integral / ((to - from) / 1000)
            })),
            mainProcessDelayMs: stats(
              delay
                .filter((sample) => sample.elapsedMs >= from && sample.elapsedMs <= to)
                .map((sample) => sample.delayMs)
            )
          };
        };
        Object.assign(result, aggregate(0, elapsedMs));
        if (split) {
          result.segments = [
            {
              name: 'streaming-8',
              elapsedMs: split.elapsedMs,
              startedAtMs,
              endedAtMs: startedAtMs + split.elapsedMs,
              ...aggregate(0, split.elapsedMs),
              operations: difference(split.counts, before),
              before: pagesBefore,
              after: split.pages
            },
            {
              name: 'settle-8',
              elapsedMs: elapsedMs - split.elapsedMs,
              startedAtMs: startedAtMs + split.elapsedMs,
              endedAtMs: startedAtMs + elapsedMs,
              ...aggregate(split.elapsedMs, elapsedMs),
              operations: difference(endedCounts, split.counts),
              before: split.pages,
              after: pagesAfter
            }
          ];
        }
        originalWrite(path.join(config.out, rawFile), JSON.stringify({ ...result, samples, delay }, null, 2));
        activePhase = undefined;
        report.phases.push(result);
        persist();
        return result;
      };
      await ready(1);
      for (const count of config.profile === 'full' ? [1, 4, 8] : [8]) {
        let state = await run('window.profiles.snapshot()');
        while (state.tabs.length < count) {
          await run('window.profiles.command({type:"tab:new"})');
          state = await run('window.profiles.snapshot()');
          await ready(state.tabs.length);
        }
        await phase('warmup-' + count, 5000, count);
        if (config.profile === 'full') await phase('idle-' + count, config.windowMs, count);
      }
      if (config.profile === 'full' && config.label === 'candidate') {
        enabled = false;
        try {
          await phase('idle-8-counters-disabled', config.windowMs, 8);
        } finally {
          enabled = true;
        }
      }
      const assertStream = (pages) => {
        if (
          pages.some(
            ({ state }) =>
              state.tokens !== 1040 ||
              Math.abs(state.tailGap) > 2 ||
              state.tailTokens.length !== 1040 ||
              state.tailTokens.some((token, index) => token !== index) ||
              !Number.isFinite(state.streamStarted) ||
              !Number.isFinite(state.streamFinished) ||
              !Number.isFinite(state.streamDurationMs) ||
              state.streamDurationMs < 130000 ||
              state.streamDurationMs > config.windowMs + 15000
          )
        )
          throw Error(
            'Fixed streaming workload did not complete all 1040 ordered DOM tokens at the visible tail within 135 seconds'
          );
      };
      const workspace = await run('window.profiles.snapshot()');
      const activeTabId = workspace.profiles.find((item) => item.id === workspace.activeProfileId).activeTabId;
      const activeView = views().find((view) => view.getVisible());
      if (!activeView) throw Error('No visible active page for snapshot alignment');
      const activeId = activeView.webContents.id;
      const targetFile = activeTabId + '.bin';
      const identityBefore = await read();
      let markerCompletedAtMs;
      let alignedAtMs;
      const preparation = await phase('prepare-stream', 45000, 8, {
        failureEvidence: () => ({
          markerCompletedAtMs,
          activeWC: activeId,
          targetFile,
          latestCapture: captures.get(activeId),
          latestReplacement: renames.get(targetFile),
          pendingCaptures: pendingCaptures.size
        }),
        begin: async () => {
          for (const view of views()) await view.webContents.executeJavaScript('window.fixturePrepareStream()');
          markerCompletedAtMs = time();
          emit('preparation-marker-complete', { wc: activeId });
        },
        until: () => {
          const capture = captures.get(activeId),
            replacement = renames.get(targetFile);
          if (
            !alignedAtMs &&
            capture?.success &&
            capture.startedAtMs >= markerCompletedAtMs &&
            replacement?.atMs >= capture.completedAtMs
          )
            alignedAtMs = replacement.atMs;
          return alignedAtMs !== undefined && time() - alignedAtMs >= 1000;
        }
      });
      report.preparation = {
        phase: preparation.name,
        markerCompletedAtMs,
        alignedAtMs,
        shortWaitMs: 1000,
        activeWC: activeId,
        targetFile,
        capture: captures.get(activeId),
        replacement: renames.get(targetFile)
      };
      const identityPrepared = await read();
      if (
        identityBefore.some(
          (item) => identityPrepared.find((page) => page.id === item.id)?.state.instance !== item.state.instance
        )
      )
        throw Error('Preparation reloaded a page');
      const lifecycle = await phase('streaming-lifecycle-8', 180000, 8, {
        splitMs: 135000,
        settleMs: 45000,
        onSplit: assertStream,
        begin: async () => {
          for (const view of views()) await view.webContents.executeJavaScript('window.fixtureStartStream()');
        }
      });
      assertStream(lifecycle.after);
      const finalProbe = safeProbe(
        await activeView.webContents.executeJavaScriptInIsolatedWorld(1017, [
          { code: 'globalThis.__studioRecoveryChanges?.()' }
        ])
      );
      const finalCapture = captures.get(activeId),
        finalReplacement = renames.get(targetFile);
      const settled =
        !!finalCapture?.success &&
        finalCapture.startedAtMs >= lifecycle.startedAtMs + 130000 &&
        finalReplacement?.atMs >= finalCapture.completedAtMs &&
        finalReplacement.atMs <= lifecycle.endedAtMs &&
        pendingCaptures.size === 0 &&
        finalProbe?.known === true &&
        ['document', 'draft', 'viewport', 'conversation'].every((key) => finalProbe[key] === finalCapture.probe?.[key]);
      report.settlement = {
        scope:
          'Active page checkpoint pipeline; all eight page identities/DOM tokens/connections separately validated.',
        passed: settled,
        pendingCaptures: pendingCaptures.size,
        finalCapture,
        finalReplacement,
        finalProbe
      };
      if (!settled)
        throw Error(
          '45-second settle ended with missing final capture/replacement, pending capture or dirty probe versions'
        );
      if (config.backgroundMs) {
        // Boundary reads only. The single late read checks the last 30 seconds,
        // rather than inferring connection activity from a 30-minute total.
        win.hide();
        if (win.isVisible()) throw Error('Window did not hide');
        const background = await phase('hidden-8', config.backgroundMs, 8, { late: true });
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
          connectionProtocol: 'websocket',
          connectionPeriodMs: 500,
          uniqueConnections: new Set(restored.map((page) => page.state.connection)).size,
          connectionCloses: restored.reduce((sum, page) => sum + page.state.connectionCloses, 0),
          connectionErrors: restored.reduce((sum, page) => sum + page.state.connectionErrors, 0),
          restored,
          stable,
          passed: stable && background.elapsedMs >= 1800000
        };
        if (!report.retention.passed)
          throw Error('Background restoration lost identity, draft, connection, counters or zero-send invariant');
      }
      report.totals = config.instrumentation === 'on' ? counts : null;
      report.passed = true;
    } catch (error) {
      report.error = { message: String(error), stack: error.stack };
      process.exitCode = 1;
      console.error(error);
    } finally {
      report.totals = config.instrumentation === 'on' ? counts : null;
      report.completedAt = Date.now();
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
  const reportFile = path.join(out, 'report.json');
  let report;
  if (existsSync(reportFile)) {
    try {
      report = JSON.parse(readFileSync(reportFile, 'utf8'));
    } catch (error) {
      console.error('Could not read native report:', String(error));
    }
  }
  if (code !== 0 || !report?.passed) {
    if (report?.error?.message) console.error('Native report error:', report.error.message);
    if (report?.error?.stack) console.error(report.error.stack);
    throw Error(
      'Native CPU measurement failed (exit ' +
        code +
        ', signal ' +
        (child.signalCode || 'none') +
        '): ' +
        out +
        (report?.error?.message
          ? '\n' + report.error.message
          : report
            ? '\nReport passed flag: ' + report.passed
            : '\nNo readable report.json')
    );
  }
} finally {
  clearTimeout(timer);
  if (child && child.exitCode === null && child.signalCode === null) child.kill('SIGKILL');
  writeFileSync(
    path.join(out, 'fixture-connections.json'),
    JSON.stringify(
      {
        protocol: 'websocket',
        periodMs: 500,
        connections: connectionAudit,
        note: 'Closed timestamps after app teardown are expected; zero-close/error retention assertions use page state before teardown.'
      },
      null,
      2
    )
  );
  // closeAllConnections() does not include upgraded sockets (Node HTTP docs).
  for (const socket of upgradedSockets) socket.destroy();
  server.closeAllConnections();
  await new Promise((resolve) => server.close(resolve));
  // Only this launch's freshly-created synthetic directory is removed.
  rmSync(root, { recursive: true, force: true, maxRetries: 5, retryDelay: 200 });
}
