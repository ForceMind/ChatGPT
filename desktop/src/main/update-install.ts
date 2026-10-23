import{execFile,spawn}from'node:child_process';import{promisify}from'node:util';
import{readFileSync,copyFileSync,chmodSync,existsSync,lstatSync,realpathSync,rmSync}from'node:fs';
import path from'node:path';import{randomUUID}from'node:crypto';
import{safeDirectory,verifyUpdate}from'./update-download';import type{UpdateFile}from'../shared/updates';
const run=promisify(execFile),BUNDLE_ID='com.forcemind.chatgpt.profiles';
async function plist(bundle:string,key:string,signal?:AbortSignal):Promise<string>{return(await run('/usr/libexec/PlistBuddy',['-c','Print:'+key,path.join(bundle,'Contents/Info.plist')],{timeout:15000,signal})).stdout.trim();}
export async function verifyBundle(bundle:string,version:string,signal?:AbortSignal):Promise<void>{
 if(lstatSync(bundle).isSymbolicLink()||!lstatSync(bundle).isDirectory()||realpathSync(bundle)!==bundle)throw Error('更新应用路径无效');
 if(await plist(bundle,'CFBundleIdentifier',signal)!==BUNDLE_ID||await plist(bundle,'CFBundleShortVersionString',signal)!==version)throw Error('更新应用身份或版本不符');
 await run('/usr/bin/codesign',['--verify','--deep','--strict',bundle],{timeout:120000,maxBuffer:1024*1024,signal});
 const executable=await plist(bundle,'CFBundleExecutable',signal);if(!/^[^/\\\u0000-\u001f]+$/.test(executable))throw Error('更新可执行文件无效');
 const arch=(await run('/usr/bin/lipo',['-archs',path.join(bundle,'Contents/MacOS',executable)],{timeout:15000,signal})).stdout.trim().split(/\s+/);if(!arch.includes(process.arch))throw Error('更新包架构不符');
}
export function installedBundle(executable:string):string{
 const tail='/Contents/MacOS/';const index=executable.lastIndexOf(tail);if(index<0)throw Error('请先将 App 安装到应用程序目录，再使用自动安装');const bundle=executable.slice(0,index);
 if(!bundle.endsWith('.app')||bundle.includes('/AppTranslocation/')||bundle.startsWith('/Volumes/'))throw Error('请将 App 移到应用程序目录后再更新');
 return bundle;
}
export type StagedUpdate={bundle:string;candidate:string;version:string;fromVersion:string;directory:string};
export async function stageUpdate(zip:string,file:UpdateFile,version:string,bundle:string,directory:string,fromVersion:string,signal?:AbortSignal):Promise<StagedUpdate>{
 if(process.platform!=='darwin')throw Error('自动安装目前仅用于 macOS');safeDirectory(directory);await verifyUpdate(zip,file,signal);await verifyBundle(bundle,fromVersion,signal);
 const parent=path.dirname(bundle);safeDirectory(parent);if(lstatSync(bundle).isSymbolicLink()||realpathSync(bundle)!==bundle)throw Error('当前 App 路径无效');
 const token=randomUUID(),unpacked=path.join(directory,'unpack-'+token),candidate=path.join(parent,'.ChatGPT-Profiles-update-'+token+'.app');
 try{
  // Reject path traversal before extraction. Framework symlinks are verified by codesign.
  const listing=(await run('/usr/bin/unzip',['-Z1',zip],{timeout:30000,maxBuffer:8*1024*1024,signal})).stdout.split('\n').filter(Boolean);
  if(!listing.length||listing.length>30000||listing.some(name=>name.startsWith('/')||name.split('/').includes('..')||name.includes('\\')||!name.startsWith('ChatGPT Profiles.app/')))throw Error('更新压缩包路径无效');
  await run('/usr/bin/ditto',['-x','-k',zip,unpacked],{timeout:180000,signal});const source=path.join(unpacked,'ChatGPT Profiles.app');await verifyBundle(source,version,signal);
  // Stage on the same filesystem for an atomic directory exchange. The running bundle is untouched.
  await run('/usr/bin/ditto',[source,candidate],{timeout:180000,signal});await verifyBundle(candidate,version,signal);
  return{bundle,candidate,version,fromVersion,directory};
 }catch(error){if(existsSync(candidate))rmSync(candidate,{recursive:true,force:true});throw error;}finally{if(existsSync(unpacked))rmSync(unpacked,{recursive:true,force:true});}
}
// The bundled native helper performs a single RENAME_SWAP; it never accesses login data.
export async function launchInstaller(staged:StagedUpdate,restart:false|'foreground'|'background',pid=process.pid):Promise<void>{
 safeDirectory(staged.directory);if(!existsSync(staged.candidate)||!existsSync(staged.bundle))throw Error('待安装更新已被移动，请重新下载');
 const helper=path.join(staged.directory,'installer-'+randomUUID()),result=path.join(staged.directory,'install-result.json');
 if(existsSync(result)){if(lstatSync(result).isSymbolicLink())throw Error('安装结果路径无效');rmSync(result);}
 copyFileSync(path.join(__dirname,'update-installer'),helper);chmodSync(helper,0o700);
 const child=spawn(helper,[String(pid),staged.bundle,staged.candidate,result,staged.version,restart==='background'?'background':restart?'yes':'no',staged.fromVersion],{detached:true,stdio:'ignore'});
 await new Promise<void>((resolve,reject)=>{child.once('spawn',resolve);child.once('error',reject);});child.unref();
}
export function readInstallResult(directory:string):boolean|undefined{const file=path.join(directory,'install-result.json');if(!existsSync(file))return;const stat=lstatSync(file);if(stat.isSymbolicLink()||!stat.isFile()||stat.size>1024)throw Error('安装记录无效');return JSON.parse(readFileSync(file,'utf8')).ok===true;}
