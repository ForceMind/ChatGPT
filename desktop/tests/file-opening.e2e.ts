import {test,expect,_electron as electron,type ElectronApplication} from '@playwright/test';
import {createServer} from 'node:http';
import {mkdtempSync,writeFileSync,readFileSync,rmSync} from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {StateStore} from '../src/main/state';
import {AssetStore} from '../src/main/asset-store';
import {shellWindow} from './helpers/shell';

const desktop=path.resolve(fileURLToPath(new URL('..',import.meta.url)));
test('asset files open with isolated interactive HTML, explicit network mode, media and system delegation',async({},info)=>{
 test.skip(!['darwin','win32'].includes(process.platform));
 const root=mkdtempSync(path.join(os.tmpdir(),'studio-file-opening-')),data=path.join(root,'data');let app:ElectronApplication|undefined;
 const network:{url:string;cookie:string}[]=[];
 const server=createServer((q,r)=>{if(q.url==='/remote.js'||q.url==='/network'){network.push({url:q.url,cookie:q.headers.cookie||''});r.setHeader('Access-Control-Allow-Origin','*');if(q.url==='/remote.js'){r.setHeader('Content-Type','text/javascript');r.end("document.querySelector('#network-state').textContent='联网资源已加载';fetch("+JSON.stringify('http://'+q.headers.host+'/network')+",{credentials:'include'}).catch(()=>{});");}else r.end('ok');return;}r.setHeader('Content-Type','text/html');r.end('<h1>Local fixture only, not ChatGPT</h1><textarea id="prompt-textarea"></textarea>');});
 await new Promise<void>(r=>server.listen(0,'127.0.0.1',r));const address=server.address();if(!address||typeof address==='string')throw Error('fixture');const origin='http://127.0.0.1:'+address.port;
 try{
  const state=new StateStore(data,origin),profile=state.read().activeProfileId,ledger=new AssetStore(data);
  const html='<!doctype html><meta charset="utf-8"><h1 id="ready">正在打开</h1><button id="counter" onclick="this.textContent=\'计数 1\'">计数 0</button><p id="network-state">联网资源未加载</p><script>localStorage.setItem("fixture","ephemeral");document.querySelector("#ready").textContent="HTML 本地功能可用";try{window.parentBridge=!!parent.profiles}catch{window.parentBridge=false}</script><script src="'+origin+'/remote.js"></script>';
  const wav=Buffer.alloc(44+1600);wav.write('RIFF',0);wav.writeUInt32LE(wav.length-8,4);wav.write('WAVEfmt ',8);wav.writeUInt32LE(16,16);wav.writeUInt16LE(1,20);wav.writeUInt16LE(1,22);wav.writeUInt32LE(8000,24);wav.writeUInt32LE(16000,28);wav.writeUInt16LE(2,32);wav.writeUInt16LE(16,34);wav.write('data',36);wav.writeUInt32LE(1600,40);
  const files:{[name:string]:string|Buffer}={'interactive.html':html,'notes.md':'# 阅读标题\n\n原件保持不变。','records.csv':'name,value\nalpha,1\nbeta,2','data.json':'{"ready":true}','source.tsx':'export const view = "<script>not executable</script>";','sound.wav':wav,'unsupported-codec.mp4':Buffer.from('invalid video fixture'),'document.docx':Buffer.from('office routing fixture'),'unknown.custom':Buffer.from([0,1,2,3]),'too-large.txt':'a'.repeat(2*1024*1024+1),'run.command':'#!/bin/sh\nexit 0\n','picture.heic':Buffer.from('unsupported image codec fixture')};
  const ids:Record<string,string>={};for(const [name,bytes] of Object.entries(files)){const file=path.join(root,name);writeFileSync(file,bytes);ids[name]=(await ledger.importFile(profile,file,{kind:'import'})).id;}
  app=await electron.launch({args:[desktop],env:{...process.env,PROFILES_E2E:'1',PROFILES_TEST_DATA:data,PROFILES_FIXTURE_ORIGIN:origin}});const shell=await shellWindow(app);
  await app.evaluate(async({BrowserWindow,session,app,shell},origin)=>{app.focus({steal:true});const win=BrowserWindow.getAllWindows().find(w=>w.webContents.getURL()==='app://shell/index.html')!;win.show();win.focus();win.webContents.focus();await session.defaultSession.cookies.set({url:origin,name:'shell_secret',value:'MUST_NOT_LEAK'});(app as any).openedAssets=[];shell.openPath=async file=>{(app as any).openedAssets.push(file);return '';};},origin);
  await shell.getByRole('button',{name:'资产中心',exact:true}).click();const grid=shell.locator('#wb-asset-grid');await expect(grid.locator('[data-asset]')).toHaveCount(Object.keys(files).length);
  const open=async(name:string)=>{await grid.locator('[data-asset]').filter({hasText:name}).dblclick();await expect(shell.locator('#wb-viewer')).toBeVisible();};
  const close=async()=>{await shell.locator('#wb-modal-root [data-action="close-modal"]').click();};
  await open('interactive.html');let frame=shell.frameLocator('#wb-viewer iframe');await expect(frame.locator('#ready')).toHaveText('HTML 本地功能可用');await expect(frame.locator('#network-state')).toHaveText('联网资源未加载');expect(network).toHaveLength(0);await expect(shell.locator('#wb-modal-root')).toContainText('引用了联网资源');
  expect(await shell.locator('#wb-viewer iframe').getAttribute('credentialless')).not.toBeNull();
  await frame.locator('#counter').focus();await frame.locator('#counter').click();await expect(frame.locator('#counter')).toHaveText('计数 1');
  expect(await frame.locator('body').evaluate(()=>({storage:localStorage.getItem('fixture'),bridge:typeof(window as any).profiles,node:typeof(window as any).require,parentBridge:(window as any).parentBridge}))).toEqual({storage:'ephemeral',bridge:'undefined',node:'undefined',parentBridge:false});
  const oldURL=await shell.locator('#wb-viewer iframe').getAttribute('src');
  // Reproduce the previous opaque-origin sandbox failure without accessing personal data.
  await shell.evaluate(url=>{const f=document.createElement('iframe');f.id='legacy-sandbox-repro';f.setAttribute('sandbox','allow-scripts');f.src=url!;document.body.append(f);},oldURL);
  const legacy=shell.frameLocator('#legacy-sandbox-repro');await expect.poll(()=>legacy.locator('body').evaluate(()=>document.readyState)).toBe('complete');await expect(legacy.locator('#ready')).toHaveText('正在打开');await shell.evaluate(()=>document.querySelector('#legacy-sandbox-repro')!.remove());
  await shell.screenshot({path:info.outputPath('html-offline-preview.png')});
  await shell.locator('[data-action="preview-online"]').click();frame=shell.frameLocator('#wb-viewer iframe');await expect(frame.locator('#ready')).toHaveText('HTML 本地功能可用');await expect(frame.locator('#network-state')).toHaveText('联网资源已加载');await expect.poll(()=>network.filter(n=>n.url==='/network').length).toBe(1);expect(network.every(n=>n.cookie==='')).toBe(true);await shell.screenshot({path:info.outputPath('html-online-preview.png')});
  await shell.locator('[data-action="preview-source"]').click();await expect(shell.locator('#wb-viewer pre')).toContainText('localStorage.setItem');await close();
  for(const [name,expected] of [['notes.md','阅读标题'],['records.csv','alpha'],['data.json','"ready":true'],['source.tsx','<script>not executable</script>']]){await open(name);if(name.endsWith('.md'))await expect(shell.frameLocator('#wb-viewer iframe').locator('h1')).toHaveText(expected);else await expect(shell.locator('#wb-viewer')).toContainText(expected);await close();}
  await open('sound.wav');await expect.poll(()=>shell.locator('#wb-viewer audio').evaluate((a:HTMLAudioElement)=>a.readyState)).toBeGreaterThanOrEqual(1);await close();
  await open('unsupported-codec.mp4');await expect(shell.locator('#wb-media-status')).toContainText('不能播放');await close();
  await open('picture.heic');await expect(shell.locator('#wb-viewer')).toContainText('不能解码');await close();
  for(const name of ['document.docx','unknown.custom','too-large.txt']){await open(name);await expect(shell.locator('#wb-viewer')).toContainText(name==='too-large.txt'?'2 MB':'系统应用');await close();}
  await open('document.docx');await shell.locator('#wb-modal-root .aw-modal-footer [data-action="open-external"]').click();expect(await app.evaluate(({app})=>(app as any).openedAssets)).toEqual([ledger.resolveFile(profile,ids['document.docx'])]);await close();
  await expect(shell.evaluate(({profile,id})=>window.profiles.workbench.act(profile,{type:'open-external',id}),{profile,id:ids['run.command']})).rejects.toThrow(/不会直接运行/);
  writeFileSync(ledger.resolveFile(profile,ids['unknown.custom']),'changed outside the app');await expect(shell.evaluate(({profile,id})=>window.profiles.workbench.act(profile,{type:'open-external',id}),{profile,id:ids['unknown.custom']})).rejects.toThrow();expect((await app.evaluate(({app})=>(app as any).openedAssets)).length).toBe(1);
  expect(readFileSync(path.join(root,'interactive.html'),'utf8')).toBe(html);
  await shell.evaluate(()=>window.profiles.command({type:'profile:add',name:'Another fixture account',email:'',color:'#667485'}));expect(await shell.evaluate(url=>fetch(url!).then(r=>r.status),oldURL)).toBe(403);
  await info.attach('file-opening-verification',{body:JSON.stringify({scope:'isolated Electron and loopback fixtures, no personal files or real ChatGPT',legacyOpaqueSandboxReproduced:true,htmlLocalStorageAndClick:true,offlineNetworkRequests:0,onlineCredentialIsolation:true,formatsChecked:Object.keys(files),systemOpenDelegation:'OS handler stub; no third-party app launched',sourceUnchanged:true,crossAccountURLDenied:true}),contentType:'application/json'});
 }finally{await app?.close().catch(()=>{});server.closeAllConnections();await new Promise<void>(r=>server.close(()=>r()));rmSync(root,{recursive:true,force:true});}
});
