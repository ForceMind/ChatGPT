import fs from 'node:fs';import path from 'node:path';import crypto from 'node:crypto';import assert from 'node:assert/strict';import {execFileSync} from 'node:child_process';
import {windowsUpdateFeed} from './windows-update-feed.mjs';
const repo=process.env.GITHUB_REPOSITORY,tag=process.env.RELEASE_TAG;
assert.equal(repo,'ForceMind/ChatGPT');
const version=JSON.parse(fs.readFileSync('desktop/package.json')).version;assert.equal(tag,'v'+version);
const c=JSON.parse(fs.readFileSync(`docs/releases/${version}-desktop-candidate.json`));
const m=JSON.parse(fs.readFileSync(`docs/releases/${version}.json`));
assert.equal(c.status,'verified-candidate-not-public');assert.equal(m.version,version);
const gh=args=>JSON.parse(execFileSync('gh',args,{encoding:'utf8'}));
const run=id=>gh(['api',`repos/${repo}/actions/runs/${id}`]);
const macRun=run(c.macos.runId),winRun=run(c.windows.packagingRunId),acceptRun=run(c.windows.acceptance.runId);
for(const [r,sha] of [[macRun,c.macos.sourceCommit],[acceptRun,c.windows.acceptance.sourceCommit]]){assert.equal(r.status,'completed');assert.equal(r.conclusion,'success');assert.equal(r.head_sha,sha);}
assert.equal(winRun.status,'completed');assert.equal(winRun.head_sha,c.windows.binarySourceCommit);
const steps=gh(['api',`repos/${repo}/actions/runs/${winRun.id}/jobs`]).jobs.flatMap(j=>j.steps);
for(const name of ['Typecheck, state tests and build','Windows isolation and interface acceptance','Native first-script language and login popup acceptance','Package Windows x64','Install, launch and uninstall acceptance'])assert(steps.some(s=>s.name===name&&s.conclusion==='success'),name);
assert(steps.filter(s=>s.conclusion==='failure').every(s=>s.name==='Actual NSIS upgrade with shipping updater modules'));
const inputs=['desktop/src','desktop/assets','desktop/resources','desktop/package.json','desktop/package-lock.json','desktop/tsconfig.json','desktop/scripts/build.mjs','desktop/scripts/icon.mjs','desktop/scripts/package-mac.mjs','desktop/scripts/package-win.mjs','desktop/scripts/windows-update-feed.mjs','LICENSE'];
for(const sha of [c.macos.sourceCommit,c.windows.binarySourceCommit,c.windows.acceptance.sourceCommit,m.sourceCommit])execFileSync('git',['diff','--exit-code',sha,'HEAD','--',...inputs],{stdio:'inherit'});
const root=path.resolve('promotion'),out=path.join(root,'out'),files=[];fs.mkdirSync(out,{recursive:true});
function walk(dir){for(const e of fs.readdirSync(dir,{withFileTypes:true})){if(e.name==='out')continue;assert(!e.isSymbolicLink());const file=path.join(dir,e.name);if(e.isDirectory())walk(file);else if(e.isFile())files.push(file);}}walk(root);
const find=name=>{const items=files.filter(f=>path.basename(f)===name);assert.equal(items.length,1,'Missing/duplicate '+name);return items[0];};
const read=name=>JSON.parse(fs.readFileSync(find(name),'utf8').replace(/^\uFEFF/,''));
const mac=read('mac-release-info.json'),win=read('windows-build-info.json'),upgrade=read('windows-upgrade.json'),retention=read('windows-native-retention.json');
assert.equal(mac.commit,c.macos.sourceCommit);assert.equal(win.sourceCommit,c.windows.binarySourceCommit);assert.equal(mac.version,version);assert.equal(win.version,version);
assert.equal(upgrade.sourceCommit,c.windows.acceptance.sourceCommit);for(const key of ['installed','identityRetained','launch','temporaryCleanup'])assert.equal(upgrade[key],true);
assert.equal(upgrade.prepared.newVersion,version);assert.equal(upgrade.prepared.oldVersion,'3.0.0-alpha.18');
for(const key of ['passed','stable','active'])assert.equal(retention.retention[key],true);assert(retention.retention.durationMs>=330000);
const before=retention.retention.before,after=retention.retention.after;assert.equal(before.length,8);assert.equal(after.length,8);
for(const item of before){const next=after.find(n=>n.id===item.id);assert(next);assert.equal(next.state.instance,item.state.instance);assert.equal(next.state.discarded,false);assert.equal(next.state.draft,item.state.draft);}
assert.equal(m.artifacts.length,5);assert.equal(new Set(m.artifacts.map(a=>a.file)).size,5);
async function digest(file,algorithm='sha256',encoding='hex'){const h=crypto.createHash(algorithm);for await(const chunk of fs.createReadStream(file))h.update(chunk);return h.digest(encoding);}
for(const a of m.artifacts){
 assert.equal(path.basename(a.file),a.file);assert(a.file.startsWith(`ChatGPT-Studio-${version}-`)||a.file===`ChatGPT-Profiles-${version}-mac-arm64.zip`);
 const source=find(a.file),info=a.platform==='macos'?mac:win;assert.equal(fs.statSync(source).size,a.bytes);assert.equal(await digest(source),a.sha256);
 assert(info.artifacts.some(i=>i.file===a.file&&i.bytes===a.bytes&&i.sha256===a.sha256));
 if(a.sha512)assert.equal(await digest(source,'sha512','base64'),a.sha512);
 fs.copyFileSync(source,path.join(out,a.file));
}
const exe=m.artifacts.find(a=>a.file.endsWith('.exe'));assert.equal(upgrade.prepared.installerSHA256,exe.sha256);
const feed=windowsUpdateFeed(win,m.windowsNotes,m.publishedAt);assert.deepEqual(feed,JSON.parse(fs.readFileSync('site/updates/windows/alpha.yml','utf8')));
fs.writeFileSync(path.join(out,'windows-update-feed.yml'),JSON.stringify(feed,null,2)+'\n');
for(const name of ['mac-release-info.json','mac-update-verification.json','mac-recovery-verification.json','mac-ui-verification.json','windows-build-info.json','windows-upgrade.json','windows-native-retention.json'])fs.copyFileSync(find(name),path.join(out,name));
fs.writeFileSync(path.join(out,'release-info.json'),JSON.stringify({...m,publisherCommit:process.env.GITHUB_SHA,verificationRuns:[macRun,acceptRun].map(r=>({id:r.id,sourceCommit:r.head_sha,conclusion:r.conclusion})),windowsOriginalPackagingConclusion:winRun.conclusion},null,2)+'\n');
const all=[];for(const name of fs.readdirSync(out).sort())all.push({file:name,bytes:fs.statSync(path.join(out,name)).size,sha256:await digest(path.join(out,name))});
fs.writeFileSync(path.join(out,'SHA256SUMS.txt'),all.map(a=>`${a.sha256}  ${a.file}\n`).join(''));all.push({file:'SHA256SUMS.txt',bytes:fs.statSync(path.join(out,'SHA256SUMS.txt')).size,sha256:await digest(path.join(out,'SHA256SUMS.txt'))});
if(process.env.INSPECT_ONLY!=='true'){
 const release=()=>gh(['release','view',tag,'--repo',repo,'--json','id,isDraft,targetCommitish']);let draft=release();assert(draft.isDraft);assert.equal(draft.targetCommitish,process.env.GITHUB_SHA);
 const id=gh(['api',`repos/${repo}/releases?per_page=100`]).find(r=>r.tag_name===tag&&r.draft)?.id;assert(Number.isSafeInteger(id));
 const assets=()=>gh(['api',`repos/${repo}/releases/${id}`]).assets;let current=assets();assert(current.every(a=>all.some(x=>x.file===a.name)),'Unexpected draft asset');
 for(const a of all){assert(release().isDraft);const old=current.find(i=>i.name===a.file);if(old){assert.equal(old.size,a.bytes);assert.equal(old.digest,'sha256:'+a.sha256);}else execFileSync('gh',['release','upload',tag,path.join(out,a.file),'--repo',repo],{stdio:'inherit'});current=assets();}
 for(const a of all){const found=current.find(i=>i.name===a.file);assert(found);assert.equal(found.size,a.bytes);assert.equal(found.digest,'sha256:'+a.sha256);assert.equal(found.state,'uploaded');}
}
fs.writeFileSync(path.join(root,'promotion-verification.json'),JSON.stringify({version,sourceCommit:m.sourceCommit,publisherCommit:process.env.GITHUB_SHA,verifiedAt:new Date().toISOString(),draftVerified:process.env.INSPECT_ONLY!=='true',originalPackagesRebuilt:false,macRun:macRun.id,windowsPackagingRun:winRun.id,windowsAcceptanceRun:acceptRun.id,shippingInputsUnchanged:true,acceptanceReportVerified:true,assets:all},null,2)+'\n');
console.log('Original desktop packages and native acceptance verified. Draft uploads do not publish the Release.');
