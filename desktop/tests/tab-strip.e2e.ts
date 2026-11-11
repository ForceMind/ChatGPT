import { test, expect, _electron as electron, type ElectronApplication } from '@playwright/test';
import { createServer } from 'node:http';
import { mkdtempSync, rmSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { shellWindow } from './helpers/shell';

test.use({ screenshot: 'off', trace: 'off', video: 'off' });

test('narrow tab strip scrolls both ways and switches side-menu tabs without reloading or losing drafts', async () => {
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
    const shell = await shellWindow(app, { width: 1000, height: 640 });
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

    const mac = process.platform === 'darwin';
    const tabButtons = shell.getByRole('tab');
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
      if (number === 2) {
        await expect(tabButtons).toHaveCount(3);
        await expect(shell.getByRole('button', { name: /^(左侧|右侧)标签 / })).toHaveCount(0);
      }
    }

    await shellWindow(app, { width: 800, height: 640 });
    await expect(shell.getByRole('tab', { selected: true })).toHaveText('Overflow fixture 8');
    await expect(shell.getByRole('button', { name: /^更多标签/ })).toHaveCount(0);
    await shell.locator('.current-account').evaluate((account) => {
      const update = document.createElement('button');
      update.className = 'update-status-button';
      update.textContent = '模拟更新进度';
      account.before(update);
    });
    await expect(shell.getByRole('tab', { selected: true })).toHaveText('Overflow fixture 8');

    const sendPickerKey = (keyCode: 'Enter', title: string) =>
      app!.evaluate(
        ({ BrowserWindow }, { key, title }) => {
          const popup = BrowserWindow.getAllWindows().find((window) => window.getTitle() === title);
          if (!popup || !popup.isFocused()) throw Error('Tab picker is not focused');
          popup.webContents.sendInputEvent({ type: 'keyDown', keyCode: key });
          if (!popup.isDestroyed()) popup.webContents.sendInputEvent({ type: 'keyUp', keyCode: key });
        },
        { key: keyCode, title }
      );

    if (mac) {
      const controls = await shell.locator('.titlebar').evaluate((titlebar) => {
        const traffic = titlebar.querySelector('.traffic-space')!.getBoundingClientRect();
        const tabs = titlebar.querySelector('.tab-strip')!.getBoundingClientRect();
        const drag = titlebar.querySelector('.drag-space')!;
        return {
          trafficReserved: traffic.width >= 80 && tabs.left >= traffic.right,
          dragWidth: drag.getBoundingClientRect().width,
          dragRegion: getComputedStyle(drag).getPropertyValue('-webkit-app-region')
        };
      });
      const nativeButtons = await app.evaluate(({ BrowserWindow }) =>
        BrowserWindow.getAllWindows()
          .find((window) => window.webContents.getURL() === 'app://shell/index.html')!
          .getWindowButtonPosition()
      );
      expect(nativeButtons).not.toBeNull();
      expect(nativeButtons!.x).toBeGreaterThanOrEqual(0);
      expect(nativeButtons!.x).toBeLessThan(80);
      expect(controls.trafficReserved).toBe(true);
      expect(controls.dragWidth).toBeGreaterThanOrEqual(32);
      expect(controls.dragRegion).toBe('drag');
    } else {
      const controls = await shell.locator('.titlebar').evaluate((titlebar) => {
        const tabs = titlebar.querySelector('.tab-strip')!.getBoundingClientRect();
        const drag = titlebar.querySelector('.drag-space')!;
        return {
          tabsWithinContent: tabs.left >= 0 && tabs.right <= innerWidth - 138,
          captionPadding: parseFloat(getComputedStyle(titlebar).paddingRight),
          dragWidth: drag.getBoundingClientRect().width
        };
      });
      expect(controls.tabsWithinContent).toBe(true);
      expect(controls.captionPadding).toBeGreaterThanOrEqual(150);
      expect(controls.dragWidth).toBeGreaterThanOrEqual(32);
    }
    const track = shell.getByRole('tablist');
    const left = shell.getByRole('button', { name: /^左侧标签 / });
    const right = shell.getByRole('button', { name: /^右侧标签 / });
    const order = (await snapshot()).tabs.map((item) => item.title);
    await expect(tabButtons).toHaveCount(order.length);
    await expect(left).toBeVisible();
    await expect(right).toHaveCount(0);
    const position = () =>
      track.evaluate((viewport) => {
        const clip = viewport.getBoundingClientRect();
        const tabs = [...viewport.querySelectorAll('.tab')];
        return {
          scroll: viewport.scrollLeft,
          viewportWidth: clip.width,
          tabWidth: tabs[0]?.getBoundingClientRect().width,
          visible: tabs.filter((tab) => {
            const rect = tab.getBoundingClientRect();
            return rect.left >= clip.left - 1 && rect.right <= clip.right + 1;
          }).length,
          activeVisible: tabs.some((tab) => {
            const rect = tab.getBoundingClientRect();
            return tab.classList.contains('active') && rect.left >= clip.left - 1 && rect.right <= clip.right + 1;
          }),
          leftTitles: tabs
            .filter((tab) => tab.getBoundingClientRect().left < clip.left - 0.5)
            .map((tab) => tab.querySelector('.tab-select > span:last-of-type')?.textContent),
          rightTitles: tabs
            .filter((tab) => tab.getBoundingClientRect().right > clip.right + 0.5)
            .map((tab) => tab.querySelector('.tab-select > span:last-of-type')?.textContent)
        };
      });
    const chooseFromEdge = async (side: 'left' | 'right', id: string) => {
      const title = side === 'left' ? '左侧标签' : '右侧标签';
      const trigger = side === 'left' ? left : right;
      const expected = (await position())[side === 'left' ? 'leftTitles' : 'rightTitles'];
      const opening = app!.waitForEvent('window');
      await trigger.press('Enter');
      const picker = await opening;
      await expect(picker.getByRole('menu', { name: title })).toBeVisible();
      expect(await picker.getByRole('menuitemradio').locator('span:first-child').allTextContents()).toEqual(expected);
      const target = 'Overflow fixture ' + id;
      await picker.getByRole('menuitemradio', { name: target, exact: true }).focus();
      const closed = picker.waitForEvent('close');
      await sendPickerKey('Enter', title);
      await closed;
      const prior = known.get(target)!;
      await expect.poll(active).toBe(prior.id);
      await expect.poll(async () => (await position()).activeVisible).toBe(true);
      expect((await snapshot()).tabs.map((item) => item.title)).toEqual(order);
      await expect.poll(() => inspectPage(id)).toEqual(prior.page);
    };
    await expect
      .poll(async () => (await position()).visible, { message: JSON.stringify(await position()) })
      .toBeGreaterThan(1);
    await expect.poll(async () => (await position()).activeVisible).toBe(true);
    await chooseFromEdge('left', '2');
    await track.hover();
    await shell.mouse.wheel(0, -10000);
    await expect.poll(async () => (await position()).scroll).toBe(0);
    await expect(left).toHaveCount(0);
    await expect(right).toBeVisible();
    await shell.mouse.wheel(0, 250);
    await expect(left).toBeVisible();
    await expect(right).toBeVisible();
    await chooseFromEdge('right', '7');
    for (let number = 1; number <= 8; number++) expect(loads.get(String(number))).toBe(1);
  } finally {
    await app?.close();
    server.closeAllConnections();
    await new Promise<void>((resolve) => server.close(() => resolve()));
    rmSync(data, { recursive: true, force: true });
  }
});
