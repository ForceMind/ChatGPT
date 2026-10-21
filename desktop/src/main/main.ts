import { app, BrowserWindow, WebContentsView, ipcMain, protocol, dialog, Menu, shell, session, screen, systemPreferences, type WebContents, type Session, type IpcMainInvokeEvent } from 'electron';
import { randomUUID } from 'node:crypto';
import { mkdirSync } from 'node:fs';
import path from 'node:path';
import { APP_VERSION, COLORS, SHELL_HEIGHT, type Command, type Profile, type Snapshot, type Tab, type TabStatus, type Reply } from '../shared/model';
import { StateStore, makeTab, validLabel, MAX_PROFILES, MAX_TABS } from './state';
import { homeURL, isAllowedURL, isChatURL, isWebURL, partitionFor } from './policy';
import { AssetStore } from './asset-store';
import { AssetDownloads } from './asset-downloads';
import { AssetRuntime } from './asset-runtime';
import { CapabilityCatalog } from './capabilities';
import { installWorkbenchIPC } from './workbench-ipc';
import type { WorkbenchPanel } from '../shared/workbench';
import { AssetConversions } from './asset-conversions';
import { localRendererResponse } from './local-renderer';
import { LoginDiagnostics } from './login-diagnostics';
import type { LinkKind } from '../shared/diagnostics';

