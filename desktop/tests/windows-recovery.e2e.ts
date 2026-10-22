import {test,expect,_electron as electron,type ElectronApplication} from '@playwright/test';
import {createServer} from 'node:http';
import {mkdtempSync,rmSync} from 'node:fs';
import {tmpdir} from 'node:os';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {shellWindow} from './helpers/shell';

const desktop=path.resolve(fileURLToPath(new URL('..',import.meta.url)));
test('Windows native close checkpoints the draft before quitting and restores on restart',async()=>{
 test.skip(process.platform!=='win32');
 const root=mkdtempSync(path.join(tmpdir(),'profiles-windows-close-'));
 const server=createServer((_q,r)=>{r.writeHead(200,{'Content-Type':'text/html;charset=utf-8'});r.end('<!doctype html><meta charset="utf-8"><title>Local Windows close fixture</title><textarea id="prompt-textarea"></textarea><script>window.instance=crypto.randomUUID();window.sent=0;</script>');});
 await new Promise<void>(resolve=>server.listen(0,'127.0.0.1',resolve));
 const address=server.address();if(!address||typeof address==='string')throw Error('No fixture address');
 const origin='http://127.0.0.1:'+address.port;
 let app:ElectronApplication|undefined;
 try{
  const launch=async()=>{
   app=await electron.launch({args:[desktop],env:{...process.env,PROFILES_E2E:'1',PROFILES_TEST_DATA:path.join(root,'data'),PROFILES_FIXTURE_ORIGIN:origin}});
   return shellWindow(app);
  };
  const read=(code:string)=>app!.evaluate(async({BrowserWindow},code)=>{
   const win=BrowserWindow.getAllWindows().find(w=>w.webContents.getURL()==='app://shell/index.html')!;
   const view=(win.contentView.children as Electron.WebContentsView[]).find(v=>v.getVisible());
   return view?.webContents.executeJavaScript(code);
  },code);
  let shell=await launch();await expect.poll(()=>read('window.instance')).toBeTruthy();
  const initial=await shell.evaluate(()=>window.profiles.snapshot());
  const instance=await read('window.instance');
  await read('document.querySelector("textarea").value="Local unsent Windows draft"');
  const closed=app!.waitForEvent('close');
  await app!.evaluate(({BrowserWindow})=>{BrowserWindow.getAllWindows().find(w=>w.webContents.getURL()==='app://shell/index.html')!.close();});
  await closed;app=undefined;
  shell=await launch();
  await expect.poll(()=>read('document.querySelector("textarea")?.value')).toBe('Local unsent Windows draft');
  expect(await read('window.instance')).not.toBe(instance);
  expect(await read('window.sent')).toBe(0);
  const restored=await shell.evaluate(()=>window.profiles.snapshot());
  expect(restored.activeProfileId).toBe(initial.activeProfileId);
  expect(restored.profiles[0].activeTabId).toBe(initial.profiles[0].activeTabId);
 }finally{
  await app?.close().catch(()=>{});
  server.closeAllConnections();await new Promise<void>(resolve=>server.close(()=>resolve()));
  rmSync(root,{recursive:true,force:true});
 }
});
