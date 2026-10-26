import test,{type TestContext} from 'node:test';import assert from 'node:assert/strict';
import {mkdtempSync,mkdirSync,readFileSync,writeFileSync,existsSync,rmSync} from 'node:fs';import os from 'node:os';import path from 'node:path';import {createHash} from 'node:crypto';
import {WindowsUpdateManager,type WindowsUpdateBackend} from '../src/main/windows-update-manager';
import {windowsRelease} from '../src/shared/windows-updates';import {registeredWindowsDirectory} from '../src/main/windows-update-platform';
const version='3.0.0-alpha.22',exe=Buffer.from('owned installer fixture bytes'),zip=Buffer.from('owned ZIP fixture bytes');
const hash=(body:Buffer,algorithm='sha256',encoding:'hex'|'base64'='hex')=>createHash(algorithm).update(body).digest(encoding);
function info(){return{schema:1,appId:'com.forcemind.chatgpt.profiles',platform:'win32',architecture:'x64',version,releaseDate:'2026-10-27T04:00:00Z',releaseNotes:'Windows fixture update',files:[{url:`https://github.com/ForceMind/ChatGPT/releases/download/v${version}/ChatGPT-Studio-${version}-windows-x64-setup.exe`,size:exe.length,sha256:hash(exe),sha512:hash(exe,'sha512','base64')},{url:`https://github.com/ForceMind/ChatGPT/releases/download/v${version}/ChatGPT-Studio-${version}-windows-x64.zip`,size:zip.length,sha256:hash(zip),sha512:hash(zip,'sha512','base64')}]};}
function setup(t:TestContext,mode:'automatic'|'manual'='automatic'){
 const root=mkdtempSync(path.join(os.tmpdir(),'windows-update-')),data=path.join(root,'data'),file=path.join(root,'installer.exe');mkdirSync(data);
 const calls={checks:0,downloads:0,fetches:0,installs:[] as boolean[],cancelled:0,verified:0,accelerated:false};let value=info();
 let progress:(v:{percent:number;transferred?:number;total?:number})=>void=()=>{},error:(e:Error)=>void=()=>{},checkGate:Promise<void>=Promise.resolve();
 const backend:WindowsUpdateBackend={check:async()=>{calls.checks++;await checkGate;return{info:structuredClone(value),available:true};},download:async(_artifact,accelerated)=>{calls.downloads++;calls.accelerated=accelerated;writeFileSync(file,exe);progress({percent:100,transferred:exe.length,total:exe.length});return[file];},install:restart=>{calls.installs.push(restart);},cancel:()=>{calls.cancelled++;},progress:listener=>{progress=listener;return()=>{progress=()=>{};};},error:listener=>{error=listener;return()=>{error=()=>{};};}};
 const manager=new WindowsUpdateManager({dataDir:data,current:'3.0.0-alpha.21',arch:'x64',mode,backend,fetch:async()=>{calls.fetches++;return new Response(zip,{headers:{'content-length':String(zip.length)}});},changed:()=>{},verifyInstaller:async(_file,v)=>{assert.equal(v,version);calls.verified++;},preflight:async()=>{}});
 t.after(()=>{manager.dispose();rmSync(root,{recursive:true,force:true});});
 return{root,data,file,calls,backend,manager,progress:(v:Parameters<typeof progress>[0])=>progress(v),error:(e:Error)=>error(e),gate:(p:Promise<void>)=>{checkGate=p;},change:(v:ReturnType<typeof info>)=>{value=v;}};
}
test('Windows metadata pins app, architecture, repository, platform suffix, sizes and both digests',()=>{
 assert.equal(windowsRelease(info()).version,version);
 for(const mutate of [(v:any)=>v.platform='darwin',(v:any)=>v.appId='other.app',(v:any)=>v.architecture='arm64',(v:any)=>v.version='3.0.0-alpha.022',(v:any)=>v.files[0].url=v.files[0].url.replace('ForceMind','Other'),(v:any)=>v.files[0].url='https://gh-proxy.org/'+v.files[0].url,(v:any)=>v.files[0].sha512='invalid',(v:any)=>v.files[0].size=0,(v:any)=>v.files[1]=v.files[0],(v:any)=>v.packages={}]){const value=info();mutate(value);assert.throws(()=>windowsRelease(value),/清单/);}
});
test('registered installers must match this executable directory and never claim a broad user folder or network share',()=>{
 const dir='C:\\Users\\测试\\Programs\\ChatGPT Studio',exe=dir+'\\ChatGPT Profiles.exe',record={InstallLocation:dir,UninstallString:'"'+dir+'\\Uninstall ChatGPT Studio.exe"'};
 assert.equal(registeredWindowsDirectory(exe,[record],['C:\\Users\\测试']),dir);
 assert.equal(registeredWindowsDirectory(exe,[{...record,InstallLocation:'C:\\other'}],[]),undefined);
 assert.equal(registeredWindowsDirectory(exe,[record],[dir]),undefined);
 assert.equal(registeredWindowsDirectory('\\\\server\\share\\ChatGPT Profiles.exe',[{...record,InstallLocation:'\\\\server\\share'}],[]),undefined);
});
test('Windows checks coalesce, save the Shanghai noon attempt and never download without approval',async t=>{
 const s=setup(t);let release!:()=>void;s.gate(new Promise<void>(resolve=>{release=resolve;}));
 const one=s.manager.check(Date.parse('2026-10-27T04:00:00Z')),two=s.manager.check();assert.equal(one,two);release();await one;
 assert.equal(s.calls.checks,1);assert.equal(s.calls.downloads,0);assert.equal(s.manager.status.phase,'available');assert.equal(JSON.parse(readFileSync(path.join(s.manager.directory,'state.json'),'utf8')).noonDay,'2026-10-27');
});
test('installed updates verify bytes and package version before a quit-triggered installer, with restart only by explicit choice',async t=>{
 const s=setup(t);await s.manager.check();await s.manager.download(true);assert.equal(s.calls.accelerated,true);assert.equal(s.calls.verified,1);assert.equal(s.manager.status.phase,'ready');assert.deepEqual(s.calls.installs,[]);
 s.manager.restartAfterInstall();await s.manager.installOnQuit();assert.deepEqual(s.calls.installs,[true]);assert.equal(s.calls.verified,2);
});
test('tampered cached installers cannot execute and leave a recoverable update error',async t=>{
 const s=setup(t);await s.manager.check();await s.manager.download();writeFileSync(s.file,'damaged');await assert.rejects(s.manager.installOnQuit(),/校验/);assert.deepEqual(s.calls.installs,[]);assert.equal(s.manager.status.phase,'error');assert.equal(JSON.parse(readFileSync(path.join(s.manager.directory,'state.json'),'utf8')).approved,undefined);
});
test('OS session end defers installation even for a fully prepared update',async t=>{
 const s=setup(t);await s.manager.check();await s.manager.download();s.manager.sessionEnd();await s.manager.installOnQuit();assert.deepEqual(s.calls.installs,[]);assert.equal(s.manager.status.phase,'ready');
});
test('portable downloads are verified and saved as a new file without running an installer or replacing the app folder',async t=>{
 const s=setup(t,'manual'),original=path.join(s.root,'original.txt'),output=path.join(s.root,'ChatGPT-Studio-update.zip');writeFileSync(original,'keep');
 await s.manager.check();await s.manager.download();await s.manager.installOnQuit();assert.deepEqual(s.calls.installs,[]);assert.equal(s.calls.fetches,1);assert.equal(s.calls.downloads,0);
 await s.manager.exportPackage(output);assert.deepEqual(readFileSync(output),zip);assert.equal(readFileSync(original,'utf8'),'keep');assert.equal(s.manager.status.phase,'available');assert.equal(JSON.parse(readFileSync(path.join(s.manager.directory,'state.json'),'utf8')).zip,undefined);assert.equal(existsSync(s.file),false);
});
test('a downloaded approval is never reused for different bytes advertised under the same version',async t=>{
 const s=setup(t);await s.manager.check();await s.manager.download();s.manager.dispose();
 const changed=info();changed.files[0].sha256='a'.repeat(64);s.change(changed);
 const restored=new WindowsUpdateManager({dataDir:s.data,current:'3.0.0-alpha.21',arch:'x64',mode:'automatic',backend:s.backend,fetch:async()=>{throw Error('unused');},changed:()=>{},verifyInstaller:async()=>{},preflight:async()=>{}});t.after(()=>restored.dispose());
 await restored.check();assert.equal(restored.status.phase,'available');assert.equal(s.calls.downloads,1);assert.deepEqual(s.calls.installs,[]);
});
test('oversized transfer progress cancels the download and never reaches install readiness',async t=>{
 const s=setup(t);s.backend.download=async()=>{s.progress({percent:50,transferred:exe.length+1,total:exe.length+1});writeFileSync(s.file,exe);return[s.file];};await s.manager.check();await assert.rejects(s.manager.download());assert.equal(s.manager.status.phase,'error');assert(s.calls.cancelled>0);assert.deepEqual(s.calls.installs,[]);
});
