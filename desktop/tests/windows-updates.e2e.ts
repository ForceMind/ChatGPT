import { test, expect, _electron as electron, type ElectronApplication } from '@playwright/test';
import { createServer } from 'node:http';
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { mkdtempSync, readFileSync, writeFileSync, rmSync, existsSync } from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { fileURLToPath } from 'node:url';
import { shellWindow } from './helpers/shell';
import packageInfo from '../package.json' with { type: 'json' };
const desktop = path.resolve(fileURLToPath(new URL('..', import.meta.url)));
const next = `${Number(packageInfo.version.split('.')[0]) + 1}.0.0`;
const digest = (bytes: Buffer, algorithm: string, encoding: 'hex' | 'base64') =>
  createHash(algorithm).update(bytes).digest(encoding);
for (const mode of ['automatic', 'manual'] as const)
  test('Windows ' + mode + ' update uses verified native SDK or explicit ZIP save', async ({}, info) => {
    test.skip(process.platform !== 'win32');
    const root = mkdtempSync(path.join(os.tmpdir(), 'studio-win-update-e2e-'));
    let app: ElectronApplication | undefined;
    const marker = path.join(root, 'installer-ran.json'),
      saved = path.join(root, 'saved.zip'),
      source = path.join(root, 'fixture.cs'),
      installer = path.join(root, 'fixture.exe');
    writeFileSync(
      source,
      `using System;using System.IO;using System.Reflection;[assembly:AssemblyProduct("ChatGPT Studio")][assembly:AssemblyVersion("3.0.0.0")][assembly:AssemblyInformationalVersion("${next}")]class Fixture{static void Main(string[] args){File.WriteAllText(Environment.GetEnvironmentVariable("STUDIO_INSTALL_MARKER"),String.Join("\\n",args));}}`
    );
    execFileSync(
      path.join(process.env.SystemRoot!, 'Microsoft.NET', 'Framework64', 'v4.0.30319', 'csc.exe'),
      ['/nologo', '/target:winexe', '/out:' + installer, source],
      { stdio: 'pipe' }
    );
    const exe = readFileSync(installer),
      zip = Buffer.from(
        'UEsDBBQAAAAIAEIFW11RwddZNwAAADcAAAALAAAAZml4dHVyZS50eHQLSy3KTMtMTVEIz8xLyS8vVojyDFAAMvJy8hNTFNIyK0pKi1KtFfJSy1KLFDLziksSc3JSU/QAUEsBAhQDFAAAAAgAQgVbXVHB11k3AAAANwAAAAsAAAAAAAAAAAAAAIABAAAAAGZpeHR1cmUudHh0UEsFBgAAAAABAAEAOQAAAGAAAAAAAA==',
        'base64'
      );
    const manifest = {
      schema: 1,
      appId: 'com.forcemind.chatgpt.profiles',
      platform: 'win32',
      architecture: 'x64',
      version: next,
      releaseDate: new Date().toISOString(),
      releaseNotes: 'Local update fixture, not a published version',
      files: [
        {
          url: `https://github.com/ForceMind/ChatGPT/releases/download/v${next}/ChatGPT-Studio-${next}-windows-x64-setup.exe`,
          size: exe.length,
          sha256: digest(exe, 'sha256', 'hex'),
          sha512: digest(exe, 'sha512', 'base64')
        },
        {
          url: `https://github.com/ForceMind/ChatGPT/releases/download/v${next}/ChatGPT-Studio-${next}-windows-x64.zip`,
          size: zip.length,
          sha256: digest(zip, 'sha256', 'hex'),
          sha512: digest(zip, 'sha512', 'base64')
        }
      ]
    };
    let release!: () => void,
      exeRequests = 0,
      zipRequests = 0;
    const gate = new Promise<void>((resolve) => {
      release = resolve;
    });
    let finishDownload!: () => void;
    const transferGate = new Promise<void>((resolve) => {
      finishDownload = resolve;
    });
    const server = createServer(async (q, r) => {
      if (q.url?.startsWith('/updates/windows/alpha.yml')) {
        await gate;
        if (r.destroyed) return;
        r.writeHead(200, { 'Content-Type': 'text/yaml' });
        r.end(JSON.stringify(manifest));
        return;
      }
      if (q.url === '/installer.exe' || q.url === '/archive.zip') {
        const body = q.url === '/installer.exe' ? exe : zip;
        if (q.url === '/installer.exe') exeRequests++;
        else zipRequests++;
        r.writeHead(200, { 'Content-Length': body.length });
        if (mode === 'automatic' && q.url === '/installer.exe') {
          r.flushHeaders();
          r.write(body.subarray(0, 1));
          await new Promise((resolve) => setTimeout(resolve, 1200));
          r.write(body.subarray(1, Math.floor(body.length / 2)));
          await transferGate;
          if (!r.destroyed) r.end(body.subarray(Math.floor(body.length / 2)));
          return;
        }
        r.end(body);
        return;
      }
      r.writeHead(200, { 'Content-Type': 'text/html;charset=utf-8', 'Cache-Control': 'no-store' });
      r.end(
        '<!doctype html><title>Windows update fixture</title><article data-message-author-role="assistant">Before update</article><textarea id="prompt-textarea"></textarea>'
      );
    });
    await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
    const addr = server.address();
    if (!addr || typeof addr === 'string') throw Error('fixture');
    const origin = 'http://127.0.0.1:' + addr.port,
      data = path.join(root, 'data');
    try {
      app = await test.step('launch isolated Windows application', () =>
        electron.launch({
          timeout: 20000,
          args: [desktop],
          env: {
            ...process.env,
            PROFILES_E2E: '1',
            PROFILES_TEST_DATA: data,
            PROFILES_FIXTURE_ORIGIN: origin,
            PROFILES_UPDATES_FIXTURE: '1',
            PROFILES_WINDOWS_UPDATE_MANUAL: mode === 'manual' ? '1' : '0',
            STUDIO_INSTALL_MARKER: marker
          }
        }));
      const shell = await shellWindow(app);
      await app.evaluate(({ app, dialog }, saved) => {
        const a = app as any;
        a.updateDialogs = [];
        a.acceptDownload = false;
        dialog.showMessageBox = (async (_w: any, o: any) => {
          a.updateDialogs.push(o);
          return {
            response: o.title === '发现新版本' ? (a.acceptDownload ? 1 : 0) : o.title === '免安装更新已准备' ? 0 : 0,
            checkboxChecked: false
          };
        }) as typeof dialog.showMessageBox;
        dialog.showSaveDialog = (async () => ({ canceled: false, filePath: saved })) as typeof dialog.showSaveDialog;
      }, saved);
      release();
      await expect
        .poll(async () => {
          const s = (await shell.evaluate(() => window.profiles.snapshot())).update;
          if (s?.phase === 'error') throw Error(s.message);
          return s?.phase;
        })
        .toBe('available');
      expect(exeRequests).toBe(0);
      expect(zipRequests).toBe(0);
      const before = await shell.evaluate(() => window.profiles.snapshot());
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
          'document.querySelector("textarea").value="Windows pre-update unsent draft"'
        );
      });
      await app.evaluate(({ app }) => {
        const target = app as any;
        target.acceptDownload = true;
        target.updateTransferEvents = [];
        // eslint-disable-next-line @typescript-eslint/no-require-imports -- Serialized callback observes the existing Electron main-process updater module.
        const prototype = require('electron-updater').NsisUpdater.prototype;
        const emit = prototype.emit;
        prototype.emit = function (event: string, ...args: any[]) {
          if (event === 'download-progress') target.updateTransferEvents.push({ event, progress: args[0] });
          if (event === 'error')
            target.updateTransferEvents.push({ event, code: args[0]?.code, message: args[0]?.message });
          return Reflect.apply(emit, this, [event, ...args]);
        };
      });
      await shell.evaluate(() => window.profiles.command({ type: 'update:check' }));
      if (mode === 'automatic') {
        await expect
          .poll(async () => {
            const status = (await shell.evaluate(() => window.profiles.snapshot())).update;
            if (status?.phase === 'error') throw Error(JSON.stringify(status));
            return status?.transferredBytes || 0;
          })
          .toBeGreaterThan(0);
        const header = shell.getByRole('button', { name: '查看应用更新', exact: true });
        await header.click();
        const card = shell.getByRole('group', { name: '应用更新', exact: true });
        await expect(card).toHaveAttribute('data-update-stage', 'download');
        await expect(card.locator('.update-bytes')).toContainText('MB');
        await expect(header).toContainText('%');
        await header.click();
        await expect(card).toHaveCount(0);
        await expect(header).toContainText('%');
        await header.click();
        await expect(card.locator('.update-bytes')).toContainText('MB');
        await header.click();
        finishDownload();
        await expect
          .poll(async () => {
            const s = (await shell.evaluate(() => window.profiles.snapshot())).update;
            if (s?.phase === 'error') throw Error(s.message);
            return s?.phase;
          })
          .toBe('ready');
        expect(exeRequests).toBe(1);
        expect(zipRequests).toBe(0);
        expect(existsSync(marker)).toBe(false);
        await shell.locator('.current-account').click();
        await expect(shell.getByRole('menuitem', { name: /更新 .* 已准备/ })).toBeVisible();
        if (!process.env.STUDIO_UPDATE_NO_SCREENSHOTS)
          await shell.screenshot({ path: info.outputPath('windows-update-ready.png') });
      } else {
        await expect.poll(() => existsSync(saved)).toBe(true);
        expect(readFileSync(saved).equals(zip)).toBe(true);
        expect(exeRequests).toBe(0);
        expect(zipRequests).toBe(1);
        expect(existsSync(marker)).toBe(false);
      }
      const closing = app.waitForEvent('close');
      await app.evaluate(({ app }) => app.quit());
      await closing;
      app = undefined;
      const state = JSON.parse(readFileSync(path.join(data, 'state.json'), 'utf8'));
      expect(state.activeProfileId).toBe(before.activeProfileId);
      expect(state.tabs.map((t: any) => t.id)).toEqual(before.tabs.map((t) => t.id));
      const profile = before.profiles.find((p) => p.id === before.activeProfileId)!;
      const encrypted = readFileSync(path.join(data, 'recovery', profile.id, profile.activeTabId + '.bin'));
      expect(encrypted.includes(Buffer.from('Windows pre-update unsent draft'))).toBe(false);
      if (mode === 'automatic') {
        await expect.poll(() => existsSync(marker)).toBe(true);
        const args = readFileSync(marker, 'utf8');
        expect(args).toContain('/S');
        expect(args).toContain('--updated');
        expect(args).not.toContain('--force-run');
      } else expect(existsSync(marker)).toBe(false);
    } finally {
      if (app) {
        try {
          const shell = await shellWindow(app);
          const status = (await shell.evaluate(() => window.profiles.snapshot())).update;
          const events = await app.evaluate(({ app }) => (app as any).updateTransferEvents);
          await info.attach('actual-update-transfer.json', {
            contentType: 'application/json',
            body: JSON.stringify({ status, events, exeRequests, zipRequests })
          });
        } catch {
          /* Preserve the original failure if the test application already exited. */
        }
      }
      release();
      finishDownload();
      await app?.close().catch(() => {});
      server.closeAllConnections();
      await new Promise<void>((resolve) => server.close(() => resolve()));
      rmSync(root, { recursive: true, force: true });
    }
  });
