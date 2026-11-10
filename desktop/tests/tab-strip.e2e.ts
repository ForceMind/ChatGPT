import { test, expect, _electron as electron, type ElectronApplication } from '@playwright/test';
import { createServer } from 'node:http';
import { mkdtempSync, rmSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { shellWindow } from './helpers/shell';

test.use({ screenshot: 'off', trace: 'off', video: 'off' });

test('narrow tab strip keeps the active tab and switches overflow tabs without loading or losing drafts', async () => {
  test.skip(!['darwin', 'win32'].includes(process.platform));
  const desktop = path.resolve(fileURLToPath(new URL('..', import.meta.url)));
  const data = mkdtempSync(path.join(os.tmpdir(), 'studio-tab-strip-'));
  const loads = new Map<string, number>();
  const server = createServer((request, response) => {
    const url = new URL(request.url || '/', 'http://127.0.0.1');
    const number = /^\/c\/overflow-(\d+)$/.exec(url.pathname)?.[1];
    if (number) loads.set(number, (loads.get(number) || 0) + 1);
    response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store' });
    response.end(
      `<!doctype html><meta charset="utf-8"><title>${number ? 'Overflow fixture ' + number : 'Local fixture home'}</title>
      <textarea id="prompt-textarea"></textarea><script>window.fixtureInstance = crypto.randomUUID()</script>`
    );
  });
  let app: ElectronApplication | undefined;
  try {
    await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
    const address = server.address();
    if (!address || typeof address === 'string') throw Error('Missing loopback fixture address');
    const origin = 'http://127.0.0.1:' + address.port;
    app = await electron.launch({
      args: [desktop],
      env: { ...process.env, PROFILES_E2E: '1', PROFILES_TEST_DATA: data, PROFILES_FIXTURE_ORIGIN: origin }
    });
    const shell = await shellWindow(app, { width: 1160, height: 640 });
    const snapshot = () => shell.evaluate(() => window.profiles.snapshot());
    const active = async () => {
      const state = await snapshot();
      return state.profiles.find((profile) => profile.id === state.activeProfileId)!.activeTabId;
    };
    const inspectPage = (id: string, draft?: string) =>
      app!.evaluate(
        async ({ BrowserWindow }, { id, draft, origin }) => {
          const win = BrowserWindow.getAllWindows().find(
            (window) => window.webContents.getURL() === 'app://shell/index.html'
          )!;
          const view = (win.contentView.children as Electron.WebContentsView[]).find((item) => item.getVisible());
          const contents = view?.webContents;
          if (!contents || contents.getURL() !== origin + '/c/overflow-' + id || contents.isLoading()) return null;
          const page = await contents.executeJavaScript(`(() => {
            const composer = document.querySelector('#prompt-textarea');
            if (!composer || !window.fixtureInstance) return null;
            const draft = ${JSON.stringify(draft ?? null)};
            if (draft !== null) {
              composer.value = draft;
              composer.dispatchEvent(new Event('input', { bubbles: true }));
            }
            return { instance: window.fixtureInstance, draft: composer.value };
          })()`);
          return page ? { ...page, contentsId: contents.id } : null;
        },
        { id, draft, origin }
      );

    const known = new Map<string, { id: string; page: unknown }>();
    for (let number = 1; number <= 8; number++) {
      const suffix = String(number);
      const result = await shell.evaluate(
        (url) => window.profiles.command({ type: 'tab:new', url }),
        origin + '/c/overflow-' + suffix
      );
      expect(result.ok).toBe(true);
      await expect.poll(() => inspectPage(suffix)).not.toBeNull();
      const title = 'Overflow fixture ' + suffix;
      await expect.poll(async () => (await snapshot()).tabs.some((tab) => tab.title === title)).toBe(true);
      const draft = '未发送草稿 ' + suffix;
      const page = await inspectPage(suffix, draft);
      expect(page).toMatchObject({ draft, instance: expect.any(String), contentsId: expect.any(Number) });
      known.set(title, { id: await active(), page });
    }
    const tabButtons = shell.getByRole('tab', { exact: false });
    const wideCount = await tabButtons.count();
    expect(wideCount).toBeGreaterThan(1);

    await shellWindow(app, { width: 800, height: 640 });
    const more = shell.getByRole('button', { name: /^更多标签 \(\d+\)$/ });
    await expect(more).toBeVisible();
    await expect.poll(() => tabButtons.count()).toBeLessThan(wideCount);
    await expect(shell.getByRole('tab', { selected: true })).toHaveText('Overflow fixture 8');
    const layout = await shell.locator('.tab-strip').evaluate((strip) => {
      const bounds = strip.getBoundingClientRect();
      const items = [...strip.querySelectorAll('.tab, .more-tabs')].map((node) => node.getBoundingClientRect());
      return {
        fullyVisible: items.every((item) => item.left >= bounds.left - 1 && item.right <= bounds.right + 1),
        withinWindow: bounds.left >= 0 && bounds.right <= innerWidth,
        widths: items.slice(0, -1).map((item) => item.width)
      };
    });
    expect(layout.fullyVisible).toBe(true);
    expect(layout.withinWindow).toBe(true);
    expect(layout.widths.every((width) => width >= 131 && width <= 220)).toBe(true);

    // Intercept only this fixture's platform popup at the native boundary.
    // The real shell command builds the menu and its real item callback performs selection.
    await app.evaluate(({ Menu }) => {
      const nativePopup = Menu.prototype.popup;
      Menu.prototype.popup = function (this: Electron.Menu, options) {
        if (this.items.some((item) => item.label.startsWith('Overflow fixture '))) {
          (globalThis as { studioFixtureOverflow?: Electron.Menu }).studioFixtureOverflow = this;
          return;
        }
        nativePopup.call(this, options);
      };
    });
    await more.focus();
    await more.press('Enter');
    const menuLabels = () =>
      app!.evaluate(() =>
        (globalThis as { studioFixtureOverflow?: Electron.Menu }).studioFixtureOverflow?.items.map((item) => item.label)
      );
    await expect.poll(menuLabels).toBeTruthy();
    const labels = (await menuLabels())!;
    const shown = await tabButtons.allTextContents();
    expect(labels).toEqual((await snapshot()).tabs.map((tab) => tab.title).filter((title) => !shown.includes(title)));
    expect(await more.textContent()).toContain('(' + labels.length + ')');
    const target = labels.find((title) => known.has(title));
    expect(target).toBeTruthy();
    const prior = known.get(target!)!;
    await app.evaluate((_electron, title) => {
      const menu = (globalThis as { studioFixtureOverflow?: Electron.Menu }).studioFixtureOverflow;
      const item = menu?.items.find((row) => row.label === title);
      if (!item) throw Error('Overflow menu target missing');
      item.click();
    }, target!);
    await expect.poll(active).toBe(prior.id);
    await expect(shell.getByRole('tab', { selected: true })).toHaveText(target!);
    await expect.poll(() => inspectPage(target!.split(' ').at(-1)!)).toEqual(prior.page);
    for (let number = 1; number <= 8; number++) expect(loads.get(String(number))).toBe(1);
  } finally {
    await app?.close();
    server.closeAllConnections();
    await new Promise<void>((resolve) => server.close(() => resolve()));
    rmSync(data, { recursive: true, force: true });
  }
});
