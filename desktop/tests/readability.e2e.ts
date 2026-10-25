import {test,expect,_electron as electron,type ElectronApplication,type Page} from '@playwright/test';
import {createServer} from 'node:http';
import {mkdtempSync,writeFileSync,readFileSync,rmSync} from 'node:fs';
import os from 'node:os';import path from 'node:path';import {fileURLToPath} from 'node:url';
import {StateStore} from '../src/main/state';import {AssetStore} from '../src/main/asset-store';import {shellWindow} from './helpers/shell';
const desktop=path.resolve(fileURLToPath(new URL('..',import.meta.url)));

async function readable(page:Page,selector:string,min=13):Promise<void>{
 const values=await page.locator(selector).evaluateAll(nodes=>nodes.filter(e=>e.getClientRects().length).map(element=>{
  const style=getComputedStyle(element),rgb=(value:string)=>value.match(/[\d.]+/g)?.map(Number)||[];
  let background:Element|null=element,bg=[255,255,255];
  while(background){const candidate=rgb(getComputedStyle(background).backgroundColor);if(candidate.length===3||candidate[3]===1){bg=candidate;break;}background=background.parentElement;}
  const lum=(color:number[])=>color.slice(0,3).map(v=>{v/=255;return v<=.04045?v/12.92:((v+.055)/1.055)**2.4;}).reduce((v,x,i)=>v+x*[.2126,.7152,.0722][i],0);
  const foreground=lum(rgb(style.color)),back=lum(bg);
  return{text:element.textContent?.trim().slice(0,90),font:parseFloat(style.fontSize),contrast:(Math.max(foreground,back)+.05)/(Math.min(foreground,back)+.05)};
 }));
 expect(values.length).toBeGreaterThan(0);for(const value of values){expect(value.font,JSON.stringify(value)).toBeGreaterThanOrEqual(min);expect(value.contrast,JSON.stringify(value)).toBeGreaterThanOrEqual(4.5);}
}

