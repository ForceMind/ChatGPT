import {test,expect,_electron as electron,type ElectronApplication} from '@playwright/test';
import {createServer} from 'node:http';import {mkdtempSync,readFileSync,rmSync} from 'node:fs';
import os from 'node:os';import path from 'node:path';import {fileURLToPath} from 'node:url';
import {shellWindow} from './helpers/shell';
const desktop=path.resolve(fileURLToPath(new URL('..',import.meta.url)));

test('Windows restores an encrypted read-only conversation offline, reconnects with the original draft and never sends',async({},info)=>{
 test.skip(process.platform!=='win32');
 const root=mkdtempSync(path.join(os.tmpdir(),'studio-windows-offline-'));let app:ElectronApplication|undefined,offline=false,sends=0;
 const transcript='Private Windows conversation snapshot fixture',draft='Unsent Windows draft - preserve locally';
 const server=createServer((q,r)=>{
  if(offline){q.socket.destroy();return;}
  if(q.url==='/send'){sends++;r.end('ok');return;}
  r.writeHead(200,{'Content-Type':'text/html;charset=utf-8','Cache-Control':'no-store'});r.end(`<!doctype html><meta charset="utf-8"><title>Windows offline fixture</title><article data-message-author-role="user">Local fixture question</article><article data-message-author-role="assistant">${transcript}</article><textarea id="prompt-textarea"></textarea><button onclick="fetch('/send',{method:'POST'})">Send</button><script>window.fixtureReady=true;</script>`);
 });
 await new Promise<void>(resolve=>server.listen(0,'127.0.0.1',resolve));const address=server.address();if(!address||typeof address==='string')throw Error('fixture');const origin='http://127.0.0.1:'+address.port;
 const launch=async()=>{app=await electron.launch({args:[desktop],env:{...process.env,PROFILES_E2E:'1',PROFILES_TEST_DATA:path.join(root,'data'),PROFILES_FIXTURE_ORIGIN:origin}});return shellWindow(app,{width:1200,height:800});};
 const remote=(code:string)=>app!.evaluate(async({BrowserWindow},{origin,code})=>{const w=BrowserWindow.getAllWindows().find(w=>w.webContents.getURL()==='app://shell/index.html')!;const view=(w.contentView.children as Electron.WebContentsView[]).find(v=>v.webContents.getURL().startsWith(origin));return view?.webContents.executeJavaScript(code);},{origin,code});
 try{
  let shell=await launch();await expect.poll(()=>remote('window.fixtureReady')).toBe(true);
  await app!.evaluate(async({BrowserWindow},url)=>{const w=BrowserWindow.getAllWindows().find(w=>w.webContents.getURL()==='app://shell/index.html')!;await (w.contentView.children[0] as Electron.WebContentsView).webContents.loadURL(url);},origin+'/c/windows-offline-fixture');
  await expect.poll(()=>remote('window.fixtureReady')).toBe(true);await remote('document.querySelector("textarea").value='+JSON.stringify(draft)+';document.cookie="offline_owner=kept; Max-Age=3600; SameSite=Lax"');
  const before=await shell.evaluate(()=>window.profiles.snapshot()),profile=before.activeProfileId,tab=before.profiles.find(p=>p.id===profile)!.activeTabId;
  const closing=app!.waitForEvent('close');await app!.evaluate(({app})=>app.quit());await closing;app=undefined;
  const encrypted=readFileSync(path.join(root,'data/recovery',profile,tab+'.bin'));expect(encrypted.includes(Buffer.from(transcript))).toBe(false);expect(encrypted.includes(Buffer.from(draft))).toBe(false);
  offline=true;shell=await launch();await expect(shell.getByLabel('本地页面快照')).toBeVisible();
  const snapshot=await shell.evaluate(()=>window.profiles.snapshot());expect(snapshot.offline?.messages.some(m=>m.text.includes(transcript))).toBe(true);expect(snapshot.activeProfileId).toBe(profile);expect(snapshot.profiles.find(p=>p.id===profile)?.activeTabId).toBe(tab);
  if(snapshot.offline?.image)await shell.getByText('阅读已保存的对话文字',{exact:true}).click();await expect(shell.locator('.offline-message').filter({hasText:transcript})).toBeVisible();expect(sends).toBe(0);
  await shell.screenshot({path:info.outputPath('windows-encrypted-offline-snapshot.png')});offline=false;await shell.getByRole('button',{name:'重新连接',exact:true}).click();
  await expect.poll(()=>remote('document.querySelector("textarea")?.value')).toBe(draft);await expect(shell.getByLabel('本地页面快照')).toHaveCount(0);expect(await remote('document.cookie')).toContain('offline_owner=kept');expect(sends).toBe(0);
 }finally{await app?.close().catch(()=>{});server.closeAllConnections();await new Promise<void>(resolve=>server.close(()=>resolve()));rmSync(root,{recursive:true,force:true});}
});
