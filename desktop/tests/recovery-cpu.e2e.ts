import { test, expect, _electron as electron, type ElectronApplication, type Page } from '@playwright/test';
import { createServer } from 'node:http';
import { existsSync, mkdtempSync, readFileSync, rmSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import type { PageResume } from '../src/shared/recovery';
import { shellWindow } from './helpers/shell';

const desktop = path.resolve(fileURLToPath(new URL('..', import.meta.url)));
type Counts = { pageState: number; readConversation: number; capturePage: number; probes: number };
type Meter = { counts: Counts; inFlight: number; lastHeavy: number };
type MeasuredContents = Electron.WebContents & { __recoveryCpuTest?: Meter };
type Fixture = {
  app: ElectronApplication;
  shell: Page;
  firstTab: string;
  file: string;
  visible: <T = any>(code: string) => Promise<T>;
  record: () => Promise<PageResume | undefined>;
  meter: (reset?: boolean) => Promise<Meter>;
};

async function withFixture(run: (fixture: Fixture) => Promise<void>) {
  const root = mkdtempSync(path.join(os.tmpdir(), 'studio-recovery-cpu-'));
  const server = createServer((request, response) => {
    if (request.url?.startsWith('/late-image.svg')) {
      response.writeHead(200, { 'Content-Type': 'image/svg+xml', 'Cache-Control': 'no-store' });
      response.end(
        '<svg xmlns="http://www.w3.org/2000/svg" width="120" height="60"><rect width="120" height="60" fill="red"/></svg>'
      );
      return;
    }
    response.writeHead(200, { 'Content-Type': 'text/html;charset=utf-8', 'Cache-Control': 'no-store' });
    response.end(
      '<!doctype html><meta charset="utf-8"><title>Owned recovery CPU fixture</title>' +
        '<style>body{margin:20px;font:16px system-ui}main{height:420px;overflow:auto}article{height:120px}textarea{width:500px;height:60px}</style>' +
        '<main>' +
        Array.from(
          { length: 20 },
          (_, index) =>
            `<article data-message-id="message-${index}" data-message-author-role="assistant">Owned paragraph ${index}${index === 5 ? '<img id="late-image" width="120" height="60">' : ''}</article>`
        ).join('') +
        '</main><textarea id="prompt-textarea"></textarea><button id="send" onclick="window.sent++">Fixture send</button>' +
        '<script>window.instance=crypto.randomUUID();window.sent=0;window.inputs=0;window.imageLoads=0;document.addEventListener("input",()=>window.inputs++,true);document.querySelector("#late-image").addEventListener("load",()=>window.imageLoads++);</script>'
    );
  });
  let app: ElectronApplication | undefined;
  try {
    await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
    const address = server.address();
    if (!address || typeof address === 'string') throw Error('Owned fixture did not listen');
    const origin = `http://127.0.0.1:${address.port}`;
    app = await electron.launch({
      args: [desktop],
      env: {
        ...process.env,
        PROFILES_E2E: '1',
        PROFILES_TEST_DATA: path.join(root, 'data'),
        PROFILES_FIXTURE_ORIGIN: origin
      }
    });
    const application = app;
    const shell = await shellWindow(application);
    const visible = async <T = any>(code: string): Promise<T> =>
      application.evaluate(
        ({ BrowserWindow }, value) => {
          const window = BrowserWindow.getAllWindows().find(
            (entry) => entry.webContents.getURL() === 'app://shell/index.html'
          );
          const view = (window?.contentView.children as Electron.WebContentsView[] | undefined)?.find(
            (entry) => entry.getVisible() && entry.webContents.getURL().startsWith(value.origin + '/')
          );
          return view?.webContents.executeJavaScript(value.code);
        },
        { origin, code }
      );
    await expect.poll(() => visible('window.instance')).toBeTruthy();
    await application.evaluate(({ BrowserWindow }, origin) => {
      const window = BrowserWindow.getAllWindows().find(
        (entry) => entry.webContents.getURL() === 'app://shell/index.html'
      )!;
      const contents = (window.contentView.children as Electron.WebContentsView[]).find(
        (entry) => entry.getVisible() && entry.webContents.getURL().startsWith(origin + '/')
      )!.webContents as MeasuredContents;
      const meter: Meter = {
        counts: { pageState: 0, readConversation: 0, capturePage: 0, probes: 0 },
        inFlight: 0,
        lastHeavy: Date.now()
      };
      contents.__recoveryCpuTest = meter;
      const execute = contents.executeJavaScript.bind(contents);
      contents.executeJavaScript = (...args: Parameters<typeof execute>) => {
        if (/\bfunction\s+pageState\s*\(/.test(args[0])) {
          meter.counts.pageState++;
          meter.lastHeavy = Date.now();
        }
        if (/\bfunction\s+readConversation\s*\(/.test(args[0])) {
          meter.counts.readConversation++;
          meter.lastHeavy = Date.now();
        }
        return execute(...args);
      };
      const isolated = contents.executeJavaScriptInIsolatedWorld.bind(contents);
      contents.executeJavaScriptInIsolatedWorld = (...args: Parameters<typeof isolated>) => {
        if (args[0] === 1017) meter.counts.probes++;
        return isolated(...args);
      };
      const capture = contents.capturePage.bind(contents);
      contents.capturePage = async (...args: Parameters<typeof capture>) => {
        meter.counts.capturePage++;
        meter.inFlight++;
        meter.lastHeavy = Date.now();
        try {
          return await capture(...args);
        } finally {
          meter.inFlight--;
          meter.lastHeavy = Date.now();
        }
      };
    }, origin);
    const meter = async (reset = false): Promise<Meter> =>
      application.evaluate(
        ({ BrowserWindow }, value) => {
          const window = BrowserWindow.getAllWindows().find(
            (entry) => entry.webContents.getURL() === 'app://shell/index.html'
          )!;
          const contents = (window.contentView.children as Electron.WebContentsView[]).find(
            (entry) => entry.getVisible() && entry.webContents.getURL().startsWith(value.origin + '/')
          )!.webContents as MeasuredContents;
          const state = contents.__recoveryCpuTest!;
          if (value.reset) state.counts = { pageState: 0, readConversation: 0, capturePage: 0, probes: 0 };
          return { ...state, counts: { ...state.counts } };
        },
        { origin, reset }
      );
    const state = await shell.evaluate(() => window.profiles.snapshot());
    const firstTab = state.profiles.find((entry) => entry.id === state.activeProfileId)!.activeTabId;
    const file = path.join(root, 'data', 'recovery', state.activeProfileId, firstTab + '.bin');
    const record = async (): Promise<PageResume | undefined> => {
      if (!existsSync(file)) return;
      const encrypted = readFileSync(file).toString('base64');
      // The temporary E2E profile uses Electron's mock keychain; no personal
      // key or synchronous/legacy decryption API is involved.
      return application.evaluate(
        async ({ safeStorage }, bytes) =>
          JSON.parse((await safeStorage.decryptStringAsync(Buffer.from(bytes, 'base64'))).result) as PageResume,
        encrypted
      );
    };
    await expect.poll(async () => !!(await record())?.page?.image, { timeout: 20000 }).toBe(true);
    await run({ app: application, shell, firstTab, file, visible, record, meter });
  } finally {
    await app?.close().catch(() => {});
    server.closeAllConnections();
    await new Promise<void>((resolve) => server.close(() => resolve()));
    rmSync(root, { recursive: true, force: true });
  }
}

test('quiet known pages keep lightweight ticks without repeating recovery DOM extraction or viewport capture', async () => {
  test.skip(process.platform !== 'darwin');
  await withFixture(async ({ meter, file, visible }) => {
    // The first observer pass, fonts and initial layout may invalidate once more.
    // Start the observation only after several real ticks and completed capture.
    await expect
      .poll(
        async () => {
          const state = await meter();
          return state.inFlight === 0 && state.counts.probes >= 3 && Date.now() - state.lastHeavy >= 5500;
        },
        { timeout: 25000 }
      )
      .toBe(true);
    const before = readFileSync(file);
    await meter(true);
    await new Promise((resolve) => setTimeout(resolve, 6500));
    const after = await meter();
    expect(after.counts.probes).toBeGreaterThanOrEqual(2);
    expect(after.counts.pageState).toBe(0);
    expect(after.counts.readConversation).toBe(0);
    expect(after.counts.capturePage).toBe(0);
    expect(after.inFlight).toBe(0);
    expect(readFileSync(file)).toEqual(before);
    expect(await visible('window.sent')).toBe(0);
  });
});

test('programmatic textarea changes without input events reach encrypted recovery on the next periodic checkpoint', async () => {
  test.skip(process.platform !== 'darwin');
  await withFixture(async ({ record, visible, file }) => {
    const draft = 'Synthetic programmatic draft — never send';
    const before = readFileSync(file);
    const inputs = await visible('window.inputs');
    await visible('document.querySelector("#prompt-textarea").value=' + JSON.stringify(draft));
    await expect.poll(async () => (await record())?.draft, { timeout: 4500, intervals: [100, 200, 300] }).toBe(draft);
    expect(await visible('window.inputs')).toBe(inputs);
    const encrypted = readFileSync(file);
    expect(encrypted).not.toEqual(before);
    expect(encrypted.includes(Buffer.from(draft))).toBe(false);
    expect(await visible('window.sent')).toBe(0);
  });
});

test('DOM, scrolling and owned image loads refresh eligible snapshots while tab close and undo retain the native page', async () => {
  test.skip(process.platform !== 'darwin');
  test.setTimeout(90000);
  await withFixture(async ({ shell, firstTab, visible, record }) => {
    const before = (await record())!;
    const instance = await visible('window.instance');
    await visible(
      `document.querySelector('[data-message-id="message-4"]').firstChild.textContent='Changed synthetic conversation';document.querySelector('main').scrollTop=420;document.querySelector('#late-image').src='/late-image.svg';`
    );
    await expect.poll(() => visible('window.imageLoads')).toBe(1);
    await expect.poll(async () => (await record())?.scroll).toBe(420);
    // Dirty text and viewport snapshots remain rate-limited to the real 30s
    // boundary; draft/reading recovery above must not wait for that boundary.
    await expect
      .poll(
        async () => {
          const current = await record();
          return (
            !!current?.page &&
            current.page.capturedAt > before.page!.capturedAt &&
            current.page.messages.some((entry) => entry.text.includes('Changed synthetic conversation')) &&
            !!current.page.image &&
            current.page.image !== before.page!.image
          );
        },
        { timeout: 45000, intervals: [250, 500, 1000] }
      )
      .toBe(true);
    await shell.evaluate(() => window.profiles.command({ type: 'tab:new' }));
    await expect
      .poll(async () => {
        const other = await visible('window.instance');
        return typeof other === 'string' && other !== instance;
      })
      .toBe(true);
    await shell.evaluate((id) => window.profiles.command({ type: 'tab:activate', id }), firstTab);
    await expect.poll(() => visible('window.instance')).toBe(instance);
    expect(await visible('document.querySelector("main").scrollTop')).toBe(420);
    await shell.evaluate((id) => window.profiles.command({ type: 'tab:close', id }), firstTab);
    await shell.evaluate(() => window.profiles.command({ type: 'tab:restore' }));
    await expect.poll(() => visible('window.instance')).toBe(instance);
    expect(await visible('document.querySelector("main").scrollTop')).toBe(420);
    expect(await visible('window.sent')).toBe(0);
  });
});

type CommitProbe = {
  baselineImage?: string;
  hold: boolean;
  release?: () => void;
  events: string[];
  writes: { draft: string; scroll: number; schema: number; sameImage: boolean }[];
  captures: { started: number; finished?: number; returned?: number }[];
};
type CommitMeasuredContents = MeasuredContents & { __checkpointCommitProbe?: CommitProbe };

test('periodic viewport updates respect the write budget and critical drafts persist before a delayed real image', async ({}, info) => {
  test.skip(process.platform !== 'darwin');
  test.setTimeout(120000);
  await withFixture(async ({ app, shell, firstTab, record, visible, file, meter }) => {
    await expect
      .poll(
        async () => {
          const state = await meter();
          return state.inFlight === 0 && state.counts.probes >= 3 && Date.now() - state.lastHeavy >= 5500;
        },
        { timeout: 25000 }
      )
      .toBe(true);
    let previous = (await record())!;
    const instance = await visible('window.instance');
    await app.evaluate(({ BrowserWindow, safeStorage }, initial) => {
      const window = BrowserWindow.getAllWindows().find(
        (entry) => entry.webContents.getURL() === 'app://shell/index.html'
      )!;
      const contents = (window.contentView.children as Electron.WebContentsView[]).find(
        (entry) => !!(entry.webContents as MeasuredContents).__recoveryCpuTest
      )!.webContents as CommitMeasuredContents;
      const state: CommitProbe = {
        baselineImage: initial.page?.image,
        hold: true,
        events: [],
        writes: [],
        captures: []
      };
      contents.__checkpointCommitProbe = state;
      const capture = contents.capturePage.bind(contents);
      contents.capturePage = async (...args: Parameters<typeof capture>) => {
        const sample: CommitProbe['captures'][number] = { started: performance.now() };
        state.captures.push(sample);
        state.events.push('capture:start');
        // Invoke the real native capture and retain that exact NativeImage. Only
        // its promise's delivery is gated; no image bytes or durations are faked.
        const image = await capture(...args);
        sample.finished = performance.now();
        state.events.push('capture:finished');
        if (state.hold)
          await new Promise<void>((resolve) => {
            state.release = resolve;
          });
        sample.returned = performance.now();
        state.events.push('capture:return');
        return image;
      };
      const encrypt = safeStorage.encryptStringAsync.bind(safeStorage);
      safeStorage.encryptStringAsync = async (text) => {
        let value: PageResume | undefined;
        try {
          value = JSON.parse(text) as PageResume;
        } catch {
          /* Other encrypted stores are outside this probe. */
        }
        if (value?.profileId === initial.profileId && value.tabId === initial.tabId) {
          const index = state.writes.length;
          state.writes.push({
            draft: value.draft,
            scroll: value.scroll,
            schema: value.schema,
            sameImage: value.page?.image === state.baselineImage
          });
          state.events.push('encrypt:' + index);
        }
        return encrypt(text);
      };
    }, previous);
    const probe = () =>
      app.evaluate(({ BrowserWindow }) => {
        const window = BrowserWindow.getAllWindows().find(
          (entry) => entry.webContents.getURL() === 'app://shell/index.html'
        )!;
        const state = (window.contentView.children as Electron.WebContentsView[])
          .map((entry) => (entry.webContents as CommitMeasuredContents).__checkpointCommitProbe)
          .find((entry) => !!entry)!;
        return {
          events: [...state.events],
          writes: [...state.writes],
          captures: state.captures.map((entry) => ({ ...entry })),
          waiting: !!state.release
        };
      });
    const release = () =>
      app.evaluate(({ BrowserWindow }) => {
        const window = BrowserWindow.getAllWindows().find(
          (entry) => entry.webContents.getURL() === 'app://shell/index.html'
        )!;
        const state = (window.contentView.children as Electron.WebContentsView[])
          .map((entry) => (entry.webContents as CommitMeasuredContents).__checkpointCommitProbe)
          .find((entry) => !!entry)!;
        state.hold = false;
        const done = state.release;
        delete state.release;
        done?.();
      });
    const eligible = async () => {
      // Wait for the actual snapshot eligibility boundary, without requiring
      // the native screenshot itself to finish within an invented latency cap.
      await expect
        .poll(() => Date.now() - previous.page!.capturedAt, { timeout: 35000, intervals: [250, 500, 1000] })
        .toBeGreaterThan(31000);
    };
    try {
      await eligible();
      await visible('document.querySelector("main").scrollTop=240');
      await expect.poll(async () => (await record())?.scroll, { timeout: 4500 }).toBe(240);
      await expect.poll(async () => (await probe()).waiting, { timeout: 4500 }).toBe(true);
      const readingOnly = await probe();
      expect(readingOnly.captures).toHaveLength(1);
      expect(readingOnly.writes).toEqual([{ draft: previous.draft, scroll: 240, schema: 1, sameImage: true }]);
      expect(readingOnly.events.indexOf('capture:start')).toBeLessThan(readingOnly.events.indexOf('encrypt:0'));
      expect((await record())!.page).toEqual(previous.page);
      await release();
      await expect.poll(async () => (await record())?.page?.capturedAt).toBeGreaterThan(previous.page!.capturedAt);
      const viewport = await probe();
      expect(viewport.writes).toHaveLength(2);
      expect(viewport.writes[1]).toEqual({ draft: previous.draft, scroll: 240, schema: 1, sameImage: false });
      await info.attach('viewport-budget-real-capture', {
        body: Buffer.from(JSON.stringify(viewport)),
        contentType: 'application/json'
      });

      previous = (await record())!;
      await app.evaluate(({ BrowserWindow }, image) => {
        const window = BrowserWindow.getAllWindows().find(
          (entry) => entry.webContents.getURL() === 'app://shell/index.html'
        )!;
        const state = (window.contentView.children as Electron.WebContentsView[])
          .map((entry) => (entry.webContents as CommitMeasuredContents).__checkpointCommitProbe)
          .find((entry) => !!entry)!;
        state.baselineImage = image;
        state.hold = true;
        state.events = [];
        state.writes = [];
        state.captures = [];
      }, previous.page!.image);
      await eligible();
      const draft = 'Second-stage synthetic critical draft — never send';
      const inputs = await visible('window.inputs');
      await visible(
        'document.querySelector("#prompt-textarea").value=' +
          JSON.stringify(draft) +
          ';document.querySelector("main").scrollTop=480'
      );
      await expect.poll(async () => (await record())?.draft, { timeout: 4500 }).toBe(draft);
      await expect.poll(async () => (await probe()).waiting, { timeout: 4500 }).toBe(true);
      const critical = await probe();
      expect(critical.captures).toHaveLength(1);
      expect(critical.writes).toEqual([{ draft, scroll: 480, schema: 1, sameImage: true }]);
      expect(critical.events.indexOf('encrypt:0')).toBeLessThan(critical.events.indexOf('capture:start'));
      expect((await record())!.page).toEqual(previous.page);
      expect(readFileSync(file).includes(Buffer.from(draft))).toBe(false);
      expect(await visible('window.inputs')).toBe(inputs);
      await release();
      await expect.poll(async () => (await record())?.page?.capturedAt).toBeGreaterThan(previous.page!.capturedAt);
      const updated = await probe();
      expect(updated.writes).toHaveLength(2);
      expect(updated.writes[1]).toEqual({ draft, scroll: 480, schema: 1, sameImage: false });
      expect((await record())!.schema).toBe(1);
      await info.attach('critical-draft-before-real-capture', {
        body: Buffer.from(JSON.stringify(updated)),
        contentType: 'application/json'
      });
      await shell.evaluate(() => window.profiles.command({ type: 'tab:new' }));
      await expect
        .poll(async () => {
          const other = await visible('window.instance');
          return typeof other === 'string' && other !== instance;
        })
        .toBe(true);
      await shell.evaluate((id) => window.profiles.command({ type: 'tab:activate', id }), firstTab);
      await expect.poll(() => visible('window.instance')).toBe(instance);
      expect(await visible('document.querySelector("#prompt-textarea").value')).toBe(draft);
      expect(await visible('window.sent')).toBe(0);
    } finally {
      await release();
    }
  });
});
