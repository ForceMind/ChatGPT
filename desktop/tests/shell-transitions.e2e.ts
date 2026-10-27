import{test,expect,_electron as electron,type ElectronApplication,type Page}from'@playwright/test';
import{createServer,type Server}from'node:http';
import{mkdtempSync,rmSync,writeFileSync,readFileSync}from'node:fs';
import path from'node:path';import os from'node:os';
import{execFileSync}from'node:child_process';
import{shellWindow}from'./helpers/shell';
let server:Server,origin='',hits=0,sends=0;
test.beforeAll(async()=>{
 server=createServer((req,res)=>{
  if(req.url==='/sent'){sends++;res.end('recorded');return;}
  if(req.url==='/favicon.ico'){res.writeHead(204);res.end();return;}
  res.writeHead(200,{'Content-Type':'text/html;charset=utf-8','Cache-Control':'public,max-age=3600'});
  res.end(`<!doctype html><meta charset="utf-8"><title>Mac 交互验收</title><style>body{margin:0;background:#fff;font:16px system-ui}#swatch{width:180px;height:90px;margin:20px 24px;background:#cbd5e1}p,textarea{margin:16px 24px}main{height:230px;overflow:auto;margin:16px 24px}article{height:180px}textarea{display:block;width:65%;height:70px}</style><div id="swatch"></div><p>离线交互验收，不调用 ChatGPT 服务</p><main>${Array.from({length:20},(_,i)=>'<article data-message-id="message-'+i+'">阅读段落 '+i+'</article>').join('')}</main><textarea id="prompt-textarea"></textarea><button id="send" onclick="fetch('/sent',{method:'POST'})">仅用于检查意外发送</button><script>window.instance=crypto.randomUUID();window.serial=${++hits};</script>`);
 });
 await new Promise<void>(r=>server.listen(0,'127.0.0.1',r));const address=server.address();if(!address||typeof address==='string')throw Error('Fixture unavailable');origin='http://127.0.0.1:'+address.port;
});
test.afterAll(async()=>{server.closeAllConnections();await new Promise<void>(r=>server.close(()=>r()));});
async function launch(data:string){
 const app=await electron.launch({args:[path.resolve('.')],env:{...process.env,PROFILES_E2E:'1',PROFILES_TEST_DATA:data,PROFILES_FIXTURE_ORIGIN:origin}});
 const shell=await shellWindow(app,{width:1100,height:800});await expect.poll(()=>remote(app,'window.instance')).toBeTruthy();await app.evaluate(({app,BrowserWindow})=>{app.focus({steal:true});const w=BrowserWindow.getAllWindows().find(w=>w.webContents.getURL()==='app://shell/index.html')!;w.show();w.focus();});return{app,shell};
}
async function remote(app:ElectronApplication,script:string,id?:number):Promise<any>{return app.evaluate(({BrowserWindow},{script,id})=>{
 const w=BrowserWindow.getAllWindows().find(w=>w.webContents.getURL()==='app://shell/index.html')!;
 const views=w.contentView.children as Electron.WebContentsView[],view=id?views.find(v=>v.webContents.id===id):views.find(v=>v.getVisible());
 return view?.webContents.executeJavaScript(script);
},{script,id});}
async function native(app:ElectronApplication,id?:number){return app.evaluate(({BrowserWindow},id)=>{
 const w=BrowserWindow.getAllWindows().find(w=>w.webContents.getURL()==='app://shell/index.html')!,views=w.contentView.children as Electron.WebContentsView[];
 const view=id?views.find(v=>v.webContents.id===id):views.find(v=>v.getVisible());return view?{id:view.webContents.id,visible:view.getVisible()}:undefined;
},id);}
const state=(shell:Page)=>shell.evaluate(()=>window.profiles.snapshot());
async function capture(app:ElectronApplication,file:string,patch=false){
 const b=await app.evaluate(({BrowserWindow})=>BrowserWindow.getAllWindows().find(w=>w.webContents.getURL()==='app://shell/index.html')!.getContentBounds());
 // BrowserWindow.capturePage captures the shell surface on macOS, omitting
 // embedded native views. Read the actual compositor output instead.
 const region=patch?[b.x+40,b.y+92,16,16]:[b.x,b.y,b.width,b.height];execFileSync('/usr/sbin/screencapture',['-x','-R'+region.join(','),file],{timeout:10000});
}
async function swatch(app:ElectronApplication,file:string){await capture(app,file,true);return app.evaluate(({nativeImage},file)=>Array.from(nativeImage.createFromPath(file).toBitmap({scaleFactor:1}).subarray(0,4)),file);}
async function screenshot(app:ElectronApplication,file:string){await capture(app,file);}
async function holdDecode(shell:Page){await shell.evaluate(()=>{
 const original=HTMLImageElement.prototype.decode;
 (window as any).previewWaiting=false;(window as any).releasePreview=undefined;
 HTMLImageElement.prototype.decode=function(){const image=this;return original.call(image).then(()=>image.classList.contains('page-preview')?new Promise<void>(resolve=>{(window as any).previewWaiting=true;(window as any).releasePreview=resolve;}):undefined);};
 (window as any).restorePreviewDecode=()=>{HTMLImageElement.prototype.decode=original;};
});}
test('account menu paints its full-resolution placeholder before hiding the live page, then restores the same page',async({},info)=>{
 test.skip(process.platform!=='darwin');const root=mkdtempSync(path.join(os.tmpdir(),'studio-overlay-'));const{app,shell}=await launch(root);
 try{
  const page=await native(app),instance=await remote(app,'window.instance');expect(page).toBeTruthy();
  await remote(app,'document.querySelector("textarea").value="菜单切换保留的草稿"');
  const sample=()=>swatch(app,info.outputPath('native-swatch.png'));await expect.poll(sample).toEqual([225,213,203,255]);const before=await sample();
  await holdDecode(shell);await shell.getByRole('button',{name:'切换账号',exact:true}).click();await expect.poll(()=>shell.evaluate(()=>(window as any).previewWaiting)).toBe(true);
  expect((await state(shell)).overlayTransition?.phase).toBe('prepare');expect((await native(app,page!.id))?.visible).toBe(true);
  const dimensions=await app.evaluate(async({BrowserWindow,nativeImage})=>{const w=BrowserWindow.getAllWindows().find(w=>w.webContents.getURL()==='app://shell/index.html')!,v=(w.contentView.children as Electron.WebContentsView[]).find(v=>v.getVisible())!;const snapshot=await w.webContents.executeJavaScript('window.profiles.snapshot()');return{preview:nativeImage.createFromDataURL(snapshot.overlayImage).getSize(),live:(await v.webContents.capturePage()).getSize()};});expect(dimensions.preview).toEqual(dimensions.live);
  await shell.evaluate(()=>(window as any).releasePreview());await expect.poll(async()=>(await state(shell)).overlayTransition?.phase).toBe('visible');expect((await native(app,page!.id))?.visible).toBe(false);
  expect(await sample()).toEqual(before);await screenshot(app,info.outputPath('mac-account-menu-no-blank.png'));
  await shell.getByRole('menu',{name:'账号空间',exact:true}).press('Escape');await expect.poll(async()=>(await state(shell)).overlayTransition).toBeUndefined();await expect(shell.locator('.page-preview')).toHaveCount(0);
  expect((await native(app,page!.id))?.visible).toBe(true);expect(await sample()).toEqual(before);expect(await remote(app,'window.instance')).toBe(instance);expect(await remote(app,'document.querySelector("textarea").value')).toBe('菜单切换保留的草稿');
  expect(readFileSync(path.join(root,'state.json'),'utf8')).not.toMatch(/overlayImage|overlayTransition/);expect(sends).toBe(0);
  writeFileSync(info.outputPath('overlay-verification.json'),JSON.stringify({scope:'Real macOS Electron and loopback fixture; no personal account',imageDecodeHeldBeforeHide:true,nativeImageSize:dimensions,swatchBefore:before,swatchAfter:await sample(),samePageInstance:true,draftRetained:true,noUnexpectedSend:true},null,2));
 }finally{await app.close();rmSync(root,{recursive:true,force:true});}
});
test('closing a menu while its image is decoding ignores the late paint acknowledgment',async()=>{
 test.skip(process.platform!=='darwin');const root=mkdtempSync(path.join(os.tmpdir(),'studio-overlay-cancel-'));const{app,shell}=await launch(root);
 try{
  const page=await native(app),instance=await remote(app,'window.instance');await holdDecode(shell);
  await shell.getByRole('button',{name:'切换账号',exact:true}).click();await expect.poll(()=>shell.evaluate(()=>(window as any).previewWaiting)).toBe(true);const old=(await state(shell)).overlayTransition!.id;
  await shell.getByRole('button',{name:'切换账号',exact:true}).click();await expect.poll(async()=>(await state(shell)).overlayTransition).toBeUndefined();
  await shell.evaluate(()=>{(window as any).releasePreview();(window as any).restorePreviewDecode();});await shell.evaluate(id=>window.profiles.command({type:'overlay:ready',id}),old);
  expect((await native(app,page!.id))?.visible).toBe(true);await expect(shell.getByRole('menu')).toHaveCount(0);expect(await remote(app,'window.instance')).toBe(instance);
  await shell.getByRole('button',{name:'切换账号',exact:true}).click();await expect.poll(async()=>(await state(shell)).overlayTransition?.phase).toBe('visible');await shell.getByRole('menu').press('Escape');await expect.poll(async()=>(await state(shell)).overlayTransition).toBeUndefined();expect((await native(app,page!.id))?.visible).toBe(true);
 }finally{await app.close();rmSync(root,{recursive:true,force:true});}
});
test('tab refresh bypasses cache, restores the saved draft and reading position, and does not reload other tabs or accounts',async({},info)=>{
 test.skip(process.platform!=='darwin');const root=mkdtempSync(path.join(os.tmpdir(),'studio-force-refresh-'));const{app,shell}=await launch(root);
 try{
  const first=await state(shell),profile=first.activeProfileId,tab=first.profiles.find(p=>p.id===profile)!.activeTabId;
  const page=await native(app),old=await remote(app,'({instance:window.instance,serial:window.serial})');
  await remote(app,'document.querySelector("textarea").value="强制刷新前的草稿";document.querySelector("main").scrollTop=1234;document.cookie="refresh-fixture=keep;path=/";localStorage.setItem("refresh-fixture","keep")');
  await app.evaluate(({webContents},id)=>{const c=webContents.fromId(id)!;const original=c.reloadIgnoringCache.bind(c);(c as any).forcedReloads=0;c.reloadIgnoringCache=()=>{(c as any).forcedReloads++;original();};},page!.id);
  await shell.getByRole('button',{name:'新建标签页',exact:true}).click();await expect.poll(()=>remote(app,'window.instance')).toBeTruthy();const other=await native(app),otherKey=await remote(app,'window.instance');
  await shell.evaluate(id=>window.profiles.command({type:'tab:activate',id}),tab);await expect(shell.getByRole('tab').nth(0)).toHaveAttribute('aria-selected','true');await shell.getByRole('button',{name:/^强制刷新标签 /}).click();
  await expect.poll(()=>remote(app,'window.instance')).not.toBe(old.instance);await expect.poll(()=>remote(app,'document.querySelector("textarea")?.value')).toBe('强制刷新前的草稿');expect(await remote(app,'document.querySelector("main").scrollTop')).toBe(1234);
  expect((await native(app))?.id).toBe(page!.id);expect(await remote(app,'window.serial')).toBeGreaterThan(old.serial);expect(await remote(app,'[localStorage.getItem("refresh-fixture"),document.cookie]')).toEqual(['keep','refresh-fixture=keep']);
  expect(await app.evaluate(({webContents},id)=>(webContents.fromId(id) as any).forcedReloads,page!.id)).toBe(1);expect(await remote(app,'window.instance',other!.id)).toBe(otherKey);
  await shell.evaluate(()=>window.profiles.command({type:'profile:add',name:'另一隔离账号',email:'',color:'#5275a0'}));await expect.poll(()=>remote(app,'window.instance')).toBeTruthy();const isolated=await native(app),isolatedKey=await remote(app,'window.instance');
  expect((await shell.evaluate(id=>window.profiles.command({type:'tab:reload',id}),tab)).ok).toBe(false);expect(await remote(app,'window.instance')).toBe(isolatedKey);
  await shell.evaluate(id=>window.profiles.command({type:'profile:activate',id}),profile);await expect(shell.getByRole('button',{name:'切换账号',exact:true})).toContainText('个人账号');await expect(shell.getByRole('tab').nth(0)).toHaveAttribute('aria-selected','true');const current=await remote(app,'window.instance');await app.evaluate(({Menu,BrowserWindow})=>{const item=Menu.getApplicationMenu()!.getMenuItemById('force-reload-page')!;item.click(item,BrowserWindow.getAllWindows()[0],{triggeredByAccelerator:false});});
  await expect.poll(()=>remote(app,'window.instance')).not.toBe(current);await expect.poll(()=>remote(app,'document.querySelector("textarea")?.value')).toBe('强制刷新前的草稿');expect(await remote(app,'window.instance',isolated!.id)).toBe(isolatedKey);expect(sends).toBe(0);
  await screenshot(app,info.outputPath('mac-tab-force-refresh.png'));writeFileSync(info.outputPath('refresh-verification.json'),JSON.stringify({scope:'Real macOS Electron and cached loopback fixture; no ChatGPT',cacheBypassMethodInvoked:true,networkResponseChanged:true,draftAndReadingRestored:true,cookieAndLocalStorageRetained:true,otherTabAndAccountUnchanged:true,crossAccountRefreshRejected:true,noUnexpectedSend:true},null,2));
 }finally{await app.close();rmSync(root,{recursive:true,force:true});}
});
test('refresh recreates a crashed page inside the existing account and restores its checkpoint',async({},info)=>{
 test.skip(process.platform!=='darwin');const root=mkdtempSync(path.join(os.tmpdir(),'studio-refresh-crash-'));const{app,shell}=await launch(root);
 try{
  const first=await state(shell),profile=first.activeProfileId,tab=first.profiles.find(p=>p.id===profile)!.activeTabId;
  const page=await native(app);await remote(app,'document.querySelector("textarea").value="崩溃前保存的草稿";document.cookie="crash-fixture=keep;path=/"');
  await shell.getByRole('button',{name:'新建标签页',exact:true}).click();await expect.poll(()=>remote(app,'window.instance')).toBeTruthy();await shell.evaluate(id=>window.profiles.command({type:'tab:activate',id}),tab);
  await app.evaluate(({webContents},id)=>webContents.fromId(id)!.forcefullyCrashRenderer(),page!.id);
  await expect.poll(()=>app.evaluate(({webContents},id)=>webContents.fromId(id)?.isCrashed(),page!.id)).toBe(true);
  await shell.getByRole('button',{name:/^强制刷新标签 /}).click();await expect.poll(()=>remote(app,'document.querySelector("textarea")?.value').catch(()=>undefined)).toBe('崩溃前保存的草稿');
  expect((await native(app))?.id).not.toBe(page!.id);expect((await state(shell)).activeProfileId).toBe(profile);expect(await remote(app,'document.cookie')).toBe('crash-fixture=keep');expect(sends).toBe(0);await screenshot(app,info.outputPath('mac-crashed-page-restored.png'));
 }finally{await app.close();rmSync(root,{recursive:true,force:true});}
});
