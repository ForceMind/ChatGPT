import {copyFile,rename,unlink} from 'node:fs/promises';
import {existsSync,lstatSync,readFileSync,writeFileSync,renameSync,unlinkSync,realpathSync} from 'node:fs';
import path from 'node:path';import {createHash,randomUUID} from 'node:crypto';
import {newerVersion,noonDue,shanghaiDay,acceleratedURL,type UpdateStatus} from '../shared/updates';
import {windowsRelease,type WindowsRelease,type WindowsArtifact} from '../shared/windows-updates';
import {safeDirectory,verifyUpdate,downloadUpdate,type FetchUpdate} from './update-download';
import type {UpdateService,UpdateSummary} from './update-service';

export type WindowsUpdateBackend={
 check:()=>Promise<{info:unknown;available:boolean}>;
 download:(file:WindowsArtifact,accelerated:boolean)=>Promise<string[]>;
 install:(restart:boolean)=>void;cancel:()=>void;
 progress:(listener:(value:{percent:number;transferred?:number;total?:number})=>void)=>()=>void;
 error:(listener:(error:Error)=>void)=>()=>void;dispose?:()=>void;
};
type Saved={schema:1;noonDay:string;approved?:string;zip?:string};
type Options={dataDir:string;current:string;arch:string;mode:'automatic'|'manual';backend:WindowsUpdateBackend;fetch:FetchUpdate;changed:()=>void;verifyInstaller:(file:string,version:string)=>Promise<void>;preflight:()=>Promise<void>};
export class WindowsUpdateManager implements UpdateService{
 readonly directory:string;status:UpdateStatus;manifest?:UpdateSummary;
 private saved:Saved={schema:1,noonDay:''};private release?:WindowsRelease;private cached?:string;
 private checking?:Promise<UpdateSummary|undefined>;private preparing?:Promise<void>;private controller?:AbortController;
 private timer?:ReturnType<typeof setInterval>;private restart=false;private disposed=false;private installing=false;private installFailure?:Error;private activeFile?:WindowsArtifact;private oversized=false;private sessionEnding=false;
 private readonly unsubscribe:(()=>void)[];
 constructor(private readonly options:Options){
  this.directory=safeDirectory(path.join(realpathSync(options.dataDir),'updates-windows'));this.status={phase:'idle',installMode:options.mode};
  const file=this.stateFile();
  if(existsSync(file)){
   try{const s=lstatSync(file);if(s.isSymbolicLink()||!s.isFile()||s.size>65536)throw Error('invalid');const raw=JSON.parse(readFileSync(file,'utf8'));
    if(raw.schema!==1||typeof raw.noonDay!=='string'||raw.noonDay&&!/^\d{4}-\d{2}-\d{2}$/.test(raw.noonDay)||raw.approved!==undefined&&!/^[a-f0-9]{64}$/.test(raw.approved)||raw.zip!==undefined&&!/^[a-f0-9-]{36}\.zip$/.test(raw.zip))throw Error('invalid');
    this.saved={schema:1,noonDay:raw.noonDay,approved:raw.approved,zip:raw.zip};
   }catch{if(!lstatSync(file).isSymbolicLink())renameSync(file,file+'.corrupt-'+randomUUID());this.status={...this.status,phase:'error',message:'更新记录需要重新确认，当前应用与账号数据保持。'};}
  }
  this.unsubscribe=[options.backend.progress(value=>{const expected=this.activeFile;if(this.disposed||!expected||this.status.phase!=='downloading')return;
   if(value.transferred!==undefined&&value.transferred>expected.size||value.total!==undefined&&value.total>expected.size){this.oversized=true;this.controller?.abort();options.backend.cancel();return;}
   if(Number.isFinite(value.percent))this.set({phase:'downloading',version:this.release?.version,progress:Math.max(0,Math.min(100,Math.floor(value.percent))),message:'后台下载中，可继续使用'});
  }),options.backend.error(error=>{if(this.installing){this.installFailure=error;this.set({phase:'error',version:this.release?.version,message:'安装程序未能启动。当前账号已保存，可重新检查更新或使用官网安装包。'});}})];
 }
 private stateFile(){return path.join(this.directory,'state.json');}
 private persist(){safeDirectory(this.directory);const file=this.stateFile();if(existsSync(file)&&lstatSync(file).isSymbolicLink())throw Error('更新记录路径无效');const temp=file+'.'+randomUUID()+'.tmp';try{writeFileSync(temp,JSON.stringify(this.saved)+'\n',{flag:'wx',mode:0o600});renameSync(temp,file);}finally{if(existsSync(temp))unlinkSync(temp);}}
 private set(value:UpdateStatus){if(this.disposed)return;this.status={...value,installMode:this.options.mode};this.options.changed();}
 private selected(release:WindowsRelease){return this.options.mode==='automatic'?release.installer:release.zip;}
 private fingerprint(release:WindowsRelease){return createHash('sha256').update(JSON.stringify({version:release.version,file:this.selected(release)})).digest('hex');}
 get suggestedFilename(){return this.release?`ChatGPT-Studio-${this.release.version}-windows-x64.zip`:undefined;}
 check(now=Date.now()):Promise<UpdateSummary|undefined>{
  if(this.checking)return this.checking;if(this.status.phase==='downloading'||this.status.phase==='ready')return Promise.resolve(this.manifest);
  if(this.options.arch!=='x64'){this.set({phase:'idle',message:'当前架构暂不提供自动更新。'});return Promise.resolve(undefined);}
  this.set({phase:'checking'});
  const operation=(async()=>{try{
   const day=shanghaiDay(now);if(day.afterNoon){this.saved.noonDay=day.day;this.persist();}
   let timer:ReturnType<typeof setTimeout>|undefined;
   const response=await Promise.race([this.options.backend.check(),new Promise<never>((_resolve,reject)=>{timer=setTimeout(()=>reject(Error('更新检查超时')),15000);timer.unref();})]).finally(()=>clearTimeout(timer));if(this.disposed)return;
   const release=windowsRelease(response.info);
   if(!response.available||!newerVersion(release.version,this.options.current)){this.release=undefined;this.manifest=undefined;this.set({phase:'idle',message:'当前暂无可用更新'});return;}
   this.release=release;this.manifest={version:release.version,publishedAt:release.publishedAt,notes:release.notes};this.set({phase:'available',version:release.version,message:this.options.mode==='manual'?'可下载并校验免安装 ZIP，退出后手动替换。':undefined});
   // Approval is bound to these exact bytes and version, never a future release.
   if(this.saved.approved===this.fingerprint(release))await this.download(false);
   return this.manifest;
  }catch{this.set({phase:'error',message:'暂时无法检查 Windows 更新，请稍后重试；当前应用仍可使用。'});return;}})();
  this.checking=operation;void operation.finally(()=>{if(this.checking===operation)this.checking=undefined;});return operation;
 }
 start(check:(manual?:boolean)=>void){this.timer=setInterval(()=>{if(noonDue(Date.now(),this.saved.noonDay))check();},30000);this.timer.unref();check();}
 resume(check:()=>void){if(noonDue(Date.now(),this.saved.noonDay))check();}
 async download(accelerated=false):Promise<void>{
  if(this.preparing||this.status.phase==='ready')return;const release=this.release;if(!release||!newerVersion(release.version,this.options.current))throw Error('请先检查新版本');
  const artifact=this.selected(release),previousApproval=this.saved.approved;let finished!:()=>void;this.preparing=new Promise<void>(resolve=>{finished=resolve;});this.controller=new AbortController();this.activeFile=artifact;this.oversized=false;
  this.set({phase:'downloading',version:release.version,progress:0,message:'后台下载中，可继续使用'});
  const timer=setTimeout(()=>{this.controller?.abort();this.options.backend.cancel();},30*60*1000);timer.unref();
  try{
   if(this.options.mode==='automatic')await this.options.preflight();
   this.saved.approved=this.fingerprint(release);this.persist();
   let file:string;
   if(this.options.mode==='automatic'){
    const files=await this.options.backend.download(artifact,accelerated);if(files.length!==1)throw Error('更新文件数量无效');file=files[0];
   }else{
    let cached=this.saved.zip&&path.join(this.directory,this.saved.zip);
    if(cached&&existsSync(cached)){let valid=false;if(previousApproval===this.fingerprint(release)){try{await verifyUpdate(cached,artifact,this.controller.signal);valid=true;}catch{this.controller.signal.throwIfAborted();}}if(!valid){await unlink(cached);cached=undefined;this.saved.zip=undefined;this.persist();}}
    if(cached&&existsSync(cached))file=cached;
    else file=await downloadUpdate(this.options.fetch,accelerated?acceleratedURL(artifact.url):artifact.url,artifact,this.directory,p=>this.set({phase:'downloading',version:release.version,progress:p,message:'正在下载免安装 ZIP'}),this.controller.signal);
   }
   this.controller.signal.throwIfAborted();this.set({phase:'downloading',version:release.version,progress:100,message:'正在校验更新文件'});
   await verifyUpdate(file,artifact,this.controller.signal);if(this.options.mode==='automatic')await this.options.verifyInstaller(file,release.version);
   this.controller.signal.throwIfAborted();this.cached=file;
   if(this.options.mode==='manual')this.saved.zip=path.basename(file);this.persist();
   this.set({phase:'ready',version:release.version,message:this.options.mode==='manual'?'免安装 ZIP 已校验，可以另存；退出后手动替换。':'更新已校验，下次退出时安装。'});
  }catch(error){
   this.saved.approved=undefined;this.persist();const aborted=this.controller.signal.aborted&&!this.oversized;
   this.set({phase:aborted?'available':'error',version:release.version,message:aborted?'下载已中断，下次检查可重试。':'更新下载或校验未完成，当前应用与账号数据已保留。'});
   throw new Error(this.status.message,{cause:error});
  }finally{clearTimeout(timer);this.activeFile=undefined;this.controller=undefined;finished();this.preparing=undefined;}
 }
 async stopForQuit(){if(this.preparing){this.controller?.abort();this.options.backend.cancel();await this.preparing;}}
 async installOnQuit(allowRelaunch=true){
  if(this.sessionEnding||this.options.mode!=='automatic'||this.status.phase!=='ready'||!this.cached||!this.release)return;
  try{
   await this.options.preflight();await verifyUpdate(this.cached,this.release.installer);await this.options.verifyInstaller(this.cached,this.release.version);if(this.sessionEnding)return;
   this.installing=true;this.installFailure=undefined;this.options.backend.install(allowRelaunch&&this.restart);if(this.installFailure)throw this.installFailure;
  }catch(error){this.installing=false;this.saved.approved=undefined;this.persist();this.set({phase:'error',version:this.release.version,message:'安装前校验或准备未完成，当前应用保持。请重新检查更新。'});throw new Error(this.status.message,{cause:error});}
 }
 restartAfterInstall(background=false){if(this.options.mode!=='automatic'||this.status.phase!=='ready')throw Error('请先下载有效的安装更新');if(background)throw Error('Windows 更新请在退出或立即重启时安装');this.restart=true;}
 cancelRestart(){this.restart=false;}
 sessionEnd(){this.sessionEnding=true;this.restart=false;}
 async exportPackage(destination:string){
  if(this.options.mode!=='manual'||this.status.phase!=='ready'||!this.cached||!this.release)throw Error('没有可保存的免安装更新');
  if(!path.isAbsolute(destination)||path.resolve(destination)===path.resolve(this.cached))throw Error('请选择其他保存位置');
  await verifyUpdate(this.cached,this.release.zip);const temp=path.join(path.dirname(destination),'.studio-download-'+randomUUID()+'.tmp');
  try{await copyFile(this.cached,temp);await verifyUpdate(temp,this.release.zip);await rename(temp,destination);}finally{if(existsSync(temp))await unlink(temp);}
  await unlink(this.cached);this.cached=undefined;this.saved.zip=undefined;this.saved.approved=undefined;this.persist();this.set({phase:'available',version:this.release.version,message:'ZIP 已保存，退出应用后可手动替换；账号目录保持。'});
 }
 dispose(){this.disposed=true;clearInterval(this.timer);this.controller?.abort();this.options.backend.cancel();for(const off of this.unsubscribe)off();this.options.backend.dispose?.();}
}
