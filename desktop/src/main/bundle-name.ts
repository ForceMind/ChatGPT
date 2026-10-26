import {copyFileSync,chmodSync,existsSync,lstatSync,readFileSync,realpathSync} from 'node:fs';
import path from 'node:path';
import {spawn} from 'node:child_process';
import {randomUUID} from 'node:crypto';
import {installedBundle,verifyBundle} from './update-install';
import {safeDirectory} from './update-download';
/** Run before opening browser partitions. Renaming the signed bundle never changes user data. */
export async function migrateBundleName(executable:string,version:string,dataDir:string,background:boolean):Promise<boolean>{
 let source:string;try{source=installedBundle(executable);}catch{return false;}
 if(path.basename(source)!=='ChatGPT Profiles.app')return false;
 const target=path.join(path.dirname(source),'ChatGPT Studio.app');
 if(lstatSync(target,{throwIfNoEntry:false}))throw Error('应用程序目录已有 ChatGPT Studio，已保留两份应用。请在 Finder 中核对后保留需要的版本。');
 if(realpathSync(source)!==source)throw Error('应用路径需要在 Finder 中手动更名，账号数据保持原样。');
 await verifyBundle(source,version);
 const directory=safeDirectory(path.join(dataDir,'updates')),output=path.join(directory,'name-migration-result.json');
 if(existsSync(output)){
  const stat=lstatSync(output);if(stat.isSymbolicLink()||!stat.isFile()||stat.size>1024)throw Error('应用名称迁移记录无效，原应用和账号数据已保留。');
  if(JSON.parse(readFileSync(output,'utf8')).ok!==true)throw Error('上次应用更名未完成。可以继续使用，或退出后在 Finder 将应用更名为 ChatGPT Studio。');
  return false;
 }
 const helper=path.join(directory,'name-installer-'+randomUUID());
 copyFileSync(path.join(__dirname,'update-installer'),helper);chmodSync(helper,0o700);
 const child=spawn(helper,['--rename-bundle',String(process.pid),source,target,version,output,background?'background':'yes'],{detached:true,stdio:'ignore'});
 await new Promise<void>((resolve,reject)=>{child.once('spawn',resolve);child.once('error',reject);});child.unref();return true;
}
