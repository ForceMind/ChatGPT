import {mkdtempSync,mkdirSync,writeFileSync,readFileSync,rmSync} from 'node:fs';
import {tmpdir} from 'node:os';import path from 'node:path';import {fileURLToPath} from 'node:url';import {createRequire} from 'node:module';import {spawn} from 'node:child_process';
const desktop=fileURLToPath(new URL('..',import.meta.url)),require=createRequire(import.meta.url),root=mkdtempSync(path.join(tmpdir(),'profiles-live-readonly-')),out=path.join(desktop,'release/live-readonly');mkdirSync(out,{recursive:true});
const adapter=await require('esbuild').build({entryPoints:[path.join(desktop,'src/main/chat-dom.ts')],bundle:true,platform:'browser',format:'iife',globalName:'ProfilesReadOnlyAdapter',write:false});
const observe=`(()=>{${adapter.outputFiles[0].text};const s=ProfilesReadOnlyAdapter.chatDOM(),text=document.body?.innerText||'';return {composer:!!s.composer,tag:s.composer?.tagName,editable:s.composer?.isContentEditable===true||s.composer instanceof HTMLTextAreaElement,id:s.composer?.id,role:s.composer?.getAttribute('role'),sendPresent:s.sendPresent,fileInputPresent:!!s.input,challenge:/just a moment|verifying|verify you are human|checking your browser|验证您是真人/i.test(text),login:/log in|登录|登入/i.test(text),readyState:document.readyState};})()`;
const verifyDraft=process.argv.includes('--verify-draft'),draftText='客户端输入框验收（未发送）\n只验证原生输入是否完整，不上传文件，不发送消息。\nNative input test only.';
const inspectDraft=`(()=>{${adapter.outputFiles[0].text};const s=ProfilesReadOnlyAdapter.chatDOM(),normalize=ProfilesReadOnlyAdapter.normalizedPrompt;return {empty:s.text.length===0,matched:normalize(s.text)===${JSON.stringify(draftText)}.trim(),writtenCharacters:s.text.length,expectedCharacters:${draftText.length},sendReady:!!s.send};})()`;
const element=`(()=>{${adapter.outputFiles[0].text};return ProfilesReadOnlyAdapter.chatDOM().composer;})()`;
const entry=path.join(root,'observe.cjs');
writeFileSync(entry,`const {app,BrowserWindow}=require('electron'),fs=require('node:fs'),path=require('node:path');require(${JSON.stringify(path.join(desktop,'dist/main/main.cjs'))});
const out=${JSON.stringify(out)},observations=[];const pause=ms=>new Promise(r=>setTimeout(r,ms));
app.whenReady().then(async()=>{
 const deadline=Date.now()+130000;let ready=false,draftResult;
 while(Date.now()<deadline){
  const w=BrowserWindow.getAllWindows().find(w=>w.webContents.getURL()==='app://shell/index.html'),view=w?.contentView.children.find(v=>v.webContents?.getURL().startsWith('https://chatgpt.com/'));
  if(view&&!view.webContents.isLoadingMainFrame()){
   const data=await view.webContents.executeJavaScript(${JSON.stringify(observe)});observations.push({...data,observedAt:new Date().toISOString()});
   if(data.composer&&!data.challenge){
    fs.writeFileSync(path.join(out,'anonymous-composer.png'),(await view.webContents.capturePage()).toPNG());
    if(${JSON.stringify(verifyDraft)}){
     const before=await view.webContents.executeJavaScript(${JSON.stringify(inspectDraft)});if(!before.empty)throw Error('Unexpected pre-existing draft; no input performed');
     const response=await w.webContents.executeJavaScript("window.profiles.command({type:'panel',panel:'assets'})");if(!response.ok)throw Error('Cannot open normal workspace overlay');
     const resolved=await view.webContents.debugger.sendCommand('Runtime.evaluate',{expression:${JSON.stringify(element)},returnByValue:false}),objectId=resolved.result?.objectId;if(!objectId)throw Error('No editable node');
     try{await view.webContents.debugger.sendCommand('DOM.focus',{objectId});await view.webContents.debugger.sendCommand('Input.insertText',{text:${JSON.stringify(draftText)}});}finally{await view.webContents.debugger.sendCommand('Runtime.releaseObject',{objectId});}
     for(let n=0;n<30;n++){draftResult=await view.webContents.executeJavaScript(${JSON.stringify(inspectDraft)});if(draftResult.matched)break;await pause(200);}
     fs.writeFileSync(path.join(out,'anonymous-draft-unsent.png'),(await view.webContents.capturePage(undefined,{stayHidden:true})).toPNG());
     ready=draftResult?.matched===true;
    }else ready=true;
    break;
   }
   if(data.challenge)fs.writeFileSync(path.join(out,'page-challenge.png'),(await view.webContents.capturePage()).toPNG());
  }
  await pause(4000);
 }
 fs.writeFileSync(path.join(out,'observation.json'),JSON.stringify({status:ready?(${JSON.stringify(verifyDraft)}?'anonymous-native-draft-verified':'anonymous-composer-observed'):'not-confirmed',scope:${JSON.stringify(verifyDraft?'Actual anonymous ChatGPT page, fresh CI data; native DOM.focus/Input.insertText while normal workspace hides the webpage. Fixed harmless draft only. No login, upload, verification click or send; not authenticated end-to-end acceptance.':'Actual anonymous ChatGPT webpage, fresh CI data. No input, upload, login, verification click or send; not authenticated end-to-end acceptance.')},sendAttempted:false,draftResult,observations},null,2)+'\n');app.exit(ready?0:2);
}).catch(e=>{fs.writeFileSync(path.join(out,'probe-error.txt'),e.message);app.exit(1);});`);
const child=spawn(require('electron'),[entry],{cwd:desktop,stdio:'inherit',env:{...process.env,PROFILES_E2E:'1',PROFILES_TEST_DATA:path.join(root,'data'),PROFILES_FIXTURE_ORIGIN:''}});
let timedOut=false;const timer=setTimeout(()=>{timedOut=true;child.kill('SIGKILL');},155000);
try{const code=await new Promise(resolve=>child.once('exit',resolve));if(code!==0)throw Error(timedOut?'Read-only probe timed out; no input sent.':'Anonymous composer not confirmed; inspect evidence, no authenticated flow was run.');const report=JSON.parse(readFileSync(path.join(out,'observation.json'),'utf8'));console.log(JSON.stringify({status:report.status,observations:report.observations.length}));}
finally{clearTimeout(timer);rmSync(root,{recursive:true,force:true});}
