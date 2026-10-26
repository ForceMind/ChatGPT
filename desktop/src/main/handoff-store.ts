import {createHash,randomUUID} from 'node:crypto';
import {existsSync,lstatSync,mkdirSync,openSync,closeSync,fsyncSync,writeFileSync,readFileSync,renameSync,unlinkSync} from 'node:fs';
import path from 'node:path';
import type {HandoffFile,HandoffJob,HandoffStatus} from '../shared/handoff';
import type {HandoffRequest} from '../shared/workbench';
import {isChatURL} from './policy';
import {CAPABILITY_REVISION} from './capabilities';
import {handoffStages,type HandoffStage} from '../shared/handoff';

const uuid=(v:unknown):v is string=>typeof v==='string'&&/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/.test(v);
const object=(v:unknown):v is Record<string,unknown>=>!!v&&typeof v==='object'&&!Array.isArray(v);
const text=(v:unknown,max:number):v is string=>typeof v==='string'&&v.trim().length>0&&v.length<=max&&!/[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/.test(v);
const phases:HandoffStatus[]=['preparing','opening','uploading','drafting','sending','submitted','failed','uncertain','cancelled','interrupted'];
const active=new Set<HandoffStatus>(['preparing','opening','uploading','drafting','sending']);
const transitions:Record<HandoffStatus,HandoffStatus[]>={preparing:['opening','failed','cancelled','interrupted'],opening:['uploading','drafting','failed','cancelled','interrupted'],uploading:['drafting','failed','cancelled','interrupted'],drafting:['sending','failed','cancelled','interrupted'],sending:['submitted','uncertain'],submitted:[],failed:[],uncertain:[],cancelled:[],interrupted:[]};
export function normalizeHandoffRequest(raw:unknown):HandoffRequest{
  if(!object(raw)||!uuid(raw.requestId)||!uuid(raw.sourceProfileId)||!uuid(raw.targetProfileId)||!Array.isArray(raw.assetIds)||raw.assetIds.length>20||!raw.assetIds.every(uuid)||new Set(raw.assetIds).size!==raw.assetIds.length||typeof raw.crossAccountConfirmed!=='boolean'||!text(raw.language,80)||typeof raw.instructions!=='string'||raw.instructions.length>12000)throw new Error('网页处理任务参数无效');
  for(const key of ['skillId','agentId'] as const)if(raw[key]!==undefined&&(typeof raw[key]!=='string'||!(key==='skillId'?/^skill-\d{3}$/:/^(?:agent|studio-agent)-\d{3}$/).test(raw[key] as string)))throw new Error('所选任务或角色标识无效');
  if(raw.sourceProfileId!==raw.targetProfileId&&raw.assetIds.length&&!raw.crossAccountConfirmed)throw new Error('请明确确认跨账号使用这些文件');
  if(!raw.skillId&&!raw.agentId&&!raw.instructions.trim())throw new Error('请选择任务、角色，或填写处理要求');
  return{requestId:raw.requestId,sourceProfileId:raw.sourceProfileId,targetProfileId:raw.targetProfileId,assetIds:[...raw.assetIds],crossAccountConfirmed:raw.crossAccountConfirmed,skillId:raw.skillId as string|undefined,agentId:raw.agentId as string|undefined,language:raw.language,instructions:raw.instructions};
}
const fingerprint=(request:HandoffRequest)=>createHash('sha256').update(JSON.stringify(request)).digest('hex');
type Data={schema:1;jobs:HandoffJob[]};
function atomic(file:string,data:Data):void{const temporary=file+'.'+randomUUID()+'.tmp';let fd:number|undefined;try{fd=openSync(temporary,'wx',0o600);writeFileSync(fd,JSON.stringify(data)+'\n');fsyncSync(fd);closeSync(fd);fd=undefined;renameSync(temporary,file);}finally{if(fd!==undefined)closeSync(fd);if(existsSync(temporary))unlinkSync(temporary);}}

export class HandoffStore {
  readonly directory:string;readonly indexFile:string;readonly backupFile:string;private data:Data;
  constructor(dataDirectory:string,private readonly fixtureOrigin?:string){
    this.directory=path.join(dataDirectory,'handoff');mkdirSync(this.directory,{recursive:true,mode:0o700});if(lstatSync(this.directory).isSymbolicLink())throw new Error('任务目录不能是链接');
    this.indexFile=path.join(this.directory,'jobs.json');this.backupFile=path.join(this.directory,'jobs.backup.json');
    if(!existsSync(this.indexFile)&&!existsSync(this.backupFile)){this.data={schema:1,jobs:[]};atomic(this.indexFile,this.data);}
    else{try{this.data=this.read(this.indexFile);}catch{try{this.data=this.read(this.backupFile);}catch{throw new Error('任务记录和备份均无法读取，已停止自动发送，请保留数据并恢复记录。');}if(existsSync(this.indexFile))renameSync(this.indexFile,path.join(this.directory,`jobs.corrupt-${Date.now()}-${randomUUID()}.json`));atomic(this.indexFile,this.data);}}
    if(this.data.jobs.some(job=>active.has(job.status))){const next=structuredClone(this.data);for(const job of next.jobs)if(active.has(job.status)){job.status=job.status==='sending'?'uncertain':'interrupted';job.message=job.status==='uncertain'?'上次发送结果不确定，请检查目标对话。应用不会自动重发。':'上次任务在发送前中断，文件保留；可打开目标对话检查草稿。';job.updatedAt=Date.now();}this.save(next);}
  }
  private validate(raw:unknown):Data{
    const fail=():never=>{throw new Error('任务记录格式无效');};
    if(!object(raw)||raw.schema!==1||!Array.isArray(raw.jobs)||raw.jobs.length>1000)return fail();const ids=new Set<string>(),requests=new Set<string>();
    for(const item of raw.jobs){
      if(!object(item)||!uuid(item.id)||ids.has(item.id)||!object(item.request)||!text(item.sourceProfileName,40)||!text(item.targetProfileName,40)||!text(item.label,120)||!phases.includes(item.status as HandoffStatus)||!text(item.message,1000)||!Number.isSafeInteger(item.createdAt)||!Number.isSafeInteger(item.updatedAt)||!Array.isArray(item.files)||(typeof item.catalogCommit!=='string'||!(/^(project-skills-v[1-9][0-9]*|[a-f0-9]{40})$/).test(item.catalogCommit)))return fail();
      const request=normalizeHandoffRequest(item.request);if(requests.has(request.requestId)||item.fingerprint!==fingerprint(request)||item.files.length!==request.assetIds.length)return fail();
      const fileIds=new Set<string>();for(const file of item.files){if(!object(file)||!uuid(file.assetId)||fileIds.has(file.assetId)||!request.assetIds.includes(file.assetId)||file.profileId!==request.sourceProfileId||!text(file.name,240)||!text(file.uploadName,240)||/[\\/]/.test(file.uploadName)||!text(file.mime,160)||!text(file.uploadMime,160)||!Number.isSafeInteger(file.size)||(file.size as number)<0||typeof file.sha256!=='string'||!/^[a-f0-9]{64}$/.test(file.sha256))return fail();fileIds.add(file.assetId);}
      if(item.tabId!==undefined&&!uuid(item.tabId))return fail();if(item.conversationURL!==undefined&&(typeof item.conversationURL!=='string'||!(isChatURL(item.conversationURL)||(this.fixtureOrigin&&item.conversationURL.startsWith(this.fixtureOrigin+'/')))||new URL(item.conversationURL).search||new URL(item.conversationURL).hash))return fail();
      ids.add(item.id);requests.add(request.requestId);
      if(item.progress!==undefined){if(!Array.isArray(item.progress)||item.progress.length>7)return fail();let previous=-1;for(const point of item.progress){if(!object(point))return fail();const position=handoffStages.indexOf(point.stage as HandoffStage);if(position<=previous||!Number.isSafeInteger(point.startedAt)||point.completedAt!==undefined&&(!Number.isSafeInteger(point.completedAt)||(point.completedAt as number)<(point.startedAt as number))||point.note!==undefined&&!text(point.note,240))return fail();previous=position;}}
    }
    return structuredClone(raw) as Data;
  }
  private read(file:string):Data{const stat=lstatSync(file);if(!stat.isFile()||stat.isSymbolicLink()||stat.size>25*1024*1024)throw new Error('任务记录无法读取');return this.validate(JSON.parse(readFileSync(file,'utf8')));}
  private save(next:Data):void{if(lstatSync(this.directory).isSymbolicLink())throw new Error('任务目录已改变');const valid=this.validate(next);if(Buffer.byteLength(JSON.stringify(valid))>25*1024*1024)throw new Error('任务记录已达容量上限');atomic(this.backupFile,this.data);atomic(this.indexFile,valid);this.data=valid;}
  find(request: HandoffRequest):HandoffJob|undefined{const found=this.data.jobs.find(j=>j.request.requestId===request.requestId);if(found&&found.fingerprint!==fingerprint(request))throw new Error('同一任务标识不能更改文件、目标账号或处理要求');return found?structuredClone(found):undefined;}
  get(id:string):HandoffJob{const job=this.data.jobs.find(j=>j.id===id);if(!job)throw new Error('处理任务不存在');return structuredClone(job);}
  list(profileId:string):HandoffJob[]{if(!uuid(profileId))throw new Error('账号标识无效');return this.data.jobs.filter(j=>j.request.sourceProfileId===profileId||j.request.targetProfileId===profileId).map(j=>structuredClone(j)).sort((a,b)=>b.createdAt-a.createdAt);}
  all():HandoffJob[]{return structuredClone(this.data.jobs);}
  create(request:HandoffRequest,files:HandoffFile[],sourceProfileName:string,targetProfileName:string,label:string):HandoffJob{
    const existing=this.find(request);if(existing)return existing;if(this.data.jobs.length>=1000)throw new Error('本机任务记录已达 1000 条，请先整理任务记录。');const now=Date.now();
    const job:HandoffJob={id:randomUUID(),request:structuredClone(request),fingerprint:fingerprint(request),sourceProfileName,targetProfileName,label,status:'preparing',message:'正在校验选中的文件与目标账号。',createdAt:now,updatedAt:now,files:structuredClone(files),catalogCommit:CAPABILITY_REVISION};
    job.progress=[{stage:'files',startedAt:now}];const next=structuredClone(this.data);next.jobs.push(job);this.save(next);return this.get(job.id);
  }
  update(id:string,patch:Partial<Pick<HandoffJob,'status'|'message'|'tabId'|'conversationURL'>>&{stage?:HandoffStage;note?:string}):HandoffJob{
    const current=this.get(id);if(patch.status&&patch.status!==current.status&&!transitions[current.status].includes(patch.status))throw new Error('任务状态已结束，禁止重复提交或回退');
    if(patch.stage&&!active.has(current.status))throw Error('任务已结束，进度不能更新或回退');
    const next=structuredClone(this.data),job=next.jobs.find(j=>j.id===id)!,now=Date.now(),{stage,note,...fields}=patch;
    if(stage){const points=job.progress||[],last=points.at(-1);if(last?.stage===stage){if(note)last.note=note;}else{if(last&&handoffStages.indexOf(stage)<=handoffStages.indexOf(last.stage))throw Error('任务进度不能回退');if(last)last.completedAt=now;points.push({stage,startedAt:now,...(note?{note}:{})});}job.progress=points;}
    if(patch.status==='submitted'&&job.progress?.length)job.progress.at(-1)!.completedAt=now;
    Object.assign(job,fields,{updatedAt:now});this.save(next);return this.get(id);
  }
}
