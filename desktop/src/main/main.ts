import {protectRemoteInput} from './remote-input';
import {RecoveryStore,RecentlyClosedPages,resumeURL} from './recovery-store';
import {RecoveryAccess,type PresenceKey} from './recovery-access';
import {captureResume,applyResume,finishResume} from './page-resume';
import {captureSnapshot} from './page-snapshot';
import {UpdateManager} from './update-manager';
import {IdleUpdate} from './idle-update';
import {pageSafeForIdle} from './page-idle';
import {shanghaiDay} from '../shared/updates';
import {installedBundle} from './update-install';
import {UPDATE_FEED} from './update-download';
import {validWorkspace,type PageResume,type PageSnapshot,type WorkbenchViewState} from '../shared/recovery';
import { app, BrowserWindow, WebContentsView, ipcMain, protocol, dialog, Menu, shell, session, screen, systemPreferences, safeStorage, clipboard, powerMonitor, type WebContents, type Session, type IpcMainInvokeEvent } from 'electron';
import {installImageContextMenu} from './image-context-menu';
import { pageProtocol,type NativeAgentMetadata,type PageEnvironment } from './page-protocol';
import { DEFAULT_LANGUAGE,preferredLanguages,validBrowserLanguage,validTimeZone } from '../shared/profile-preferences';
import { randomUUID } from 'node:crypto';
import { mkdirSync,realpathSync } from 'node:fs';
import path from 'node:path';
import { APP_VERSION, COLORS, SHELL_HEIGHT, desktopWorkspaceEnabled, type Command, type Profile, type Snapshot, type Tab, type TabStatus, type Reply } from '../shared/model';
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
import { HandoffStore } from './handoff-store';
import { HandoffRunner } from './handoff-runner';
import type { HandoffJob } from '../shared/handoff';
import { LoginDiagnostics } from './login-diagnostics';
import type { LinkKind } from '../shared/diagnostics';
import {MacMenuBar} from './mac-menu-bar';

const desktopWorkspace = desktopWorkspaceEnabled(process.platform);
const testMode = !app.isPackaged && process.env.PROFILES_E2E === '1';
if (testMode) app.commandLine.appendSwitch('use-mock-keychain');
const fixtureOrigin = testMode ? process.env.PROFILES_FIXTURE_ORIGIN : undefined;
if (fixtureOrigin && !/^http:\/\/127\.0\.0\.1:\d+$/.test(fixtureOrigin)) throw new Error('测试页面必须使用本机回环地址');
const dataDir = testMode ? process.env.PROFILES_TEST_DATA : app.isPackaged ? path.join(app.getPath('appData'), 'ChatGPT Profiles') : path.join(app.getAppPath(), '.local-data');
if (!dataDir || !path.isAbsolute(dataDir)) throw new Error('无效的数据目录');
mkdirSync(dataDir, { recursive: true, mode: 0o700 });
app.setPath('userData', dataDir);
// Keep the internal name stable for Chromium/Keychain identity across rebranding.
app.setName('ChatGPT Profiles');
if (process.platform === 'win32') app.setAppUserModelId('com.forcemind.chatgpt.profiles');
protocol.registerSchemesAsPrivileged([{ scheme: 'app', privileges: { standard: true, secure: true, supportFetchAPI: true } }, ...(desktopWorkspace?[{scheme:'asset',privileges:{standard:true,secure:true,supportFetchAPI:true,corsEnabled:true,stream:true}}]:[])]);
const single = app.requestSingleInstanceLock();
if (!single) app.quit();
let win: BrowserWindow | undefined;
// Kept in main-process module scope so the native status item survives hidden windows.
export let menuBar:MacMenuBar|undefined;
let updates:UpdateManager|undefined,updateDialog=false;const offeredUpdates=new Set<string>(),readyUpdatePrompts=new Set<string>();
let idleUpdate:IdleUpdate|undefined,idleTimer:ReturnType<typeof setTimeout>|undefined,idleNoticeWindow:BrowserWindow|undefined,idleInstallEpoch:number|undefined;
let backgroundModePending=process.platform==='darwin'&&process.argv.includes('--profiles-background-update');
let store: StateStore;
let assetStore: AssetStore | undefined;
let assetRuntime: AssetRuntime | undefined;
let capabilityCatalog: CapabilityCatalog | undefined;
let assetError: string | undefined;
let capabilityError: string | undefined;
let loginDiagnostics: LoginDiagnostics | undefined;
let loginDiagnosticsError: string | undefined;
let handoffRunner:HandoffRunner|undefined;
let handoffError:string|undefined;
let panel: WorkbenchPanel | undefined;
let assetConversions: AssetConversions | undefined;
const assetDownloads = new Map<string,AssetDownloads>();
let overlayOpen = false;
let overlayImage: string | undefined;
let quitting = false;
let commandQueue: Promise<unknown> = Promise.resolve();
let boundsTimer: ReturnType<typeof setTimeout> | undefined;
const pageLanguages=new Map<number,string>();
const nativeAgents=new Map<string,NativeAgentMetadata>();
const viewLayout=new Map<string,{width:number;height:number;visible:boolean}>();
let lastWindowTitle='';let publishTimer:ReturnType<typeof setTimeout>|undefined;
const views = new Map<string, WebContentsView>();
let recovery:RecoveryStore|undefined,recoveryError:string|undefined;
let recoveryAccess:RecoveryAccess|undefined,recoveryChanging=false,pagesStarted=false;
const pageReady=new Map<string,Promise<unknown>>(),restoringPages=new Set<string>(),blockedRecovery=new Set<string>();
const localDrafts=new Map<string,PageResume>(),closedReplacements=new Map<string,string>();
const recentClosed=new RecentlyClosedPages(id=>destroyView(id));
let checkpointTimer:ReturnType<typeof setInterval>|undefined,quitPending=false,warming=false;
const checkpointErrors=new Set<string>();
const savedPages=new Map<string,PageSnapshot>(),offlinePages=new Map<string,PageSnapshot>(),snapshotTimes=new Map<string,number>(),capturingSnapshots=new Set<string>();

