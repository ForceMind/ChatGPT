import {createCipheriv,createDecipheriv,createHash,randomBytes,randomUUID} from 'node:crypto';
import {existsSync,lstatSync,readFileSync,readdirSync,realpathSync,renameSync,writeFileSync,unlinkSync} from 'node:fs';
import path from 'node:path';
import type {RecoveryCipher} from './recovery-store';

export type PresenceKey = {capabilities:()=>{touchID:boolean;signed:boolean};unlock:(slot:string,create:boolean)=>Promise<Buffer>};
const MAGIC=Buffer.from('CPR-PRESENCE-1\0');
export type RecoveryAccessStatus={phase:'pending'|'working'|'ready'|'deferred'|'error';message?:string;touchID:boolean;signed:boolean;protected:boolean;blocking?:boolean};
/** No cookies, browser partitions, or existing OS keys are altered. Wrapped
 * records retain the old OS ciphertext, so migration never emits plaintext. */
export class RecoveryAccess {
 private file:string;private key?:Buffer;private enabled=false;private accepted?:string;private busy=false;
 readonly slot:string;status:RecoveryAccessStatus;
 constructor(private directory:string,private version:string,private legacy:RecoveryCipher,private available:()=>Promise<boolean>,private native?:PresenceKey){
  this.directory=realpathSync(directory);this.file=path.join(this.directory,'recovery-access.json');this.slot=createHash('sha256').update(this.directory).digest('hex');
  if(existsSync(this.file)){this.safe(this.file);const raw=JSON.parse(readFileSync(this.file,'utf8'));if(raw.schema!==1||typeof raw.protected!=='boolean'||typeof raw.accepted!=='string'||raw.accepted.length>100)throw Error('恢复授权设置无效，原始数据保留');this.enabled=raw.protected;this.accepted=raw.accepted;}
  const capabilities=native?.capabilities()||{touchID:false,signed:false};this.status={phase:'pending',...capabilities,protected:this.enabled};
 }
 get needsConsent(){return this.accepted!==this.version||this.enabled;}
 private safe(file:string){const s=lstatSync(file);if(s.isSymbolicLink()||!s.isFile()||s.size>3*1024*1024||realpathSync(file)!==file)throw Error('恢复授权文件路径或大小无效');}
 private settings(){const tmp=this.file+'.'+randomUUID()+'.tmp';try{writeFileSync(tmp,JSON.stringify({schema:1,protected:this.enabled,accepted:this.accepted||''})+'\n',{mode:0o600,flag:'wx'});renameSync(tmp,this.file);}finally{if(existsSync(tmp))unlinkSync(tmp);}}
 defer(){if(!this.busy)this.status={...this.status,phase:'deferred',message:'本地恢复尚未解锁；登录空间和旧恢复原件保留，本次草稿暂不保存。'};}
 async authorize(){
  if(this.busy)throw Error('恢复授权正在进行');this.busy=true;this.status={...this.status,phase:'working',message:undefined};
  try{
   if(this.enabled){if(!this.native||!this.status.signed)throw Error('此版本缺少稳定签名，不能解锁受 Touch ID 保护的恢复密钥；请使用原签名版本。');this.key=await this.native.unlock(this.slot,false);this.validateKey();}
   if(!await this.available())throw Error('钥匙串暂不可用。请解锁系统钥匙串后重试；不会保存明文。');
   this.accepted=this.version;this.settings();this.status={...this.status,phase:'ready',message:undefined};
  }catch(error){this.key?.fill(0);this.key=undefined;this.status={...this.status,phase:'error',message:error instanceof Error?error.message:'恢复授权失败'};throw error;}finally{this.busy=false;}
 }
 private validateKey(){if(!Buffer.isBuffer(this.key)||this.key.length!==32)throw Error('恢复密钥格式无效');}
 private wrap(value:Buffer){this.validateKey();const nonce=randomBytes(12),cipher=createCipheriv('aes-256-gcm',this.key!,nonce);cipher.setAAD(MAGIC);const encrypted=Buffer.concat([cipher.update(value),cipher.final()]);return Buffer.concat([MAGIC,nonce,encrypted,cipher.getAuthTag()]);}
 private unwrap(value:Buffer){if(!value.subarray(0,MAGIC.length).equals(MAGIC))return value;this.validateKey();if(value.length<MAGIC.length+28)throw Error('恢复记录不完整');const cipher=createDecipheriv('aes-256-gcm',this.key!,value.subarray(MAGIC.length,MAGIC.length+12));cipher.setAAD(MAGIC);cipher.setAuthTag(value.subarray(-16));return Buffer.concat([cipher.update(value.subarray(MAGIC.length+12,-16)),cipher.final()]);}
 readonly cipher:RecoveryCipher={encrypt:async value=>{if(this.status.phase!=='ready')throw Error('本地恢复尚未授权');const encrypted=await this.legacy.encrypt(value);return this.enabled?this.wrap(encrypted):encrypted;},decrypt:async value=>{if(this.status.phase!=='ready')throw Error('本地恢复尚未授权');return this.legacy.decrypt(this.unwrap(value));}};
 async enablePresence(){
  if(this.busy||this.status.phase!=='ready')throw Error('请先完成本地恢复授权');
  if(!this.native||!this.status.signed)throw Error('Touch ID 密钥保护需要 Developer ID 签名版本；当前临时签名版继续使用系统钥匙串加密。');
  if(!this.status.touchID)throw Error('此 Mac 的 Touch ID 暂不可用，请先在系统设置中配置指纹。');
  if(this.enabled){this.finishMigration();return;}this.busy=true;
  try{
   this.key=await this.native.unlock(this.slot,true);this.validateKey();
   // Commit the requirement first. Interrupted conversion remains readable only
   // after key authorization; the next enable call can safely finish wrapping.
   this.enabled=true;try{this.settings();}catch(error){this.enabled=false;this.key.fill(0);this.key=undefined;throw error;}this.status={...this.status,protected:true};
   this.wrapExisting();
  }finally{this.busy=false;}
 }
 finishMigration(){if(this.enabled&&this.status.phase==='ready')this.wrapExisting();}
 private wrapExisting(){
  const root=path.join(this.directory,'recovery');if(!existsSync(root))return;
  if(lstatSync(root).isSymbolicLink()||realpathSync(root)!==root)throw Error('恢复目录路径无效');
  for(const id of readdirSync(root)){if(!/^[a-f0-9-]{36}$/.test(id))continue;const folder=path.join(root,id);if(lstatSync(folder).isSymbolicLink()||!lstatSync(folder).isDirectory()||realpathSync(folder)!==folder)throw Error('恢复账号目录无效');
   for(const name of readdirSync(folder)){if(!/^[a-f0-9-]{36}\.bin(?:\.bak)?$/.test(name))continue;const file=path.join(folder,name);this.safe(file);const bytes=readFileSync(file);if(bytes.subarray(0,MAGIC.length).equals(MAGIC))continue;const tmp=file+'.'+randomUUID()+'.presence.tmp';try{writeFileSync(tmp,this.wrap(bytes),{flag:'wx',mode:0o600});renameSync(tmp,file);}finally{if(existsSync(tmp))unlinkSync(tmp);}}
  }
 }
 dispose(){this.key?.fill(0);this.key=undefined;}
}
