import { test, expect, _electron as electron, type ElectronApplication } from '@playwright/test';
import { createServer, type Server } from 'node:http';
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import {
  mkdtempSync,
  mkdirSync,
  realpathSync,
  readFileSync,
  writeFileSync,
  rmSync,
  readdirSync,
  existsSync
} from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { fileURLToPath } from 'node:url';
import { shellWindow } from './helpers/shell';
import packageInfo from '../package.json' with { type: 'json' };
const desktop = path.resolve(fileURLToPath(new URL('..', import.meta.url)));
for (const mode of ['quit', 'idle', 'manual'] as const)
  test('confirmed update installs safely via ' + mode, async ({}, info) => {
    test.skip(process.platform !== 'darwin');
    const root = mkdtempSync(path.join(realpathSync(os.tmpdir()), 'profiles-updater-e2e-'));
    let app: ElectronApplication | undefined, server: Server | undefined, release!: () => void;
    const gate = new Promise<void>((r) => {
      release = r;
    });
    let nextChunk!: () => void, finishDownload!: () => void;
    const chunkGate = new Promise<void>((resolve) => {
      nextChunk = resolve;
    });
    const finishGate = new Promise<void>((resolve) => {
      finishDownload = resolve;
    });
    let archiveRequests = 0,
      versionRequests = 0,
      archiveBytesSent = 0;
    try {
      const current = path.join(root, 'Installed.app'),
        newDir = path.join(root, 'new');
      mkdirSync(newDir);
      const next = `${Number(packageInfo.version.split('.')[0]) + 1}.0.0`;
      const bundle = (dir: string, version: string) => {
        mkdirSync(path.join(dir, 'Contents/MacOS'), { recursive: true });
        execFileSync(
          'xcrun',
          ['clang', '-arch', 'arm64', '-x', 'c', '-o', path.join(dir, 'Contents/MacOS/fixture'), '-'],
          { input: 'int main(void){return 0;}' }
        );
        writeFileSync(
          path.join(dir, 'Contents/Info.plist'),
          `<?xml version="1.0"?><plist version="1.0"><dict><key>CFBundleIdentifier</key><string>com.forcemind.chatgpt.profiles</string><key>CFBundleExecutable</key><string>fixture</string><key>CFBundleShortVersionString</key><string>${version}</string><key>CFBundleVersion</key><string>1</string><key>CFBundlePackageType</key><string>APPL</string></dict></plist>`
        );
        execFileSync('/usr/bin/codesign', ['--force', '--deep', '--sign', '-', dir], { stdio: 'pipe' });
      };
      bundle(current, packageInfo.version);
      bundle(path.join(newDir, 'ChatGPT Profiles.app'), next);
      const zip = path.join(root, 'fixture.zip');
      execFileSync('/usr/bin/zip', ['-q', '-r', zip, 'ChatGPT Profiles.app'], { cwd: newDir });
      const bytes = readFileSync(zip),
        manifest = {
          schema: 1,
          version: next,
          publishedAt: new Date().toISOString(),
          notes: '隔离夹具更新，不是公开版本',
          files: {
            arm64: {
              url: `https://github.com/ForceMind/ChatGPT/releases/download/v${next}/ChatGPT-Profiles-${next}-mac-arm64.zip`,
              size: bytes.length,
              sha256: createHash('sha256').update(bytes).digest('hex')
            }
          }
        };
      const preparationHook = path.join(root, 'hold-preparation.cjs');
      // Hold only a real successful extraction callback. Download bytes, digest,
      // bundle checks and UI status remain real; preserve execFile's promisify shape.
      if (mode === 'manual')
        writeFileSync(
          preparationHook,
          `
        const childProcess = require('node:child_process');
        const { promisify } = require('node:util');
        const original = childProcess.execFile;
        let release;
        const held = new Promise(resolve => { release = resolve; });
        const gate = { entered: false, release };
        require('electron').app.__profilesUpdatePreparation = gate;
        function gated(...args) {
          const callback = args.at(-1);
          if (typeof callback !== 'function') return Reflect.apply(original, childProcess, args);
          const extracting = args[0] === '/usr/bin/ditto' && args[1]?.[0] === '-x';
          args[args.length - 1] = (...result) => {
            if (extracting && !result[0]) {
              gate.entered = true;
              held.then(() => callback(...result));
            } else callback(...result);
          };
          return Reflect.apply(original, childProcess, args);
        }
        gated[promisify.custom] = (...args) => new Promise((resolve, reject) => {
          gated(...args, (error, stdout, stderr) => error ? reject(error) : resolve({ stdout, stderr }));
        });
        childProcess.execFile = gated;
      `
        );
      server = createServer(async (req, res) => {
        if (req.url === '/version.json') {
          versionRequests++;
          await gate;
          if (res.destroyed) return;
          res.writeHead(200, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify(manifest));
          return;
        }
        if (req.url === '/archive.zip') {
          archiveRequests++;
          res.writeHead(200, { 'Content-Length': bytes.length });
          if (mode === 'manual') {
            const first = Math.floor(bytes.length / 3),
              second = Math.floor((bytes.length * 2) / 3);
            res.write(bytes.subarray(0, first));
            archiveBytesSent = first;
            await chunkGate;
            if (res.destroyed) return;
            res.write(bytes.subarray(first, second));
            archiveBytesSent = second;
            await finishGate;
            if (res.destroyed) return;
            archiveBytesSent = bytes.length;
            res.end(bytes.subarray(second));
            return;
          }
          archiveBytesSent = bytes.length;
          res.end(bytes);
          return;
        }
        res.writeHead(200, { 'Content-Type': 'text/html;charset=utf-8' });
        res.end(
          '<!doctype html><meta charset="utf-8"><title>Updater fixture</title><article data-message-id="one" data-message-author-role="assistant">更新前的页面</article><textarea id="prompt-textarea"></textarea>'
        );
      });
      await new Promise<void>((r) => server!.listen(0, '127.0.0.1', r));
      const address = server.address();
      if (!address || typeof address === 'string') throw Error('fixture');
      const origin = 'http://127.0.0.1:' + address.port,
        data = path.join(root, 'data');
      app = await electron.launch({
        args: mode === 'manual' ? ['-r', preparationHook, desktop] : [desktop],
        env: {
          ...process.env,
          PROFILES_E2E: '1',
          PROFILES_TEST_DATA: data,
          PROFILES_FIXTURE_ORIGIN: origin,
          PROFILES_UPDATES_FIXTURE: '1',
          PROFILES_IDLE_FAST: mode === 'idle' ? '1' : '0'
        }
      });
      const shell = await shellWindow(app);
      await app.evaluate(
        ({ app, dialog }, { idle, manual }) => {
          const host = app as any;
          host.testUpdateResponse = 0;
          host.testUpdateDialogs = [];
          host.testUpdateReadyResponse = 0;
          dialog.showMessageBox = (async (_window: any, options: any) => {
            host.testUpdateDialogs.push(options);
            if (manual && options.title === '更新已准备' && host.testUpdateReadyResponse === 1) {
              await new Promise<void>((resolve) => {
                host.testConfirmUpdateInstallation = resolve;
              });
            }
            return {
              response: options.title === '更新已准备' ? host.testUpdateReadyResponse : host.testUpdateResponse,
              checkboxChecked: idle
            };
          }) as typeof dialog.showMessageBox;
        },
        { idle: mode === 'idle', manual: mode === 'manual' }
      );
      release();
      await expect.poll(() => app!.evaluate(({ app }) => (app as any).testUpdateDialogs.length)).toBe(1);
      expect(archiveRequests).toBe(0);
      expect((await shell.evaluate(() => window.profiles.snapshot())).update?.phase).toBe('available');
      await expect
        .poll(() =>
          app!.evaluate(({ BrowserWindow }) => {
            const w = BrowserWindow.getAllWindows().find((w) => w.webContents.getURL() === 'app://shell/index.html');
            return (
              (w?.contentView.children[0] as Electron.WebContentsView | undefined)?.webContents.executeJavaScript(
                '!!document.querySelector("textarea")'
              ) ?? false
            );
          })
        )
        .toBe(true);
      await app.evaluate(({ BrowserWindow }) => {
        const w = BrowserWindow.getAllWindows().find((w) => w.webContents.getURL() === 'app://shell/index.html')!;
        return (w.contentView.children[0] as Electron.WebContentsView).webContents.executeJavaScript(
          'document.querySelector("textarea").value="更新前草稿，不发送"'
        );
      });
      await app.evaluate(({ app }) => {
        (app as any).testUpdateResponse = 1;
      });
      await shell.evaluate(() => window.profiles.command({ type: 'update:check' }));
      if (mode === 'manual') {
        const header = shell.getByRole('button', { name: '查看应用更新', exact: true });
        await expect(header).toBeVisible();
        await expect
          .poll(async () => {
            const status = (await shell.evaluate(() => window.profiles.snapshot())).update;
            if (status?.phase === 'error') throw Error(status.message);
            return status?.progress || 0;
          })
          .toBeGreaterThan(0);
        await header.click();
        const card = shell.getByRole('group', { name: '应用更新', exact: true });
        const progress = card.getByRole('progressbar', { name: '更新下载进度' });
        await expect(card).toHaveAttribute('data-update-phase', 'downloading');
        await expect(card).toHaveAttribute('data-update-stage', 'download');
        await expect(progress).toHaveAttribute('max', '100');
        await expect
          .poll(() => progress.evaluate((element) => (element as HTMLProgressElement).value))
          .toBe(Math.floor((archiveBytesSent * 100) / bytes.length));
        const firstProgress = await progress.evaluate((element) => (element as HTMLProgressElement).value);
        expect(firstProgress).toBeGreaterThan(0);
        expect(firstProgress).toBeLessThan(100);
        expect(firstProgress).toBe(Math.floor((archiveBytesSent * 100) / bytes.length));
        await expect(shell.locator('.drag-space')).toBeVisible();
        expect(
          await shell
            .locator('.drag-space')
            .evaluate((element) => getComputedStyle(element).getPropertyValue('-webkit-app-region'))
        ).toBe('drag');
        expect((await shell.locator('.drag-space').boundingBox())!.width).toBeGreaterThan(20);
        const originalSize = await app.evaluate(({ BrowserWindow }) =>
          BrowserWindow.getAllWindows()
            .find((window) => window.webContents.getURL() === 'app://shell/index.html')!
            .getContentSize()
        );
        await app.evaluate(({ BrowserWindow }) =>
          BrowserWindow.getAllWindows()
            .find((window) => window.webContents.getURL() === 'app://shell/index.html')!
            .setContentSize(900, 800)
        );
        await expect.poll(() => shell.evaluate(() => innerWidth)).toBe(900);
        await expect(header).toBeVisible();
        expect(
          await shell.locator('.titlebar').evaluate((element) => element.scrollWidth <= element.clientWidth + 1)
        ).toBe(true);
        expect((await shell.locator('.drag-space').boundingBox())!.width).toBeGreaterThan(0);
        await app.evaluate(
          ({ BrowserWindow }, size) =>
            BrowserWindow.getAllWindows()
              .find((window) => window.webContents.getURL() === 'app://shell/index.html')!
              .setContentSize(size[0], size[1]),
          originalSize
        );
        await expect.poll(() => shell.evaluate(() => innerWidth)).toBe(originalSize[0]);
        await shell.screenshot({ path: info.outputPath('macos-update-real-download-progress.png') });
        nextChunk();
        await expect
          .poll(() => progress.evaluate((element) => (element as HTMLProgressElement).value))
          .toBeGreaterThan(firstProgress);
        await expect
          .poll(() => progress.evaluate((element) => (element as HTMLProgressElement).value))
          .toBe(Math.floor((archiveBytesSent * 100) / bytes.length));
        const secondProgress = await progress.evaluate((element) => (element as HTMLProgressElement).value);
        expect(secondProgress).toBeLessThan(100);
        expect(secondProgress).toBe(Math.floor((archiveBytesSent * 100) / bytes.length));
        finishDownload();
        await expect
          .poll(async () => {
            const status = (await shell.evaluate(() => window.profiles.snapshot())).update;
            if (status?.phase === 'error') throw Error(status.message);
            return app!.evaluate(({ app }) => (app as any).__profilesUpdatePreparation.entered);
          })
          .toBe(true);
        await expect(card).toHaveAttribute('data-update-stage', 'prepare');
        await expect(card).toHaveAttribute('data-update-phase', 'downloading');
        await expect(progress).toHaveAttribute('value', '100');
        await expect(card).toContainText('正在准备安装');
        expect(archiveBytesSent).toBe(bytes.length);
        expect((await shell.evaluate(() => window.profiles.snapshot())).update?.phase).not.toBe('ready');
        await expect(card.getByRole('menuitem', { name: /立即安装并重启/ })).toHaveCount(0);
        await info.attach('real-download-progress.json', {
          contentType: 'application/json',
          body: JSON.stringify({
            bytes: bytes.length,
            firstProgress,
            secondProgress,
            completedProgress: 100,
            phaseWhilePreparationHeld: 'downloading',
            extractionActuallyCompleted: true
          })
        });
        await app.evaluate(({ app }) => (app as any).__profilesUpdatePreparation.release());
        await header.click();
      }
      await expect
        .poll(async () => {
          const status = (await shell.evaluate(() => window.profiles.snapshot())).update;
          if (status?.phase === 'error') throw Error(status.message);
          return status?.phase;
        })
        .toBe('ready');
      await expect
        .poll(() =>
          app!.evaluate(({ app }) => (app as any).testUpdateDialogs.filter((d: any) => d.title === '更新已准备').length)
        )
        .toBe(1);
      expect(archiveRequests).toBe(1);
      expect(versionRequests).toBeGreaterThanOrEqual(2);
      expect(readFileSync(path.join(current, 'Contents/Info.plist'), 'utf8')).toContain(packageInfo.version);
      await shell.locator('.current-account').click();
      await expect(shell.getByRole('menuitem', { name: /更新 .* 已准备/ })).toBeVisible();
      await shell.screenshot({ path: info.outputPath('macos-update-ready.png') });
      const closed = app.waitForEvent('close');
      if (mode === 'manual') {
        await app.evaluate(({ app }) => {
          (app as any).testUpdateReadyResponse = 1;
        });
        await shell.getByRole('menuitem', { name: /更新 .* 已准备.*立即安装并重启/ }).click();
        await expect
          .poll(() => app!.evaluate(({ app }) => typeof (app as any).testConfirmUpdateInstallation))
          .toBe('function');
        const prompt = await app.evaluate(({ app }) => (app as any).testUpdateDialogs.at(-1));
        expect(prompt.title).toBe('更新已准备');
        expect(prompt.buttons).toContain('保存并立即重启安装');
        await app.evaluate(({ app }) => {
          (app as any).testConfirmUpdateInstallation();
        });
      } else if (mode === 'quit') {
        await app.evaluate(({ app }) => app.quit());
      } else {
        await shell.locator('.current-account').click();
        const pageScript = async (code: string) =>
          app!.evaluate(({ BrowserWindow }, code) => {
            const w = BrowserWindow.getAllWindows().find((w) => w.webContents.getURL() === 'app://shell/index.html')!;
            return (w.contentView.children[0] as Electron.WebContentsView).webContents.executeJavaScript(code);
          }, code);
        await pageScript(
          'const busy=document.createElement("button");busy.id="idle-busy";busy.dataset.testid="stop-button";busy.textContent="生成中";document.body.append(busy);'
        );
        const hide = () =>
          app!.evaluate(({ BrowserWindow }) =>
            BrowserWindow.getAllWindows()
              .find((w) => w.webContents.getURL() === 'app://shell/index.html')!
              .hide()
          );
        await hide();
        await new Promise((r) => setTimeout(r, 1600));
        expect((await shell.evaluate(() => window.profiles.snapshot())).update?.idleDeadline).toBeUndefined();
        await pageScript(
          'document.getElementById("idle-busy").remove();document.querySelector("textarea").id="unknown-composer"'
        );
        await new Promise((r) => setTimeout(r, 1300));
        expect((await shell.evaluate(() => window.profiles.snapshot())).update?.idleDeadline).toBeUndefined();
        await pageScript(
          'document.querySelector("textarea").id="prompt-textarea";const file=document.createElement("span");file.id="pending-attachment";file.dataset.testid="attachment-chip";file.textContent="未发送的附件";document.body.append(file)'
        );
        await new Promise((r) => setTimeout(r, 1300));
        expect((await shell.evaluate(() => window.profiles.snapshot())).update?.idleDeadline).toBeUndefined();
        await pageScript('document.getElementById("pending-attachment").remove()');
        const warning = async () => {
          await expect
            .poll(() => app!.windows().some((p) => p.url().startsWith('app://shell/update-notice.html')))
            .toBe(true);
          return app!.windows().find((p) => p.url().startsWith('app://shell/update-notice.html'))!;
        };
        let note = await warning();
        await expect
          .poll(() =>
            app!.evaluate(({ BrowserWindow }) =>
              BrowserWindow.getAllWindows()
                .find((w) => w.webContents.getURL().startsWith('app://shell/update-notice.html'))
                ?.isVisible()
            )
          )
          .toBe(true);
        expect(
          await app.evaluate(({ BrowserWindow }) =>
            BrowserWindow.getAllWindows()
              .find((w) => w.webContents.getURL().startsWith('app://shell/update-notice.html'))!
              .isFocused()
          )
        ).toBe(false);
        await note.screenshot({ path: info.outputPath('macos-idle-update-countdown.png') });
        await note.getByRole('button', { name: '取消本次自动安装' }).click();
        await expect
          .poll(async () => (await shell.evaluate(() => window.profiles.snapshot())).update?.idleDeadline)
          .toBeUndefined();
        expect(readFileSync(path.join(current, 'Contents/Info.plist'), 'utf8')).toContain(packageInfo.version);
        note = await warning();
        await app.evaluate(({ app }) => app.emit('activate'));
        await expect
          .poll(async () => (await shell.evaluate(() => window.profiles.snapshot())).update?.idleDeadline)
          .toBeUndefined();
        await new Promise((r) => setTimeout(r, 1300));
        expect(app.windows().some((p) => p.url().startsWith('app://shell/update-notice.html'))).toBe(false);
        await hide();
        await warning();
      }
      await closed;
      app = undefined;
      await expect.poll(() => readFileSync(path.join(current, 'Contents/Info.plist'), 'utf8')).toContain(next);
      const state = JSON.parse(readFileSync(path.join(data, 'state.json'), 'utf8')),
        profile = state.activeProfileId,
        records = readdirSync(path.join(data, 'recovery', profile)).filter((n) => n.endsWith('.bin'));
      expect(records.length).toBe(1);
      expect(readFileSync(path.join(data, 'recovery', profile, records[0])).includes(Buffer.from('更新前草稿'))).toBe(
        false
      );
      const updateState = JSON.parse(readFileSync(path.join(data, 'updates/state.json'), 'utf8'));
      expect(readFileSync(path.join(updateState.ready.staged.candidate, 'Contents/Info.plist'), 'utf8')).toContain(
        packageInfo.version
      );
      const installResult = path.join(data, 'updates/install-result.json');
      // The swap exposes the new Info.plist before signature validation and
      // atomic publication of the installer's terminal result have finished.
      await expect.poll(() => existsSync(installResult)).toBe(true);
      expect(JSON.parse(readFileSync(installResult, 'utf8')).ok).toBe(true);
      if (mode === 'idle') {
        app = await electron.launch({
          args: [desktop, '--profiles-background-update'],
          env: { ...process.env, PROFILES_E2E: '1', PROFILES_TEST_DATA: data, PROFILES_FIXTURE_ORIGIN: origin }
        });
        await shellWindow(app);
        expect(
          await app.evaluate(({ BrowserWindow }) =>
            BrowserWindow.getAllWindows()
              .find((w) => w.webContents.getURL() === 'app://shell/index.html')!
              .isVisible()
          )
        ).toBe(false);
        await app.evaluate(({ app }) => app.emit('activate'));
        await expect
          .poll(() =>
            app!.evaluate(({ BrowserWindow }) =>
              BrowserWindow.getAllWindows()
                .find((w) => w.webContents.getURL() === 'app://shell/index.html')!
                .isVisible()
            )
          )
          .toBe(true);
      }
    } finally {
      release();
      nextChunk();
      finishDownload();
      await app?.evaluate(({ app }) => (app as any).__profilesUpdatePreparation?.release()).catch(() => {});
      await app?.close().catch(() => {});
      if (server) {
        server.closeAllConnections();
        await new Promise<void>((r) => server!.close(() => r()));
      }
      rmSync(root, { recursive: true, force: true });
    }
  });

