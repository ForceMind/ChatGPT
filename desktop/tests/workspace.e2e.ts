import{shellWindow}from'./helpers/shell';
import{openNativePopup}from'./helpers/popup';
import { test, expect, _electron, type ElectronApplication, type Page } from '@playwright/test';
import { createServer, type Server } from 'node:http';
import { mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import type { Snapshot } from '../src/shared/model';

let server:Server,origin:string;
const fixture=`<!doctype html><html lang="zh-CN"><head><meta charset="UTF-8"><title>本地隔离验收页面</title><style>body{font-family:-apple-system,sans-serif;margin:0;background:#fff;color:#294337}.banner{background:#f8f4e9;color:#937442;padding:12px 30px;font-size:11px}.content{padding:100px 12%;max-width:850px}small{font-size:11px;letter-spacing:2px;color:#82917c}h1{font-size:34px;font-weight:500;letter-spacing:-1px;margin:18px 0}p{font-size:13px;color:#87927e;line-height:2}.cards{display:flex;gap:14px;margin-top:34px}.card{padding:20px;border:1px solid #e6e9e0;border-radius:12px;flex:1;font-size:12px}.card strong{display:block;margin-bottom:12px}.dot{width:28px;height:28px;border-radius:8px;background:#e9f0e4;margin-bottom:25px}</style></head><body><div class="banner">本地验收页面 · 非 ChatGPT · 不包含真实账号</div><div class="content"><small>INDEPENDENT SPACES</small><h1>每个账号，都有自己的空间。</h1><p>此页面仅用于验证浏览器存储、登录弹窗与重启恢复。<br>正式运行时，这里直接打开 ChatGPT 网页。</p><div class="cards"><div class="card"><div class="dot"></div><strong>独立存储</strong>Cookie · IndexedDB · 网站缓存</div><div class="card"><div class="dot"></div><strong>清晰切换</strong>账号标识 · 专属标签页</div><div class="card"><div class="dot"></div><strong>本机恢复</strong>关闭再打开，回到工作现场</div></div></div><script>window.addEventListener('message',e=>{if(e.origin===location.origin)window.popupAck=e.data;});</script></body></html>`;
test.beforeAll(async()=>{
  server=createServer((req,res)=>{
    if(req.url==='/sw.js'){res.writeHead(200,{'Content-Type':'text/javascript','Service-Worker-Allowed':'/'});res.end("self.addEventListener('install',()=>self.skipWaiting());self.addEventListener('activate',event=>event.waitUntil(clients.claim()));");return;}
    res.writeHead(200,{'Content-Type':'text/html; charset=utf-8'});res.end(req.url?.startsWith('/popup')?`<html><head><title>测试登录窗口</title></head><body><h1>本地登录弹窗验收</h1><script>window.opener?.postMessage('popup-ready',location.origin)</script></body></html>`:fixture);
  });
  await new Promise<void>(resolve=>server.listen(0,'127.0.0.1',resolve));origin=`http://127.0.0.1:${(server.address() as {port:number}).port}`;
});
test.afterAll(async()=>{await new Promise<void>(resolve=>server.close(()=>resolve()));});
async function launch(dir:string):Promise<{app:ElectronApplication;shell:Page}>{
  const env=Object.fromEntries(Object.entries(process.env).filter((e):e is [string,string]=>e[1]!==undefined));delete env.ELECTRON_RUN_AS_NODE;
  const app=await _electron.launch({args:[path.resolve('.')],env:{...env,PROFILES_E2E:'1',PROFILES_TEST_DATA:dir,PROFILES_FIXTURE_ORIGIN:origin}});
  const shell=await shellWindow(app);await shell.getByRole('button',{name:'切换账号',exact:true}).waitFor();
  await expect.poll(async()=>remote(app,'document.readyState')).toBe('complete');return{app,shell};
}
async function remote(app:ElectronApplication,code:string):Promise<any>{
  return app.evaluate(async({BrowserWindow},source)=>{
    const w=BrowserWindow.getAllWindows().find(w=>w.webContents.getURL()==='app://shell/index.html')!;
    const view=(w.contentView.children as Electron.WebContentsView[]).find(v=>v.getVisible());
    if(!view||view.webContents.isDestroyed())return null;
    return view.webContents.executeJavaScript(source);
  },code);
}
async function state(shell:Page):Promise<Snapshot>{return shell.evaluate(()=>window.profiles.snapshot());}
const writeMark=(value:string)=>`(async()=>{document.cookie='account=${value};Max-Age=86400;path=/';localStorage.setItem('account','${value}');const db=await new Promise((resolve,reject)=>{const r=indexedDB.open('profile-test',1);r.onupgradeneeded=()=>r.result.createObjectStore('marks');r.onsuccess=()=>resolve(r.result);r.onerror=()=>reject(r.error);});await new Promise((resolve,reject)=>{const tx=db.transaction('marks','readwrite');tx.objectStore('marks').put('${value}','account');tx.oncomplete=resolve;tx.onerror=reject;});db.close();await(await caches.open('profile-test')).put('/mark',new Response('${value}'));await navigator.serviceWorker.register('/sw.js');await navigator.serviceWorker.ready;return true;})()`;
const readMark=`(async()=>{const db=await new Promise((resolve,reject)=>{const r=indexedDB.open('profile-test',1);r.onupgradeneeded=()=>r.result.createObjectStore('marks');r.onsuccess=()=>resolve(r.result);r.onerror=()=>reject(r.error);});const mark=await new Promise((resolve,reject)=>{const r=db.transaction('marks').objectStore('marks').get('account');r.onsuccess=()=>resolve(r.result||null);r.onerror=reject;});db.close();const response=await(await caches.open('profile-test')).match('/mark');return {cookie:document.cookie,local:localStorage.getItem('account'),indexed:mark,cache:response?await response.text():null,workers:(await navigator.serviceWorker.getRegistrations()).length};})()`;
async function accounts(shell:Page){if(!await shell.getByRole('menu',{name:'账号空间',exact:true}).count())await shell.getByRole('button',{name:'切换账号',exact:true}).click();await expect(shell.getByRole('menu',{name:'账号空间',exact:true})).toBeVisible();}
async function switchAccount(shell:Page,name:string){
  await accounts(shell);
  await shell.evaluate(()=>{const logs:unknown[]=[];(window as any).__menuDebug=logs;for(const type of ['mousedown','mouseup','click'])document.addEventListener(type,event=>{const e=event as MouseEvent;logs.push({type,x:e.clientX,y:e.clientY,target:(e.target as Element)?.closest('button')?.getAttribute('aria-label')||(e.target as HTMLElement)?.className});},{capture:true,once:true});});
  try{await shell.getByRole('menuitemradio',{name:'切换到 '+name,exact:true}).click({timeout:5000});}
  catch(error){console.log('MENU_DIAGNOSTIC',await shell.evaluate(async()=>({active:(await window.profiles.snapshot()).activeProfileId,menu:!!document.querySelector('[role=menu]'),events:(window as any).__menuDebug})));throw error;}
  await expect(shell.getByRole('menu')).toHaveCount(0);
}
async function manage(shell:Page,name:string){await accounts(shell);await shell.getByRole('menuitem',{name:'管理 '+name,exact:true}).click();await expect(shell.getByRole('dialog')).toBeVisible();}
async function menuAction(app:ElectronApplication,id:string){await app.evaluate(({Menu,BrowserWindow},key)=>{const item=Menu.getApplicationMenu()?.getMenuItemById(key);if(!item)throw new Error('Missing application menu item: '+key);item.click(item,BrowserWindow.getAllWindows()[0],{triggeredByAccelerator:false});},id);}
async function add(shell:Page,name:string,email:string){await accounts(shell);await shell.getByRole('menuitem',{name:'添加账号',exact:false}).click();await shell.getByRole('textbox',{name:'账号名称'}).fill(name);await shell.getByRole('textbox',{name:'邮箱备注'}).fill(email);await shell.getByRole('button',{name:'创建并打开网页'}).click();await expect(shell.getByRole('dialog')).toHaveCount(0);await expect.poll(async()=>(await state(shell)).profiles.find(p=>p.name===name)?.id).toBeTruthy();}

test('真实 Electron：同账号共享、跨账号隔离、登录弹窗、重启及单账号清理',async({},info)=>{
  const dir=mkdtempSync(path.join(tmpdir(),'profiles-isolation-'));let {app,shell}=await launch(dir);
  try{
    const initial=await state(shell),a=initial.activeProfileId;
    await expect.poll(()=>remote(app,'document.readyState')).toBe('complete');expect(await remote(app,writeMark('alpha'))).toBe(true);
    const first=await remote(app,readMark);expect(first).toMatchObject({local:'alpha',indexed:'alpha',cache:'alpha',workers:1});expect(first.cookie).toContain('account=alpha');
    await shell.getByRole('button',{name:'新建标签页',exact:true}).click();await expect.poll(()=>remote(app,'localStorage.getItem("account")')).toBe('alpha');
    await add(shell,'工作账号','work@example.com');const b=(await state(shell)).activeProfileId;
    await expect.poll(()=>remote(app,'document.readyState')).toBe('complete');const empty=await remote(app,readMark);expect(empty).toMatchObject({cookie:'',local:null,indexed:null,cache:null,workers:0});
    expect(await remote(app,writeMark('beta'))).toBe(true);
    const popup=await openNativePopup(app,`Boolean(window.open('${origin}/popup','login-check','width=500,height=600'))`);
    expect(await popup.evaluate(()=>localStorage.getItem('account'))).toBe('beta');
    await popup.evaluate(()=>{document.cookie='from_popup=beta;Max-Age=86400;path=/';});await expect.poll(()=>remote(app,'window.popupAck')).toBe('popup-ready');await popup.close();
    await switchAccount(shell,'个人账号');expect(await remote(app,'document.cookie')).not.toContain('from_popup');
    await switchAccount(shell,'工作账号');await app.close();
    ({app,shell}=await launch(dir));expect((await state(shell)).activeProfileId).toBe(b);expect((await state(shell)).profiles.find(p=>p.id===a)?.id).toBe(a);
    await expect.poll(()=>remote(app,'localStorage.getItem("account")')).toBe('beta');expect(await remote(app,'document.cookie')).toContain('account=beta');expect(await remote(app,readMark)).toMatchObject({indexed:'beta',cache:'beta',workers:1});
    await switchAccount(shell,'个人账号');await expect.poll(()=>remote(app,'localStorage.getItem("account")')).toBe('alpha');
    // Reopen twice more: first access to an unloaded account must also switch
    // through the native-view overlay without dropping the menu click.
    for(let cycle=0;cycle<2;cycle++){
      await switchAccount(shell,'工作账号');await app.close();({app,shell}=await launch(dir));
      await switchAccount(shell,'个人账号');await expect.poll(()=>remote(app,'localStorage.getItem("account")')).toBe('alpha');
      await switchAccount(shell,'工作账号');await expect.poll(()=>remote(app,'localStorage.getItem("account")')).toBe('beta');
    }
    await switchAccount(shell,'个人账号');
    await app.evaluate(({dialog})=>{dialog.showMessageBox=async()=>({response:1,checkboxChecked:false});});
    const cleared=await shell.evaluate(id=>window.profiles.command({type:'profile:clear',id}),a);expect(cleared.ok).toBe(true);
    await expect.poll(()=>remote(app,'document.readyState')).toBe('complete');expect(await remote(app,readMark)).toMatchObject({cookie:'',local:null,indexed:null,cache:null,workers:0});
    await switchAccount(shell,'工作账号');expect(await remote(app,readMark)).toMatchObject({local:'beta',indexed:'beta',cache:'beta',workers:1});expect(await remote(app,'document.cookie')).toContain('from_popup=beta');
    await app.evaluate(async({BrowserWindow})=>{const w=BrowserWindow.getAllWindows().find(w=>w.webContents.getURL().startsWith('app://'))!;await w.webContents.session.flushStorageData();});
    await info.attach('isolation-evidence',{body:JSON.stringify({dataDir:dir,profiles:(await state(shell)).profiles.map(p=>({id:p.id,name:p.name})),checks:['same-profile-shared','cross-profile-isolated','popup-session-and-opener','restart-persistent','clear-single-profile-only']},null,2),contentType:'application/json'});
  }finally{await app.close().catch(()=>{});}
});

test('账号与标签交互、沙箱边界、窗口状态和代表性尺寸',async({},info)=>{
  const dir=mkdtempSync(path.join(tmpdir(),'profiles-interactions-'));const {app,shell}=await launch(dir);
  const errors:string[]=[];shell.on('pageerror',error=>errors.push(error.message));
  try{
    expect(await remote(app,'[typeof window.profiles,typeof require,typeof process]')).toEqual(['undefined','undefined','undefined']);
    const paths=await app.evaluate(({BrowserWindow})=>{const w=BrowserWindow.getAllWindows()[0];return(w.contentView.children as Electron.WebContentsView[]).map(v=>v.webContents.session.storagePath);});
    expect(paths).toHaveLength(1);expect(paths[0]).toContain('profile-');
    const rejected=await shell.evaluate(()=>window.profiles.command({type:'tab:new',url:'file:///etc/passwd'}));expect(rejected.ok).toBe(false);
    await shell.getByRole('button',{name:'新建标签页',exact:true}).click();await expect(shell.getByRole('tab')).toHaveCount(2);
    await menuAction(app,'pin-current-tab');const pinnedState=await state(shell);expect(pinnedState.tabs.find(t=>t.id===pinnedState.profiles[0].activeTabId)?.pinned).toBe(true);
    const active=(await state(shell)).profiles[0].activeTabId;await shell.evaluate(id=>window.profiles.command({type:'tab:close',id}),active);await expect(shell.getByRole('tab')).toHaveCount(1);
    await menuAction(app,'restore-tab');await expect(shell.getByRole('tab')).toHaveCount(2);
    await manage(shell,'个人账号');await shell.getByRole('textbox',{name:'账号名称'}).fill('个人主账号');await shell.getByRole('textbox',{name:'邮箱备注'}).fill('personal@example.com');await shell.getByRole('button',{name:'保存修改'}).click();await expect(shell.getByRole('button',{name:'切换账号',exact:true})).toContainText('个人主账号');
    await add(shell,'工作账号','work@example.com');await manage(shell,'工作账号');
    await shell.screenshot({path:info.outputPath('account-editor.png')});await shell.getByRole('button',{name:'关闭账号管理',exact:true}).click();
    await app.evaluate(({BrowserWindow})=>BrowserWindow.getAllWindows()[0].setSize(1360,900));
    const capture=await app.evaluate(async({BrowserWindow})=>(await BrowserWindow.getAllWindows()[0].capturePage()).toPNG().toString('base64'));writeFileSync(info.outputPath('workspace-wide.png'),Buffer.from(capture,'base64'));
    await app.evaluate(({BrowserWindow})=>BrowserWindow.getAllWindows()[0].setSize(900,700));await shell.screenshot({path:info.outputPath('workspace-compact-shell.png')});expect(await shell.evaluate(()=>document.documentElement.scrollWidth>innerWidth)).toBe(false);
    await expect(shell.locator('.address,.toolbar,.sidebar')).toHaveCount(0);
    expect(await shell.locator('body').innerText()).not.toContain(origin);
    const layout=await app.evaluate(({BrowserWindow})=>{const w=BrowserWindow.getAllWindows()[0],v=(w.contentView.children as Electron.WebContentsView[]).find(v=>v.getVisible())!;return{view:v.getBounds(),size:w.getContentSize()};});
    const headerBottom=await shell.getByLabel('客户端标题栏').evaluate(el=>el.getBoundingClientRect().bottom);
    expect(layout.view).toEqual({x:0,y:headerBottom,width:layout.size[0],height:layout.size[1]-headerBottom});
    if(process.platform==='win32'){
      expect((await state(shell)).platform).toBe('win32');
      expect(await app.evaluate(({BrowserWindow})=>BrowserWindow.getAllWindows()[0].isMenuBarVisible())).toBe(false);
      const safe=await shell.evaluate(()=>{const overlay=(navigator as Navigator & {windowControlsOverlay:{visible:boolean;getTitlebarAreaRect:()=>DOMRect}}).windowControlsOverlay;const rect=overlay.getTitlebarAreaRect();const account=document.querySelector('.current-account')!.getBoundingClientRect();return{visible:overlay.visible,right:rect.right,accountRight:account.right,traffic:getComputedStyle(document.querySelector('.traffic-space')!).display};});
      expect(safe.visible).toBe(true);expect(safe.traffic).toBe('none');expect(safe.accountRight).toBeLessThanOrEqual(safe.right);
    }
    await accounts(shell);await expect(shell.locator('.page-preview')).toBeVisible();expect((await state(shell)).overlayImage).toMatch(/^data:image\/png;base64,/);
    await shell.screenshot({path:info.outputPath('account-menu.png')});
    expect(readFileSync(path.join(dir,'state.json'),'utf8')).not.toContain('overlayImage');
    await shell.getByRole('menu').press('Escape');await expect(shell.getByRole('menu')).toHaveCount(0);await expect(shell.getByRole('button',{name:'切换账号',exact:true})).toBeFocused();
    expect((await state(shell)).overlayImage).toBeUndefined();
    await menuAction(app,'toggle-fullscreen');await expect.poll(async()=>(await state(shell)).fullScreen).toBe(true);await menuAction(app,'toggle-fullscreen');await expect.poll(async()=>(await state(shell)).fullScreen).toBe(false);
    expect(errors).toEqual([]);
  }finally{await app.close().catch(()=>{});}
});

test('Windows 原生拖动、窗口按钮安全区和窗口状态',async({},info)=>{
  test.skip(process.platform!=='win32','Windows native input acceptance');
  const dir=mkdtempSync(path.join(tmpdir(),'profiles-windows-'));const {app,shell}=await launch(dir);
  try{
    // Initial placement fits the runner display; the probe measures its baseline
    // afterwards and only native pointer input may produce the tested movement.
    await app.evaluate(({BrowserWindow})=>BrowserWindow.getAllWindows()[0].setBounds({x:24,y:24,width:900,height:650}));
    await expect.poll(()=>shell.evaluate(()=>innerWidth)).toBe(900);
    const rect=await shell.getByLabel('窗口拖动区域').boundingBox();expect(rect).not.toBeNull();
    const handle=await app.evaluate(({BrowserWindow})=>BrowserWindow.getAllWindows()[0].getNativeWindowHandle().readBigUInt64LE().toString());
    const {stdout}=await promisify(execFile)('powershell.exe',['-NoProfile','-ExecutionPolicy','Bypass','-File',path.resolve('scripts/check-windows-window.ps1'),'-Handle',handle,'-DragX',String(rect!.x+rect!.width/2),'-DragY',String(rect!.y+rect!.height/2),'-Screenshot',info.outputPath('windows-native.png')],{timeout:30000});
    const evidence=JSON.parse(stdout.trim());writeFileSync(info.outputPath('windows-native-input.json'),JSON.stringify(evidence,null,2));
    if(evidence.inputAvailable&&evidence.foreground)expect(evidence.passed,'Native pointer events must move the actual window; no SetWindowPos is used').toBe(true);
    await app.evaluate(({BrowserWindow})=>BrowserWindow.getAllWindows()[0].maximize());await expect.poll(()=>app.evaluate(({BrowserWindow})=>BrowserWindow.getAllWindows()[0].isMaximized())).toBe(true);
    await app.evaluate(({BrowserWindow})=>BrowserWindow.getAllWindows()[0].unmaximize());await expect.poll(()=>app.evaluate(({BrowserWindow})=>BrowserWindow.getAllWindows()[0].isMaximized())).toBe(false);
    await app.evaluate(({BrowserWindow})=>BrowserWindow.getAllWindows()[0].minimize());await expect.poll(()=>app.evaluate(({BrowserWindow})=>BrowserWindow.getAllWindows()[0].isMinimized())).toBe(true);
    await app.evaluate(({BrowserWindow})=>{const w=BrowserWindow.getAllWindows()[0];w.restore();w.focus();});
    await shell.screenshot({path:info.outputPath('windows-shell.png')});
  }finally{await app.close().catch(()=>{});}
});
