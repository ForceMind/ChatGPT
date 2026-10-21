import type {WebContents} from 'electron';
import {createHash} from 'node:crypto';
import {constants,createWriteStream,lstatSync,mkdirSync} from 'node:fs';
import {open,rm} from 'node:fs/promises';
import {Transform} from 'node:stream';import {pipeline} from 'node:stream/promises';import path from 'node:path';
import type {Profile} from '../shared/model';import type {HandoffFile,HandoffJob} from '../shared/handoff';
import {AssetStore} from './asset-store';import {CapabilityCatalog} from './capabilities';
import {HandoffStore,normalizeHandoffRequest} from './handoff-store';import {ChatPage} from './chat-page';
type Host={profile:(id:string)=>Profile;available:(id:string)=>boolean;open:(profileId:string)=>{tabId:string;contents:WebContents};reveal:(job:HandoffJob)=>void;changed:(job:HandoffJob)=>void;failed:(message:string)=>void;fixtureOrigin?:string};
const active=new Set(['preparing','opening','uploading','drafting','sending']);
export class HandoffRunner {
 private running=new Map<string,{cancelled:boolean;requestId:string}>();
 constructor(readonly jobs:HandoffStore,private readonly assets:AssetStore,private readonly catalog:CapabilityCatalog,private readonly host:Host){}
 start(raw:unknown,currentProfileId:string):HandoffJob{
  const request=normalizeHandoffRequest(raw);if(request.sourceProfileId!==currentProfileId)throw Error('账号已切换，请重新选择来源文件。');const prior=this.jobs.find(request);if(prior)return prior;
  if(this.running.size>=2)throw Error('已有两个网页任务正在处理，请稍后再试。');
  const source=this.host.profile(request.sourceProfileId),target=this.host.profile(request.targetProfileId);if(!this.host.available(source.id)||!this.host.available(target.id))throw Error('来源或目标账号正在移除/清除，已停止。');
  let total=0;const used=new Set<string>();const files:HandoffFile[]=request.assetIds.map((id,index)=>{const a=this.assets.get(source.id,id);if(a.status!=='ready'||a.trashedAt||!a.sha256)throw Error('所选文件不可用，请重新选择。');total+=a.size;if(a.size>100*1024*1024)throw Error('本版单个附件最多 100 MB。');let uploadName=a.fileName;const asText=/\.(html?|svg|[cm]?jsx?|tsx?|css|sh|py)$/i.test(uploadName);if(asText)uploadName+='.txt';if(used.has(uploadName))uploadName=`${index+1}-${uploadName}`;used.add(uploadName);return{assetId:id,profileId:source.id,name:a.name,uploadName,mime:a.mime,uploadMime:asText?'text/plain':a.mime,size:a.size,sha256:a.sha256};});
  if(total>200*1024*1024)throw Error('本版一次附件总量最多 200 MB。');
  const prompt=this.catalog.compose({...request,files:files.map(f=>({name:f.uploadName,mime:f.uploadMime,size:f.size}))});
  const label=request.skillId?this.catalog.skill(request.skillId).name:request.agentId?this.catalog.agent(request.agentId).name:'自定义任务';
  const job=this.jobs.create(request,files,source.name,target.name,label);const control={cancelled:false,requestId:request.requestId};this.running.set(job.id,control);this.host.changed(job);
  void this.run(job,control,prompt).catch(()=>this.host.failed('任务记录写入或清理失败，请检查目标标签页；应用不会自动重发。'));return job;
 }
 private publish(id:string,patch:Parameters<HandoffStore['update']>[1]):HandoffJob{const j=this.jobs.update(id,patch);this.host.changed(j);return j;}
 cancel(id:string,profileId:string):HandoffJob{const job=this.jobs.get(id);this.assertAccess(job,profileId);const c=this.running.get(id);if(c){c.cancelled=true;if(job.status!=='sending')return this.publish(id,{status:'cancelled',message:'已取消；原始文件保留，网页中已形成的草稿可手动检查。'});}return this.jobs.get(id);}
 private assertAccess(job:HandoffJob,profileId:string):void{if(job.request.sourceProfileId!==profileId&&job.request.targetProfileId!==profileId)throw Error('该任务不属于当前账号。');}
 open(id:string,profileId:string):void{const j=this.jobs.get(id);this.assertAccess(j,profileId);if(!j.tabId)throw Error('任务尚未创建目标标签页。');this.host.reveal(j);}
 cancelProfile(profileId:string):void{for(const job of this.jobs.all())if(active.has(job.status)&&(job.request.sourceProfileId===profileId||job.request.targetProfileId===profileId))this.cancel(job.id,profileId);}
 dispose():void{for(const c of this.running.values())c.cancelled=true;}
 private async run(job:HandoffJob,control:{cancelled:boolean},basePrompt:string):Promise<void>{
  let page:ChatPage|undefined;const dir=path.join(this.jobs.directory,'staging',job.id);
  const check=()=>{if(control.cancelled)throw Error('任务已取消');if(!this.host.available(job.request.sourceProfileId)||!this.host.available(job.request.targetProfileId))throw Error('来源或目标账号已不可用');};
  try{
   check();const parent=path.dirname(dir);mkdirSync(parent,{recursive:true,mode:0o700});if(lstatSync(parent).isSymbolicLink()||lstatSync(this.jobs.directory).isSymbolicLink())throw Error('任务暂存目录已改变');mkdirSync(dir,{mode:0o700});const paths:string[]=[];
   for(const f of job.files){check();const source=await this.assets.verifyFile(f.profileId,f.assetId),destination=path.join(dir,f.uploadName);const digest=createHash('sha256');let count=0;const input=await open(source,constants.O_RDONLY|(constants.O_NOFOLLOW||0));
    try{await pipeline(input.createReadStream({autoClose:false}),new Transform({transform(chunk:Buffer,_e,cb){try{check();digest.update(chunk);count+=chunk.length;cb(null,chunk);}catch(e){cb(e as Error);}}}),createWriteStream(destination,{flags:'wx',mode:0o600}));}finally{await input.close();}
    const original=this.assets.get(f.profileId,f.assetId);if(count!==f.size||digest.digest('hex')!==f.sha256||original.trashedAt||original.status!=='ready')throw Error('源文件在准备时发生变化，已停止上传。');paths.push(destination);
   }
   check();this.publish(job.id,{status:'opening',message:'正在所选账号的独立空间新建 ChatGPT 对话。'});const target=this.host.open(job.request.targetProfileId);this.publish(job.id,{tabId:target.tabId});page=new ChatPage(target.contents,this.host.fixtureOrigin,check);await page.ready();
   if(paths.length){this.publish(job.id,{status:'uploading',message:`正在上传 ${paths.length} 个附件，请等待网页确认。`});await page.upload(paths,job.files.map(f=>f.uploadName));}
   this.publish(job.id,{status:'drafting',message:'附件已确认，正在填入任务与角色要求。'});
   const aliases=job.files.filter(f=>f.name!==f.uploadName).map(f=>`${f.name} → ${f.uploadName}`);const prompt=basePrompt+(aliases.length?'\n\n附件名称映射（保留原始内容；HTML、SVG、代码以文本附件提供）：\n'+aliases.join('\n'):'');
   await page.draft(prompt);check();const url=await page.sendOnce(prompt,job.files.map(f=>f.uploadName),()=>{check();this.publish(job.id,{status:'sending',message:'正在发送一次；若结果不确定，不会自动重发。'});});
   this.publish(job.id,{status:'submitted',conversationURL:url,message:'网页已显示本次用户消息；模型处理结果请在目标对话查看。'});
  }catch(error){const state=this.jobs.get(job.id);if(active.has(state.status)){const sending=state.status==='sending';this.publish(job.id,{status:sending?'uncertain':control.cancelled?'cancelled':'failed',message:sending?'发送结果未能确认。请打开目标对话检查，应用不会自动重发。':(error instanceof Error?error.message:'网页任务未能完成').slice(0,900)});}}
  finally{try{page?.dispose();}catch{/* the target may have closed during cleanup */}await rm(dir,{recursive:true,force:true}).catch(()=>{});this.running.delete(job.id);}
 }
}