const testMode = !app.isPackaged && process.env.PROFILES_E2E === '1';
if (testMode) app.commandLine.appendSwitch('use-mock-keychain');
const fixtureOrigin = testMode ? process.env.PROFILES_FIXTURE_ORIGIN : undefined;
if (fixtureOrigin && !/^http:\/\/127\.0\.0\.1:\d+$/.test(fixtureOrigin)) throw new Error('测试页面必须使用本机回环地址');
const dataDir = testMode ? process.env.PROFILES_TEST_DATA : app.isPackaged ? path.join(app.getPath('appData'), 'ChatGPT Profiles') : path.join(app.getAppPath(), '.local-data');
if (!dataDir || !path.isAbsolute(dataDir)) throw new Error('无效的数据目录');
mkdirSync(dataDir, { recursive: true, mode: 0o700 });
app.setPath('userData', dataDir);
app.setName('ChatGPT Profiles');
if (process.platform === 'win32') app.setAppUserModelId('com.forcemind.chatgpt.profiles');
protocol.registerSchemesAsPrivileged([{ scheme: 'app', privileges: { standard: true, secure: true, supportFetchAPI: true } }, ...(process.platform==='darwin'?[{scheme:'asset',privileges:{standard:true,secure:true,supportFetchAPI:true,corsEnabled:true,stream:true}}]:[])]);
const single = app.requestSingleInstanceLock();
if (!single) app.quit();
let win: BrowserWindow | undefined;
let store: StateStore;
let assetStore: AssetStore | undefined;
let assetRuntime: AssetRuntime | undefined;
let capabilityCatalog: CapabilityCatalog | undefined;
let assetError: string | undefined;
let capabilityError: string | undefined;
let loginDiagnostics: LoginDiagnostics | undefined;
let loginDiagnosticsError: string | undefined;
let panel: WorkbenchPanel | undefined;
let assetConversions: AssetConversions | undefined;
const assetDownloads = new Map<string,AssetDownloads>();
let overlayOpen = false;
let overlayImage: string | undefined;
let quitting = false;
let commandQueue: Promise<unknown> = Promise.resolve();
let boundsTimer: ReturnType<typeof setTimeout> | undefined;
const views = new Map<string, WebContentsView>();
const status = new Map<string, TabStatus>();
const popups = new Map<BrowserWindow, string>();
const configuredSessions = new Set<string>();
const permissionGrants = new Map<string, Set<string>>();
const pendingProfiles = new Set<string>();
const homepage = () => homeURL(fixtureOrigin);
const activeProfile = () => { const s = store.read(); return s.profiles.find(p => p.id === s.activeProfileId)!; };
const snapshot = (): Snapshot => ({ ...store.read(), version: APP_VERSION, platform: process.platform, fullScreen: win?.isFullScreen() ?? false, tabStatus: Object.fromEntries(status), testMode, notice: store.recoveryNotice || assetStore?.recoveryNotice, overlayImage, panel, assetError });
function publish(): void { if (win && !win.isDestroyed()) win.webContents.send('profiles:state', snapshot()); }
function notice(message: string): void { if (win && !win.isDestroyed()) win.webContents.send('profiles:shortcut', `notice:${message}`); }
function report(error: unknown): void { notice(error instanceof Error ? error.message : '操作失败，请重试。'); }
function assetsChanged(profileId:string):void { if(win&&!win.isDestroyed())win.webContents.send('assets:changed',profileId); }
function profileById(id: string): Profile { const p = store.read().profiles.find(p => p.id === id); if (!p) throw new Error('账号已不存在'); return p; }
function activeContents(): WebContents | undefined { return views.get(activeProfile().activeTabId)?.webContents; }
function layout(): void {
  if (!win || win.isDestroyed()) return;
  const [width, height] = win.getContentSize(); const s = store.read();
  const left = 0; const top = SHELL_HEIGHT;
  const activeId = s.profiles.find(p => p.id === s.activeProfileId)!.activeTabId;
  for (const [id, view] of views) {
    view.setBounds({ x: left, y: top, width: Math.max(1, width - left), height: Math.max(1, height - top) });
    view.setVisible(!overlayOpen && !panel && id === activeId && !status.get(id)?.error);
  }
  win.setTitle(`${activeProfile().name} — ChatGPT Profiles`);
}
function saveBounds(): void {
  if (!win || win.isDestroyed() || win.isFullScreen() || win.isMaximized() || win.isMinimized()) return;
  const b = win.getBounds();
  store.commit(s => { s.bounds = b; });
}
function recordLink(profileId:string,kind:LinkKind,url:string,contents:WebContents):string|undefined {
  try{return loginDiagnostics?.add(profileId,kind,url,contents.getURL()).id;}catch(error){report(error);return undefined;}
}
function recordLinkResult(id:string|undefined,result:'cancelled'|'browser-opened'|'browser-failed'):void {
  if(id)try{loginDiagnostics?.result(id,result);}catch(error){report(error);}
}
async function externalFromPage(url: string,recordId?:string): Promise<void> {
  if (!win || !isWebURL(url)) { notice('已拦截不受支持的链接，可在账号菜单的「登录与链接记录」查看站点与原因。'); return; }
  const u = new URL(url);
  const result = await dialog.showMessageBox(win, { type: 'question', title: '打开外部链接', message: `在系统浏览器打开 ${u.hostname}？`, detail: '系统浏览器使用自己的账号登录状态。站点与原因可在账号菜单的「登录与链接记录」查看。', buttons: ['取消', '打开浏览器'], defaultId: 0, cancelId: 0 });
  if(result.response===1){try{await shell.openExternal(url);recordLinkResult(recordId,'browser-opened');}catch(error){recordLinkResult(recordId,'browser-failed');throw error;}}else recordLinkResult(recordId,'cancelled');
}
function configureSession(profileId: string): Session {
  const partition = partitionFor(profileId); const ses = session.fromPartition(partition);
  if (configuredSessions.has(partition)) return ses;
  configuredSessions.add(partition); permissionGrants.set(profileId, new Set());
  ses.setPermissionCheckHandler((_contents, permission, origin) => {
    if (permission === 'clipboard-sanitized-write' && isChatURL(origin)) return true;
    let normalized=origin;try{normalized=new URL(origin).origin;}catch{/* deny unknown origins */}
    return permissionGrants.get(profileId)?.has(`${normalized}:${permission}`) ?? false;
  });
  ses.setPermissionRequestHandler(async (contents, permission, callback, details) => {
    let answered = false;
    const finish = (allowed: boolean) => { if (!answered) { answered = true; callback(allowed); } };
    try {
      const url = details.requestingUrl || contents?.getURL() || '';
      if (permission === 'clipboard-sanitized-write' && isChatURL(url) && contents && isChatURL(contents.getURL())) { finish(true); return; }
      if (!win || !isChatURL(url) || !contents || !isChatURL(contents.getURL()) || !['media', 'clipboard-read', 'notifications', 'fullscreen'].includes(permission)) { finish(false); return; }
      const profile = profileById(profileId);
      const permissionName = permission === 'media' ? '麦克风或摄像头' : permission === 'clipboard-read' ? '剪贴板' : permission === 'notifications' ? '系统通知' : '网页全屏';
      const { response } = await dialog.showMessageBox(win, { type: 'question', title: '网页权限', message: `${profile.name} 请求使用${permissionName}`, detail: `${new URL(url).origin}\n仅对当前账号授权。`, buttons: ['不允许', '允许'], defaultId: 0, cancelId: 0 });
      let allowed = response === 1;
      if (allowed && permission === 'media' && process.platform === 'darwin') {
        const mediaTypes = (details as { mediaTypes?: string[] }).mediaTypes ?? [];
        for (const media of mediaTypes) if (media === 'audio' || media === 'video') allowed = (await systemPreferences.askForMediaAccess(media === 'audio' ? 'microphone' : 'camera')) && allowed;
      }
      if (allowed) permissionGrants.get(profileId)?.add(`${new URL(url).origin}:${permission}`);
      finish(allowed);
    } catch { finish(false); }
  });
  if(process.platform==='darwin'&&assetStore) {
    const tracker=new AssetDownloads(ses,profileId,assetStore,{
      profileAvailable:()=>!pendingProfiles.has(profileId)&&store.read().profiles.some(p=>p.id===profileId),
      source:contents=>{const p=profileById(profileId);const tab=[...views.entries()].find(([,view])=>view.webContents===contents);return {profileName:p.name,tabId:tab?.[0],conversationURL:contents.getURL(),title:contents.getTitle()};},
      changed:asset=>{assetsChanged(profileId);if(asset.status==='ready')notice(`${asset.source.profileName||'当前账号'}：${asset.name} 已归档到资产中心`);},
      failed:report
    });
    assetDownloads.set(profileId,tracker);
  } else ses.on('will-download', (_event, item) => {
    const p = store.read().profiles.find(p => p.id === profileId);
    if (!p) { item.cancel(); return; }
    const filename = path.basename(item.getFilename()).replace(/[\u0000-\u001f]/g, '_');
    item.setSaveDialogOptions({ title: `${p.name} · 保存下载`, defaultPath: path.join(app.getPath('downloads'), filename) });
    item.once('done', (_e, state) => notice(state === 'completed' ? `${p.name}：下载已保存` : `${p.name}：下载${state === 'cancelled' ? '已取消' : '失败，请重试'}`));
  });
  return ses;
}
function secureWebPreferences(profileId: string) { return { session: configureSession(profileId), sandbox: true, contextIsolation: true, nodeIntegration: false, webSecurity: true, allowRunningInsecureContent: false, spellcheck: true }; }
function configureRemote(contents: WebContents, profileId: string): void {
  contents.on('did-navigate',(_event,url)=>{if(isAllowedURL(url,fixtureOrigin)&&!isChatURL(url)&&(!fixtureOrigin||!url.startsWith(fixtureOrigin)))recordLink(profileId,'login-page',url,contents);});
  contents.on('will-navigate', (event, url) => { if (!isAllowedURL(url, fixtureOrigin)) { event.preventDefault(); const id=recordLink(profileId,'navigation',url,contents);void externalFromPage(url,id).catch(report); } });
  contents.on('will-redirect', (event, url) => { if (!isAllowedURL(url, fixtureOrigin)) { event.preventDefault();recordLink(profileId,'redirect',url,contents);notice('已拦截不受支持的跳转，可在账号菜单的「登录与链接记录」查看站点与原因。'); } });
  contents.setWindowOpenHandler(({ url }) => {
    if (url !== 'about:blank' && !isAllowedURL(url, fixtureOrigin)) {const id=recordLink(profileId,'popup',url,contents);void externalFromPage(url,id).catch(report); return { action: 'deny' }; }
    if (isChatURL(url) && !/^\/(auth|api\/auth)(\/|$)/.test(new URL(url).pathname)) { queueCommandInternal(() => createTab(profileId, url)); return { action: 'deny' }; }
    return { action: 'allow', overrideBrowserWindowOptions: { parent: win, width: 560, height: 760, minWidth: 420, minHeight: 500, title: `${profileById(profileId).name} · 登录窗口`, webPreferences: secureWebPreferences(profileId) } };
  });
  contents.on('did-create-window', popup => {
    popups.set(popup, profileId); configureRemote(popup.webContents, profileId);
    popup.on('page-title-updated', event => { event.preventDefault(); const url = popup.webContents.getURL(); let host = ''; try { host = new URL(url).hostname; } catch { /* about:blank */ } popup.setTitle(`${profileById(profileId).name} · ${host || '登录窗口'}`); });
    popup.once('closed', () => popups.delete(popup));
  });
}
function ensureView(tab: Tab): WebContentsView {
  const existing = views.get(tab.id); if (existing && !existing.webContents.isDestroyed()) return existing;
  const view = new WebContentsView({ webPreferences: secureWebPreferences(tab.profileId) });
  views.set(tab.id, view); win!.contentView.addChildView(view); view.setBackgroundColor('#ffffff');
  const c = view.webContents; configureRemote(c, tab.profileId);
  const stillCurrent = () => views.get(tab.id) === view && !pendingProfiles.has(tab.profileId) && store.read().tabs.some(t => t.id === tab.id);
  const update = (patch: Partial<TabStatus>) => {
    if (!stillCurrent() || c.isDestroyed()) return;
    status.set(tab.id, { loading: false, ...status.get(tab.id), ...patch, url: c.getURL(), canGoBack: c.navigationHistory.canGoBack(), canGoForward: c.navigationHistory.canGoForward() }); layout(); publish();
  };
  c.on('did-start-loading', () => update({ loading: true, error: undefined, crashed: false }));
  c.on('did-stop-loading', () => update({ loading: false }));
  c.on('did-fail-load', (_e, code, description, _url, isMainFrame) => { if (isMainFrame && code !== -3) update({ loading: false, error: `${description} (${code})` }); });
  c.on('render-process-gone', (_e, details) => update({ loading: false, crashed: true, error: `网页进程已退出（${details.reason}），可重新加载。` }));
  c.on('page-title-updated', (_event, title) => {
    if (!stillCurrent()) return;
    const nextTitle = title.replace(/[\u0000-\u001f\u007f]/g, ' ').slice(0, 240);
    if (store.read().tabs.find(t => t.id === tab.id)?.title !== nextTitle) store.commit(s => { const t = s.tabs.find(t => t.id === tab.id); if (t) t.title = nextTitle; });
    publish();
  });
  const navigated = (_e: unknown, url: string) => {
    if (!stillCurrent()) return;
    if ((isChatURL(url) && !/^\/(auth|api\/auth)(\/|$)/.test(new URL(url).pathname)) || (fixtureOrigin && url.startsWith(`${fixtureOrigin}/`))) {
      const clean = new URL(url); for (const key of ['code', 'state', 'token', 'access_token', 'session_token']) clean.searchParams.delete(key);
      if (store.read().tabs.find(t => t.id === tab.id)?.url !== clean.href) store.commit(s => { const t = s.tabs.find(t => t.id === tab.id); if (t) t.url = clean.href; });
    }
    update({ error: undefined });
  };
  c.on('did-navigate', navigated); c.on('did-navigate-in-page', navigated);
  layout(); void c.loadURL(tab.url).catch(error => update({ loading: false, error: error.message }));
  return view;
}
function showActive(focus = true): void {
  const p = activeProfile(); const t = store.read().tabs.find(t => t.id === p.activeTabId)!;
  const view = ensureView(t); layout(); if (focus && !overlayOpen && !panel) view.webContents.focus(); publish();
}
function destroyView(id: string): void {
  const view = views.get(id); views.delete(id); status.delete(id);
  if (view) { if (win && !win.isDestroyed()) win.contentView.removeChildView(view); if (!view.webContents.isDestroyed()) view.webContents.close({ waitForBeforeUnload: false }); }
}
function createTab(profileId: string, url = homepage()): void {
  profileById(profileId);
  if (!isAllowedURL(url, fixtureOrigin)) throw new Error('只能在账号空间中打开 ChatGPT 和支持的登录页面');
  if (store.read().tabs.length >= MAX_TABS) throw new Error(`标签页已达到 ${MAX_TABS} 个，请先关闭不需要的标签。`);
  const tab = makeTab(profileId, url);
  store.commit(s => { s.tabs.push(tab); s.activeProfileId = profileId; s.profiles.find(p => p.id === profileId)!.activeTabId = tab.id; }); showActive();
}
async function clearProfile(id: string, remove: boolean): Promise<void> {
  const p = profileById(id);
  if (remove && store.read().profiles.length === 1) throw new Error('请至少保留一个账号空间。');
  const choice = await dialog.showMessageBox(win!, { type: 'warning', title: remove ? '移除账号空间' : '清除本机登录数据', message: `${remove ? '移除' : '清除'}“${p.name}”${remove ? '？' : '的本机登录数据？'}`, detail: `${p.email || '未填写邮箱备注'}\n将关闭此账号的标签和登录弹窗，清除本机 Cookie 与网站存储。其他账号保持不变，ChatGPT 云端对话不会被删除。`, buttons: ['取消', remove ? '移除账号' : '清除并重新登录'], cancelId: 0, defaultId: 0 });
  if (choice.response !== 1) return;
  pendingProfiles.add(id);
  try {
    assetConversions?.cancelProfile(id);
    const tracker=assetDownloads.get(id);
    if(tracker){for(const assetId of tracker.activeIds())tracker.cancel(assetId);await tracker.whenIdle();if(remove){tracker.dispose();assetDownloads.delete(id);}}
    assetRuntime?.revoke(id);
    for (const [popup, pid] of popups) if (pid === id && !popup.isDestroyed()) popup.destroy();
    for (const t of store.read().tabs.filter(t => t.profileId === id)) destroyView(t.id);
    const ses = configureSession(id); await ses.clearStorageData(); await ses.clearCache(); await ses.clearAuthCache(); permissionGrants.set(id, new Set());
    store.commit(s => {
      s.tabs = s.tabs.filter(t => t.profileId !== id); s.closedTabs = s.closedTabs.filter(t => t.profileId !== id);
      if (remove) { s.profiles = s.profiles.filter(p => p.id !== id); if (s.activeProfileId === id) s.activeProfileId = s.profiles[0].id; }
      else { const tab = makeTab(id, homepage()); s.tabs.push(tab); s.profiles.find(p => p.id === id)!.activeTabId = tab.id; }
    });
    notice(remove ? `已移除 ${p.name}` : `${p.name} 的本机登录数据已清除`);
  } finally { pendingProfiles.delete(id); showActive(false); }
}
function validateCommand(value: unknown): asserts value is Command {
  if (!value || typeof value !== 'object') throw new Error('无效操作');
  const c = value as Record<string, unknown>;
  if (typeof c.type !== 'string') throw new Error('无效操作');
  const known = ['profile:add','profile:edit','profile:activate','profile:remove','profile:clear','profile:move','tab:new','tab:activate','tab:close','tab:pin','tab:menu','tab:reorder','tab:restore','back','forward','reload','home','fullscreen','maximize','external','overlay','panel'];
  if (!known.includes(c.type)) throw new Error('未知操作');
  if (['profile:edit','profile:activate','profile:remove','profile:clear','profile:move','tab:activate','tab:close','tab:pin','tab:menu'].includes(c.type) && (typeof c.id !== 'string' || !/^[a-f0-9-]{36}$/.test(c.id))) throw new Error('无效标识');
  if (['profile:add','profile:edit'].includes(c.type) && (!validLabel(c.name,40,true) || !validLabel(c.email,120) || !(COLORS as readonly unknown[]).includes(c.color))) throw new Error('请填写 1–40 字账号名称、有效备注与颜色。');
  if (c.type === 'profile:move' && c.direction !== -1 && c.direction !== 1) throw new Error('无效排序');
  if (c.type === 'tab:new' && c.url !== undefined && !isAllowedURL(c.url,fixtureOrigin)) throw new Error('网址不在支持范围');
  if (c.type === 'tab:reorder' && (!Array.isArray(c.ids) || c.ids.length > MAX_TABS || !c.ids.every(v => typeof v === 'string'))) throw new Error('无效排序');
  if (c.type === 'overlay' && typeof c.open !== 'boolean') throw new Error('无效窗口状态');
  if (c.type === 'panel' && (process.platform!=='darwin'||(c.panel!==undefined&&!['assets','skills','agents'].includes(c.panel as string)))) throw new Error('工作台入口无效');
}
async function dispatch(c: Command): Promise<void> {
  switch (c.type) {
    case 'profile:add': {
      if (store.read().profiles.length >= MAX_PROFILES) throw new Error(`最多支持 ${MAX_PROFILES} 个账号空间。`);
      const id = randomUUID(), tab = makeTab(id, homepage());
      store.commit(s => { s.profiles.push({ id, name: c.name.trim(), email: c.email.trim(), color: c.color, activeTabId: tab.id, createdAt: Date.now() }); s.tabs.push(tab); s.activeProfileId = id; }); showActive(false); break;
    }
    case 'profile:edit': profileById(c.id); store.commit(s => { Object.assign(s.profiles.find(p => p.id === c.id)!, { name: c.name.trim(), email: c.email.trim(), color: c.color }); }); layout(); break;
    case 'profile:activate': profileById(c.id);assetRuntime?.revoke(); store.commit(s => { s.activeProfileId = c.id; }); showActive(); break;
    case 'profile:remove': await clearProfile(c.id, true); break;
    case 'profile:clear': await clearProfile(c.id, false); break;
    case 'profile:move': profileById(c.id); store.commit(s => { const index = s.profiles.findIndex(p => p.id === c.id), next = index + c.direction; if (next >= 0 && next < s.profiles.length) [s.profiles[index], s.profiles[next]] = [s.profiles[next], s.profiles[index]]; }); break;
    case 'tab:new': panel=undefined;createTab(activeProfile().id, c.url); break;
    case 'tab:menu': {
      const tab=store.read().tabs.find(t=>t.id===c.id&&t.profileId===store.read().activeProfileId);
      if(!tab)throw new Error('标签不属于当前账号');
      const run=(command:Command)=>()=>queueCommandInternal(()=>dispatch(command));
      Menu.buildFromTemplate([{label:tab.pinned?'取消固定标签':'固定标签',click:run({type:'tab:pin',id:tab.id})},{label:'关闭标签页',click:run({type:'tab:close',id:tab.id})},{type:'separator'},{label:'恢复关闭的标签页',click:run({type:'tab:restore'})}]).popup({window:win});break;
    }
    case 'tab:activate': {
      const t = store.read().tabs.find(t => t.id === c.id); if (!t || t.profileId !== activeProfile().id) throw new Error('标签不属于当前账号');
      panel=undefined;store.commit(s => { s.profiles.find(p => p.id === t.profileId)!.activeTabId = t.id; }); showActive(); break;
    }
    case 'tab:close': {
      const s = store.read(), index = s.tabs.findIndex(t => t.id === c.id), tab = s.tabs[index];
      if (!tab || tab.profileId !== activeProfile().id) throw new Error('标签不属于当前账号');
      store.commit(next => {
        next.tabs = next.tabs.filter(t => t.id !== tab.id); next.closedTabs = [...next.closedTabs, tab].slice(-24);
        const profile = next.profiles.find(p => p.id === tab.profileId)!, remaining = next.tabs.filter(t => t.profileId === profile.id);
        if (!remaining.length) { const replacement = makeTab(profile.id, homepage()); next.tabs.push(replacement); profile.activeTabId = replacement.id; }
        else if (profile.activeTabId === tab.id) { const siblings = s.tabs.filter(t => t.profileId === profile.id); profile.activeTabId = remaining[Math.max(0, Math.min(siblings.findIndex(t => t.id === tab.id) - 1, remaining.length - 1))].id; }
      }); destroyView(tab.id); showActive(); break;
    }
    case 'tab:pin': store.commit(s => { const t = s.tabs.find(t => t.id === c.id && t.profileId === s.activeProfileId); if (!t) throw new Error('标签不属于当前账号'); t.pinned = !t.pinned; const siblings = s.tabs.filter(x => x.profileId === s.activeProfileId); const ordered = [...siblings.filter(x => x.pinned), ...siblings.filter(x => !x.pinned)]; let i = 0; s.tabs = s.tabs.map(x => x.profileId === s.activeProfileId ? ordered[i++] : x); }); break;
    case 'tab:reorder': store.commit(s => { const tabs = s.tabs.filter(t => t.profileId === s.activeProfileId); if (tabs.length !== c.ids.length || new Set(c.ids).size !== tabs.length || !tabs.every(t => c.ids.includes(t.id))) throw new Error('标签排序已过期，请重试'); const ordered = c.ids.map(id => tabs.find(t => t.id === id)!); if (ordered.some((t,i) => t.pinned && ordered.slice(0,i).some(x => !x.pinned))) throw new Error('固定标签应保留在左侧'); let i = 0; s.tabs = s.tabs.map(t => t.profileId === s.activeProfileId ? ordered[i++] : t); }); break;
    case 'tab:restore': {
      const s = store.read(), old = [...s.closedTabs].reverse().find(t => t.profileId === s.activeProfileId);
      if (!old) { notice('当前账号没有可恢复的标签页'); break; }
      if (s.tabs.length >= MAX_TABS) throw new Error('标签页数量已达上限');
      store.commit(next => { const tab = { ...old, id: randomUUID() }; next.closedTabs = next.closedTabs.filter(t => t.id !== old.id); next.tabs.push(tab); next.profiles.find(p => p.id === tab.profileId)!.activeTabId = tab.id; const ordered=next.tabs.filter(t=>t.profileId===tab.profileId).sort((a,b)=>Number(b.pinned)-Number(a.pinned));let i=0;next.tabs=next.tabs.map(t=>t.profileId===tab.profileId?ordered[i++]:t); }); showActive(); break;
    }
    case 'back': if (activeContents()?.navigationHistory.canGoBack()) activeContents()!.navigationHistory.goBack(); break;
    case 'forward': if (activeContents()?.navigationHistory.canGoForward()) activeContents()!.navigationHistory.goForward(); break;
    case 'reload': { const t = store.read().tabs.find(t => t.id === activeProfile().activeTabId)!; const c = views.get(t.id)?.webContents; if (!c || c.isDestroyed() || c.isCrashed()) { destroyView(t.id); ensureView(t); } else c.reload(); break; }
    case 'home': void activeContents()?.loadURL(homepage()).catch(report); break;
    case 'fullscreen': win?.setFullScreen(!win.isFullScreen()); break;
    case 'maximize': if (win?.isMaximized()) win.unmaximize(); else win?.maximize(); break;
    case 'external': { const url = activeContents()?.getURL(); if (url && isWebURL(url)) await shell.openExternal(url); else notice('当前页面不能在系统浏览器打开'); break; }
    case 'panel': panel=c.panel;overlayOpen=false;overlayImage=undefined;layout();if(panel)win?.webContents.focus();else activeContents()?.focus();break;
    case 'overlay': {
      if(c.open&&!overlayOpen){
        // Keep the page visible behind local menus. This image stays in memory
        // and is only sent to the trusted toolbar; it is never saved to disk.
        const contents=activeContents();overlayImage=undefined;
        if(contents&&!contents.isDestroyed()&&!contents.isCrashed()){
          let timer:ReturnType<typeof setTimeout>|undefined;
          try{const image=await Promise.race([contents.capturePage(),new Promise<undefined>(resolve=>{timer=setTimeout(()=>resolve(undefined),800);})]);
            if(image&&!image.isEmpty())overlayImage=image.resize({width:Math.min(1600,image.getSize().width)}).toDataURL();
          }catch{/* a failed page can still use the account menu */}finally{clearTimeout(timer);}
        }
      }
      overlayOpen=c.open;if(!c.open)overlayImage=undefined;layout();if(c.open)win?.webContents.focus();break;
    }
  }
  publish();
}
function queueCommandInternal(action: () => void | Promise<void>): void { commandQueue = commandQueue.then(action).catch(report); }
function trusted(event: IpcMainInvokeEvent): boolean {
  if (!win || event.sender !== win.webContents || event.senderFrame !== win.webContents.mainFrame) return false;
  return event.senderFrame.url === 'app://shell/index.html';
}
function installIPC(): void {
  ipcMain.handle('login-links:list',event=>{if(!trusted(event)||process.platform!=='darwin')throw new Error('拒绝非本地工具栏请求');if(!loginDiagnostics)throw new Error(loginDiagnosticsError||'登录记录暂不可用');return loginDiagnostics.list();});
  ipcMain.handle('login-links:clear',event=>{if(!trusted(event)||process.platform!=='darwin')throw new Error('拒绝非本地工具栏请求');if(!loginDiagnostics)throw new Error(loginDiagnosticsError||'登录记录暂不可用');loginDiagnostics.clear();});
  ipcMain.handle('profiles:snapshot', event => { if (!trusted(event)) throw new Error('拒绝非本地工具栏请求'); return snapshot(); });
  ipcMain.handle('profiles:command', (event, value: unknown) => {
    if (!trusted(event)) return { ok: false, error: '拒绝非本地工具栏请求' } satisfies Reply;
    const run = async (): Promise<Reply> => { try { validateCommand(value); await dispatch(value); return { ok: true }; } catch (error) { return { ok: false, error: error instanceof Error ? error.message : '操作失败' }; } };
    const result = commandQueue.then(run, run); commandQueue = result.then(() => undefined); return result;
  });
  if(process.platform==='darwin')installWorkbenchIPC({
    dataDirectory:app.getPath('userData'),window:()=>win,trusted,currentProfile:activeProfile,state:()=>store.read(),
    store:()=>{if(!assetStore)throw new Error(assetError||'资产库暂不可用');return assetStore;},
    runtime:()=>{if(!assetRuntime)throw new Error(assetError||'资产预览暂不可用');return assetRuntime;},
    catalog:()=>{if(!capabilityCatalog)throw new Error(capabilityError||'能力库暂不可用');return capabilityCatalog;},
    downloads:id=>assetDownloads.get(id),changed:assetsChanged,
    openChat:(id,url)=>{panel=undefined;createTab(id,url);},
    favoriteCapability:(id,favorite)=>{if(!capabilityCatalog)throw new Error(capabilityError||'能力库暂不可用');if(id.startsWith('skill-'))capabilityCatalog.skill(id);else capabilityCatalog.agent(id);store.commit(s=>{const ids=new Set(s.capabilityFavorites||[]);favorite?ids.add(id):ids.delete(id);s.capabilityFavorites=[...ids];});}
    ,conversions:()=>{if(!assetConversions)throw new Error(assetError||'本地转换暂不可用');return assetConversions;}
  });
}
function installMenu(): void {
  const action = (command: Command) => () => queueCommandInternal(() => dispatch(command));
  const tabStep = (delta: number) => () => { const p = activeProfile(), tabs = store.read().tabs.filter(t => t.profileId === p.id), i = tabs.findIndex(t => t.id === p.activeTabId); action({ type: 'tab:activate', id: tabs[(i+delta+tabs.length)%tabs.length].id })(); };
  const template: Electron.MenuItemConstructorOptions[] = [
    { label: 'ChatGPT Profiles', submenu: [{ role: 'about', label: '关于 ChatGPT Profiles' }, ...(process.platform === 'darwin' ? [{ type: 'separator' as const }, { role: 'services' as const, label: '服务' }, { role: 'hide' as const, label: '隐藏' }, { role: 'hideOthers' as const, label: '隐藏其他' }, { role: 'unhide' as const, label: '显示全部' }] : []), { type: 'separator' }, { role: 'quit', label: '退出' }] },
    { label: '文件', submenu: [{ label: '新建标签页', accelerator: 'CmdOrCtrl+T', click: action({type:'tab:new'}) }, { label: '恢复关闭的标签页', accelerator: 'CmdOrCtrl+Shift+T', click: action({type:'tab:restore'}) }, { label: '添加账号', accelerator: 'CmdOrCtrl+Shift+N', click: () => win?.webContents.send('profiles:shortcut','add-profile') }, { type:'separator' }, { label:'关闭标签页', accelerator:'CmdOrCtrl+W', click: () => { const focused=BrowserWindow.getFocusedWindow(); if(focused&&focused!==win) focused.close(); else if(panel)win?.webContents.send('profiles:shortcut','close-workbench');else action({type:'tab:close',id:activeProfile().activeTabId})(); } }, { role:'close', label:'关闭窗口', accelerator:'CmdOrCtrl+Shift+W' }] },
    { label: '编辑', submenu: [{role:'undo',label:'撤销'},{role:'redo',label:'重做'},{type:'separator'},{role:'cut',label:'剪切'},{role:'copy',label:'复制'},{role:'paste',label:'粘贴'},{role:'selectAll',label:'全选'}] },
    { label: '显示', submenu: [
      {id:'reload-page',label:'重新加载网页',accelerator:'CmdOrCtrl+R',click:action({type:'reload'})},
      {label:'后退',accelerator:'CmdOrCtrl+[',click:action({type:'back'})},{label:'前进',accelerator:'CmdOrCtrl+]',click:action({type:'forward'})},
      {label:'返回 ChatGPT 首页',click:action({type:'home'})},{label:'在系统浏览器打开',click:action({type:'external'})},{type:'separator'},
      {id:'toggle-accounts',label:'切换账号',accelerator:'CmdOrCtrl+B',click:()=>win?.webContents.send('profiles:shortcut','toggle-accounts')},
      {id:'toggle-fullscreen',label:'进入/退出全屏',accelerator:process.platform==='darwin'?'Ctrl+Cmd+F':'F11',click:action({type:'fullscreen'})},
      {label:'实际大小',accelerator:'CmdOrCtrl+0',click:()=>activeContents()?.setZoomFactor(1)},
      {label:'放大网页',accelerator:'CmdOrCtrl+Plus',click:()=>{const c=activeContents();if(c)c.setZoomFactor(Math.min(2,c.getZoomFactor()+0.1));}},
      {label:'缩小网页',accelerator:'CmdOrCtrl+-',click:()=>{const c=activeContents();if(c)c.setZoomFactor(Math.max(0.5,c.getZoomFactor()-0.1));}}
    ] },
    { label: '标签页', submenu: [{id:'pin-current-tab',label:'固定/取消固定当前标签',click:()=>action({type:'tab:pin',id:activeProfile().activeTabId})()},{id:'restore-tab',label:'恢复关闭的标签页',click:action({type:'tab:restore'})},{type:'separator'}, {label:'下一个标签页',accelerator:'Ctrl+Tab',click:tabStep(1)},{label:'上一个标签页',accelerator:'Ctrl+Shift+Tab',click:tabStep(-1)}, ...Array.from({length:9},(_,i)=>({label:`切换标签 ${i+1}`,accelerator:`CmdOrCtrl+${i+1}`,click:()=>{const tabs=store.read().tabs.filter(t=>t.profileId===activeProfile().id),t=tabs[i===8?tabs.length-1:i];if(t)action({type:'tab:activate',id:t.id})();}}))] },
    { label:'窗口',submenu:[{role:'minimize',label:'最小化'},{label:'缩放窗口',click:action({type:'maximize'})},...(process.platform==='darwin'?[{role:'front' as const,label:'前置全部窗口'}]:[])] }
  ];
  Menu.setApplicationMenu(Menu.buildFromTemplate(template));
}
function registerProtocol(): void {
  const root = path.join(__dirname,'../renderer');
  protocol.handle('app',request=>localRendererResponse(root,request));
}
function createWindow(): void {
  const b = store.read().bounds;
  const onScreen = screen.getAllDisplays().some(d => b.x !== undefined && b.y !== undefined && b.x+Math.min(b.width,200)>d.workArea.x && b.x<d.workArea.x+d.workArea.width && b.y+40>d.workArea.y && b.y<d.workArea.y+d.workArea.height);
  const chrome: Electron.BrowserWindowConstructorOptions = process.platform === 'win32'
    ? { titleBarStyle:'hidden', titleBarOverlay:{color:'#f8f8f8',symbolColor:'#171717',height:SHELL_HEIGHT},autoHideMenuBar:true,icon:path.join(__dirname,'icon.png') }
    : { titleBarStyle:'hiddenInset',trafficLightPosition:{x:18,y:17} };
  win = new BrowserWindow({ ...b, ...(!onScreen ? {x:undefined,y:undefined} : {}), ...chrome, minWidth:800,minHeight:600,movable:true,title:'ChatGPT Profiles',backgroundColor:'#ffffff',show:false,webPreferences:{preload:path.join(__dirname,'preload.cjs'),sandbox:true,contextIsolation:true,nodeIntegration:false,webSecurity:true} });
  if (process.platform === 'win32') win.setMenuBarVisibility(false);
  win.webContents.setWindowOpenHandler(()=>({action:'deny'}));
  win.webContents.on('will-navigate',event=>event.preventDefault());
  win.on('resize',()=>{layout();clearTimeout(boundsTimer);boundsTimer=setTimeout(()=>{try{saveBounds();}catch(error){report(error);}},250);});
  win.on('move',()=>{clearTimeout(boundsTimer);boundsTimer=setTimeout(()=>{try{saveBounds();}catch(error){report(error);}},250);});
  win.on('enter-full-screen',()=>{layout();publish();});win.on('leave-full-screen',()=>{layout();publish();});
  win.on('close',()=>{clearTimeout(boundsTimer);try{saveBounds();}catch(error){report(error);} for(const id of [...views.keys()])destroyView(id);for(const popup of popups.keys())if(!popup.isDestroyed())popup.destroy();});
  win.on('closed',()=>{win=undefined;overlayOpen=false;overlayImage=undefined;panel=undefined;assetRuntime?.revoke();});
  win.once('ready-to-show',()=>{win?.show();showActive();});
  void win.loadURL('app://shell/index.html').catch(error=>dialog.showErrorBox('界面加载失败',error.message));
}
if (single) {
  app.on('second-instance',()=>{if(win){if(win.isMinimized())win.restore();win.show();win.focus();}});
  app.whenReady().then(()=>{
    store = new StateStore(dataDir, fixtureOrigin);
    if(process.platform==='darwin'){
      try{loginDiagnostics=new LoginDiagnostics(dataDir,()=>win?.webContents.send('login-links:changed'));}catch(error){loginDiagnosticsError=error instanceof Error?error.message:'登录记录暂不可用';}
      try{assetStore=new AssetStore(dataDir);assetRuntime=new AssetRuntime(assetStore,()=>activeProfile().id);assetRuntime.register();}catch(error){assetError=error instanceof Error?error.message:'资产库无法读取';}
      if(assetStore)assetConversions=new AssetConversions(assetStore,{rendererRoot:path.join(__dirname,'../renderer'),profileAvailable:id=>!quitting&&!pendingProfiles.has(id)&&store.read().profiles.some(p=>p.id===id),changed:assetsChanged});
      try{capabilityCatalog=new CapabilityCatalog(path.join(__dirname,'../capabilities'));}catch(error){capabilityError=error instanceof Error?error.message:'能力资源无法读取';}
    }
    registerProtocol();installIPC();installMenu();createWindow();
    app.on('activate',()=>{if(!win&&!quitting)createWindow();});
  }).catch(error=>{dialog.showErrorBox('无法安全恢复账号',error instanceof Error?error.message:String(error));app.quit();});
  app.on('before-quit',()=>{quitting=true;assetConversions?.dispose();for(const tracker of assetDownloads.values())tracker.dispose();});
  app.on('window-all-closed',()=>{if(process.platform!=='darwin'||testMode)app.quit();});
}