test('App text remains readable and drawer/form actions stay reachable in wide and narrow windows',async({},info)=>{
 test.skip(!['darwin','win32'].includes(process.platform));
 const root=mkdtempSync(path.join(os.tmpdir(),'studio-readable-'));let app:ElectronApplication|undefined;
 const server=createServer((_request,response)=>{response.setHeader('Content-Type','text/html; charset=utf-8');response.end('<!doctype html><title>Local readability fixture</title><p>Local offline fixture; no ChatGPT service</p><textarea id="prompt-textarea"></textarea>');});
 await new Promise<void>(r=>server.listen(0,'127.0.0.1',r));const address=server.address();if(!address||typeof address==='string')throw Error('Fixture address');const origin='http://127.0.0.1:'+address.port;
 try{
  const data=path.join(root,'data'),store=new StateStore(data,origin),profile=store.read().activeProfileId,assets=new AssetStore(data);
  store.commit(s=>{s.profiles[0].name='可读性验收账号';s.profiles[0].email='readability@example.test';});
  const file=path.join(root,'本地网页交互与文字可读性检查.html'),original='<!doctype html><meta charset="utf-8"><h1>Readability file fixture</h1><p>Original file content stays unchanged.</p>';writeFileSync(file,original);await assets.importFile(profile,file,{kind:'import'});
  const note=path.join(root,'检查清单.txt');writeFileSync(note,'Synthetic fixture only');await assets.importFile(profile,note,{kind:'import'});
  app=await electron.launch({args:[desktop],env:{...process.env,PROFILES_E2E:'1',PROFILES_TEST_DATA:data,PROFILES_FIXTURE_ORIGIN:origin}});const shell=await shellWindow(app);
  const viewports:{width:number;height:number}[]=[];
  const resize=async(width:number,requestedHeight:number)=>{
   const height=await app!.evaluate(({BrowserWindow,screen},{width,requestedHeight})=>{const window=BrowserWindow.getAllWindows().find(w=>w.webContents.getURL()==='app://shell/index.html')!,frame=window.getBounds().height-window.getContentBounds().height,available=screen.getDisplayMatching(window.getBounds()).workArea.height-frame,height=Math.min(requestedHeight,available);if(height<600)throw Error('CI display cannot show the supported 600px minimum window');window.setContentSize(width,height);return height;},{width,requestedHeight});
   await expect.poll(()=>shell.evaluate(()=>({width:innerWidth,height:innerHeight}))).toEqual({width,height});viewports.push({width,height});
  };
  const fits=async(selector:string)=>{const r=await shell.locator(selector).evaluate(e=>({rect:e.getBoundingClientRect().toJSON(),width:innerWidth,height:innerHeight}));expect(r.rect.left).toBeGreaterThanOrEqual(0);expect(r.rect.right).toBeLessThanOrEqual(r.width+1);expect(r.rect.bottom).toBeLessThanOrEqual(r.height+1);};
  await resize(1440,1000);await expect(shell.locator('.titlebar')).toHaveCSS('height','52px');await readable(shell,'.workspace-action,.current-account',14);
  await shell.getByRole('button',{name:'资产中心',exact:true}).click();await expect(shell.locator('#wb-asset-grid [data-asset]')).toHaveCount(2);
  await readable(shell,'#wb-asset-detail .aw-property-row,#wb-asset-detail .aw-property-row>span,#wb-asset-detail .aw-detail-footer button',14);
  await expect(shell.locator('#wb-asset-detail .aw-detail-footer button')).toHaveCount(4);await fits('#wb-asset-detail .aw-detail-footer');
  await shell.screenshot({path:info.outputPath('readability-assets-wide.png')});
  await shell.getByRole('button',{name:'能力库',exact:true}).click();await shell.locator('#wb-capability-grid [data-capability]').first().click();await readable(shell,'.aw-capability-description',15);
  await shell.locator('#wb-capability-detail [data-action="use-capability"]').click();await expect(shell.getByRole('dialog',{name:'交给 GPT',exact:true})).toBeVisible();
  await readable(shell,'.aw-field-label,.aw-modal-note',14);await fits('.aw-modal-footer');await shell.screenshot({path:info.outputPath('readability-task-form.png')});await shell.locator('#wb-modal-root [data-action="close-modal"]').click();
  await shell.getByRole('button',{name:'资产中心',exact:true}).click();
  for(const [width,height] of [[1000,760],[900,650],[800,600]]){
   await resize(width,height);await expect(shell.locator('#wb-asset-detail')).toBeHidden();
   const card=shell.locator('#wb-asset-grid [data-asset]').filter({hasText:'本地网页交互'});await card.click();const detail=shell.getByRole('dialog',{name:'资产详情',exact:true});await expect(detail).toBeVisible();
   await readable(shell,'#wb-asset-detail .aw-detail-footer button',14);await fits('#wb-asset-detail .aw-detail-footer');
   expect(await shell.locator('#wb-workbench .aw-asset-main').evaluate(e=>(e as HTMLElement).inert)).toBe(true);
   expect(await shell.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
   await shell.screenshot({path:info.outputPath('readability-drawer-'+width+'.png')});await detail.getByRole('button',{name:'关闭详情抽屉',exact:true}).click();
   expect(await shell.locator('#wb-workbench .aw-asset-main').evaluate(e=>(e as HTMLElement).inert)).toBe(false);
  }
  const card=shell.locator('#wb-asset-grid [data-asset]').filter({hasText:'本地网页交互'});await card.dblclick();await expect(shell.frameLocator('#wb-viewer iframe').getByRole('heading',{name:'Readability file fixture'})).toBeVisible();await shell.locator('#wb-modal-root [data-action="close-modal"]').click();
  await shell.getByRole('button',{name:'切换账号',exact:true}).click();await shell.getByRole('menuitem',{name:'管理 可读性验收账号',exact:true}).click();
  await expect(shell.getByRole('dialog',{name:'管理账号空间',exact:true})).toBeVisible();await readable(shell,'.account-form-body>label,.identity-note p',14);await readable(shell,'.modal input:not([type=radio])',15);await fits('.modal-actions');await shell.screenshot({path:info.outputPath('readability-account-small.png')});await shell.getByRole('button',{name:'关闭账号管理',exact:true}).click();
  await shell.getByRole('button',{name:'切换账号',exact:true}).click();await shell.getByRole('menuitem',{name:'登录与链接记录',exact:true}).click();await readable(shell,'.login-record-header p,.login-record-tools',14);await shell.screenshot({path:info.outputPath('readability-diagnostics.png')});
  expect(readFileSync(file,'utf8')).toBe(original);writeFileSync(info.outputPath('readability-viewport-info.json'),JSON.stringify({platform:process.platform,viewports,scope:'Actual native windows constrained by CI work area; synthetic account/files only. Text, contrast, action visibility, double click and drawer interaction assertions apply at every recorded size.'},null,2));
 }finally{await app?.close().catch(()=>{});server.closeAllConnections();await new Promise<void>(r=>server.close(()=>r()));rmSync(root,{recursive:true,force:true});}
});
