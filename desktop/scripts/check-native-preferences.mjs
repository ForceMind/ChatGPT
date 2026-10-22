import {spawn} from 'node:child_process';
import {createServer} from 'node:http';
import {mkdtempSync,writeFileSync,rmSync,readFileSync,existsSync} from 'node:fs';
import {fileURLToPath} from 'node:url';
import path from 'node:path';
import os from 'node:os';
// Playwright pauses new targets before attaching its own debugger. Use a normal
// Electron launch to check the production popup's very first script instead.
if(!['darwin','win32'].includes(process.platform))throw Error('This native preference check requires macOS or Windows');
const desktop=fileURLToPath(new URL('..',import.meta.url));
const root=mkdtempSync(path.join(os.tmpdir(),'profiles-native-preferences-'));
const server=createServer((q,r)=>{
 r.writeHead(200,{'Content-Type':'text/html;charset=utf-8'});
 r.end(`<!doctype html><meta charset="utf-8"><title>Local native preferences fixture</title><script>window.first={language:navigator.language,locale:Intl.DateTimeFormat().resolvedOptions().locale,tz:Intl.DateTimeFormat().resolvedOptions().timeZone,header:${JSON.stringify(q.headers['accept-language']||'')},cookie:document.cookie,hasOpener:!!window.opener};</script>`);
});
await new Promise(r=>server.listen(0,'127.0.0.1',r));
const origin='http://127.0.0.1:'+server.address().port;
function hookMain(origin,resultFile){
 const{app,BrowserWindow}=require('electron'),assert=require('node:assert/strict'),{writeFileSync}=require('node:fs');
 const sleep=ms=>new Promise(r=>setTimeout(r,ms));
 const until=async read=>{for(let n=0;n<150;n++){const result=await read();if(result)return result;await sleep(100);}throw Error('Native preference fixture did not become ready');};
 app.whenReady().then(async()=>{try{
  const shell=await until(async()=>{const w=BrowserWindow.getAllWindows().find(w=>w.webContents.getURL()==='app://shell/index.html');if(!w)return;try{if(await w.webContents.executeJavaScript('!!window.profiles'))return w;}catch{}});
  const command=value=>shell.webContents.executeJavaScript('window.profiles.command('+JSON.stringify(value)+')');
  await command({type:'profile:add',name:'Native JP fixture',email:'',color:'#5275a0',language:'ja-JP',timeZone:'Asia/Tokyo'});
  const parent=await until(()=>{const c=shell.contentView.children.find(v=>v.getVisible())?.webContents;return c?.getURL()===origin+'/'&&!c.isLoading()?c:undefined;});
  const first=await parent.executeJavaScript('window.first');
  assert.equal(first.language,'ja-JP');assert.equal(new Intl.Locale(first.locale).maximize().toString(),new Intl.Locale('ja-JP').maximize().toString());assert.equal(first.tz,'Asia/Tokyo');assert.match(first.header,/^ja-JP/);
  await parent.executeJavaScript('document.cookie="native_fixture=jp; SameSite=Lax; path=/"');
  const state=await shell.webContents.executeJavaScript('window.profiles.snapshot()'),profile=state.profiles.find(p=>p.id===state.activeProfileId);
  await command({type:'profile:edit',id:profile.id,name:profile.name,email:profile.email,color:profile.color,language:'zh-TW',timeZone:'UTC'});
  assert.equal(await parent.executeJavaScript('Boolean(window.open("/popup","native-login-fixture"))',true),true);
  const popup=await until(()=>BrowserWindow.getAllWindows().find(w=>w.webContents.getURL()===origin+'/popup'&&!w.webContents.isLoading()));
  const child=await popup.webContents.executeJavaScript('window.first');
  assert.equal(child.language,'ja-JP');assert.equal(new Intl.Locale(child.locale).maximize().toString(),new Intl.Locale('ja-JP').maximize().toString());assert.equal(child.tz,'Asia/Tokyo');assert.match(child.header,/^ja-JP/);assert.equal(child.cookie,'native_fixture=jp');assert.equal(child.hasOpener,true);
  writeFileSync(resultFile,JSON.stringify({passed:true,platform:process.platform,parent:first,popup:child}));popup.close();console.log('Native popup first language, Intl locale, timezone, request header, opener, account storage and old-tab preferences passed.');
 }catch(error){console.error(error);process.exitCode=1;}finally{app.quit();}});
}
const resultFile=path.join(root,'result.json');
writeFileSync(path.join(root,'hook.cjs'),`(${hookMain.toString()})(${JSON.stringify(origin)},${JSON.stringify(resultFile)});`);
const runtime=process.platform==='darwin'?path.join(desktop,'node_modules/electron/dist/Electron.app/Contents/MacOS/Electron'):path.join(desktop,'node_modules/electron/dist/electron.exe');
const child=spawn(runtime,['-r',path.join(root,'hook.cjs'),desktop],{env:{...process.env,PROFILES_E2E:'1',PROFILES_TEST_DATA:path.join(root,'data'),PROFILES_FIXTURE_ORIGIN:origin},stdio:['ignore','pipe','pipe']});
let stderr='';child.stdout.pipe(process.stdout);child.stderr.on('data',b=>{stderr+=b;});
const timer=setTimeout(()=>child.kill('SIGTERM'),30000);
try{const code=await new Promise((resolve,reject)=>{child.once('error',reject);child.once('exit',resolve);});if(code!==0||!existsSync(resultFile)||!JSON.parse(readFileSync(resultFile,'utf8')).passed)throw Error('Native preference check failed: '+stderr.slice(-2500));}
finally{clearTimeout(timer);server.closeAllConnections();await new Promise(r=>server.close(r));rmSync(root,{recursive:true,force:true});}