test('manual update checks expose real failures, retry and distinguish a newer installed candidate from the public version', async ({}, info) => {
  test.skip(process.platform !== 'darwin');
  const root = mkdtempSync(path.join(realpathSync(os.tmpdir()), 'profiles-update-check-e2e-'));
  let app: ElectronApplication | undefined, server: Server | undefined;
  let versionRequests = 0,
    archiveRequests = 0;
  const publicVersion = '3.0.0-alpha.22';
  const manifest = {
    schema: 1,
    version: publicVersion,
    publishedAt: new Date().toISOString(),
    notes: 'Owned local manifest only',
    files: {
      arm64: {
        url: `https://github.com/ForceMind/ChatGPT/releases/download/v${publicVersion}/ChatGPT-Profiles-${publicVersion}-mac-arm64.zip`,
        size: 1,
        sha256: '0'.repeat(64)
      }
    }
  };
  try {
    server = createServer((request, response) => {
      if (request.url === '/version.json') {
        versionRequests++;
        response.writeHead(versionRequests === 1 ? 503 : 200, { 'Content-Type': 'application/json' });
        response.end(
          versionRequests === 1 ? JSON.stringify({ error: 'synthetic unavailable' }) : JSON.stringify(manifest)
        );
        return;
      }
      if (request.url === '/archive.zip') {
        archiveRequests++;
        response.writeHead(500);
        response.end();
        return;
      }
      response.writeHead(200, { 'Content-Type': 'text/html;charset=utf-8' });
      response.end(
        '<!doctype html><meta charset="utf-8"><title>Manual check fixture</title><article data-message-id="one">Synthetic local page</article><textarea id="prompt-textarea">Unsent</textarea>'
      );
    });
    await new Promise<void>((resolve) => server!.listen(0, '127.0.0.1', resolve));
    const address = server.address();
    if (!address || typeof address === 'string') throw Error('No owned update fixture origin');
    app = await electron.launch({
      args: [desktop],
      env: {
        ...process.env,
        PROFILES_E2E: '1',
        PROFILES_TEST_DATA: path.join(root, 'data'),
        PROFILES_FIXTURE_ORIGIN: 'http://127.0.0.1:' + address.port,
        PROFILES_UPDATES_FIXTURE: '1'
      }
    });
    const shell = await shellWindow(app);
    const status = async () => (await shell.evaluate(() => window.profiles.snapshot())).update;
    await expect.poll(async () => (await status())?.phase).toBe('error');
    const failure = await status();
    expect(failure?.message).toContain('503');
    await shell.getByRole('button', { name: '查看应用更新', exact: true }).click();
    const card = shell.getByRole('group', { name: '应用更新', exact: true });
    await expect(card).toHaveAttribute('data-update-phase', 'error');
    await expect(card).toContainText(failure!.message!);
    await expect(card.getByRole('menuitem', { name: '检查更新', exact: true })).toBeEnabled();
    const beforeRetry = versionRequests;
    await card.getByRole('menuitem', { name: '检查更新', exact: true }).click();
    await expect.poll(() => versionRequests).toBeGreaterThan(beforeRetry);
    await expect.poll(async () => (await status())?.phase).toBe('idle');
    expect((await status())?.version).toBe(publicVersion);
    expect((await status())?.message).toBe('当前版本较新，官网最新公开版本为 ' + publicVersion);
    await shell.getByRole('button', { name: '切换账号', exact: true }).click();
    await expect(card).toHaveAttribute('data-update-phase', 'idle');
    await expect(card).toContainText('当前版本 ' + packageInfo.version);
    await expect(card).toContainText('官网公开版本 ' + publicVersion);
    await expect(card).toContainText('当前版本较新');
    await expect(card.getByRole('menuitem', { name: '检查更新', exact: true })).toBeEnabled();
    const beforeIdleCheck = versionRequests;
    await card.getByRole('menuitem', { name: '检查更新', exact: true }).click();
    await expect.poll(() => versionRequests).toBeGreaterThan(beforeIdleCheck);
    await expect.poll(async () => (await status())?.phase).toBe('idle');
    expect(archiveRequests).toBe(0);
    await info.attach('manual-check-retry.json', {
      contentType: 'application/json',
      body: JSON.stringify({ firstFailure: failure, final: await status(), versionRequests, archiveRequests })
    });
  } finally {
    await app?.close().catch(() => {});
    if (server) {
      server.closeAllConnections();
      await new Promise<void>((resolve) => server!.close(() => resolve()));
    }
    rmSync(root, { recursive: true, force: true });
  }
});
