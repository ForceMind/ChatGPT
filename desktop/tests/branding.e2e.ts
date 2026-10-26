import {test,expect,_electron as electron,type ElectronApplication} from '@playwright/test';
import {createServer} from 'node:http';
import {mkdtempSync,rmSync,readFileSync,writeFileSync,realpathSync} from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import {fileURLToPath} from 'node:url';
import {shellWindow} from './helpers/shell';
import {validManifest} from '../src/shared/updates';
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

test('Studio website renders canonical pages and preserves legacy update feeds',async({},info)=>{
 test.skip(process.platform!=='darwin');
 const root=mkdtempSync(path.join(os.tmpdir(),'studio-website-')),site=path.resolve(desktop,'../site');let app:ElectronApplication|undefined;
 const server=createServer((q,r)=>{
  const name=new URL(q.url||'/','http://localhost').pathname;
  const file=path.resolve(site,'.'+(name==='/'?'/index.html':name));
  if(!file.startsWith(site+path.sep)){r.writeHead(403);r.end();return;}
  try{r.setHeader('Content-Type',({'.html':'text/html;charset=utf-8','.js':'text/javascript','.css':'text/css','.svg':'image/svg+xml','.png':'image/png','.json':'application/json','.txt':'text/plain;charset=utf-8'}as Record<string,string>)[path.extname(file)]||'application/octet-stream');r.end(readFileSync(file));}catch{r.writeHead(404);r.end();}
 });
 await new Promise<void>(r=>server.listen(0,'127.0.0.1',r));const address=server.address();if(!address||typeof address==='string')throw Error('fixture');
 const publicOrigin=process.env.STUDIO_VERIFY_ORIGIN;
 if(publicOrigin&&!['https://chatgpt-studio.pages.dev','https://chatgpt.xincreates.com','https://chatgpt-profiles.pages.dev'].includes(publicOrigin))throw Error('Unexpected website verification origin');
 const origin=publicOrigin||'http://127.0.0.1:'+address.port,entry=path.join(root,'main.cjs');
 const feed=JSON.parse(readFileSync(path.join(site,'updates/macos.json'),'utf8'));expect(validManifest(feed)).toBe(true);
 const versionFor=(platform:string)=>readFileSync(path.join(site,'index.html'),'utf8').match(new RegExp('<article\\b[^>]*data-os="'+platform+'"[^>]*>[\\s\\S]*?<span class="version">v([^<]+)<\\/span>'))?.[1];
 const platformVersions={macos:versionFor('macos'),windows:versionFor('windows'),android:versionFor('android')};
 for(const version of Object.values(platformVersions))expect(version).toMatch(/^\d+\.\d+\.\d+-alpha\.\d+$/);
 expect(platformVersions.macos).toBe(feed.version);
 const manifest=(version:string)=>JSON.parse(readFileSync(path.join(desktop,'../docs/releases',version+'.json'),'utf8'));
 const mac=manifest(platformVersions.macos!),windows=manifest(platformVersions.windows!);
 const android=JSON.parse(readFileSync(path.join(desktop,'../docs/releases',platformVersions.android+'-android.json'),'utf8'));
 expect(android.version).toBe(platformVersions.android);
 const release={artifacts:[...mac.artifacts.filter((item:{platform:string;purpose?:string})=>item.platform==='macos'&&item.purpose!=='legacy-updater'),...windows.artifacts.filter((item:{platform:string})=>item.platform==='windows'),{...android.artifact,platform:'android',url:'https://github.com/ForceMind/ChatGPT/releases/download/v'+android.version+'/'+android.artifact.file}]};
 expect(release.artifacts).toHaveLength(5);
 const updateFile=mac.artifacts.find((item:{file:string})=>feed.files.arm64?.url.endsWith('/'+item.file));
 expect(updateFile.sha256).toBe(feed.files.arm64?.sha256);expect(updateFile.bytes).toBe(feed.files.arm64?.size);
 writeFileSync(entry,"const {app,BrowserWindow}=require('electron');require('node:fs').mkdirSync(process.env.STUDIO_SITE_DATA,{recursive:true});app.setPath('userData',process.env.STUDIO_SITE_DATA);app.whenReady().then(()=>{const win=new BrowserWindow({width:1440,height:1000,webPreferences:{sandbox:true,contextIsolation:true,nodeIntegration:false}});win.loadURL(process.env.STUDIO_SITE_ORIGIN)});");
 try{
  app=await electron.launch({args:[entry],env:{...process.env,STUDIO_SITE_DATA:path.join(root,'data'),STUDIO_SITE_ORIGIN:origin}});const page=await app.firstWindow();const errors:string[]=[];page.on('pageerror',error=>errors.push(error.message));
  await expect(page).toHaveTitle(/ChatGPT Studio/);await expect(page.getByRole('link',{name:'ChatGPT Studio 首页',exact:true})).toBeVisible();
  await expect(page.locator('.hero-build')).toContainText(feed.version);
  for(const version of Object.values(platformVersions))await expect(page.locator('.hero-build')).toContainText(version!);
  for(const [platform,version] of Object.entries(platformVersions))await expect(page.locator('article[data-os="'+platform+'"] .version')).toHaveText('v'+version);
  const canonical='https://chatgpt-studio.pages.dev';
  if(publicOrigin){
   const landed=publicOrigin==='https://chatgpt-profiles.pages.dev'?canonical:publicOrigin;
   await expect.poll(()=>new URL(page.url()).origin).toBe(landed);
   await expect(page.locator('link[rel="canonical"]')).toHaveAttribute('href',canonical+'/');
   if(publicOrigin==='https://chatgpt-profiles.pages.dev'){await page.goto(publicOrigin+'/?source=legacy-check#downloads');await expect.poll(()=>new URL(page.url()).origin).toBe(canonical);expect(new URL(page.url()).hash).toBe('#downloads');await page.goto(canonical+'/');}
   if(publicOrigin==='https://chatgpt.xincreates.com'){
    expect(new URL(page.url()).origin).toBe(publicOrigin);
    const status=await app.evaluate(async({session},base)=>(await session.fromPartition('studio-custom-domain-check').fetch(base+'/',{credentials:'omit',redirect:'error'})).status,publicOrigin);
    expect(status).toBe(200);
   }
  }
  const repositoryLinks=await page.locator('a[href^="https://github.com/ForceMind/ChatGPT"]').evaluateAll(links=>links.map(a=>(a as HTMLAnchorElement).href).filter(href=>!href.includes('/releases/download/')));
  expect(repositoryLinks).toEqual([]);
  expect(await page.locator('a[href="/license.txt"]').count()).toBeGreaterThan(0);
  expect(await page.locator('a[href^="mailto:me@xincreates.com"]').count()).toBeGreaterThan(0);
  for(const item of release.artifacts){
   expect(await page.locator('a[href="'+item.url+'"]').count()).toBeGreaterThanOrEqual(1);
   expect(await page.locator('a[href="https://gh-proxy.org/'+item.url+'"]').count()).toBeGreaterThanOrEqual(1);
   expect(await page.locator('[data-copy="'+item.sha256+'"]').count()).toBe(1);
  }
  for(const [width,height] of [[1440,1000],[390,844]]){
   await app.evaluate(({BrowserWindow},size)=>BrowserWindow.getAllWindows()[0].setContentSize(size[0],size[1]),[width,height]);
   await expect.poll(()=>page.evaluate(()=>innerWidth)).toBe(width);
   expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
   await page.screenshot({path:info.outputPath('studio-website-'+width+'.png')});
   await page.getByRole('tab',{name:'Android',exact:true}).click();
   for(const view of ['tabs','tablet','assets','skills']){
    await page.locator('[data-android-view="'+view+'"]').click();
    await expect(page.locator('[data-android-view="'+view+'"]')).toHaveAttribute('aria-pressed','true');
    await expect.poll(()=>page.locator('#product-image').evaluate((image:HTMLImageElement)=>image.complete&&image.naturalWidth>0)).toBe(true);
    expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
    if(view==='tabs'||view==='tablet')await page.locator('#product-panel').screenshot({path:info.outputPath('studio-android-'+view+'-'+width+'.png')});
   }
  }
  await app.evaluate(({BrowserWindow})=>BrowserWindow.getAllWindows()[0].setContentSize(1440,1000));
  const zip=release.artifacts.find((a:{file:string})=>a.file.endsWith('-mac-arm64.zip'));
  await page.locator('article[data-os="macos"] details.checksums > summary').click();
  await page.locator('[data-copy="'+zip.sha256+'"]').click();
  await expect.poll(()=>app!.evaluate(({clipboard})=>clipboard.readText())).toBe(zip.sha256);
  await page.screenshot({path:info.outputPath('studio-downloads-1440.png')});
  const remoteFeed=await app.evaluate(async({session},origin)=>{const response=await session.fromPartition('studio-feed-public-check').fetch(origin+'/updates/macos.json',{credentials:'omit',redirect:'error',headers:{Accept:'application/json'}});return{status:response.status,contentType:response.headers.get('content-type'),text:await response.text()};},origin);
  expect(remoteFeed.status).toBe(200);expect(remoteFeed.contentType).toContain('application/json');expect(JSON.parse(remoteFeed.text)).toEqual(feed);
  const licenseOrigin=new URL(page.url()).origin;
  const remoteLicense=await app.evaluate(async({session},base)=>{const response=await session.fromPartition('studio-license-check').fetch(base+'/license.txt',{credentials:'omit',redirect:'error'});return{status:response.status,text:await response.text()};},licenseOrigin);
  expect(remoteLicense.status).toBe(200);expect(remoteLicense.text).toBe(readFileSync(path.join(desktop,'../LICENSE'),'utf8'));
  writeFileSync(info.outputPath('site-verification.json'),JSON.stringify({origin,landedOrigin:new URL(page.url()).origin,licenseOrigin,platformVersions,macosUpdateVersion:feed.version,downloadFiles:release.artifacts.length,acceleratedFiles:release.artifacts.length,widths:[1440,390],checksumCopy:true,credentiallessElectronFeed:true,legacyPageRedirect:publicOrigin==='https://chatgpt-profiles.pages.dev',customDomainServedDirectly:publicOrigin==='https://chatgpt.xincreates.com',repositoryNavigationAbsent:true,rootLicenseMatchesWebsite:true,publicCommercialContact:true,errors}));
  expect(errors).toEqual([]);
 }finally{await app?.close().catch(()=>{});server.closeAllConnections();await new Promise<void>(r=>server.close(()=>r()));rmSync(root,{recursive:true,force:true});}
});
