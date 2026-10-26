import {versionParts,type UpdateFile} from './updates';
export const WINDOWS_UPDATE_BASE='https://chatgpt.xincreates.com/updates/windows/';
export type WindowsArtifact=UpdateFile&{sha512:string;kind:'installer'|'zip'};
export type WindowsRelease={version:string;publishedAt:string;notes:string;installer:WindowsArtifact;zip:WindowsArtifact};
const object=(value:unknown):value is Record<string,unknown>=>!!value&&typeof value==='object'&&!Array.isArray(value);
const bad=():never=>{throw Error('Windows 更新清单无效，当前应用与账号数据已保留。');};
export function windowsRelease(value:unknown):WindowsRelease{
 if(!object(value)||value.schema!==1||value.appId!=='com.forcemind.chatgpt.profiles'||value.platform!=='win32'||value.architecture!=='x64'||typeof value.version!=='string'||!versionParts(value.version)||typeof value.releaseDate!=='string'||!Number.isFinite(Date.parse(value.releaseDate))||typeof value.releaseNotes!=='string'||value.releaseNotes.length>5000||!Array.isArray(value.files)||value.files.length!==2||value.packages!==undefined)return bad();
 const files:Partial<Record<'installer'|'zip',WindowsArtifact>>={};
 for(const file of value.files){
  if(!object(file)||typeof file.url!=='string'||!Number.isSafeInteger(file.size)||(file.size as number)<=0||(file.size as number)>1024*1024*1024||typeof file.sha256!=='string'||!/^[a-f0-9]{64}$/.test(file.sha256)||typeof file.sha512!=='string'||!/^[A-Za-z0-9+/]{86}==$/.test(file.sha512))return bad();
  const kind=file.url.endsWith('-setup.exe')?'installer':file.url.endsWith('.zip')?'zip':undefined;if(!kind||files[kind])return bad();
  const suffix=kind==='installer'?'-setup.exe':'.zip';
  const valid=['Studio','Profiles'].some(name=>file.url===`https://github.com/ForceMind/ChatGPT/releases/download/v${value.version}/ChatGPT-${name}-${value.version}-windows-x64${suffix}`);if(!valid)return bad();
  files[kind]={url:file.url,size:file.size as number,sha256:file.sha256,sha512:file.sha512,kind};
 }
 if(!files.installer||!files.zip)return bad();
 return{version:value.version,publishedAt:value.releaseDate,notes:value.releaseNotes,installer:files.installer,zip:files.zip};
}
