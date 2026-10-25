import {test,expect,_electron as electron,type ElectronApplication} from '@playwright/test';
import {createServer} from 'node:http';
import {mkdtempSync,rmSync,writeFileSync,existsSync} from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import {fileURLToPath} from 'node:url';
import {execFileSync} from 'node:child_process';
import {shellWindow} from './helpers/shell';
const desktop=path.resolve(fileURLToPath(new URL('..',import.meta.url)));

test('macOS native menu bar survives hidden windows and reuses account pages for its actions',async({},info)=>{
 test.skip(process.platform!=='darwin');
 const root=mkdtempSync(path.join(os.tmpdir(),'studio-menubar-')),data=path.join(root,'data');let app:ElectronApplication|undefined;
 const server=createServer((_request,response)=>{response.setHeader('Content-Type','text/html; charset=utf-8');response.end('<!doctype html><title>Menu bar offline fixture</title><p>Local fixture; no ChatGPT service</p><textarea id="prompt-textarea"></textarea><script>window.menuBarPageKey=crypto.randomUUID();window.sent=0;</script>');});
 await new Promise<void>(resolve=>server.listen(0,'127.0.0.1',resolve));const address=server.address();if(!address||typeof address==='string')throw Error('Missing fixture address');const origin='http://127.0.0.1:'+address.port;
 try{
  app=await electron.launch({args:[desktop],env:{...process.env,PROFILES_E2E:'1',PROFILES_TEST_DATA:data,PROFILES_FIXTURE_ORIGIN:origin}});const shell=await shellWindow(app);
  const state=()=>shell.evaluate(()=>window.profiles.snapshot());
  const inspect=()=>app!.evaluate(({app})=>(app as any).profilesTestMenuBar?.inspect());
  const click=(id:string)=>app!.evaluate(({app},id)=>{const menu=(app as any).profilesTestMenuBar?.menu;const item=menu?.getMenuItemById(id);if(!item||!item.enabled)throw Error('Native menu unavailable: '+id);item.click();},id);
  const label=(id:string)=>app!.evaluate(({app},id)=>(app as any).profilesTestMenuBar?.menu?.getMenuItemById(id)?.label,id);
  const pageKey=()=>app!.evaluate(async({webContents},url)=>{const page=webContents.getAllWebContents().find(w=>w.getURL()===url+'/');return page?{id:page.id,key:await page.executeJavaScript('window.menuBarPageKey')}:undefined;},origin);
  await expect.poll(async()=>!!(await inspect())?.template).toBe(true);await expect.poll(async()=>typeof(await pageKey())?.key).toBe('string');
  const native=await inspect();expect(native.imageSize).toEqual({width:18,height:18});expect(native.scaleFactors).toEqual(expect.arrayContaining([1,2]));expect(native.bounds.width).toBeGreaterThan(0);expect(native.bounds.height).toBeGreaterThan(0);
  if(process.env.CI)execFileSync('/usr/sbin/screencapture',['-x',info.outputPath('macos-system-menu-bar.png')],{timeout:15000});
  const first=(await state()).activeProfileId,key=await pageKey(),windowId=await app.evaluate(({BrowserWindow})=>BrowserWindow.getAllWindows().find(w=>w.webContents.getURL()==='app://shell/index.html')!.id);
  await click('menubar-window');await expect.poll(()=>app!.evaluate(({BrowserWindow},id)=>BrowserWindow.fromId(id)?.isVisible(),windowId)).toBe(false);expect((await inspect()).destroyed).toBe(false);expect(await pageKey()).toEqual(key);
  await click('menubar-window');await expect.poll(()=>app!.evaluate(({BrowserWindow},id)=>BrowserWindow.fromId(id)?.isVisible(),windowId)).toBe(true);expect(await pageKey()).toEqual(key);
  const before=(await state()).tabs.length;await click('menubar-new-tab');await expect.poll(async()=>(await state()).tabs.length).toBe(before+1);
  await shell.evaluate(()=>window.profiles.command({type:'profile:add',name:'菜单栏测试 B',email:'fixture@example.test',color:'#5275a0'}));const second=(await state()).activeProfileId;
  await expect.poll(()=>label('menubar-status')).toContain('菜单栏测试 B');await click('menubar-profile-'+first);await expect.poll(async()=>(await state()).activeProfileId).toBe(first);
  await click('menubar-assets');await expect.poll(async()=>(await state()).panel).toBe('assets');await expect(shell.locator('#wb-workbench')).toBeVisible();
  await click('menubar-skills');await expect.poll(async()=>(await state()).panel).toBe('skills');await expect(shell.locator('#wb-capability-page')).toBeVisible();
  await click('menubar-profile-'+second);await expect.poll(async()=>(await state()).activeProfileId).toBe(second);
  const updateDisabled=await app.evaluate(({app})=>(app as any).profilesTestMenuBar.menu.getMenuItemById('menubar-updates').enabled===false);expect(updateDisabled).toBe(true);
  await shell.screenshot({path:info.outputPath('menubar-actions-shell.png')});
  const closing=app.waitForEvent('close');await click('menubar-quit');await closing;app=undefined;expect(existsSync(path.join(data,'state.json'))).toBe(true);
  writeFileSync(info.outputPath('menu-bar-verification.json'),JSON.stringify({scope:'Real macOS Tray/native menu in isolated Electron with loopback fixtures; no personal data or real ChatGPT. Physical user menu-bar appearance still requires the candidate app.',native,windowIdentityRetained:true,pageIdentityRetained:true,newTab:true,accountSwitch:true,assetsAndSkills:true,safeQuit:true,unpackagedUpdateDisabled:true},null,2));
 }finally{await app?.close().catch(()=>{});server.closeAllConnections();await new Promise<void>(resolve=>server.close(()=>resolve()));rmSync(root,{recursive:true,force:true});}
});
