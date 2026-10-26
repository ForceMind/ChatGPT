import type {NsisUpdater} from 'electron-updater';
import {execFile} from 'node:child_process';import {promisify} from 'node:util';
import {access,writeFile,unlink} from 'node:fs/promises';import {constants,existsSync,realpathSync,writeFileSync} from 'node:fs';
import path from 'node:path';import {createHash,randomUUID} from 'node:crypto';
import {WindowsUpdateManager,type WindowsUpdateBackend} from './windows-update-manager';
import {WINDOWS_UPDATE_BASE} from '../shared/windows-updates';import {acceleratedURL} from '../shared/updates';
import {safeDirectory,type FetchUpdate} from './update-download';
const run=promisify(execFile);
const ps=()=>path.join(process.env.SystemRoot||'C:\\Windows','System32','WindowsPowerShell','v1.0','powershell.exe');
const same=(a:string,b:string)=>path.win32.resolve(a).toLowerCase()===path.win32.resolve(b).toLowerCase();
export function registeredWindowsDirectory(executable:string,records:unknown,protectedDirectories:string[]):string|undefined{
 const directory=path.win32.dirname(executable);if(!path.win32.isAbsolute(executable)||directory.startsWith('\\\\')||protectedDirectories.some(p=>same(p,directory))||path.win32.parse(directory).root===directory||path.win32.basename(executable).toLowerCase()!=='chatgpt profiles.exe')return;
 for(const record of Array.isArray(records)?records:[]){
  if(!record||typeof record!=='object')continue;const item=record as Record<string,unknown>;
  if(typeof item.InstallLocation==='string'&&same(item.InstallLocation,directory)&&typeof item.UninstallString==='string'&&item.UninstallString.toLowerCase().includes('uninstall'))return directory;
 }
}
async function registered(executable:string,protectedDirectories:string[]):Promise<string|undefined>{
 const command="[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false); $items=@(Get-ItemProperty 'HKCU:\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\*' -ErrorAction SilentlyContinue | Where-Object { $_.DisplayName -eq 'ChatGPT Studio' } | Select-Object InstallLocation,UninstallString); ConvertTo-Json -InputObject @($items) -Compress";
 try{const result=await run(ps(),['-NoLogo','-NoProfile','-NonInteractive','-Command',command],{timeout:10000,windowsHide:true,encoding:'utf8',maxBuffer:65536});return registeredWindowsDirectory(executable,JSON.parse(result.stdout.trim()||'[]'),protectedDirectories);}catch{return;}
}
export async function verifyWindowsInstaller(file:string,version:string):Promise<void>{
 const command="$ErrorActionPreference='Stop'; [Console]::OutputEncoding=[Text.UTF8Encoding]::new($false); $v=[Diagnostics.FileVersionInfo]::GetVersionInfo($env:STUDIO_UPDATE_INSPECT_FILE); @{productName=$v.ProductName;version=$v.ProductVersion} | ConvertTo-Json -Compress";
 const result=await run(ps(),['-NoLogo','-NoProfile','-NonInteractive','-Command',command],{timeout:15000,windowsHide:true,env:{...process.env,STUDIO_UPDATE_INSPECT_FILE:file},encoding:'utf8',maxBuffer:65536});const value=JSON.parse(result.stdout.trim());
 if(value.productName!=='ChatGPT Studio'||value.version!==version)throw Error('安装包身份或版本不符，已停止更新。');
}
type PlatformOptions={dataDir:string;current:string;arch:string;executable:string;protectedDirectories:string[];fetch:FetchUpdate;changed:()=>void;fixtureOrigin?:string;fixtureMode?:'automatic'|'manual'};
export async function createWindowsUpdates(options:PlatformOptions):Promise<WindowsUpdateManager>{
 if(process.platform!=='win32')throw Error('Windows only');
 if(options.fixtureOrigin&&!/^http:\/\/127\.0\.0\.1:\d+$/.test(options.fixtureOrigin))throw Error('更新测试来源无效');
 const executable=realpathSync(options.executable),directory=path.dirname(executable);
 const mode=options.fixtureOrigin?(options.fixtureMode||'automatic'):await registered(executable,options.protectedDirectories)?'automatic':'manual';
 const {NsisUpdater}=await import('electron-updater');
 const native=new NsisUpdater({provider:'generic',url:options.fixtureOrigin?options.fixtureOrigin+'/updates/windows/':WINDOWS_UPDATE_BASE});
 native.installDirectory=directory;
 if(options.fixtureOrigin){
  native.forceDevUpdateConfig=true;const root=safeDirectory(path.join(realpathSync(options.dataDir),'updates-windows'));
  const configFile=path.join(root,'dev-app-update.yml');writeFileSync(configFile,JSON.stringify({provider:'generic',url:options.fixtureOrigin+'/updates/windows/',updaterCacheDirName:'studio-update-fixture-'+createHash('sha256').update(root).digest('hex').slice(0,12)}),{mode:0o600});native.updateConfigPath=configFile;
 }
 const backend=createWindowsUpdateBackend(native,options.fixtureOrigin);
 return new WindowsUpdateManager({dataDir:options.dataDir,current:options.current,arch:options.arch,mode,backend,fetch:options.fetch,changed:options.changed,verifyInstaller:verifyWindowsInstaller,preflight:async()=>{
  if(options.fixtureOrigin)return;
  if(!await registered(executable,options.protectedDirectories)||!existsSync(executable))throw Error('安装位置已改变，请使用官网安装包手动更新。');
  await access(directory,constants.W_OK);const test=path.join(directory,'.studio-update-'+randomUUID()+'.tmp');try{await writeFile(test,'',{flag:'wx'});}finally{if(existsSync(test))await unlink(test);}
 }});
}

export function createWindowsUpdateBackend(native:NsisUpdater,fixtureOrigin?:string):WindowsUpdateBackend{
 native.channel='alpha';native.allowDowngrade=false;native.allowPrerelease=true;native.autoDownload=false;native.autoInstallOnAppQuit=false;native.autoRunAppAfterInstall=false;native.disableDifferentialDownload=true;native.disableWebInstaller=true;native.logger=null;
 let token:Parameters<typeof native.downloadUpdate>[0];
 return {
  check:async()=>{const result=await native.checkForUpdates();token=result?.cancellationToken;return{info:result?.updateInfo,available:!!result&&(result as typeof result&{isUpdateAvailable?:boolean}).isUpdateAvailable===true};},
  download:async(file,accelerated)=>{
   const updaterSession=native.netSession;
   updaterSession.webRequest.onBeforeRequest({urls:['https://github.com/ForceMind/ChatGPT/releases/download/*']},(details,callback)=>{
    if(details.url!==file.url){callback({});return;}
    callback(fixtureOrigin?{redirectURL:fixtureOrigin+'/installer.exe'}:accelerated?{redirectURL:acceleratedURL(file.url)}:{});
   });
   try{return await native.downloadUpdate(token);}finally{updaterSession.webRequest.onBeforeRequest({urls:['https://github.com/ForceMind/ChatGPT/releases/download/*']},null);}
  },
  install:restart=>native.quitAndInstall(true,restart),cancel:()=>token?.cancel(),
  progress:listener=>{native.on('download-progress',listener);return()=>{native.removeListener('download-progress',listener);};},
  error:listener=>{native.on('error',listener);return()=>{native.removeListener('error',listener);};}
 };
}
