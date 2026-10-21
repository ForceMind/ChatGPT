import {randomUUID} from 'node:crypto';
import {existsSync,lstatSync,mkdirSync,readFileSync,writeFileSync,renameSync,unlinkSync} from 'node:fs';
import path from 'node:path';
import {LINK_KINDS, LINK_RESULTS, type LinkKind, type LinkRecord, type LinkResult} from '../shared/diagnostics';
const UUID=/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/;
// Only the scheme and host survive. OAuth parameters, fragments, user info and even
// path segments can contain secrets; none are retained in this diagnostic index.
export function diagnosticOrigin(value:string):string {
  try{const u=new URL(value);if(u.protocol==='https:'||u.protocol==='http:')return u.origin.length<=350?u.origin:'（站点名称过长）';return /^[a-z][a-z0-9+.-]{0,40}:$/.test(u.protocol)?u.protocol+'（内容已隐藏）':'（无法识别的链接）';}catch{return '（无法识别的链接）';}
}
function reason(url:string,kind:LinkKind):string {
  if(kind==='login-page')return '在当前账号的独立空间访问登录站点。';
  try{const u=new URL(url);if(u.protocol!=='https:')return '该链接使用非 HTTPS 协议，客户端未接管此类链接。';if(u.username||u.password)return '该链接包含身份信息，客户端拒绝在内置窗口打开。';return '目标站点不在客户端内置浏览与登录站点列表中。';}catch{return '链接格式无法识别，客户端未打开。';}
}
export class LoginDiagnostics {
  private records:LinkRecord[]=[];private file:string;
  constructor(directory:string,private readonly changed:()=>void=()=>{}) {
    mkdirSync(directory,{recursive:true,mode:0o700});this.file=path.join(directory,'login-links.json');
    if(existsSync(this.file))try{const stat=lstatSync(this.file);if(!stat.isFile()||stat.isSymbolicLink()||stat.size>512*1024)throw Error();const raw:unknown=JSON.parse(readFileSync(this.file,'utf8'));if(!Array.isArray(raw)||raw.length>200)throw Error();
      this.records=raw.map(item=>{if(!item||!UUID.test(item.id)||!UUID.test(item.profileId)||!Number.isSafeInteger(item.time)||!LINK_KINDS.includes(item.kind)||!LINK_RESULTS.includes(item.result)||typeof item.destination!=='string'||typeof item.source!=='string')throw Error();return{id:item.id,time:item.time,profileId:item.profileId,kind:item.kind,result:item.result,destination:diagnosticOrigin(item.destination),source:diagnosticOrigin(item.source),reason:reason(item.destination,item.kind)};});
    }catch{throw new Error('登录与链接记录无法读取；现有登录仍可使用。');}
  }
  list():LinkRecord[]{return structuredClone(this.records).reverse();}
  private save(next:LinkRecord[]):void {
    if(existsSync(this.file)&&lstatSync(this.file).isSymbolicLink())throw new Error('登录记录文件已改变');
    const temp=this.file+'.'+randomUUID()+'.tmp';try{writeFileSync(temp,JSON.stringify(next)+'\n',{flag:'wx',mode:0o600});renameSync(temp,this.file);this.records=next;this.changed();}finally{if(existsSync(temp))unlinkSync(temp);}
  }
  add(profileId:string,kind:LinkKind,destination:string,source:string):LinkRecord {
    if(!UUID.test(profileId))throw new Error('账号标识无效');
    const record:LinkRecord={id:randomUUID(),time:Date.now(),profileId,kind,result:kind==='login-page'?'allowed':'blocked',destination:diagnosticOrigin(destination),source:diagnosticOrigin(source),reason:reason(destination,kind)};
    const last=this.records.at(-1);if(last&&record.time-last.time<1000&&last.profileId===profileId&&last.kind===kind&&last.destination===record.destination)return structuredClone(last);
    this.save([...this.records.slice(-199),record]);return structuredClone(record);
  }
  result(id:string,result:LinkResult):void {if(!LINK_RESULTS.includes(result))throw new Error('记录状态无效');const next=structuredClone(this.records),record=next.find(r=>r.id===id);if(record){record.result=result;this.save(next);}}
  clear():void{this.save([]);}
}
