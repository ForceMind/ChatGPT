import {readFileSync,writeFileSync} from 'node:fs';
import path from 'node:path';
import {pathToFileURL} from 'node:url';
/** JSON is valid YAML for the generic updater; both hashes come from the same CI bytes. */
export function windowsUpdateFeed(info,notes,date){
 if(info.platform!=='win32'||info.architecture!=='x64'||!/^3\.\d+\.\d+-alpha\.[1-9]\d*$/.test(info.version)||!Array.isArray(info.artifacts)||typeof notes!=='string'||notes.length>5000||!Number.isFinite(Date.parse(date)))throw Error('Invalid Windows build metadata');
 const files=['-setup.exe','.zip'].map(suffix=>{
  const name=`ChatGPT-Studio-${info.version}-windows-x64${suffix}`,matches=info.artifacts.filter(a=>a.file===name);if(matches.length!==1)throw Error('Missing unique Windows package: '+name);const a=matches[0];
  if(!Number.isSafeInteger(a.bytes)||a.bytes<=0||a.bytes>1024**3||!/^[a-f0-9]{64}$/.test(a.sha256)||!/^[A-Za-z0-9+/]{86}==$/.test(a.sha512))throw Error('Invalid package digest');
  return{url:`https://github.com/ForceMind/ChatGPT/releases/download/v${info.version}/${name}`,size:a.bytes,sha256:a.sha256,sha512:a.sha512};
 });
 return{schema:1,appId:'com.forcemind.chatgpt.profiles',platform:'win32',architecture:'x64',version:info.version,releaseDate:date,releaseNotes:notes,files};
}
if(process.argv[1]&&import.meta.url===pathToFileURL(path.resolve(process.argv[1])).href){
 const [, ,input,output,notesFile]=process.argv;if(!input||!output||!notesFile)throw Error('Usage: windows-update-feed.mjs build-info.json output.yml notes.txt');
 const feed=windowsUpdateFeed(JSON.parse(readFileSync(input,'utf8')),readFileSync(notesFile,'utf8'),new Date().toISOString());writeFileSync(output,JSON.stringify(feed,null,2)+'\n');
}
