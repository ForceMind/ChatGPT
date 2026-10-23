import {createHash,randomUUID} from 'node:crypto';
import {mkdirSync,lstatSync,realpathSync,existsSync,unlinkSync,renameSync,createReadStream} from 'node:fs';
import {open} from 'node:fs/promises';
import path from 'node:path';
import type {UpdateFile} from '../shared/updates';
export const UPDATE_FEED='https://chatgpt.xincreates.com/updates/macos.json';
export type FetchUpdate=(url:string,options:RequestInit)=>Promise<Response>;
export function safeDirectory(directory:string):string{mkdirSync(directory,{recursive:true,mode:0o700});const stat=lstatSync(directory);if(stat.isSymbolicLink()||!stat.isDirectory()||realpathSync(directory)!==directory)throw Error('更新目录路径无效');return directory;}
function permitted(url:string,feed:boolean):boolean{
 try{const u=new URL(url);if(u.protocol!=='https:'||u.username||u.password||u.port)return false;
  return feed?url===UPDATE_FEED:u.hostname==='gh-proxy.org'?/^\/https:\/\/github\.com\/ForceMind\/ChatGPT\/releases\/download\//.test(u.pathname):u.hostname==='github.com'?u.pathname.startsWith('/ForceMind/ChatGPT/releases/download/'):['release-assets.githubusercontent.com','objects.githubusercontent.com'].includes(u.hostname);
 }catch{return false;}
}
export async function updateResponse(fetch:FetchUpdate,url:string,signal:AbortSignal,feed=false):Promise<Response>{
 for(let i=0;i<6;i++){
  if(!permitted(url,feed))throw Error('更新地址不在允许范围');
  const response=await fetch(url,{credentials:'omit',redirect:'manual',cache:'no-store',signal,headers:{'Accept':feed?'application/json':'application/octet-stream'}});
  if([301,302,303,307,308].includes(response.status)){const location=response.headers.get('location');await response.body?.cancel();if(!location||feed)throw Error('版本清单不允许重定向');url=new URL(location,url).href;continue;}
  if(!response.ok){await response.body?.cancel();throw Error(`更新服务器返回 ${response.status}`);}return response;
 }throw Error('更新下载重定向过多');
}
export async function readManifest(fetch:FetchUpdate):Promise<unknown>{
 const controller=new AbortController(),timer=setTimeout(()=>controller.abort(),15000);timer.unref();
 try{const r=await updateResponse(fetch,UPDATE_FEED,controller.signal,true),reader=r.body?.getReader();if(!reader)throw Error('版本清单为空');const chunks:Uint8Array[]=[];let size=0;try{for(;;){const next=await reader.read();if(next.done)break;size+=next.value.length;if(size>65536)throw Error('版本清单过大');chunks.push(next.value);}}finally{await reader.cancel();}return JSON.parse(Buffer.concat(chunks).toString('utf8'));}finally{clearTimeout(timer);}
}
export async function downloadUpdate(fetch:FetchUpdate,url:string,file:UpdateFile,directory:string,onProgress:(percent:number)=>void,signal:AbortSignal):Promise<string>{
 safeDirectory(directory);const partial=path.join(directory,randomUUID()+'.part'),target=path.join(directory,randomUUID()+'.zip');let handle;
 try{const response=await updateResponse(fetch,url,signal),length=response.headers.get('content-length');if(length!==null&&Number(length)!==file.size){await response.body?.cancel();throw Error('更新包大小不符');}
  const reader=response.body?.getReader();if(!reader)throw Error('更新包为空');handle=await open(partial,'wx',0o600);const hash=createHash('sha256');let bytes=0,last=-1;
  try{for(;;){signal.throwIfAborted();const next=await reader.read();if(next.done)break;bytes+=next.value.length;if(bytes>file.size)throw Error('更新包超过声明大小');hash.update(next.value);await handle.writeFile(next.value);const percent=Math.floor(bytes*100/file.size);if(percent!==last){last=percent;onProgress(percent);}}}finally{await reader.cancel();}
  if(bytes!==file.size||hash.digest('hex')!==file.sha256)throw Error('更新包校验失败，已丢弃下载内容');await handle.sync();await handle.close();handle=undefined;safeDirectory(directory);renameSync(partial,target);return target;
 }catch(error){await handle?.close();if(existsSync(partial))unlinkSync(partial);throw error;}
}
export async function verifyUpdate(file:string,expected:UpdateFile):Promise<void>{
 const stat=lstatSync(file);if(stat.isSymbolicLink()||!stat.isFile()||stat.size!==expected.size)throw Error('已下载更新包无效');
 const hash=createHash('sha256');for await(const chunk of createReadStream(file))hash.update(chunk);
 if(hash.digest('hex')!==expected.sha256)throw Error('已下载更新包校验失败');
}
