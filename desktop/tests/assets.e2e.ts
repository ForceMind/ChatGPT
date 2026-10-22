import{shellWindow}from'./helpers/shell';
import { test, expect, _electron as electron, type ElectronApplication, type Page } from '@playwright/test';
import { createServer, type Server } from 'node:http';
import { mkdtempSync, writeFileSync, readFileSync, rmSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { AssetStore } from '../src/main/asset-store';

const desktop=fileURLToPath(new URL('..',import.meta.url));
test('desktop real download, account-scoped assets, safe HTML and full capability resources',async({},info)=>{
  test.skip(!['darwin','win32'].includes(process.platform),'工作台支持 macOS 与 Windows');
  const root=mkdtempSync(path.join(os.tmpdir(),'profiles-assets-e2e-')),data=path.join(root,'data');
  let app:ElectronApplication|undefined,server:Server|undefined;let leaks=0;
  try{
    server=createServer((request,response)=>{
      if(request.url?.startsWith('/leak')){leaks++;response.end('blocked target');return;}
      if(request.url==='/download'){response.writeHead(200,{'Content-Type':'text/html','Content-Disposition':'attachment; filename="downloaded-page.html"'});response.end(`<!doctype html><html><body><h1>Native download fixture 中文</h1><button id="counter" onclick="this.textContent='clicked'">click</button><script>try{window.parentBridge=!!parent.profiles}catch{window.parentBridge=false}fetch(${JSON.stringify('http://'+request.headers.host+'/leak')}).catch(()=>{})</script></body></html>`);return;}
      response.writeHead(200,{'Content-Type':'text/html'});response.end('<!doctype html><title>Local asset acceptance fixture</title><h1>本地文件验收网页</h1><a id="download" href="/download" download>Download asset</a>');
    });
    await new Promise<void>(resolve=>server!.listen(0,'127.0.0.1',resolve));
    const address=server.address();if(!address||typeof address==='string')throw new Error('fixture address missing');
    const origin=`http://127.0.0.1:${address.port}`;
    const launch=async()=>{
      app=await electron.launch({args:[desktop],env:{...process.env,PROFILES_E2E:'1',PROFILES_TEST_DATA:data,PROFILES_FIXTURE_ORIGIN:origin}});
      const shell=await shellWindow(app);await shell.waitForURL('app://shell/index.html');await expect(shell.locator('.current-account')).toBeVisible();return shell;
    };
    const state=(shell:Page)=>shell.evaluate(()=>window.profiles.snapshot());
    let shell=await launch();
    await expect.poll(()=>app!.evaluate(({BrowserWindow})=>(BrowserWindow.getAllWindows().find(w=>w.webContents.getURL()==='app://shell/index.html')!.contentView.children[0] as Electron.WebContentsView)?.webContents.getURL())).toBe(origin+'/');
    const initial=await state(shell);const profile=initial.activeProfileId;
    await app!.evaluate(async({BrowserWindow})=>{const w=BrowserWindow.getAllWindows().find(w=>w.webContents.getURL()==='app://shell/index.html')!;const view=w.contentView.children[0] as Electron.WebContentsView;await view.webContents.executeJavaScript("document.getElementById('download').click()",true);});
    await shell.getByRole('button',{name:'资产中心',exact:true}).click();
    await expect(shell.locator('#wb-asset-grid [data-asset]')).toHaveCount(1);
    await expect(shell.locator('#wb-asset-grid [data-status="ready"]')).toHaveCount(1);
    await shell.locator('#wb-workbench [data-action="import"]').first().focus();
    await app!.evaluate(({Menu})=>{Menu.getApplicationMenu()!.getMenuItemById('chat-search')!.click();});
    await expect(shell.locator('#wb-asset-search')).toBeFocused();
    await expect(shell.locator('#wb-workbench kbd').first()).toHaveText(process.platform==='win32'?'Ctrl K':'⌘ K');
    const ledger=new AssetStore(data);const downloaded=ledger.list(profile)[0];expect(downloaded.status).toBe('ready');expect(readFileSync(ledger.resolveFile(profile,downloaded.id),'utf8')).toContain('Native download fixture');
    await shell.locator('#wb-asset-detail [data-action="preview"]').first().click();
    const frame=shell.frameLocator('#wb-viewer iframe');await expect(frame.locator('h1')).toHaveText('Native download fixture 中文');
    await app!.evaluate(({app,BrowserWindow})=>{app.focus({steal:true});const win=BrowserWindow.getAllWindows().find(w=>w.webContents.getURL()==='app://shell/index.html')!;win.focus();win.webContents.focus();});
    await expect.poll(()=>app!.evaluate(({BrowserWindow})=>BrowserWindow.getAllWindows().find(w=>w.webContents.getURL()==='app://shell/index.html')!.isFocused())).toBeTruthy();
    await frame.locator('#counter').focus();await expect.poll(()=>frame.locator('body').evaluate(()=>document.hasFocus())).toBeTruthy();
    await frame.locator('#counter').click();console.log('HTML click outcome:',await frame.locator('body').evaluate(()=>({focused:document.hasFocus(),value:document.querySelector('#counter')!.textContent,handler:typeof document.querySelector<HTMLButtonElement>('#counter')!.onclick})));
    await expect(frame.locator('#counter')).toHaveText('clicked');
    const guard=await frame.locator('body').evaluate(()=>({bridge:typeof(window as unknown as {profiles?:unknown}).profiles,parentBridge:(window as unknown as {parentBridge:boolean}).parentBridge,node:typeof(window as unknown as {require?:unknown}).require}));
    expect(guard).toEqual({bridge:'undefined',parentBridge:false,node:'undefined'});expect(leaks).toBe(0);
    await shell.locator('#wb-modal-root [data-action="close-modal"]').click();
    const image=path.join(root,'imported-image.svg'),notes=path.join(root,'notes.md');writeFileSync(image,'<svg xmlns="http://www.w3.org/2000/svg" width="300" height="180"><rect width="300" height="180" fill="#eee"/><text x="30" y="90">Native import</text></svg>');writeFileSync(notes,'# Imported notes\n\nOriginal bytes must stay unchanged.');
    await shell.evaluate(()=>{const input=document.createElement('input');input.type='file';input.multiple=true;input.id='native-import-fixture';document.body.append(input);input.addEventListener('change',()=>{const windowWithTask=window as Window&{importTask?:Promise<void>};windowWithTask.importTask=window.profiles.snapshot().then(s=>window.profiles.workbench.importDropped(s.activeProfileId,[...input.files!]));});});
    await shell.locator('#native-import-fixture').setInputFiles([image,notes]);await shell.evaluate(()=> (window as Window&{importTask?:Promise<void>}).importTask);
    await expect(shell.locator('#wb-asset-grid [data-asset]')).toHaveCount(3);expect(readFileSync(notes,'utf8')).toContain('Original bytes');
    await shell.locator('[data-filter="image"]').click();await expect(shell.locator('#wb-asset-grid [data-asset]')).toHaveCount(1);
    await shell.locator('[data-filter="all"]').click();await shell.locator('#wb-asset-search').fill('notes');await expect(shell.locator('#wb-asset-grid [data-asset]')).toHaveCount(1);await shell.locator('#wb-asset-grid [data-asset]').click();
    await shell.locator('#wb-asset-detail [data-action="rename"]').click();await shell.locator('#wb-edit-value').fill('renamed-notes.md');await shell.locator('[data-action="save-edit"]').click();await expect(shell.locator('#wb-asset-detail .aw-detail-name')).toHaveText('renamed-notes.md');
    await shell.locator('#wb-asset-search').fill('');await shell.locator('#wb-asset-detail [data-action="trash-current"]').click();await shell.locator('[data-filter="trash"]').click();await expect(shell.locator('#wb-asset-grid [data-asset]')).toHaveCount(1);await shell.locator('[data-action="restore-current"]').click();await shell.locator('[data-filter="all"]').click();await expect(shell.locator('#wb-asset-grid [data-asset]')).toHaveCount(3);
    await shell.screenshot({path:info.outputPath(process.platform+'-native-assets.png')});
    await shell.evaluate(()=>window.profiles.command({type:'profile:add',name:'Second profile',email:'',color:'#5275a0'}));await shell.getByRole('button',{name:'资产中心',exact:true}).click();await expect(shell.locator('#wb-asset-grid .aw-empty-state')).toBeVisible();await expect(shell.locator('#wb-asset-grid [data-asset]')).toHaveCount(0);
    await shell.evaluate(id=>window.profiles.command({type:'profile:activate',id}),profile);await expect(shell.locator('#wb-asset-grid [data-asset]')).toHaveCount(3);
    await shell.getByRole('button',{name:'能力库',exact:true}).click();await expect(shell.locator('#wb-capability-grid [data-capability]')).toHaveCount(120);
    const favorite=shell.locator('[data-capability-favorite]').first();const favoriteId=await favorite.getAttribute('data-capability-favorite');await favorite.click();
    await expect(shell.locator('#wb-capability-detail')).toContainText('质量检查');
    await shell.screenshot({path:info.outputPath(process.platform+'-native-skills.png')});
    await shell.locator('#wb-agents-nav').click();await expect(shell.locator('#wb-capability-grid [data-capability]')).toHaveCount(0);
    await expect(shell.getByRole('heading',{name:'暂无预置智能体',exact:true})).toHaveText('暂无预置智能体');
    await shell.screenshot({path:info.outputPath(process.platform+'-native-library-empty.png')});
    await app!.close();app=undefined;shell=await launch();await shell.getByRole('button',{name:'资产中心',exact:true}).click();await expect(shell.locator('#wb-asset-grid [data-asset]')).toHaveCount(3);
    const after=await shell.evaluate(()=>window.profiles.workbench.capabilities());expect(after.favorites).toContain(favoriteId);
    await shell.locator('#wb-workbench [data-action="chat"]').click();expect((await state(shell)).panel).toBeUndefined();
    const visible=await app!.evaluate(({BrowserWindow})=>{const w=BrowserWindow.getAllWindows().find(w=>w.webContents.getURL()==='app://shell/index.html')!;return(w.contentView.children[0] as Electron.WebContentsView).getVisible();});expect(visible).toBeTruthy();
  }finally{await app?.close().catch(()=>{});if(server)await new Promise<void>(resolve=>server!.close(()=>resolve()));rmSync(root,{recursive:true,force:true});}
});