const status = new Map<string, TabStatus>();
const popups = new Map<BrowserWindow, string>();
const hiddenPopups=new Set<BrowserWindow>();
const configuredSessions = new Set<string>();
const permissionGrants = new Map<string, Set<string>>();
const pendingProfiles = new Set<string>();
const homepage = () => homeURL(fixtureOrigin);
const activeProfile = () => { const s = store.read(); return s.profiles.find(p => p.id === s.activeProfileId)!; };
const snapshot = (): Snapshot => ({ ...store.read(), version: APP_VERSION, platform: process.platform, fullScreen: win?.isFullScreen() ?? false, tabStatus: Object.fromEntries(status), testMode, recoveryAccess:recoveryAccess?{...recoveryAccess.status,blocking:!pagesStarted}:undefined, offline:offlinePages.get(activeProfile().activeTabId), update:updates?.status, notice: store.recoveryNotice || assetStore?.recoveryNotice || recoveryError, overlayImage, panel, assetError });
function publish():void{if(publishTimer)return;publishTimer=setTimeout(()=>{publishTimer=undefined;menuBar?.refresh();if(win&&!win.isDestroyed())win.webContents.send('profiles:state',snapshot());},25);publishTimer.unref();}
function notice(message: string): void { if (win && !win.isDestroyed()) win.webContents.send('profiles:shortcut', `notice:${message}`); }
function report(error: unknown): void { notice(error instanceof Error ? error.message : '操作失败，请重试。'); }
function startPages(focus=true):void {if(pagesStarted)return;pagesStarted=true;showActive(focus);void warmPreviousTabs();}
async function authorizeRecovery(manual=false):Promise<void>{
 if(!recoveryAccess||recoveryChanging||!win?.isVisible()||(!manual&&!win.isFocused()))return;
 recoveryChanging=true;publish();
 try{
  const authorization=recoveryAccess.authorize();publish();await authorization;recoveryAccess.finishMigration();
  recovery=new RecoveryStore(dataDir!,recoveryAccess.cipher,fixtureOrigin);
  if(store.recoveryNotice)recovery.preserveOrphans();recovery.prune([...store.read().tabs,...store.read().closedTabs]);recoveryError=undefined;
  if(pagesStarted){for(const tab of store.read().tabs){const c=views.get(tab.id)?.webContents;if(!c||c.isDestroyed()||restoringPages.has(tab.id))continue;blockedRecovery.delete(tab.id);restoringPages.add(tab.id);try{await restoreTab(tab,c);}finally{restoringPages.delete(tab.id);}}}
 }catch(error){recovery=undefined;recoveryError=error instanceof Error?error.message:'本地恢复尚未授权';recoveryAccess.status={...recoveryAccess.status,phase:'error',message:recoveryError};}
 finally{recoveryChanging=false;if(recovery)startPages();layout();publish();}
}
async function recoverySettings():Promise<void>{
 if(!win||!recoveryAccess||recoveryChanging)return;
 const s=recoveryAccess.status,canEnable=s.phase==='ready'&&s.signed&&s.touchID&&!s.protected;
 const explanation=s.protected?'本地恢复密钥由系统身份验证保护，每次真正启动后在前台解锁。':!s.signed?'当前版本使用临时签名，Touch ID 密钥保护暂不可启用。需要带 Developer ID 签名的版本；现有系统钥匙串加密继续有效。':!s.touchID?'此 Mac 的 Touch ID 暂不可用，请在系统设置中配置指纹。':'可用 Touch ID 或系统允许的密码验证保护本地恢复密钥。';
 updateDialog=true;
 try{const choice=await dialog.showMessageBox(win,{type:'info',title:'隐私与本地恢复',message:s.protected?'Touch ID 恢复保护已开启':'本机加密与钥匙串授权',detail:explanation+'\n\n本地草稿、阅读位置及离线页面加密保存在这台电脑。不会读取 Chrome 密码。系统钥匙串授权与 Touch ID 是不同的验证，升级后的系统授权仍可能要求 Mac 登录密码。\n\n'+(s.message||'取消授权不会删除现有登录或恢复原件。'),buttons:canEnable?['关闭','重新授权恢复','启用 Touch ID 保护']:['关闭','重新授权恢复'],defaultId:0,cancelId:0});
  if(choice.response===1)await authorizeRecovery(true);
  if(choice.response===2&&canEnable){recoveryChanging=true;try{await recovery?.idle();await recoveryAccess.enablePresence();notice('本地恢复密钥的系统身份验证保护已开启。');}catch(error){report(error);}finally{recoveryChanging=false;publish();}}
 }finally{updateDialog=false;recordAppActivity();}
}
function assetsChanged(profileId:string):void { if(win&&!win.isDestroyed())win.webContents.send('assets:changed',profileId); }
function profileById(id: string): Profile { const p = store.read().profiles.find(p => p.id === id); if (!p) throw new Error('账号已不存在'); return p; }
function activeContents(): WebContents | undefined { return views.get(activeProfile().activeTabId)?.webContents; }
function layout():void {
 if(!win||win.isDestroyed())return;const[width,height]=win.getContentSize(),s=store.read(),p=s.profiles.find(p=>p.id===s.activeProfileId)!;
 const viewHeight=Math.max(1,height-SHELL_HEIGHT),viewWidth=Math.max(1,width);
 for(const[id,view]of views){const nextVisible=!overlayOpen&&!panel&&id===p.activeTabId&&!offlinePages.has(id)&&!status.get(id)?.error,previous=viewLayout.get(id);
  if(!previous||previous.width!==viewWidth||previous.height!==viewHeight)view.setBounds({x:0,y:SHELL_HEIGHT,width:viewWidth,height:viewHeight});
  if(!previous||previous.visible!==nextVisible)view.setVisible(nextVisible);
  viewLayout.set(id,{width:viewWidth,height:viewHeight,visible:nextVisible});
 }
 const title=p.name+' — ChatGPT Studio';if(title!==lastWindowTitle){win.setTitle(title);lastWindowTitle=title;}
}
function saveBounds(): void {
 if(!win||win.isDestroyed()||win.isMinimized())return;
 const mode={maximized:win.isMaximized(),fullscreen:win.isFullScreen()},bounds=win.getBounds();
 if(!desktopWorkspace){if(mode.maximized||mode.fullscreen)return;store.commit(s=>{s.bounds=bounds;});return;}
 store.commit(s=>{s.windowMode=mode;if(!mode.maximized&&!mode.fullscreen)s.bounds=bounds;});
}
function deadline<T>(work:Promise<T>,ms:number):Promise<T>{let timer:ReturnType<typeof setTimeout>;return Promise.race([work,new Promise<never>((_,reject)=>{timer=setTimeout(()=>reject(Error('界面恢复记录处理超时')),ms);timer.unref();})]).finally(()=>clearTimeout(timer));}
function savePanel():void{if(!desktopWorkspace)return;store.commit(s=>{const p=s.profiles.find(p=>p.id===s.activeProfileId)!;p.workspace={...p.workspace,panel};});}
async function checkpointTab(id:string,strict=false):Promise<void>{
 if(!recovery||recoveryChanging||restoringPages.has(id)||blockedRecovery.has(id))return;
 const tab=[...store.read().tabs,...store.read().closedTabs].find(t=>t.id===id),contents=views.get(id)?.webContents;if(!tab||!contents||pendingProfiles.has(tab.profileId))return;
 try{const record=await deadline(captureResume(contents,tab.profileId,id,fixtureOrigin),1500);if(!record)return;
  if(!capturingSnapshots.has(id)&&(strict||Date.now()-(snapshotTimes.get(id)||0)>30000)){
   capturingSnapshots.add(id);snapshotTimes.set(id,Date.now());
   try{const page=await deadline(captureSnapshot(contents,fixtureOrigin),1200);if(page&&page.url===record.url&&!pendingProfiles.has(tab.profileId)&&[...store.read().tabs,...store.read().closedTabs].some(t=>t.id===id&&t.profileId===tab.profileId))savedPages.set(id,page);}catch{/* A viewport capture must not prevent saving the draft. */}finally{capturingSnapshots.delete(id);}
  }
  const page=savedPages.get(id);if(page?.url===record.url)record.page=page;
  const alternative=localDrafts.get(id)?.draft;if(alternative&&alternative!==record.draft)record.alternativeDraft=alternative;
  if(recoveryChanging)return;
  await recovery.save(record,()=>!pendingProfiles.has(tab.profileId)&&[...store.read().tabs,...store.read().closedTabs].some(t=>t.id===id&&t.profileId===tab.profileId)&&!contents.isDestroyed()&&resumeURL(contents.getURL(),fixtureOrigin)===record.url);
  checkpointErrors.delete(id);
 }catch(error){if(!checkpointErrors.has(id)){checkpointErrors.add(id);report(error);}if(strict)throw error;}
}
function pruneRecovery():void{const tabs=[...store.read().tabs,...store.read().closedTabs],ids=new Set(tabs.map(t=>t.id));recovery?.prune(tabs);for(const map of [localDrafts,closedReplacements,savedPages,offlinePages,snapshotTimes])for(const id of map.keys())if(!ids.has(id))map.delete(id);for(const set of [blockedRecovery,checkpointErrors])for(const id of set)if(!ids.has(id))set.delete(id);}
async function checkpointWorkspace():Promise<void>{
 if(recoveryAccess&&recoveryAccess.status.phase!=='ready'&&views.size)throw Error('本地恢复尚未授权，本次草稿和阅读位置还未保存；旧恢复原件仍保留');
 if(win&&!win.isDestroyed())await deadline(win.webContents.executeJavaScript(`(async()=>{const detail={pending:[]};window.dispatchEvent(new CustomEvent('profiles:checkpoint',{detail}));await Promise.all(detail.pending);})()`),2000);
 const ids=[...views.keys()];for(let i=0;i<ids.length;i+=2)await Promise.all(ids.slice(i,i+2).map(id=>checkpointTab(id,true)));await recovery?.idle();
}
async function restoreTab(tab:Tab,contents:WebContents):Promise<void>{
 if(!recovery)return;let saved:PageResume|undefined;
 try{const record=await recovery.load(tab.profileId,tab.id,contents.getURL());if(!record||pendingProfiles.has(tab.profileId))return;saved=record;
  if(record.alternativeDraft)localDrafts.set(tab.id,{...record,draft:record.alternativeDraft,alternativeDraft:undefined});
  for(let i=0;i<60;i++){if(contents.isDestroyed()||resumeURL(contents.getURL(),fixtureOrigin)!==record.url)return;const outcome=await applyResume(contents,record,fixtureOrigin,i===59);
   if(outcome==='cancelled'){if(record.draft)localDrafts.set(tab.id,record);return;}
   if(outcome==='conflict'){localDrafts.set(tab.id,record);notice('网页已有草稿，已保留。可从「文件 → 查看本地恢复草稿」取回本地版本。');return;}
   if(outcome!=='waiting')return;await new Promise(r=>setTimeout(r,250));
  }
  if(record.draft){localDrafts.set(tab.id,record);notice('本地草稿已保留，网页就绪后可从文件菜单查看。');}
 }catch(error){blockedRecovery.add(tab.id);report(error);}finally{if(saved)await deadline(finishResume(contents,saved),1000).catch(()=>{});}
}
async function warmPreviousTabs():Promise<void>{
 if(warming||!desktopWorkspace)return;warming=true;
 try{const active=activeProfile().activeTabId;await pageReady.get(active);for(const tab of store.read().tabs.filter(t=>t.loaded&&t.id!==active)){
  if(quitting||quitPending||!win||win.isDestroyed())break;if(!store.read().tabs.some(t=>t.id===tab.id))continue;ensureView(tab);await Promise.race([pageReady.get(tab.id),new Promise(r=>{const t=setTimeout(r,15000);t.unref();})]);
 }}finally{warming=false;}
}
function reopenWindow():void{recordAppActivity();setTimeout(()=>void offerUpdate().catch(report),500).unref();if(quitting)return;if(!win){createWindow();return;}if(win.isMinimized())win.restore();win.show();win.focus();if(backgroundModePending){backgroundModePending=false;const mode=store.read().windowMode;if(mode?.maximized)win.maximize();if(mode?.fullscreen)win.setFullScreen(true);}if(recoveryAccess&&!pagesStarted&&!recoveryAccess.needsConsent)void authorizeRecovery();if(desktopWorkspace&&!overlayOpen&&!panel)activeContents()?.focus();for(const popup of hiddenPopups)if(!popup.isDestroyed()&&popups.get(popup)===activeProfile().id){popup.show();hiddenPopups.delete(popup);}}
async function openChatSearch():Promise<void>{recordAppActivity();
 if(!win?.isVisible()||win.isMinimized())reopenWindow();
 if(overlayOpen){notice('请先关闭当前菜单或弹窗，再打开搜索。');return;}if(panel){win?.webContents.send('profiles:shortcut','workbench-search');return;}const c=activeContents();if(!c||c.isDestroyed())return;c.focus();
 const opened=await c.executeJavaScript(`(()=>{const items=[...document.querySelectorAll('button,a,[role="button"]')];const target=items.find(e=>e.getClientRects().length&&/^(搜索|搜索聊天|搜索对话|Search|Search chats|Search conversations)$/i.test((e.getAttribute('aria-label')||e.getAttribute('title')||e.textContent||'').trim()));if(!target)return false;target.click();return true;})()`,true);
 if(!opened){c.setIgnoreMenuShortcuts(true);c.sendInputEvent({type:'keyDown',keyCode:'K',modifiers:[process.platform==='darwin'?'meta':'control']});c.sendInputEvent({type:'keyUp',keyCode:'K',modifiers:[process.platform==='darwin'?'meta':'control']});}
}
async function showLocalDraft():Promise<void>{const id=activeProfile().activeTabId,record=localDrafts.get(id);if(!record?.draft){notice('当前标签没有待取回的本地草稿。');return;}const choice=await dialog.showMessageBox(win!,{type:'info',title:'本地恢复草稿',message:activeProfile().name+' · 本地草稿',detail:record.draft,buttons:['关闭','复制草稿'],defaultId:0,cancelId:0});if(choice.response===1)await clipboard.writeText(record.draft);}
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
  if(desktopWorkspace)ses.webRequest.onBeforeSendHeaders({urls:['http://*/*','https://*/*']},(details,callback)=>{
    const profile=store.read().profiles.find(p=>p.id===profileId);
    if(!profile||pendingProfiles.has(profileId)){callback({cancel:true});return;}
    const languages=(details.webContentsId===undefined?undefined:pageLanguages.get(details.webContentsId))||preferredLanguages(profile.language,app.getPreferredSystemLanguages());
    const header=languages.split(',').map((language,i)=>i?language+';q='+Math.max(0.1,1-i*0.1).toFixed(1):language).join(',');
    callback({requestHeaders:{...details.requestHeaders,'Accept-Language':header}});
  });
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
  if(desktopWorkspace&&assetStore) {
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
function secureWebPreferences(profileId:string){
 const profile=profileById(profileId);
 // Related login windows may share a renderer; native --lang avoids competing
 // process-wide Emulation locale controllers while keeping Intl consistent.
 const additionalArguments=desktopWorkspace&&profile.language&&profile.language!=='system'?[`--lang=${profile.language}`]:[];
 return{session:configureSession(profileId),sandbox:true,contextIsolation:true,nodeIntegration:false,webSecurity:true,allowRunningInsecureContent:false,spellcheck:true,backgroundThrottling:!desktopWorkspace,additionalArguments};
}
function pageEnvironment(profile:Profile,metadata:NativeAgentMetadata,contents:WebContents):PageEnvironment{return{metadata,userAgent:contents.getUserAgent(),acceptLanguages:preferredLanguages(profile.language,app.getPreferredSystemLanguages()),locale:profile.language&&profile.language!=='system'?profile.language.replaceAll('-','_'):'',timezoneId:profile.timeZone&&profile.timeZone!=='system'?profile.timeZone:''};}
async function prepareProfilePage(contents:WebContents,profile:Profile,bootstrap:boolean,inherited?:PageEnvironment):Promise<void>{
 let timer:ReturnType<typeof setTimeout>|undefined;
 const setup=async()=>{
  if(!bootstrap)await new Promise<void>(resolve=>setImmediate(resolve));
  let metadata=nativeAgents.get(profile.id);
  if(bootstrap){await contents.loadFile(path.join(__dirname,'../renderer/profile-empty.html'));if(!metadata){metadata=await contents.executeJavaScript(`(async()=>{const data=navigator.userAgentData;if(!data)throw Error('原生浏览器标识不可用');return data.getHighEntropyValues(['architecture','bitness','model','platformVersion','uaFullVersion','fullVersionList','wow64']);})()`);nativeAgents.set(profile.id,metadata!);}}
  if(!metadata)throw Error('账号环境尚未准备好，请从主标签页重新打开登录窗口。');
  await pageProtocol(contents).configure(inherited||pageEnvironment(profile,metadata,contents));
 };
 try{await Promise.race([setup(),new Promise<never>((_,reject)=>{timer=setTimeout(()=>reject(Error('账号语言与时区初始化超时，请重新加载网页。')),10000);})]);}finally{clearTimeout(timer);}
}
function configureRemote(contents: WebContents, profileId: string,initial?:Promise<void>,inherited?:PageEnvironment): void {
 if(desktopWorkspace)contents.on('before-input-event',(_event,input)=>{contents.setIgnoreMenuShortcuts(input.type==='keyDown'&&(input.meta||input.control)&&input.key.toLowerCase()==='k');});
 if(desktopWorkspace)protectRemoteInput(contents,fixtureOrigin);
 if(desktopWorkspace)installImageContextMenu(contents,{window:()=>win,notice,fixtureOrigin});
 if(desktopWorkspace){
  pageLanguages.set(contents.id,inherited?.acceptLanguages||preferredLanguages(profileById(profileId).language,app.getPreferredSystemLanguages()));
  const ready=initial||prepareProfilePage(contents,profileById(profileId),false,inherited);if(!initial)void ready.catch(error=>{if(!contents.isDestroyed())report(error);});
  contents.once('destroyed',()=>pageLanguages.delete(contents.id));
 }

  contents.on('did-navigate',(_event,url)=>{if(isAllowedURL(url,fixtureOrigin)&&!isChatURL(url)&&(!fixtureOrigin||!url.startsWith(fixtureOrigin)))recordLink(profileId,'login-page',url,contents);});
  contents.on('will-navigate', (event, url) => { if (!isAllowedURL(url, fixtureOrigin)) { event.preventDefault(); const id=recordLink(profileId,'navigation',url,contents);void externalFromPage(url,id).catch(report); } });
  contents.on('will-redirect', (event, url) => { if (!isAllowedURL(url, fixtureOrigin)) { event.preventDefault();recordLink(profileId,'redirect',url,contents);notice('已拦截不受支持的跳转，可在账号菜单的「登录与链接记录」查看站点与原因。'); } });
  contents.setWindowOpenHandler(({ url }) => {
    if (url !== 'about:blank' && !isAllowedURL(url, fixtureOrigin)) {const id=recordLink(profileId,'popup',url,contents);void externalFromPage(url,id).catch(report); return { action: 'deny' }; }
    if (isChatURL(url) && !/^\/(auth|api\/auth)(\/|$)/.test(new URL(url).pathname)) { queueCommandInternal(() => createTab(profileId, url)); return { action: 'deny' }; }
    return { action: 'allow', overrideBrowserWindowOptions: { parent: win, width: 560, height: 760, minWidth: 420, minHeight: 500, title: `${profileById(profileId).name} · 登录窗口`, webPreferences: {...secureWebPreferences(profileId),additionalArguments:pageProtocol(contents).snapshot()?.locale?[`--lang=${pageProtocol(contents).snapshot()!.locale.replaceAll('_','-')}`]:[]} } };
  });
  contents.on('did-create-window', popup => {
    popups.set(popup, profileId); configureRemote(popup.webContents,profileId,undefined,pageProtocol(contents).snapshot());
    popup.on('page-title-updated', event => { event.preventDefault(); const url = popup.webContents.getURL(); let host = ''; try { host = new URL(url).hostname; } catch { /* about:blank */ } popup.setTitle(`${profileById(profileId).name} · ${host || '登录窗口'}`); });
    popup.once('closed', () => {popups.delete(popup);hiddenPopups.delete(popup);});
  });
}
function ensureView(tab: Tab): WebContentsView {
  const existing = views.get(tab.id); if (existing && !existing.webContents.isDestroyed()) return existing;
  const view = new WebContentsView({ webPreferences: secureWebPreferences(tab.profileId) });
  views.set(tab.id, view); win!.contentView.addChildView(view); view.setBackgroundColor('#ffffff');
  const c = view.webContents;const prepared=desktopWorkspace?prepareProfilePage(c,profileById(tab.profileId),true):Promise.resolve();configureRemote(c,tab.profileId,prepared);
  const stillCurrent = () => views.get(tab.id) === view && !pendingProfiles.has(tab.profileId) && store.read().tabs.some(t => t.id === tab.id);
  const stillKnown=()=>views.get(tab.id)===view&&!pendingProfiles.has(tab.profileId)&&[...store.read().tabs,...store.read().closedTabs].some(t=>t.id===tab.id);
  const update = (patch: Partial<TabStatus>) => {
    if (!stillKnown() || c.isDestroyed()) return;
    status.set(tab.id, { loading: false, ...status.get(tab.id), ...patch, url: c.getURL(), canGoBack: c.navigationHistory.canGoBack(), canGoForward: c.navigationHistory.canGoForward() }); layout(); publish();
  };
  c.on('did-start-loading', () => update({ loading: true, error: undefined, crashed: false }));
  c.on('did-stop-loading', () => update({ loading: false }));
  c.on('did-fail-load', (_e, code, description, _url, isMainFrame) => { if (isMainFrame && code !== -3) update({ loading: false, error: `${description} (${code})` }); });
  c.on('render-process-gone', (_e, details) => update({ loading: false, crashed: true, error: `网页进程已退出（${details.reason}），可重新加载。` }));
  c.on('page-title-updated', (_event, title) => {
    if (!stillKnown()) return;
    const nextTitle = title.replace(/[\u0000-\u001f\u007f]/g, ' ').slice(0, 240);
    if ([...store.read().tabs,...store.read().closedTabs].find(t => t.id === tab.id)?.title !== nextTitle) store.commit(s => { const t = [...s.tabs,...s.closedTabs].find(t => t.id === tab.id); if (t) t.title = nextTitle; });
    publish();
  });
  const navigated = (_e: unknown, url: string) => {
    if (!stillKnown()) return;
    if ((isChatURL(url) && !/^\/(auth|api\/auth)(\/|$)/.test(new URL(url).pathname)) || (fixtureOrigin && url.startsWith(`${fixtureOrigin}/`))) {
      const clean = new URL(url); for (const key of ['code', 'state', 'token', 'access_token', 'session_token']) clean.searchParams.delete(key);
      if ([...store.read().tabs,...store.read().closedTabs].find(t => t.id === tab.id)?.url !== clean.href) store.commit(s => { const t = [...s.tabs,...s.closedTabs].find(t => t.id === tab.id); if (t) t.url = clean.href; });
    }
    update({ error: undefined });
  };
  c.on('did-navigate', navigated); c.on('did-navigate-in-page', navigated);
  restoringPages.add(tab.id);
  const ready=prepared.then(async()=>{
   if(!stillCurrent()||c.isDestroyed())return;
   if(recovery){try{const record=await recovery.load(tab.profileId,tab.id,tab.url);if(stillCurrent()&&record?.page){savedPages.set(tab.id,record.page);offlinePages.set(tab.id,record.page);layout();publish();}}catch(error){report(error);}}
   if(!stillCurrent()||c.isDestroyed())return;await c.loadURL(tab.url);if(!stillCurrent()||c.isDestroyed())return;
   if(desktopWorkspace){store.commit(s=>{const t=s.tabs.find(t=>t.id===tab.id);if(t)t.loaded=true;});await restoreTab(tab,c);}
   if(stillCurrent()&&!c.isDestroyed()){
    const usable=await c.executeJavaScript(`!!document.querySelector('[data-message-id],[data-message-author-role],#prompt-textarea,input[type="password"]')`);
    if(usable||resumeURL(c.getURL(),fixtureOrigin)!==resumeURL(tab.url,fixtureOrigin)){offlinePages.delete(tab.id);layout();publish();}
   }
  }).catch(error=>update({loading:false,error:error.message})).finally(()=>{if(views.get(tab.id)===view)restoringPages.delete(tab.id);});pageReady.set(tab.id,ready);
  layout();
  return view;
}
function showActive(focus = true): void {
  const p = activeProfile(); const t = store.read().tabs.find(t => t.id === p.activeTabId)!;
  const view = ensureView(t); layout(); if (focus && !overlayOpen && !panel) view.webContents.focus(); publish();
}
function destroyView(id: string): void {
  const view = views.get(id);recentClosed.forget(id);views.delete(id);status.delete(id);viewLayout.delete(id);pageReady.delete(id);offlinePages.delete(id);restoringPages.delete(id);
  if (view) { if (win && !win.isDestroyed()) win.contentView.removeChildView(view); if (!view.webContents.isDestroyed()) view.webContents.close({ waitForBeforeUnload: false }); }
}
function createHandoffTab(profileId:string):{tabId:string;contents:WebContents}{
  profileById(profileId);if(store.read().tabs.length>=MAX_TABS)throw Error('标签页数量已满，请先关闭不需要的标签页。');
  const tab=makeTab(profileId,homepage());store.commit(s=>{s.tabs.push(tab);});const view=ensureView(tab);layout();publish();return{tabId:tab.id,contents:view.webContents};
}
function revealHandoff(job:HandoffJob):void {
  const p=profileById(job.request.targetProfileId);const tab=store.read().tabs.find(t=>t.id===job.tabId&&t.profileId===p.id);
  if(!tab)throw Error('目标标签页已关闭。可从该账号的 ChatGPT 历史记录查找已发送的对话。');
  assetRuntime?.revoke();panel=undefined;overlayOpen=false;overlayImage=undefined;store.commit(s=>{s.activeProfileId=p.id;s.profiles.find(x=>x.id===p.id)!.activeTabId=tab.id;});showActive();
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
    assetConversions?.cancelProfile(id);handoffRunner?.cancelProfile(id);
    const tracker=assetDownloads.get(id);
    if(tracker){for(const assetId of tracker.activeIds())tracker.cancel(assetId);await tracker.whenIdle();if(remove){tracker.dispose();assetDownloads.delete(id);}}
    assetRuntime?.revoke(id);
    await recovery?.removeProfile(id);
    for(const t of [...store.read().tabs,...store.read().closedTabs].filter(t=>t.profileId===id)){localDrafts.delete(t.id);savedPages.delete(t.id);offlinePages.delete(t.id);snapshotTimes.delete(t.id);blockedRecovery.delete(t.id);if(views.has(t.id))destroyView(t.id);}
    for (const [popup, pid] of popups) if (pid === id && !popup.isDestroyed()) popup.destroy();
    for (const t of store.read().tabs.filter(t => t.profileId === id)) destroyView(t.id);
    const ses = configureSession(id); await ses.clearStorageData(); await ses.clearCache(); await ses.clearAuthCache(); permissionGrants.set(id, new Set());
    store.commit(s => {
      s.tabs = s.tabs.filter(t => t.profileId !== id); s.closedTabs = s.closedTabs.filter(t => t.profileId !== id);
      if (remove) { nativeAgents.delete(id);s.profiles = s.profiles.filter(p => p.id !== id); if (s.activeProfileId === id) s.activeProfileId = s.profiles[0].id; }
      else { const tab = makeTab(id, homepage()); s.tabs.push(tab); const p=s.profiles.find(p => p.id === id)!;p.activeTabId = tab.id;p.workspace=undefined;p.recoveryEpoch=(p.recoveryEpoch||0)+1; }
    });
    notice(remove ? `已移除 ${p.name}` : `${p.name} 的本机登录数据已清除`);
  } finally {pendingProfiles.delete(id);if(id===store.read().activeProfileId){panel=undefined;savePanel();}showActive(false);}
}
function validateCommand(value: unknown): asserts value is Command {
  if (!value || typeof value !== 'object') throw new Error('无效操作');
  const c = value as Record<string, unknown>;
  if (typeof c.type !== 'string') throw new Error('无效操作');
  if(['recovery:authorize','recovery:defer','recovery:settings'].includes(c.type)){if(process.platform!=='darwin')throw Error('此恢复授权入口仅用于 macOS');return;}
  const known = ['profile:add','profile:edit','profile:activate','profile:remove','profile:clear','profile:move','tab:new','tab:activate','tab:close','tab:pin','tab:menu','tab:reorder','tab:restore','back','forward','reload','home','fullscreen','maximize','external','overlay','panel','offline:live','offline:retry','update:check','update:idle'];
  if (!known.includes(c.type)) throw new Error('未知操作');
  if (['profile:edit','profile:activate','profile:remove','profile:clear','profile:move','tab:activate','tab:close','tab:pin','tab:menu'].includes(c.type) && (typeof c.id !== 'string' || !/^[a-f0-9-]{36}$/.test(c.id))) throw new Error('无效标识');
  if (['profile:add','profile:edit'].includes(c.type) && (!validLabel(c.name,40,true) || !validLabel(c.email,120) || !(COLORS as readonly unknown[]).includes(c.color))) throw new Error('请填写 1–40 字账号名称、有效备注与颜色。');
  if(['profile:add','profile:edit'].includes(c.type)&&((c.language!==undefined&&!validBrowserLanguage(c.language))||(c.timeZone!==undefined&&!validTimeZone(c.timeZone))))throw Error('请选择有效浏览器语言与 IANA 时区。');
  if (c.type === 'profile:move' && c.direction !== -1 && c.direction !== 1) throw new Error('无效排序');
  if (c.type === 'tab:new' && c.url !== undefined && !isAllowedURL(c.url,fixtureOrigin)) throw new Error('网址不在支持范围');
  if (c.type === 'tab:reorder' && (!Array.isArray(c.ids) || c.ids.length > MAX_TABS || !c.ids.every(v => typeof v === 'string'))) throw new Error('无效排序');
  if(c.type==='update:idle'&&typeof c.enabled!=='boolean')throw Error('无效更新设置');
  if (c.type === 'overlay' && typeof c.open !== 'boolean') throw new Error('无效窗口状态');
  if (c.type === 'panel' && (!desktopWorkspace||(c.panel!==undefined&&!['assets','skills','agents'].includes(c.panel as string)))) throw new Error('工作台入口无效');
}
async function dispatch(c: Command): Promise<void> {
  recordAppActivity();
  if(c.type==='recovery:authorize'){await authorizeRecovery(true);return;}
  if(c.type==='recovery:defer'){if(recoveryChanging)throw Error('授权正在进行，请先完成或取消系统验证');recoveryAccess?.defer();startPages();publish();return;}
  if(c.type==='recovery:settings'){await recoverySettings();return;}
  if(recoveryAccess&&!pagesStarted)throw Error('请先选择恢复授权，或先打开网页');
  if(quitPending)throw Error('正在保存恢复记录，请稍候。');
  if(desktopWorkspace&&['profile:add','profile:activate','tab:activate','tab:close','tab:new','tab:restore','home','back','forward','reload'].includes(c.type))await checkpointTab(activeProfile().activeTabId);
  if(desktopWorkspace&&c.type==='tab:close'&&c.id!==activeProfile().activeTabId)await checkpointTab(c.id);
  switch (c.type) {
    case 'profile:add': {
      if (store.read().profiles.length >= MAX_PROFILES) throw new Error(`最多支持 ${MAX_PROFILES} 个账号空间。`);
      savePanel();panel=undefined;const id = randomUUID(), tab = makeTab(id, homepage());
      store.commit(s => { s.profiles.push({ id, name: c.name.trim(), email: c.email.trim(), color: c.color, activeTabId: tab.id, createdAt: Date.now(),language:c.language||DEFAULT_LANGUAGE,timeZone:c.timeZone||'system' }); s.tabs.push(tab); s.activeProfileId = id; }); showActive(false); break;
    }
    case 'profile:edit': profileById(c.id); store.commit(s => { Object.assign(s.profiles.find(p => p.id === c.id)!, { name: c.name.trim(), email: c.email.trim(), color: c.color,...(c.language!==undefined?{language:c.language}:{}),...(c.timeZone!==undefined?{timeZone:c.timeZone}:{}) }); }); layout(); break;
    case 'profile:activate': profileById(c.id);savePanel();assetRuntime?.revoke(); store.commit(s => { s.activeProfileId = c.id; });panel=desktopWorkspace?activeProfile().workspace?.panel:undefined;showActive(); break;
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
      const closingContents=views.get(tab.id)?.webContents;const retain=desktopWorkspace&&closingContents&&!closingContents.isDestroyed()&&!!resumeURL(closingContents.getURL(),fixtureOrigin)&&!await deadline(closingContents.executeJavaScript(`!![...document.querySelectorAll('input[type="password"],input[autocomplete="one-time-code"]')].find(e=>e.getClientRects().length)`),500).catch(()=>true);
      store.commit(next => {
        next.tabs = next.tabs.filter(t => t.id !== tab.id); next.closedTabs = [...next.closedTabs, {...tab,closedPosition:s.tabs.filter(t=>t.profileId===tab.profileId).findIndex(t=>t.id===tab.id)}].slice(-24);
        const profile = next.profiles.find(p => p.id === tab.profileId)!, remaining = next.tabs.filter(t => t.profileId === profile.id);
        if (!remaining.length) { const replacement = makeTab(profile.id, homepage()); next.tabs.push(replacement);closedReplacements.set(tab.id,replacement.id); profile.activeTabId = replacement.id; }
        else if (profile.activeTabId === tab.id) { const siblings = s.tabs.filter(t => t.profileId === profile.id); profile.activeTabId = remaining[Math.max(0, Math.min(siblings.findIndex(t => t.id === tab.id) - 1, remaining.length - 1))].id; }
      });if(retain)recentClosed.remember(tab.id);else destroyView(tab.id);pruneRecovery();showActive();break;
    }
    case 'tab:pin': store.commit(s => { const t = s.tabs.find(t => t.id === c.id && t.profileId === s.activeProfileId); if (!t) throw new Error('标签不属于当前账号'); t.pinned = !t.pinned; const siblings = s.tabs.filter(x => x.profileId === s.activeProfileId); const ordered = [...siblings.filter(x => x.pinned), ...siblings.filter(x => !x.pinned)]; let i = 0; s.tabs = s.tabs.map(x => x.profileId === s.activeProfileId ? ordered[i++] : x); }); break;
    case 'tab:reorder': store.commit(s => { const tabs = s.tabs.filter(t => t.profileId === s.activeProfileId); if (tabs.length !== c.ids.length || new Set(c.ids).size !== tabs.length || !tabs.every(t => c.ids.includes(t.id))) throw new Error('标签排序已过期，请重试'); const ordered = c.ids.map(id => tabs.find(t => t.id === id)!); if (ordered.some((t,i) => t.pinned && ordered.slice(0,i).some(x => !x.pinned))) throw new Error('固定标签应保留在左侧'); let i = 0; s.tabs = s.tabs.map(t => t.profileId === s.activeProfileId ? ordered[i++] : t); }); break;
    case 'tab:restore': {
      const s = store.read(), old = [...s.closedTabs].reverse().find(t => t.profileId === s.activeProfileId);
      if (!old) { notice('当前账号没有可恢复的标签页'); break; }
      if (s.tabs.length >= MAX_TABS) throw new Error('标签页数量已达上限');
      if(!desktopWorkspace){
        store.commit(next=>{const tab={...old,id:randomUUID()};next.closedTabs=next.closedTabs.filter(t=>t.id!==old.id);next.tabs.push(tab);next.profiles.find(p=>p.id===tab.profileId)!.activeTabId=tab.id;const ordered=next.tabs.filter(t=>t.profileId===tab.profileId).sort((a,b)=>Number(b.pinned)-Number(a.pinned));let index=0;next.tabs=next.tabs.map(t=>t.profileId===tab.profileId?ordered[index++]:t);});showActive();break;
      }
      const id=old.id;recentClosed.take(old.id);const cached=views.get(old.id)?.webContents;if(cached&&!cached.isDestroyed()&&(!cached.getURL()||cached.getURL()==='about:blank'||cached.getURL().startsWith('file:')))destroyView(old.id);panel=undefined;
      const replacement=closedReplacements.get(old.id);let dropReplacement=false;
      if(replacement){const t=s.tabs.find(t=>t.id===replacement),c=views.get(replacement)?.webContents;if(t&&t.url===homepage()&&c&&!c.isDestroyed()){const captured=await captureResume(c,t.profileId,t.id,fixtureOrigin).catch(()=>undefined);dropReplacement=!!captured&&!captured.draft&&resumeURL(c.getURL(),fixtureOrigin)===resumeURL(homepage(),fixtureOrigin);}}
      store.commit(next=>{if(dropReplacement)next.tabs=next.tabs.filter(t=>t.id!==replacement);const tab={...old,id};delete tab.closedPosition;next.closedTabs=next.closedTabs.filter(t=>t.id!==old.id);const siblings=next.tabs.filter(t=>t.profileId===tab.profileId);siblings.splice(Math.min(old.closedPosition??siblings.length,siblings.length),0,tab);siblings.sort((a,b)=>Number(b.pinned)-Number(a.pinned));next.tabs=[...next.tabs.filter(t=>t.profileId!==tab.profileId),...siblings];next.profiles.find(p=>p.id===tab.profileId)!.activeTabId=id;});
      if(dropReplacement&&replacement)destroyView(replacement);closedReplacements.delete(old.id);showActive();break;
    }
    case 'update:idle': updates?.setIdleAllowed(c.enabled);break;
    case 'update:check': void checkUpdates(true).catch(report);break;
    case 'offline:live': offlinePages.delete(activeProfile().activeTabId);layout();break;
    case 'offline:retry': {const id=activeProfile().activeTabId;destroyView(id);showActive();break;}
    case 'back': if (activeContents()?.navigationHistory.canGoBack()) activeContents()!.navigationHistory.goBack(); break;
    case 'forward': if (activeContents()?.navigationHistory.canGoForward()) activeContents()!.navigationHistory.goForward(); break;
    case 'reload': { const t = store.read().tabs.find(t => t.id === activeProfile().activeTabId)!; const c = views.get(t.id)?.webContents; if (!c || c.isDestroyed() || c.isCrashed()) { destroyView(t.id); ensureView(t); } else c.reload(); break; }
    case 'home': void activeContents()?.loadURL(homepage()).catch(report); break;
    case 'fullscreen': win?.setFullScreen(!win.isFullScreen()); break;
    case 'maximize': if (win?.isMaximized()) win.unmaximize(); else win?.maximize(); break;
    case 'external': { const url = activeContents()?.getURL(); if (url && isWebURL(url)) await shell.openExternal(url); else notice('当前页面不能在系统浏览器打开'); break; }
    case 'panel': panel=c.panel;savePanel();overlayOpen=false;overlayImage=undefined;layout();if(panel)win?.webContents.focus();else activeContents()?.focus();break;
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
      overlayOpen=c.open;if(!c.open)overlayImage=undefined;layout();if(c.open||panel)win?.webContents.focus();else activeContents()?.focus();break;
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
  ipcMain.on('updates:cancel-countdown',event=>{if(event.sender===idleNoticeWindow?.webContents&&event.senderFrame===event.sender.mainFrame&&event.senderFrame.url.startsWith('app://shell/update-notice.html'))recordAppActivity();});
  ipcMain.handle('login-links:list',event=>{if(!trusted(event)||!desktopWorkspace)throw new Error('拒绝非本地工具栏请求');if(!loginDiagnostics)throw new Error(loginDiagnosticsError||'登录记录暂不可用');return loginDiagnostics.list();});
  ipcMain.handle('login-links:clear',event=>{if(!trusted(event)||!desktopWorkspace)throw new Error('拒绝非本地工具栏请求');if(!loginDiagnostics)throw new Error(loginDiagnosticsError||'登录记录暂不可用');loginDiagnostics.clear();});
  if(desktopWorkspace)ipcMain.handle('profiles:workbench-view',(event,profileId:unknown,value:unknown,epoch:unknown)=>{
    if(!trusted(event)||typeof profileId!=='string'||!Number.isSafeInteger(epoch)||!validWorkspace({workbench:value}))throw Error('界面恢复参数或账号无效');
    const profile=store.read().profiles.find(p=>p.id===profileId);if(!profile||pendingProfiles.has(profileId)||(profile.recoveryEpoch||0)!==epoch)return;if(JSON.stringify(profile.workspace?.workbench)===JSON.stringify(value))return;
    store.commit(state=>{const p=state.profiles.find(p=>p.id===profileId)!;p.workspace={...p.workspace,workbench:structuredClone(value) as WorkbenchViewState};});publish();
  });
  ipcMain.handle('profiles:snapshot', event => { if (!trusted(event)) throw new Error('拒绝非本地工具栏请求'); return snapshot(); });
  ipcMain.handle('profiles:command', (event, value: unknown) => {
    if (!trusted(event)) return { ok: false, error: '拒绝非本地工具栏请求' } satisfies Reply;
    const run = async (): Promise<Reply> => { try { validateCommand(value); await dispatch(value); return { ok: true }; } catch (error) { return { ok: false, error: error instanceof Error ? error.message : '操作失败' }; } };
    const result = commandQueue.then(run, run); commandQueue = result.then(() => undefined); return result;
  });
  if(desktopWorkspace){
    const runner=()=>{if(!handoffRunner)throw Error(handoffError||'网页处理任务暂不可用');return handoffRunner;};
    const access=(event:IpcMainInvokeEvent,profileId?:unknown)=>{if(!trusted(event))throw Error('拒绝非本地工具栏请求');if(profileId!==undefined&&profileId!==activeProfile().id)throw Error('账号已切换，请重新打开任务记录。');};
    ipcMain.handle('handoff:start',(event,request:unknown)=>{access(event);return runner().start(request,activeProfile().id);});
    ipcMain.handle('handoff:list',(event,profileId:unknown)=>{access(event,profileId);return runner().jobs.list(profileId as string);});
    ipcMain.handle('handoff:cancel',(event,profileId:unknown,id:unknown)=>{access(event,profileId);if(typeof id!=='string')throw Error('无效任务');return runner().cancel(id,profileId as string);});
    ipcMain.handle('handoff:open',(event,profileId:unknown,id:unknown)=>{access(event,profileId);if(typeof id!=='string')throw Error('无效任务');runner().open(id,profileId as string);});
    ipcMain.handle('handoff:copy',(event,profileId:unknown,id:unknown)=>{access(event,profileId);if(typeof id!=='string')throw Error('无效任务');clipboard.writeText(runner().prompt(id,profileId as string));});
  }
  if(desktopWorkspace)installWorkbenchIPC({
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
function recordAppActivity():void{idleUpdate?.activity();}
function syncIdleTimer():void{
 clearTimeout(idleTimer);idleTimer=undefined;
 if(!idleUpdate||updates?.status.phase!=='ready'||!updates.status.idleEnabled||quitting||quitPending){idleUpdate?.cancel();return;}
 const delay=idleUpdate.deadline!==undefined?1000:testMode&&process.env.PROFILES_IDLE_FAST==='1'?200:30000;
 idleTimer=setTimeout(()=>{void idleUpdate!.tick().finally(syncIdleTimer);},delay);idleTimer.unref();
}
async function safeForIdleUpdate():Promise<boolean>{
 if(!recovery||recoveryChanging||recoveryError||assetError||handoffError||overlayOpen||panel||updateDialog||menuBar?.isOpen||pendingProfiles.size||restoringPages.size||warming||assetConversions?.hasRunning())return false;
 if([...popups.keys()].some(p=>!p.isDestroyed())||[...assetDownloads.values()].some(d=>d.activeIds().length))return false;
 if(handoffRunner?.jobs.all().some(j=>['preparing','opening','uploading','drafting','sending'].includes(j.status)))return false;
 if(!views.size||store.read().tabs.some(t=>t.loaded&&!views.has(t.id)))return false;
 for(const view of views.values())if(!await pageSafeForIdle(view.webContents,fixtureOrigin))return false;
 return true;
}
function showIdleNotice(deadline?:number):void{
 idleNoticeWindow?.destroy();idleNoticeWindow=undefined;updates?.setIdleDeadline(deadline);
 if(deadline===undefined||quitting)return;
 const area=screen.getDisplayNearestPoint(screen.getCursorScreenPoint()).workArea,width=390,height=205;
 const note=new BrowserWindow({width,height,x:area.x+area.width-width-20,y:area.y+area.height-height-20,show:false,frame:false,focusable:false,resizable:false,movable:false,minimizable:false,maximizable:false,skipTaskbar:true,alwaysOnTop:true,backgroundColor:'#fafafa',webPreferences:{preload:path.join(__dirname,'update-notice-preload.cjs'),sandbox:true,contextIsolation:true,nodeIntegration:false,webSecurity:true}});
 idleNoticeWindow=note;note.setVisibleOnAllWorkspaces(true,{visibleOnFullScreen:true});note.webContents.setWindowOpenHandler(()=>({action:'deny'}));note.webContents.on('will-navigate',e=>e.preventDefault());
 note.once('ready-to-show',()=>{if(idleNoticeWindow===note&&idleUpdate?.deadline===deadline)note.showInactive();});
 note.once('closed',()=>{if(idleNoticeWindow===note)idleNoticeWindow=undefined;});
 void note.loadURL('app://shell/update-notice.html?deadline='+deadline).catch(()=>{updates?.setIdleAllowed(false);recordAppActivity();notice('自动安装提示无法显示，已延后到下次退出时安装。');});
}
function initializeIdleUpdate():void{
 const fast=testMode&&process.env.PROFILES_IDLE_FAST==='1';
 idleUpdate=new IdleUpdate({idleMs:fast?1000:1800000,countdownMs:fast?7000:60000,
  eligible:()=>updates?.status.phase==='ready'&&updates.status.idleEnabled===true&&!quitting,
  focused:()=>menuBar?.isOpen===true||BrowserWindow.getAllWindows().some(w=>w!==idleNoticeWindow&&w.isFocused()),safe:safeForIdleUpdate,
  countdown:showIdleNotice,visible:()=>idleNoticeWindow?.isVisible()===true,
  install:epoch=>{idleInstallEpoch=epoch;updates?.restartAfterInstall(true);app.quit();}
 });syncIdleTimer();
}
app.on('web-contents-created',(_event,contents)=>{
 contents.on('before-input-event',()=>{if(contents!==idleNoticeWindow?.webContents)recordAppActivity();});
 contents.on('before-mouse-event',(_event,mouse)=>{if(contents!==idleNoticeWindow?.webContents&&!['mouseEnter','mouseLeave'].includes(mouse.type))recordAppActivity();});
});
app.on('browser-window-focus',(_event,window)=>{if(window!==idleNoticeWindow)recordAppActivity();});
async function offerUpdate(manual=false):Promise<void>{
 if(!updates||updateDialog||quitPending||quitting||!win?.isVisible()||win.isDestroyed())return;
 const state=updates.status,manifest=updates.manifest;
 if(state.phase==='ready'){
  if(!manual&&readyUpdatePrompts.has(state.version||''))return;readyUpdatePrompts.add(state.version||'');updateDialog=true;try{const choice=await dialog.showMessageBox(win,{type:'info',title:'更新已准备',message:`${state.version} 已下载并校验`,detail:'可以立即保存并重启，或下次退出时安装。勾选空闲安装后，仅在本 App 处于后台且 30 分钟无操作、所有标签确认无生成和文件任务时，提前 60 秒提示；回到应用或取消会延后。其他软件的操作不影响计时。',checkboxLabel:'允许本 App 空闲 30 分钟后保存、安装并在后台重新打开',checkboxChecked:updates.idleChoiceMade()?state.idleEnabled===true:true,buttons:['下次退出时安装','保存并立即重启安装'],defaultId:0,cancelId:0});if(choice.response===1){updates.restartAfterInstall();app.quit();}else updates.setIdleAllowed(choice.checkboxChecked);}finally{updateDialog=false;recordAppActivity();syncIdleTimer();}return;
 }
 if(state.phase!=='available'||!manifest||!manual&&offeredUpdates.has(manifest.version+':'+shanghaiDay(Date.now()).day))return;
 offeredUpdates.add(manifest.version+':'+shanghaiDay(Date.now()).day);updateDialog=true;let source=0;
 try{const choice=await dialog.showMessageBox(win,{type:'question',title:'发现新版本',message:`ChatGPT Studio ${manifest.version}`,detail:manifest.notes+'\n\n确认后后台下载、校验并准备安装，你可以继续使用。下次真正退出 App 时安装，登录数据保留。国内加速使用第三方 gh-proxy.org。',buttons:['稍后','后台下载更新','国内加速下载'],defaultId:1,cancelId:0});source=choice.response;}finally{updateDialog=false;}
 if(source===0)return;
 void updates.download(source===2).then(()=>{notice('更新已准备，下次退出时安装；可在「检查更新」中立即重启。');void offerUpdate().catch(report);}).catch(error=>{if(!quitPending&&!quitting)report(error);});
}
async function checkUpdates(manual=false):Promise<void>{
 if(manual)recordAppActivity();
 if(!updates){if(manual)notice('自动更新将用于已安装的 macOS 客户端，开发模式和其他平台暂不替换应用。');return;}
 if(manual&&updates.status.phase==='downloading'){notice(`更新正在后台下载 ${updates.status.progress||0}%`);return;}
 await updates.check();await offerUpdate(manual);
 if(manual&&['idle','error'].includes(updates.status.phase))notice(updates.status.message||'暂时无法检查更新');
}
function installMenu(): void {
  const action = (command: Command) => () => queueCommandInternal(() => dispatch(command));
  const tabStep = (delta: number) => () => { const p = activeProfile(), tabs = store.read().tabs.filter(t => t.profileId === p.id), i = tabs.findIndex(t => t.id === p.activeTabId); action({ type: 'tab:activate', id: tabs[(i+delta+tabs.length)%tabs.length].id })(); };
  const template: Electron.MenuItemConstructorOptions[] = [
    { label: 'ChatGPT Studio', submenu: [{ role: 'about', label: '关于 ChatGPT Studio' }, ...(process.platform==='darwin'?[{id:'check-updates',label:'检查更新…',click:()=>void checkUpdates(true).catch(report)}]:[]), ...(process.platform === 'darwin' ? [{ type: 'separator' as const }, { role: 'services' as const, label: '服务' }, { role: 'hide' as const, label: '隐藏' }, { role: 'hideOthers' as const, label: '隐藏其他' }, { role: 'unhide' as const, label: '显示全部' }] : []), { type: 'separator' }, { role: 'quit', label: '退出' }] },
    { label: '文件', submenu: [...(desktopWorkspace?[{id:'chat-search',label:'搜索 ChatGPT',accelerator:'CmdOrCtrl+K',click:()=>void openChatSearch().catch(report)},...(process.platform==='darwin'?[{id:'recovery-privacy',label:'隐私与本地恢复…',click:()=>void recoverySettings().catch(report)}]:[]),{id:'recovery-draft',label:'查看本地恢复草稿',click:()=>void showLocalDraft().catch(report)},{type:'separator' as const}]:[]),{ label: '新建标签页', accelerator: 'CmdOrCtrl+T', click: action({type:'tab:new'}) }, { label: '恢复关闭的标签页', accelerator: 'CmdOrCtrl+Shift+T', click: action({type:'tab:restore'}) }, { label: '添加账号', accelerator: 'CmdOrCtrl+Shift+N', click: () => win?.webContents.send('profiles:shortcut','add-profile') }, { type:'separator' }, { label:'关闭标签页', accelerator:'CmdOrCtrl+W', click: () => { const focused=BrowserWindow.getFocusedWindow(); if(focused&&focused!==win) focused.close(); else if(panel)win?.webContents.send('profiles:shortcut','close-workbench');else action({type:'tab:close',id:activeProfile().activeTabId})(); } }, { role:'close', label:process.platform==='darwin'?'关闭窗口（后台保留）':'关闭窗口', accelerator:'CmdOrCtrl+Shift+W' }] },
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
function installMenuBar():void{
 if(process.platform!=='darwin')return;
 menuBar=new MacMenuBar(path.join(__dirname,'menuBarTemplate.png'),{
  read:()=>({profiles:store.read().profiles.map(({id,name,email})=>({id,name,email})),activeProfileId:store.read().activeProfileId,windowVisible:!!win&&!win.isDestroyed()&&win.isVisible()&&!win.isMinimized(),canInteract:pagesStarted&&!quitPending&&!quitting&&!recoveryChanging&&!pendingProfiles.size,canCheckUpdates:!!updates&&!quitPending&&!quitting,quitting:quitting||quitPending}),
  activity:recordAppActivity,
  toggleWindow:()=>{if(quitting)return;if(win&&!win.isDestroyed()&&win.isVisible()&&!win.isMinimized())win.close();else reopenWindow();},
  command:command=>queueCommandInternal(async()=>{try{await dispatch(command);}finally{reopenWindow();}}),
  checkUpdates:()=>{reopenWindow();void checkUpdates(true).catch(report);},
  quit:()=>app.quit()
 });
}
function createWindow(): void {
  const b = store.read().bounds;
  const onScreen = screen.getAllDisplays().some(d => b.x !== undefined && b.y !== undefined && b.x+Math.min(b.width,200)>d.workArea.x && b.x<d.workArea.x+d.workArea.width && b.y+40>d.workArea.y && b.y<d.workArea.y+d.workArea.height);
  const chrome: Electron.BrowserWindowConstructorOptions = process.platform === 'win32'
    ? { titleBarStyle:'hidden', titleBarOverlay:{color:'#f8f8f8',symbolColor:'#171717',height:SHELL_HEIGHT},autoHideMenuBar:true,icon:path.join(__dirname,'icon.png') }
    : { titleBarStyle:'hiddenInset',trafficLightPosition:{x:18,y:17} };
  win = new BrowserWindow({ ...b, ...(!onScreen ? {x:undefined,y:undefined} : {}), ...chrome, minWidth:800,minHeight:600,movable:true,title:'ChatGPT Studio',backgroundColor:'#ffffff',show:false,webPreferences:{preload:path.join(__dirname,'preload.cjs'),sandbox:true,contextIsolation:true,nodeIntegration:false,webSecurity:true} });
  if (process.platform === 'win32') win.setMenuBarVisibility(false);
  win.webContents.setWindowOpenHandler(()=>({action:'deny'}));
  win.webContents.on('will-navigate',event=>event.preventDefault());
  for(const event of ['show','hide','minimize','restore'] as const)win.on(event,()=>menuBar?.refresh());
  win.on('focus',()=>{if(!backgroundModePending&&!pagesStarted&&recoveryAccess?.status.phase==='pending'&&!recoveryAccess.needsConsent)void authorizeRecovery();});
  win.on('resize',()=>{recordAppActivity();layout();clearTimeout(boundsTimer);boundsTimer=setTimeout(()=>{try{saveBounds();}catch(error){report(error);}},250);});
  win.on('move',()=>{recordAppActivity();clearTimeout(boundsTimer);boundsTimer=setTimeout(()=>{try{saveBounds();}catch(error){report(error);}},250);});
  win.on('enter-full-screen',()=>{layout();publish();});win.on('leave-full-screen',()=>{layout();publish();});
  win.on('close',event=>{if(desktopWorkspace&&!quitting){recordAppActivity();event.preventDefault();if(process.platform==='win32'){app.quit();return;}try{saveBounds();}catch(error){report(error);}void checkpointWorkspace().catch(report);for(const popup of popups.keys())if(!popup.isDestroyed()&&popup.isVisible()){hiddenPopups.add(popup);popup.hide();}win?.hide();return;}clearTimeout(boundsTimer);try{saveBounds();}catch(error){report(error);} for(const id of [...views.keys()])destroyView(id);for(const popup of popups.keys())if(!popup.isDestroyed())popup.destroy();});
  win.on('closed',()=>{win=undefined;lastWindowTitle='';clearTimeout(publishTimer);publishTimer=undefined;overlayOpen=false;overlayImage=undefined;panel=undefined;assetRuntime?.revoke();});
  win.once('ready-to-show',()=>{if(!backgroundModePending){win?.show();win?.focus();}if(!recoveryAccess)startPages(!backgroundModePending);else if(!backgroundModePending){if(recoveryAccess.status.phase==='ready')startPages();else if(!recoveryAccess.needsConsent)void authorizeRecovery();}const mode=desktopWorkspace&&!backgroundModePending?store.read().windowMode:undefined;if(mode?.maximized)win?.maximize();if(mode?.fullscreen)win?.setFullScreen(true);});
  void win.loadURL('app://shell/index.html').catch(error=>dialog.showErrorBox('界面加载失败',error.message));
}
if (single) {
  app.on('second-instance',reopenWindow);
  app.whenReady().then(async()=>{
    app.setAboutPanelOptions({applicationName:'ChatGPT Studio',applicationVersion:APP_VERSION});
    store = new StateStore(dataDir, fixtureOrigin);
    if(desktopWorkspace){
      try{
       const legacy={encrypt:(value:string)=>safeStorage.encryptStringAsync(value),decrypt:async(value:Buffer)=>(await safeStorage.decryptStringAsync(value)).result};
       if(process.platform==='darwin'){
        let native:PresenceKey|undefined;try{native=require(path.join(__dirname,'recovery-protection.node')) as PresenceKey;}catch{/* Legacy recovery remains available; protected records cannot bypass the missing native key provider. */}
        let attempts=0;recoveryAccess=new RecoveryAccess(dataDir,APP_VERSION,legacy,()=>testMode&&process.env.PROFILES_RECOVERY_DENY_ONCE==='1'&&attempts++===0?Promise.resolve(false):safeStorage.isAsyncEncryptionAvailable(),native);
        if(testMode&&process.env.PROFILES_RECOVERY_CONSENT!=='1'){await recoveryAccess.authorize();recovery=new RecoveryStore(dataDir!,recoveryAccess.cipher,fixtureOrigin);}
       }else{if(!await safeStorage.isAsyncEncryptionAvailable())throw Error('本机加密暂不可用；不会写入明文。');recovery=new RecoveryStore(dataDir,legacy,fixtureOrigin);}
       if(recovery){if(store.recoveryNotice)recovery.preserveOrphans();recovery.prune([...store.read().tabs,...store.read().closedTabs]);}
      }catch(error){recoveryError=error instanceof Error?error.message:'恢复记录暂不可用';}
      panel=activeProfile().workspace?.panel;
      checkpointTimer=setInterval(()=>{if(!quitPending)void checkpointTab(activeProfile().activeTabId);},2500);checkpointTimer.unref();
      try{loginDiagnostics=new LoginDiagnostics(dataDir,()=>win?.webContents.send('login-links:changed'));}catch(error){loginDiagnosticsError=error instanceof Error?error.message:'登录记录暂不可用';}
      try{assetStore=new AssetStore(dataDir);assetRuntime=new AssetRuntime(assetStore,()=>activeProfile().id,fixtureOrigin);assetRuntime.register();}catch(error){assetError=error instanceof Error?error.message:'资产库无法读取';}
      if(assetStore)assetConversions=new AssetConversions(assetStore,{rendererRoot:path.join(__dirname,'../renderer'),profileAvailable:id=>!quitting&&!pendingProfiles.has(id)&&store.read().profiles.some(p=>p.id===id),changed:assetsChanged});
      try{capabilityCatalog=new CapabilityCatalog(path.join(__dirname,'../capabilities'));}catch(error){capabilityError=error instanceof Error?error.message:'能力资源无法读取';}
    }
    if(desktopWorkspace&&assetStore&&capabilityCatalog){try{
      handoffRunner=new HandoffRunner(new HandoffStore(dataDir,fixtureOrigin),assetStore,capabilityCatalog,{
        profile:profileById,available:id=>!quitting&&!pendingProfiles.has(id)&&store.read().profiles.some(p=>p.id===id),
        open:createHandoffTab,reveal:revealHandoff,fixtureOrigin,failed:notice,
        changed:job=>{if(win&&!win.isDestroyed()){win.webContents.send('handoff:changed');if(['submitted','failed','uncertain'].includes(job.status))notice(`${job.targetProfileName}：${job.message}`);}}
      });
    }catch(error){handoffError=error instanceof Error?error.message:'网页任务记录无法读取';}}
    registerProtocol();installIPC();installMenu();createWindow();
    const updateFixture=testMode&&!!fixtureOrigin&&process.env.PROFILES_UPDATES_FIXTURE==='1';
    if(process.platform==='darwin'&&(app.isPackaged||updateFixture)){
     const updateSession=session.fromPartition('updates',{cache:false});let bundle:string|undefined;try{bundle=updateFixture?path.join(path.dirname(realpathSync(dataDir)),'Installed.app'):installedBundle(process.execPath);}catch{/* A DMG/translocated app can check versions, but automatic install requires a normal installed bundle. */}
     updates=new UpdateManager(dataDir,APP_VERSION,process.arch,(url,options)=>updateSession.fetch(updateFixture?fixtureOrigin+(url===UPDATE_FEED?'/version.json':'/archive.zip'):url,options),()=>{publish();syncIdleTimer();},bundle);
     initializeIdleUpdate();updates.start(()=>void checkUpdates().catch(report));powerMonitor.on('resume',()=>{recordAppActivity();updates?.resume(()=>void checkUpdates().catch(report));});
     win?.once('show',()=>void offerUpdate().catch(report));publish();
    }
    try{installMenuBar();}catch(error){const message=error instanceof Error?error.message:'菜单栏暂不可用';console.error('菜单栏初始化失败:',message);win?.webContents.once('did-finish-load',()=>notice(message+'，仍可从 Dock 打开应用。'));}
    app.on('activate',reopenWindow);
  }).catch(error=>{dialog.showErrorBox('无法安全恢复账号',error instanceof Error?error.message:String(error));app.quit();});
  app.on('before-quit',event=>{
   if(desktopWorkspace&&!quitting){
    event.preventDefault();if(quitPending)return;quitPending=true;clearTimeout(idleTimer);
    const epoch=idleInstallEpoch;if(epoch===undefined)recordAppActivity();
    const permitted=()=>epoch===undefined?Promise.resolve(true):idleUpdate?.permit(epoch)||Promise.resolve(false);
    void deadline(commandQueue.then(async()=>{await updates?.stopForQuit();if(recoveryChanging)throw Error('请先完成或取消系统恢复授权');if(!await permitted())throw Error('应用状态已变化，空闲安装已延后');await checkpointWorkspace();if(!await permitted())throw Error('网页仍在处理，空闲安装已延后');}),8000)
    .then(async()=>{if(!await permitted())throw Error('应用状态已变化');await updates?.installOnQuit(!testMode);quitting=true;quitPending=false;app.quit();})
    .catch(async error=>{
     quitPending=false;
     if(epoch!==undefined){idleInstallEpoch=undefined;updates?.cancelRestart();recordAppActivity();syncIdleTimer();notice('空闲安装已延后，当前页面与更新包保留。');return;}
     reopenWindow();const choice=await dialog.showMessageBox(win!,{type:'warning',title:'退出准备尚未完成',message:'恢复记录或更新暂时无法准备完成',detail:(error instanceof Error?error.message:'本机加密或网页繁忙')+'。可以返回应用继续保存；仍然退出可能无法恢复最后的修改。',buttons:['返回应用','仍然退出'],defaultId:0,cancelId:0});if(choice.response===1){quitting=true;app.quit();}
    });return;
   }
   quitting=true;menuBar?.dispose();menuBar=undefined;recoveryAccess?.dispose();clearTimeout(idleTimer);idleUpdate?.cancel();idleNoticeWindow?.destroy();updates?.dispose();clearInterval(checkpointTimer);recentClosed.dispose();handoffRunner?.dispose();assetConversions?.dispose();for(const tracker of assetDownloads.values())tracker.dispose();
  });
  app.on('window-all-closed',()=>{if(process.platform!=='darwin'||testMode)app.quit();});
}
