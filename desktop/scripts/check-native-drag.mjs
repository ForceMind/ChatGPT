import { _electron } from '@playwright/test';
import { createServer } from 'node:http';
import { existsSync, mkdtempSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

// A bounded native-input acceptance check. Bounds are set only for setup;
// success requires an actual move after baseline, without JS moving the window.
const root = fileURLToPath(new URL('..', import.meta.url));
const evidencePath = path.join(root, 'test-results/native-drag-evidence.json');
const dataDir = mkdtempSync(path.join(tmpdir(), 'profiles-native-drag-'));
const armPath = path.join(dataDir, 'arm-native-drag.json');
const server = createServer((_request, response) => {
  response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
  response.end('<!doctype html><html lang="zh-CN"><meta charset="utf-8"><title>本地窗口拖动验收</title><style>body{margin:0;font:14px -apple-system,sans-serif;color:#171717;background:white}main{padding:100px 80px}small{color:#999}h1{font-size:28px;font-weight:500}p{color:#888;line-height:1.8}</style><main><small>本地验收页面 · 不含真实账号</small><h1>拖动顶部空白区，移动这个窗口。</h1><p>本检查不会通过代码移动窗口来制造成功。<br>验证结束后，临时窗口将自动关闭。</p></main></html>');
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const origin = `http://127.0.0.1:${server.address().port}`;
const env = Object.fromEntries(Object.entries(process.env).filter(([, value]) => value !== undefined));
delete env.ELECTRON_RUN_AS_NODE;
let application;
try {
  application = await _electron.launch({ args: [root], env: { ...env, ELECTRON_DEBUG_DRAGGABLE_REGIONS: '1', PROFILES_E2E: '1', PROFILES_TEST_DATA: dataDir, PROFILES_FIXTURE_ORIGIN: origin } });
  const page = await application.firstWindow();
  await page.getByRole('button', { name: '切换账号', exact: true }).waitFor();
  await page.evaluate(async () => { const s = await window.profiles.snapshot(); await window.profiles.command({ type: 'profile:edit', id: s.activeProfileId, name: '窗口拖动验收', email: '', color: '#667485' }); });
  await application.evaluate(({ BrowserWindow }) => {
    const w = BrowserWindow.getAllWindows()[0]; w.setBounds({ x: 160, y: 140, width: 1000, height: 650 }); w.show(); w.focus();
  });
  await new Promise(resolve => setTimeout(resolve, 500));
  const readWindow = () => application.evaluate(({ BrowserWindow, screen }) => {
    const w = BrowserWindow.getAllWindows()[0];
    return { bounds: w.getBounds(), contentBounds: w.getContentBounds(), movable: w.isMovable(), maximized: w.isMaximized(), fullScreen: w.isFullScreen(), scaleFactor: screen.getDisplayMatching(w.getBounds()).scaleFactor };
  });
  const before = await readWindow();
  const dragRegion = await page.getByLabel('窗口拖动区域').evaluate(element => { const r = element.getBoundingClientRect(); return { x: r.x, y: r.y, width: r.width, height: r.height, appRegion: getComputedStyle(element).getPropertyValue('-webkit-app-region') }; });
  await page.evaluate(() => {
    window.__dragCheckEvents = [];
    for (const type of ['mousedown', 'mouseup']) document.addEventListener(type, event => { window.__dragCheckEvents.push({ type, target: event.target.className?.baseVal ?? event.target.className, x: event.clientX, y: event.clientY, screenX: event.screenX, screenY: event.screenY }); }, true);
  });
  await application.evaluate(({ BrowserWindow }) => {
    const w=BrowserWindow.getAllWindows()[0],v=w.contentView.children.find(v=>v.getVisible());
    return v.webContents.executeJavaScript("window.__dragCheckEvents=[];for(const type of ['mousedown','mouseup'])document.addEventListener(type,event=>window.__dragCheckEvents.push({type,x:event.clientX,y:event.clientY,screenX:event.screenX,screenY:event.screenY}),true);");
  });
  mkdirSync(path.dirname(evidencePath), { recursive: true });
  const initial = { status: 'waiting-native-input', dataDir, armPath, pid: application.process().pid, before, dragRegion };
  writeFileSync(evidencePath, JSON.stringify(initial, null, 2)); console.log(JSON.stringify(initial));
  let verified = false;
  for (let i = 0; i < 360; i++) {
    await new Promise(resolve => setTimeout(resolve, 1000));
    const after = await readWindow();
    const events = await page.evaluate(() => window.__dragCheckEvents.slice(-12));
    const remoteEvents=await application.evaluate(({BrowserWindow})=>{const v=BrowserWindow.getAllWindows()[0].contentView.children.find(v=>v.getVisible());return v?.webContents.executeJavaScript('window.__dragCheckEvents?.slice(-12)||[]')||[];});
    const arm=existsSync(armPath)?JSON.parse(readFileSync(armPath,'utf8')):undefined;
    verified = !!arm && !after.fullScreen && !after.maximized && after.bounds.width === before.bounds.width && after.bounds.height === before.bounds.height && Math.abs(after.bounds.x-arm.before.x-arm.delta.x)<=3 && Math.abs(after.bounds.y-arm.before.y-arm.delta.y)<=3;
    writeFileSync(evidencePath, JSON.stringify({ ...initial, status: verified ? 'passed' : 'waiting-native-input', after, events, remoteEvents, arm }, null, 2));
    if (verified) { console.log('Native window move verified.'); break; }
  }
  if (!verified) { console.error('Native drag remains unverified.'); process.exitCode = 1; }
} finally {
  if (application) await application.close().catch(() => {});
  server.closeAllConnections(); await new Promise(resolve => server.close(resolve));
}
