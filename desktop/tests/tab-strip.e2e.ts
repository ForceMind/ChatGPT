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
    let wideCount = 0;
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
        wideCount = await tabButtons.count();
      }
    }
    expect(wideCount).toBeGreaterThan(1);

    await shellWindow(app, { width: 800, height: 640 });
    const more = shell.getByRole('button', { name: /^更多标签 \(\d+\)$/ });
    if (!mac) {
      await expect(more).toBeVisible();
      await expect.poll(() => tabButtons.count()).toBeLessThan(wideCount);
    }
    await expect(shell.getByRole('tab', { selected: true })).toHaveText('Overflow fixture 8');
    const layout = () =>
      shell.locator('.tab-strip').evaluate((strip) => {
        const bounds = strip.getBoundingClientRect();
        const items = [...strip.querySelectorAll('.tab, .more-tabs')].map((node) => node.getBoundingClientRect());
        return {
          fullyVisible: items.every((item) => item.left >= bounds.left - 1 && item.right <= bounds.right + 1),
          withinWindow: bounds.left >= 0 && bounds.right <= innerWidth,
          widths: items.slice(0, -1).map((item) => item.width)
        };
      });
    const verifyLayout = async () => {
      await expect.poll(layout).toMatchObject({ fullyVisible: true, withinWindow: true });
      const { widths } = await layout();
      expect(widths.length).toBeGreaterThan(0);
      expect(
        widths.every((width) => width >= 131 && width <= 220),
        JSON.stringify(widths)
      ).toBe(true);
    };
    if (!mac) await verifyLayout();
    await shell.locator('.current-account').evaluate((account) => {
      const update = document.createElement('button');
      update.className = 'update-status-button';
      update.textContent = '模拟更新进度';
      account.before(update);
    });
    await expect(shell.getByRole('tab', { selected: true })).toHaveText('Overflow fixture 8');
    if (!mac) await verifyLayout();

    const sendPickerKey = (keyCode: 'Enter' | 'Escape', title = '全部标签') =>
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
      await expect.poll(async () => (await position()).visible).toBeGreaterThan(1);
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
      return;
    }

    await expect(tabButtons).toHaveCount(1);
    expect(await shell.locator('.tab.active').evaluate((item) => getComputedStyle(item).borderTopWidth)).toBe('2px');
    const order = (await snapshot()).tabs.map((item) => item.title);
    await expect(more).toContainText('(' + order.length + ')');
    const openPicker = async () => {
      const opening = app!.waitForEvent('window');
      await more.focus();
      await more.press('Enter');
      const picker = await opening;
      await expect(picker.getByRole('menu', { name: '全部标签' })).toBeVisible();
      return picker;
    };
    const picker = await openPicker();
    const items = picker.getByRole('menuitemradio');
    const titles = () => items.locator('span:first-child').allTextContents();
    expect(await titles()).toEqual(order);
    const selected = picker.getByRole('menuitemradio', { checked: true });
    await expect(selected).toContainText('✓ 当前');
    await expect(selected).toContainText('Overflow fixture 8');
    expect(await selected.evaluate((item) => getComputedStyle(item).borderTopColor)).toBe('rgb(37, 37, 37)');
    await expect(selected).toBeFocused();
    await selected.press('ArrowUp');
    const target = picker.getByRole('menuitemradio', { name: 'Overflow fixture 7' });
    await expect(target).toBeFocused();
    const closed = picker.waitForEvent('close');
    await sendPickerKey('Enter');
    await closed;
    const prior = known.get('Overflow fixture 7')!;
    await expect.poll(active).toBe(prior.id);
    await expect(shell.getByRole('tab', { selected: true })).toHaveText('Overflow fixture 7');
    await expect.poll(() => inspectPage('7')).toEqual(prior.page);
    const reopened = await openPicker();
    expect(await reopened.getByRole('menuitemradio').locator('span:first-child').allTextContents()).toEqual(order);
    const current = reopened.getByRole('menuitemradio', { checked: true });
    await expect(current).toContainText('Overflow fixture 7');
    const dismissed = reopened.waitForEvent('close');
    await sendPickerKey('Escape');
    await dismissed;
    expect(await active()).toBe(prior.id);
    for (let number = 1; number <= 8; number++) expect(loads.get(String(number))).toBe(1);
  } finally {
    await app?.close();
    server.closeAllConnections();
    await new Promise<void>((resolve) => server.close(() => resolve()));
    rmSync(data, { recursive: true, force: true });
  }
});
