export type UpdateFile={url:string;size:number;sha256:string};
export type UpdateManifest={schema:1;version:string;publishedAt:string;notes:string;files:Partial<Record<'arm64'|'x64',UpdateFile>>};
export type UpdateStatus={phase:'idle'|'checking'|'available'|'downloading'|'ready'|'error';version?:string;progress?:number;message?:string};
const versionPattern=/^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?$/;
export function versionParts(value:string):(number|string)[]|undefined{
 if(value.length>100)return;const m=versionPattern.exec(value);if(!m)return;
 const core=m.slice(1,4).map(Number);if(core.some(n=>!Number.isSafeInteger(n)))return;
 const pre=m[4]?.split('.')||[];if(pre.some(p=>/^\d+$/.test(p)&&(!/^(0|[1-9]\d*)$/.test(p)||!Number.isSafeInteger(Number(p)))))return;
 return [...core,...pre.map(p=>/^\d+$/.test(p)?Number(p):p)];
}
export function newerVersion(candidate:string,current:string):boolean{
 const a=versionParts(candidate),b=versionParts(current);if(!a||!b)return false;
 for(let i=0;i<3;i++)if(a[i]!==b[i])return(a[i] as number)>(b[i] as number);
 if(a.length===3||b.length===3)return a.length===3&&b.length>3;
 for(let i=3;i<Math.max(a.length,b.length);i++){
  if(a[i]===undefined)return false;if(b[i]===undefined)return true;if(a[i]===b[i])continue;
  if(typeof a[i]!==typeof b[i])return typeof a[i]==='string';return a[i]>b[i];
 }return false;
}
export function validManifest(value:unknown):value is UpdateManifest{
 if(!value||typeof value!=='object')return false;const m=value as UpdateManifest;
 if(m.schema!==1||typeof m.version!=='string'||!versionParts(m.version)||typeof m.publishedAt!=='string'||!Number.isFinite(Date.parse(m.publishedAt))||typeof m.notes!=='string'||m.notes.length>5000||!m.files||typeof m.files!=='object'||Array.isArray(m.files))return false;
 const files=Object.entries(m.files);if(!files.length||files.some(([arch])=>!['arm64','x64'].includes(arch)))return false;
 return files.every(([arch,f])=>!!f&&typeof f==='object'&&typeof f.url==='string'&&f.url===`https://github.com/ForceMind/ChatGPT/releases/download/v${m.version}/ChatGPT-Profiles-${m.version}-mac-${arch}.zip`&&Number.isSafeInteger(f.size)&&f.size>0&&f.size<=1024*1024*1024&&typeof f.sha256==='string'&&/^[a-f0-9]{64}$/.test(f.sha256));
}
const shanghai=new Intl.DateTimeFormat('en-CA',{timeZone:'Asia/Shanghai',year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',hourCycle:'h23'});
export function shanghaiDay(now:number):{day:string;afterNoon:boolean}{
 const parts=Object.fromEntries(shanghai.formatToParts(now).map(p=>[p.type,p.value]));
 return{day:`${parts.year}-${parts.month}-${parts.day}`,afterNoon:Number(parts.hour)>=12};
}
export function noonDue(now:number,lastDay:string):boolean{const today=shanghaiDay(now);return today.afterNoon&&today.day>lastDay;}
export function acceleratedURL(original:string):string{return 'https://gh-proxy.org/'+original;}
