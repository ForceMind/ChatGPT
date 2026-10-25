import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
const tag=process.env.RELEASE_TAG,repo=process.env.GITHUB_REPOSITORY;
const pkg=JSON.parse(fs.readFileSync('desktop/package.json','utf8'));
assert.equal(repo,'ForceMind/ChatGPT');assert.equal(tag,'v'+pkg.version);
assert.match(pkg.version,/^\d+\.\d+\.\d+-alpha\.\d+$/);
const manifest=JSON.parse(fs.readFileSync('docs/releases/'+pkg.version+'.json','utf8'));
const candidate=JSON.parse(fs.readFileSync('docs/releases/'+pkg.version+'-ci.json','utf8'));
assert.equal(manifest.version,pkg.version);assert.equal(candidate.version,pkg.version);
assert.match(manifest.sourceCommit,/^[a-f0-9]{40}$/);
const gh=args=>JSON.parse(execFileSync('gh',args,{encoding:'utf8'}));
const verifiedRuns=[];
assert.equal(manifest.artifacts.length,5);assert.equal(new Set(manifest.artifacts.map(a=>a.file)).size,5);
assert(Array.isArray(manifest.verificationRuns)&&manifest.verificationRuns.length>=6);
for(const check of manifest.verificationRuns){
 assert(Number.isSafeInteger(check.runId)&&check.runId>0);assert.match(check.sourceCommit,/^[a-f0-9]{40}$/);
 const run=gh(['api','repos/'+repo+'/actions/runs/'+check.runId]);assert.equal(run.status,'completed');assert.equal(run.conclusion,'success');assert.equal(run.head_sha,check.sourceCommit);
 verifiedRuns.push({kind:check.kind,id:run.id,conclusion:run.conclusion,sourceCommit:run.head_sha});
}
for(const platform of ['macos','windows','android']){assert.match(manifest.binarySourceCommits[platform],/^[a-f0-9]{40}$/);assert(verifiedRuns.some(r=>r.id===manifest.runs[platform]&&r.sourceCommit===manifest.binarySourceCommits[platform]));}
assert.equal(manifest.runs.macos,candidate.macos.package.runId);assert.equal(manifest.runs.windows,candidate.windows.runId);
const release=()=>gh(['release','view',tag,'--repo',repo,'--json','id,isDraft,tagName,targetCommitish']);
let draft=release();assert(draft.isDraft);assert.equal(draft.targetCommitish,manifest.sourceCommit);
const restRelease=gh(['api','repos/'+repo+'/releases?per_page=100']).find(r=>r.tag_name===tag);
assert(restRelease?.draft&&Number.isSafeInteger(restRelease.id));
const draftId=restRelease.id;
const desktopInputs=['desktop/src','desktop/assets','desktop/resources','desktop/package.json','desktop/package-lock.json','desktop/tsconfig.json','desktop/scripts/build.mjs','desktop/scripts/icon.mjs','desktop/scripts/package-mac.mjs','desktop/scripts/package-win.mjs'];
for(const platform of ['macos','windows'])execFileSync('git',['diff','--exit-code',manifest.binarySourceCommits[platform],'HEAD','--',...desktopInputs],{stdio:'inherit'});
execFileSync('git',['diff','--exit-code',manifest.binarySourceCommits.android,'HEAD','--','android/app/src/main','android/app/src/release','android/app/build.gradle','android/app/proguard-rules.pro','android/build.gradle','android/settings.gradle','android/gradle.properties','android/gradle'],{stdio:'inherit'});
const root=path.resolve('promotion'),out=path.join(root,'out');fs.mkdirSync(out,{recursive:true});
const files=[];
function walk(dir){for(const e of fs.readdirSync(dir,{withFileTypes:true})){if(e.name==='out')continue;const file=path.join(dir,e.name);assert(!e.isSymbolicLink());if(e.isDirectory())walk(file);else if(e.isFile())files.push(file);}}
walk(root);
const find=name=>{const found=files.filter(f=>path.basename(f)===name);assert.equal(found.length,1,'Missing or duplicate artifact '+name);return found[0];};
async function hash(file){const h=crypto.createHash('sha256');for await(const chunk of fs.createReadStream(file))h.update(chunk);return h.digest('hex');}
const mac=JSON.parse(fs.readFileSync(find('mac-release-info.json'),'utf8'));
const windows=JSON.parse(fs.readFileSync(find('windows-build-info.json'),'utf8'));
assert.equal(mac.version,pkg.version);assert.equal(mac.commit,manifest.binarySourceCommits.macos);
assert.equal(windows.version,pkg.version);assert.equal(windows.sourceCommit,manifest.binarySourceCommits.windows);
for(const item of manifest.artifacts){
 assert.equal(path.basename(item.file),item.file);assert(item.file.startsWith('ChatGPT-Profiles-'+pkg.version+'-'));
 assert.match(item.sha256,/^[a-f0-9]{64}$/);assert(item.bytes>0);
 if(item.file.endsWith('.apk'))continue; // Fixed-identity APK is already in the draft, signed locally.
 const source=find(item.file);assert.equal(fs.statSync(source).size,item.bytes);assert.equal(await hash(source),item.sha256);
 const info=item.platform==='macos'?mac:windows;assert(info.artifacts.some(a=>a.file===item.file&&a.bytes===item.bytes&&a.sha256===item.sha256));
 fs.copyFileSync(source,path.join(out,item.file));
}
for(const name of ['mac-release-info.json','mac-update-verification.json','mac-recovery-verification.json','mac-ui-verification.json','windows-build-info.json'])fs.copyFileSync(find(name),path.join(out,name));
const android=JSON.parse(fs.readFileSync('docs/releases/'+pkg.version+'-android.json','utf8'));
assert.equal(android.version,pkg.version);assert.equal(android.certificateSha256,'8b6eef928fded91f291ad954e20976b2cc6011b55e8cbec0152695fe1f05cf19');
assert.equal(android.sourceCommit,manifest.binarySourceCommits.android);assert.equal(android.buildRun,manifest.runs.android);
const apk=manifest.artifacts.find(a=>a.file.endsWith('.apk'));assert(apk);assert.deepEqual(android.artifact,{file:apk.file,bytes:apk.bytes,sha256:apk.sha256});
fs.writeFileSync(path.join(out,'android-build-info.json'),JSON.stringify(android,null,2)+'\n');
fs.writeFileSync(path.join(out,'release-info.json'),JSON.stringify(manifest,null,2)+'\n');
const receiptFiles=await Promise.all(fs.readdirSync(out).map(async file=>({file,bytes:fs.statSync(path.join(out,file)).size,sha256:await hash(path.join(out,file))})));
const all=[...receiptFiles,apk];
fs.writeFileSync(path.join(out,'SHA256SUMS.txt'),all.sort((a,b)=>a.file.localeCompare(b.file)).map(a=>a.sha256+'  '+a.file).join('\n')+'\n');
all.push({file:'SHA256SUMS.txt',bytes:fs.statSync(path.join(out,'SHA256SUMS.txt')).size,sha256:await hash(path.join(out,'SHA256SUMS.txt'))});
const assets=()=>gh(['api','repos/'+repo+'/releases/'+draftId]).assets;
let current=assets();
assert(current.every(a=>all.some(e=>e.file===a.name)),'Unknown pre-existing draft assets');
const signed=current.find(a=>a.name===apk.file);assert(signed&&signed.state==='uploaded');assert.equal(signed.size,apk.bytes);assert.equal(signed.digest,'sha256:'+apk.sha256);
for(const item of receiptFiles.concat(all.find(a=>a.file==='SHA256SUMS.txt'))){
 draft=release();assert(draft.isDraft,'Release must remain private during upload');
 const existing=current.find(a=>a.name===item.file);
 if(existing){assert.equal(existing.size,item.bytes);assert.equal(existing.digest,'sha256:'+item.sha256);continue;}
 execFileSync('gh',['release','upload',tag,path.join(out,item.file),'--repo',repo],{stdio:'inherit'});
 current=assets();
}
for(let n=0;n<10;n++){
 current=assets();
 if(all.every(e=>current.some(a=>a.name===e.file&&a.size===e.bytes&&a.digest==='sha256:'+e.sha256&&a.state==='uploaded')))break;
 await new Promise(r=>setTimeout(r,3000));
}
for(const e of all){const a=current.find(a=>a.name===e.file);assert(a,'Missing '+e.file);assert.equal(a.size,e.bytes);assert.equal(a.digest,'sha256:'+e.sha256);assert.equal(a.state,'uploaded');}
fs.writeFileSync(path.join(root,'promotion-verification.json'),JSON.stringify({version:pkg.version,sourceCommit:manifest.sourceCommit,publisherCommit:process.env.GITHUB_SHA,verifiedAt:new Date().toISOString(),draftVerified:true,verifiedRuns,assets:all,desktopBinarySource:'existing validated Actions artifacts, not rebuilt',privateKeyExported:false},null,2)+'\n');
console.log('Draft verified; all asset sizes and GitHub SHA-256 digests match. Publication is a separate explicit step.');
