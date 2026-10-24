import {test,expect,_electron as electron,type ElectronApplication} from '@playwright/test';
import {createServer} from 'node:http';
import {mkdtempSync,rmSync,readFileSync,writeFileSync,realpathSync} from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import {fileURLToPath} from 'node:url';
import {shellWindow} from './helpers/shell';
const desktop=path.resolve(fileURLToPath(new URL('..',import.meta.url)));

test('Studio display name preserves legacy internal identity and isolated storage',async({},info)=>{
 test.skip(!['darwin','win32'].includes(process.platform));
 const root=mkdtempSync(path.join(os.tmpdir(),'studio-brand-'));let app:ElectronApplication|undefined;
 const server=createServer((_q,r)=>r.end('<title>Local branding fixture</title><textarea id="prompt-textarea"></textarea>'));
 await new Promise<void>(r=>server.listen(0,'127.0.0.1',r));const address=server.address();if(!address||typeof address==='string')throw Error('fixture');
 try{
  app=await electron.launch({args:[desktop],env:{...process.env,PROFILES_E2E:'1',PROFILES_TEST_DATA:path.join(root,'data'),PROFILES_FIXTURE_ORIGIN:'http://127.0.0.1:'+address.port}});
  await shellWindow(app);
  await expect.poll(()=>app!.evaluate(({BrowserWindow})=>BrowserWindow.getAllWindows().find(w=>w.webContents.getURL()==='app://shell/index.html')?.getTitle())).toMatch(/ — ChatGPT Studio$/);
  const state=await app.evaluate(({app,Menu})=>({internalName:app.getName(),userData:app.getPath('userData'),menu:Menu.getApplicationMenu()?.items[0].label}));
  expect(state.internalName).toBe('ChatGPT Profiles');expect(state.menu).toBe('ChatGPT Studio');expect(realpathSync(state.userData)).toBe(realpathSync(path.join(root,'data')));
  writeFileSync(info.outputPath('branding.json'),JSON.stringify({displayName:state.menu,internalName:state.internalName,isolatedStorage:true}));
 }finally{await app?.close().catch(()=>{});server.closeAllConnections();await new Promise<void>(r=>server.close(()=>r()));rmSync(root,{recursive:true,force:true});}
});

test('Studio website renders its new brand on desktop and mobile with historical downloads intact',async({},info)=>{
 test.skip(process.platform!=='darwin');
 const root=mkdtempSync(path.join(os.tmpdir(),'studio-website-')),site=path.resolve(desktop,'../site');let app:ElectronApplication|undefined;
 const server=createServer((q,r)=>{
  const name=new URL(q.url||'/','http://localhost').pathname;
  const file=path.resolve(site,'.'+(name==='/'?'/index.html':name));
  if(!file.startsWith(site+path.sep)){r.writeHead(403);r.end();return;}
  try{r.setHeader('Content-Type',({'.html':'text/html;charset=utf-8','.js':'text/javascript','.css':'text/css','.svg':'image/svg+xml','.png':'image/png'}as Record<string,string>)[path.extname(file)]||'application/octet-stream');r.end(readFileSync(file));}catch{r.writeHead(404);r.end();}
 });
 await new Promise<void>(r=>server.listen(0,'127.0.0.1',r));const address=server.address();if(!address||typeof address==='string')throw Error('fixture');
 const origin='http://127.0.0.1:'+address.port,entry=path.join(root,'main.cjs');
 writeFileSync(entry,"const {app,BrowserWindow}=require('electron');require('node:fs').mkdirSync(process.env.STUDIO_SITE_DATA,{recursive:true});app.setPath('userData',process.env.STUDIO_SITE_DATA);app.whenReady().then(()=>{const win=new BrowserWindow({width:1440,height:1000,webPreferences:{sandbox:true,contextIsolation:true,nodeIntegration:false}});win.loadURL(process.env.STUDIO_SITE_ORIGIN)});");
 try{
  app=await electron.launch({args:[entry],env:{...process.env,STUDIO_SITE_DATA:path.join(root,'data'),STUDIO_SITE_ORIGIN:origin}});const page=await app.firstWindow();const errors:string[]=[];page.on('pageerror',error=>errors.push(error.message));
  await expect(page).toHaveTitle(/ChatGPT Studio/);await expect(page.getByRole('link',{name:'ChatGPT Studio 首页',exact:true})).toBeVisible();
  expect(await page.locator('a[href*="releases/download/v3.0.0-alpha.13/"]').count()).toBeGreaterThanOrEqual(2);
  for(const [width,height] of [[1440,1000],[390,844]]){
   await app.evaluate(({BrowserWindow},size)=>BrowserWindow.getAllWindows()[0].setContentSize(size[0],size[1]),[width,height]);
   await expect.poll(()=>page.evaluate(()=>innerWidth)).toBe(width);
   expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
   await page.screenshot({path:info.outputPath('studio-website-'+width+'.png')});
  }
  expect(errors).toEqual([]);
 }finally{await app?.close().catch(()=>{});server.closeAllConnections();await new Promise<void>(r=>server.close(()=>r()));rmSync(root,{recursive:true,force:true});}
});
