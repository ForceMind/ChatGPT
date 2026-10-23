import{existsSync,lstatSync,readFileSync,realpathSync,writeFileSync,renameSync,unlinkSync,rmSync}from'node:fs';import path from'node:path';import{randomUUID}from'node:crypto';
import{newerVersion,noonDue,shanghaiDay,validManifest,acceleratedURL,type UpdateManifest,type UpdateStatus}from'../shared/updates';
import{readManifest,downloadUpdate,safeDirectory,type FetchUpdate}from'./update-download';
import{stageUpdate,launchInstaller,readInstallResult,type StagedUpdate}from'./update-install';
type Saved={schema:1;noonDay:string;backup?:string;ready?:{manifest:UpdateManifest;zip:string;staged:StagedUpdate}};
export class UpdateManager{
 readonly directory:string;status:UpdateStatus={phase:'idle'};manifest?:UpdateManifest;private saved:Saved={schema:1,noonDay:''};private checking?:Promise<UpdateManifest|undefined>;private controller?:AbortController;private timer?:ReturnType<typeof setInterval>;private restart=false;
 constructor(dataDir:string,private current:string,private arch:string,private fetch:FetchUpdate,private changed:()=>void,private bundle?:string){
  this.directory=safeDirectory(path.join(realpathSync(dataDir),'updates'));const file=path.join(this.directory,'state.json');
  try{if(existsSync(file)){const stat=lstatSync(file);if(stat.isSymbolicLink()||!stat.isFile()||stat.size>65536)throw Error('更新记录无效');const s=JSON.parse(readFileSync(file,'utf8')) as Saved;if(s.schema!==1||typeof s.noonDay!=='string'||s.noonDay&&!/^\d{4}-\d{2}-\d{2}$/.test(s.noonDay))throw Error('更新记录无效');this.saved.noonDay=s.noonDay;
    const ownedCandidate=(v:unknown):v is string=>typeof v==='string'&&!!bundle&&path.dirname(v)===path.dirname(bundle)&&/^\.ChatGPT-Profiles-update-[a-f0-9-]{36}\.app$/.test(path.basename(v));
    if(ownedCandidate(s.backup))this.saved.backup=s.backup;
    const r=s.ready;
    if(r&&validManifest(r.manifest)&&r.staged?.bundle===bundle&&r.staged.version===r.manifest.version&&r.staged.directory===this.directory&&typeof r.zip==='string'&&path.dirname(r.zip)===this.directory&&/^[a-f0-9-]{36}\.zip$/.test(path.basename(r.zip))&&ownedCandidate(r.staged.candidate)&&existsSync(r.staged.candidate)){
     if(newerVersion(r.manifest.version,current)&&r.staged.fromVersion===current){
      this.saved.ready=r;this.manifest=r.manifest;this.status={phase:'ready',version:r.manifest.version,message:'更新已下载，下次退出时安装'};
     }else if(r.manifest.version===current&&readInstallResult(this.directory)===true){
      // Keep one previous app, but remove its obsolete predecessor and the ZIP.
      const prior=this.saved.backup;if(prior&&prior!==r.staged.candidate&&existsSync(prior)){
       if(lstatSync(prior).isSymbolicLink()||realpathSync(prior)!==prior)throw Error('旧版备份路径无效');rmSync(prior,{recursive:true,force:true});
      }
      this.saved.backup=r.staged.candidate;
      if(existsSync(r.zip)){if(lstatSync(r.zip).isSymbolicLink())throw Error('旧更新包路径无效');unlinkSync(r.zip);}
      this.persist();
     }
    }
   }
   if(readInstallResult(this.directory)===false)this.status=this.saved.ready?{phase:'ready',version:this.saved.ready.manifest.version,message:'上次未能安装，可在退出时重试；旧版与登录数据已保留'}:{phase:'error',message:'上次更新未能安装，当前 App 与登录数据已保留。请重试或手动安装。'};
  }catch(error){this.status={phase:'error',message:error instanceof Error?error.message:'更新记录无法读取'};}
 }
 private persist():void{safeDirectory(this.directory);const file=path.join(this.directory,'state.json');if(existsSync(file)&&lstatSync(file).isSymbolicLink())throw Error('更新记录路径无效');const temp=file+'.'+randomUUID()+'.tmp';try{writeFileSync(temp,JSON.stringify(this.saved),{flag:'wx',mode:0o600});renameSync(temp,file);}finally{if(existsSync(temp))unlinkSync(temp);}}
 private set(status:UpdateStatus):void{this.status=status;this.changed();}
 check(now=Date.now()):Promise<UpdateManifest|undefined>{
  if(this.checking)return this.checking;if(['downloading','ready'].includes(this.status.phase))return Promise.resolve(this.manifest);
  this.set({phase:'checking'});
  const operation=(async()=>{try{
   const today=shanghaiDay(now);if(today.afterNoon){this.saved.noonDay=today.day;this.persist();}
   const data=await readManifest(this.fetch);if(!validManifest(data))throw Error('官网版本清单格式无效');
   if(!data.files[this.arch as 'arm64'|'x64']){this.set({phase:'idle',message:'当前架构暂无更新包'});return;}
   if(newerVersion(data.version,this.current)){this.manifest=data;this.set({phase:'available',version:data.version});return data;}
   this.manifest=undefined;this.set({phase:'idle',message:'已是当前公开版本'});
  }catch(error){this.set({phase:'error',message:error instanceof Error?error.message:'暂时无法连接更新服务'});}})();
  this.checking=operation;void operation.finally(()=>{if(this.checking===operation)this.checking=undefined;});return operation;
 }
 start(check:(manual?:boolean)=>void):void{this.timer=setInterval(()=>{if(noonDue(Date.now(),this.saved.noonDay))check();},30000);this.timer.unref();check();}
 resume(check:()=>void):void{if(noonDue(Date.now(),this.saved.noonDay))check();}
 async download(accelerated=false):Promise<void>{
  if(this.status.phase==='downloading'||this.status.phase==='ready')return;const manifest=this.manifest,file=manifest?.files[this.arch as 'arm64'|'x64'];if(!manifest||!file||!newerVersion(manifest.version,this.current))throw Error('请先检查新版本');
  this.controller=new AbortController();const timer=setTimeout(()=>this.controller?.abort(),30*60*1000);timer.unref();this.set({phase:'downloading',version:manifest.version,progress:0,message:'后台下载中，可继续使用'});
  try{const zip=await downloadUpdate(this.fetch,accelerated?acceleratedURL(file.url):file.url,file,this.directory,p=>this.set({phase:'downloading',version:manifest.version,progress:p,message:p===100?'正在校验并准备更新':'后台下载中，可继续使用'}),this.controller.signal);
   if(!this.bundle)throw Error('更新包已校验。开发模式不替换 App；请在已安装客户端中使用自动更新');
   const staged=await stageUpdate(zip,file,manifest.version,this.bundle,this.directory,this.current);this.saved.ready={manifest,zip,staged};this.persist();
   this.set({phase:'ready',version:manifest.version,message:'更新已准备，下次退出时安装'});
  }catch(error){this.set({phase:'error',version:manifest.version,message:error instanceof Error?error.message:'更新下载失败，当前版本仍可使用'});throw error;}finally{clearTimeout(timer);this.controller=undefined;}
 }
 async installOnQuit():Promise<void>{if(!this.saved.ready||this.status.phase!=='ready')return;await launchInstaller(this.saved.ready.staged,this.restart);}
 restartAfterInstall():void{if(this.status.phase!=='ready')throw Error('请等待更新下载完成');this.restart=true;}
 dispose():void{clearInterval(this.timer);this.controller?.abort();}
}
