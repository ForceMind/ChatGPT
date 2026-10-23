import {test,expect,_electron as electron,type ElectronApplication} from '@playwright/test';
import {createServer} from 'node:http';
import {mkdtempSync,rmSync,readFileSync,readdirSync} from 'node:fs';
import path from 'node:path';import os from 'node:os';import {fileURLToPath} from 'node:url';
import {shellWindow} from './helpers/shell';
const desktop=path.resolve(fileURLToPath(new URL('..',import.meta.url)));
test('visible recovery consent can defer, reject and retry without losing old drafts; hidden update relaunch stays silent',async({},info)=>{
 test.skip(process.platform!=='darwin');test.setTimeout(120000);
 const root=mkdtempSync(path.join(os.tmpdir(),'profiles-consent-'));let app:ElectronApplication|undefined;
 const server=createServer((_req,res)=>{res.setHeader('Content-Type','text/html');res.end('<title>Local recovery fixture</title><p>Offline test page, not ChatGPT</p><textarea id="prompt-textarea"></textarea><script>window.instance=crypto.randomUUID()</script>');});
 await new Promise<void>(r=>server.listen(0,'127.0.0.1',r));const address=server.address();if(!address||typeof address==='string')throw Error('fixture');
 const launch=async(extra:Record<string,string>={},background=false)=>{app=await electron.launch({args:[desktop,...(background?['--profiles-background-update']:[])],env:{...process.env,PROFILES_E2E:'1',PROFILES_TEST_DATA:path.join(root,'data'),PROFILES_FIXTURE_ORIGIN:'http://127.0.0.1:'+address.port,...extra}});return shellWindow(app);};
 const quit=async()=>{const done=app!.waitForEvent('close');await app!.evaluate(({app})=>app.quit());await done;app=undefined;};
 const remote=async(code:string)=>app!.evaluate(({BrowserWindow},code)=>{const w=BrowserWindow.getAllWindows().find(w=>w.webContents.getURL()==='app://shell/index.html')!;return (w.contentView.children as Electron.WebContentsView[])[0]?.webContents.executeJavaScript(code);},code);
 try{
  let shell=await launch();await expect.poll(()=>remote('!!document.querySelector("textarea")')).toBe(true);await remote('document.querySelector("textarea").value="legacy private draft";document.cookie="recovery_fixture=kept; Max-Age=3600; SameSite=Lax"');const oldState=await shell.evaluate(()=>window.profiles.snapshot());await quit();
  const record=path.join(root,'data','recovery',oldState.activeProfileId,readdirSync(path.join(root,'data','recovery',oldState.activeProfileId)).find(n=>n.endsWith('.bin'))!);const original=readFileSync(record);
  // Force consent for this fixture by removing only its own acceptance file.
  // The next process starts with the same encrypted records and profile IDs.
  const fs=await import('node:fs');fs.unlinkSync(path.join(root,'data','recovery-access.json'));
  shell=await launch({PROFILES_RECOVERY_CONSENT:'1',PROFILES_RECOVERY_DENY_ONCE:'1'});
  await expect(shell.getByLabel('本地恢复授权',{exact:true})).toBeVisible();expect(await remote('window.instance')).toBeUndefined();await shell.screenshot({path:info.outputPath('recovery-consent.png')});
  await shell.getByRole('button',{name:'先打开网页',exact:true}).click();await expect.poll(()=>remote('window.instance')).toBeTruthy();expect(readFileSync(record)).toEqual(original);
  await shell.getByRole('button',{name:'重新授权',exact:true}).click();await expect.poll(async()=>(await shell.evaluate(()=>window.profiles.snapshot())).recoveryAccess?.phase).toBe('error');expect(readFileSync(record)).toEqual(original);
  await shell.getByRole('button',{name:'重新授权',exact:true}).click();await expect.poll(()=>remote('document.querySelector("textarea").value')).toBe('legacy private draft');expect((await shell.evaluate(()=>window.profiles.snapshot())).activeProfileId).toBe(oldState.activeProfileId);expect(await remote('document.cookie')).toContain('recovery_fixture=kept');
  const settings=await app!.evaluate(async({dialog,Menu})=>{let text='';dialog.showMessageBox=(async(_w:any,o:any)=>{text=o.detail;return{response:0,checkboxChecked:false};}) as typeof dialog.showMessageBox;Menu.getApplicationMenu()!.getMenuItemById('recovery-privacy')!.click();await new Promise(r=>setImmediate(r));return text;});expect(settings).toContain('临时签名');expect(settings).toContain('Touch ID');await quit();
  shell=await launch({PROFILES_RECOVERY_CONSENT:'1'},true);expect(await app!.evaluate(({BrowserWindow})=>BrowserWindow.getAllWindows()[0].isVisible())).toBe(false);expect((await shell.evaluate(()=>window.profiles.snapshot())).recoveryAccess?.phase).toBe('pending');expect(await remote('window.instance')).toBeUndefined();expect(readFileSync(record).includes(Buffer.from('legacy private draft'))).toBe(false);
  await app!.evaluate(({app})=>app.emit('activate'));await expect.poll(()=>remote('document.querySelector("textarea")?.value')).toBe('legacy private draft');await quit();
 }finally{await app?.close().catch(()=>{});server.closeAllConnections();await new Promise<void>(r=>server.close(()=>r()));rmSync(root,{recursive:true,force:true});}
});
