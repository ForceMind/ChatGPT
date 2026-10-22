import {spawn} from 'node:child_process';import {createServer}from'node:http';import {mkdtempSync,writeFileSync,readFileSync,existsSync}from'node:fs';import {fileURLToPath}from'node:url';import path from'node:path';import os from'node:os';
// Native Electron launch, deliberately without Playwright's automation/background flags.
const desktop=fileURLToPath(new URL('..',import.meta.url)),args=process.argv.slice(2);
const option=(name,fallback)=>{const i=args.indexOf(name);return i<0?fallback:args[i+1];};
const appRoot=path.resolve(option('--app-root',desktop)),label=option('--label','current'),site=option('--site','local'),out=path.resolve(option('--output',path.join(os.tmpdir(),`profiles-resources-${label}.json`)));
if(existsSync(out))throw Error('Output already exists; choose a new report path to avoid stale results');
const workload=option('--workload','light');
if(!['light','conversation'].includes(workload)||site!=='local'&&workload!=='light')throw Error('Conversation workloads use local fixtures only');
const holdMs=Number(option('--keepalive-ms','0'));if(!Number.isSafeInteger(holdMs)||holdMs<0||holdMs>3600000)throw Error('keepalive duration must be 0–3600000 ms');
if(holdMs&&site!=='local')throw Error('Keepalive checks use local fixtures only');
if(!['local','public'].includes(site))throw Error('--site must be local or public');
const root=mkdtempSync(path.join(os.tmpdir(),'profiles-native-bench-'));let server;
function conversationFixture(){
 const main=document.querySelector('main');
 for(let n=0;n<240;n++){
  const article=document.createElement('article');article.dataset.messageId='local-'+n;article.dataset.messageAuthorRole=n%2?'assistant':'user';
  const p=document.createElement('p');p.textContent=('本地模拟长对话，仅用于资源与恢复测量，不是真实聊天记录。Local conversation fixture '+n+' ').repeat(12);article.append(p);
  const pre=document.createElement('pre');pre.textContent=JSON.stringify({step:n,source:'local fixture',sent:false},null,2);article.append(pre);
  if(n%40===0){const canvas=document.createElement('canvas');canvas.width=1024;canvas.height=768;const context=canvas.getContext('2d');const g=context.createLinearGradient(0,0,1024,768);g.addColorStop(0,'hsl('+n+' 35% 65%)');g.addColorStop(1,'#e4e4e4');context.fillStyle=g;context.fillRect(0,0,1024,768);const img=new Image();img.width=1024;img.height=768;img.alt='本地模拟图片 '+n;img.src=canvas.toDataURL();article.append(img);}
  main.append(article);
 }
 return Promise.all([...document.images].map(i=>i.decode()));
}
if(site==='local'){
 server=createServer((q,r)=>{if(holdMs&&q.url==='/events'){r.writeHead(200,{'Content-Type':'text/event-stream','Cache-Control':'no-store'});r.write(': connected\n\n');const timer=setInterval(()=>r.write('data: alive\n\n'),500);q.on('close',()=>clearInterval(timer));return;}r.writeHead(200,{'Content-Type':'text/html;charset=utf-8'});r.end(`<!doctype html><meta charset="utf-8"><title>Local native resource fixture</title><h1>本地资源测量页 · ${workload}</h1><textarea id="prompt-textarea">未发送草稿</textarea><canvas width="600" height="400"></canvas><main></main><script>window.addEventListener("error",e=>window.fixtureError=e.message);window.addEventListener("unhandledrejection",e=>window.fixtureError=String(e.reason));window.instance=crypto.randomUUID();window.events=0;window.sent=0;${holdMs?"new EventSource('/events').onmessage=()=>window.events++;":''}window.beats=0;setInterval(()=>window.beats++,250);const c=document.querySelector('canvas').getContext('2d');for(let n=0;n<300;n++){c.fillStyle='hsl('+n+' 35% 65%)';c.fillRect(n%30*20,Math.floor(n/30)*40,19,39);}window.payload=Array.from({length:2000},(_,i)=>({i,text:'representative local conversation '+i}));Promise.resolve(${workload==='conversation'?'('+conversationFixture.toString()+')()':'undefined'}).then(()=>window.fixtureReady=true);</script>`);});await new Promise(r=>server.listen(0,'127.0.0.1',r));
}
const config={label,site,out,holdMs,workload,dataDir:path.join(root,'data'),counts:holdMs?[8]:site==='public'?[1,4]:[1,4,8],warm:site==='public'?15000:2500};
function hookMain(config){
 const {app,BrowserWindow}=require('electron'),{writeFileSync,realpathSync}=require('node:fs');const sleep=ms=>new Promise(r=>setTimeout(r,ms));
 app.whenReady().then(async()=>{
  try{
   let win;for(let n=0;n<600;n++){win=BrowserWindow.getAllWindows().find(w=>w.webContents.getURL()==='app://shell/index.html');if(win){try{if(await win.webContents.executeJavaScript('!!window.profiles&&document.readyState!=="loading"'))break;}catch{}}await sleep(100);}
   if(!win)throw Error('shell did not initialize');const run=js=>win.webContents.executeJavaScript(js);
   const initial=await run('window.profiles.snapshot()');
   const isolated=initial.testMode&&realpathSync(app.getPath('userData'))===realpathSync(config.dataDir);
   if(!isolated)throw Error('Measurement requires an isolated test profile');
   const waitFixture=async()=>{if(config.site!=='local')return;for(const view of win.contentView.children){let ready=false;for(let i=0;i<100;i++){if(await view.webContents.executeJavaScript('window.fixtureReady===true')){ready=true;break;}await sleep(100);}if(!ready)throw Error('Fixture not ready: '+JSON.stringify(await view.webContents.executeJavaScript('({url:location.href,error:window.fixtureError,messages:document.querySelectorAll(\"[data-message-id]\").length,images:[...document.images].map(i=>({complete:i.complete,width:i.naturalWidth})),ready:document.readyState})')));}};
   // A hidden page can defer decode() completion. Finish visiting each new page
   // before switching away, as a user opening and reading several tabs would.
   await waitFixture();
   const results=[];if(config.holdMs){for(let i=1;i<3;i++){await run('window.profiles.command('+JSON.stringify({type:'profile:add',name:'Keepalive '+i,email:'',color:'#5275a0'})+')');await waitFixture();}}
   for(const count of config.counts){let state=await run('window.profiles.snapshot()');while(state.tabs.length<count){await run('window.profiles.command({type:"tab:new"})');state=await run('window.profiles.snapshot()');await waitFixture();}
    await sleep(config.warm);
    await waitFixture();
    app.getAppMetrics();const samples=[];
    for(let n=0;n<12;n++){await sleep(1000);const metrics=app.getAppMetrics();samples.push({cpu:metrics.reduce((s,p)=>s+p.cpu.percentCPUUsage,0),workingSetKB:metrics.reduce((s,p)=>s+p.memory.workingSetSize,0),processes:metrics.length});}
    const views=await Promise.all(win.contentView.children.map(async v=>({id:v.webContents.id,pid:v.webContents.getOSProcessId(),throttled:v.webContents.getBackgroundThrottling(),state:await v.webContents.executeJavaScript('({title:document.title,instance:window.instance,beats:window.beats,language:navigator.language,timeZone:Intl.DateTimeFormat().resolvedOptions().timeZone,visibility:document.visibilityState,discarded:document.wasDiscarded,messages:document.querySelectorAll("[data-message-id]").length,images:document.images.length,sent:window.sent})')})));
    const avg=k=>samples.reduce((s,v)=>s+v[k],0)/samples.length;results.push({tabs:count,cpuPercent:avg('cpu'),workingSetMiB:avg('workingSetKB')/1024,peakWorkingSetMiB:Math.max(...samples.map(s=>s.workingSetKB))/1024,processes:samples.at(-1).processes,samples,views});
   }
   let retention;
   if(config.holdMs){
    const read=()=>Promise.all(win.contentView.children.map(async v=>({id:v.webContents.id,state:await v.webContents.executeJavaScript('({instance:window.instance,beats:window.beats,events:window.events,draft:document.querySelector("textarea").value,discarded:document.wasDiscarded})')})));
    const before=await read(),start=Date.now();let late;win.hide();for(let n=0;Date.now()-start<config.holdMs;n++){await sleep(1000);if(!late&&Date.now()-start>=config.holdMs-30000)late=await read();if(n%60===0)console.log(JSON.stringify({keepaliveSeconds:Math.floor((Date.now()-start)/1000),tabs:before.length,hidden:true}));}
    const after=await read();const stable=before.every(a=>{const b=after.find(b=>b.id===a.id);return b&&b.state.instance===a.state.instance&&b.state.draft===a.state.draft&&!b.state.discarded;});const active=late&&late.every(a=>{const b=after.find(b=>b.id===a.id);return b.state.beats-a.state.beats>=60&&b.state.events-a.state.events>=20;});retention={durationMs:Date.now()-start,profiles:(await run('window.profiles.snapshot()')).profiles.length,before,late,after,stable,active,passed:!!stable&&!!active};if(!retention.passed)process.exitCode=1;
   }
   writeFileSync(config.out,JSON.stringify({retention,label:config.label,site:config.site,workload:config.workload,isolatedTestData:isolated,nativeLaunch:true,argv:process.argv,versions:process.versions,note:'Process working-set sum includes shared pages. CPU is Electron API sum. Conversation workload is synthetic (240 messages and 6 local images per page), not a logged-in ChatGPT benchmark or a memory limit.',results},null,2));
  }catch(error){console.error(error);process.exitCode=1;}finally{app.quit();}
 });
}
const hook=path.join(root,'hook.cjs');writeFileSync(hook,`(${hookMain.toString()})(${JSON.stringify(config)});`);
const runtime=process.platform==='darwin'?path.join(desktop,'node_modules/electron/dist/Electron.app/Contents/MacOS/Electron'):path.join(desktop,'node_modules/electron/dist/electron.exe');
try{const child=spawn(runtime,['-r',hook,appRoot],{env:{...process.env,PROFILES_E2E:'1',PROFILES_TEST_DATA:path.join(root,'data'),PROFILES_FIXTURE_ORIGIN:server?'http://127.0.0.1:'+server.address().port:''},stdio:['ignore','pipe','pipe']});let errors='';child.stdout.on('data',b=>process.stdout.write(b));child.stderr.on('data',b=>{errors+=b;});const code=await new Promise((resolve,reject)=>{child.once('error',reject);child.once('exit',resolve);});if(code!==0||!existsSync(out))throw Error('Native measurement failed: '+errors.slice(-2400));const result=JSON.parse(readFileSync(out,'utf8'));if(result.retention&&!result.retention.passed)throw Error('Native retention check failed: '+out);console.log(JSON.stringify({output:out,nativeLaunch:true,site,results:result.results.map(({tabs,cpuPercent,workingSetMiB,processes})=>({tabs,cpuPercent,workingSetMiB,processes}))},null,2));}finally{if(server){server.closeAllConnections();await new Promise(r=>server.close(r));}}
