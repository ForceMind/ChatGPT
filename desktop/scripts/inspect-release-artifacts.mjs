import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
const pkg=JSON.parse(fs.readFileSync('desktop/package.json','utf8'));
assert.equal(process.env.GITHUB_REPOSITORY,'ForceMind/ChatGPT');assert.equal(process.env.RELEASE_TAG,'v'+pkg.version);
const candidate=JSON.parse(fs.readFileSync('docs/releases/'+pkg.version+'-ci.json','utf8'));
const root=path.resolve('promotion'),files=[];
function walk(dir){for(const item of fs.readdirSync(dir,{withFileTypes:true})){const name=path.join(dir,item.name);assert(!item.isSymbolicLink());if(item.isDirectory())walk(name);else files.push(name);}}walk(root);
const find=name=>{const matches=files.filter(p=>path.basename(p)===name);assert.equal(matches.length,1,'Missing/duplicate '+name);return matches[0];};
async function digest(file){const hash=crypto.createHash('sha256');for await(const chunk of fs.createReadStream(file))hash.update(chunk);return hash.digest('hex');}
const mac=JSON.parse(fs.readFileSync(find('mac-release-info.json'),'utf8')),windows=JSON.parse(fs.readFileSync(find('windows-build-info.json'),'utf8'));
const verified=[];
for(const [platform,info,run,commit] of [['macos',mac,candidate.macos.package.runId,candidate.macos.package.sourceCommit],['windows',windows,candidate.windows.runId,candidate.windows.sourceCommit]]){
 assert.equal(info.version,pkg.version);assert.equal(info.commit||info.sourceCommit,commit);assert.match(commit,/^[a-f0-9]{40}$/);
 const status=JSON.parse(execFileSync('gh',['api','repos/ForceMind/ChatGPT/actions/runs/'+run],{encoding:'utf8'}));assert.equal(status.conclusion,'success');assert.equal(status.head_sha,commit);
 execFileSync('git',['diff','--exit-code',commit,'HEAD','--','desktop/src','desktop/assets','desktop/resources','desktop/scripts/build.mjs','desktop/scripts/icon.mjs','desktop/scripts/package-mac.mjs','desktop/scripts/package-win.mjs','desktop/package.json','desktop/package-lock.json','desktop/tsconfig.json'],{stdio:'inherit'});
 for(const artifact of info.artifacts){if(artifact.file.endsWith('.blockmap'))continue;const file=find(artifact.file);assert.equal(fs.statSync(file).size,artifact.bytes);assert.equal(await digest(file),artifact.sha256);verified.push({...artifact,platform,binarySourceCommit:commit,runId:run});}
}
fs.writeFileSync(path.join(root,'desktop-artifact-info.json'),JSON.stringify({version:pkg.version,verifiedAt:new Date().toISOString(),artifacts:verified,macSourceCommit:mac.commit,windowsSourceCommit:windows.sourceCommit,desktopProductInputsUnchanged:true,installerBodiesDownloadedLocally:false,scope:'Existing successful GitHub Actions binaries inspected and hashed inside hosted CI only; no Release writes.'},null,2)+'\n');
console.log('Desktop binary identity and hashes verified in CI; no Release writes.');
