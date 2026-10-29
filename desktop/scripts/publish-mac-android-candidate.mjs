// Promote already tested packages. No compilation, signing or browser execution.
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';

const repo=process.env.GITHUB_REPOSITORY,tag=process.env.RELEASE_TAG;
const read=file=>JSON.parse(fs.readFileSync(file,'utf8'));
const version=read('desktop/package.json').version;
assert.equal(repo,'ForceMind/ChatGPT');assert.equal(tag,'v'+version);
assert.match(version,/^\d+\.\d+\.\d+-alpha\.\d+$/);
const manifest=read('docs/releases/'+version+'.json');
const macCandidate=read('docs/releases/'+version+'-macos-candidate.json');
const android=read('docs/releases/'+version+'-android-signed-candidate.json');
assert.equal(manifest.version,version);assert.equal(macCandidate.version,version);assert.equal(android.version,version);
assert.match(manifest.sourceCommit,/^[a-f0-9]{40}$/);
assert.deepEqual(Object.keys(manifest.binarySourceCommits).sort(),['android','macos']);
assert.equal(manifest.binarySourceCommits.macos,macCandidate.sourceCommit);
assert.equal(manifest.binarySourceCommits.android,android.source);
assert.equal(manifest.runs.android,android.ci.run);
assert.equal(manifest.artifacts.length,4);
assert.equal(new Set(manifest.artifacts.map(a=>a.file)).size,4);
const gh=args=>JSON.parse(execFileSync('gh',args,{encoding:'utf8'}));
const release=()=>{const matches=gh(['api','repos/'+repo+'/releases?per_page=100']).filter(r=>r.tag_name===tag);assert.equal(matches.length,1);return matches[0];};
const draft=release();
assert(draft.draft);assert.equal(draft.target_commitish,manifest.sourceCommit);
const verifiedRuns=[];
for(const check of manifest.verificationRuns){
 assert(Number.isSafeInteger(check.runId)&&check.runId>0);assert.match(check.sourceCommit,/^[a-f0-9]{40}$/);
 const run=gh(['api','repos/'+repo+'/actions/runs/'+check.runId]);
 assert.equal(run.status,'completed');assert.equal(run.conclusion,'success');assert.equal(run.head_sha,check.sourceCommit);
 verifiedRuns.push({kind:check.kind,id:run.id,sourceCommit:run.head_sha,conclusion:run.conclusion});
}
for(const platform of ['macos','android'])assert(verifiedRuns.some(r=>r.id===manifest.runs[platform]&&r.sourceCommit===manifest.binarySourceCommits[platform]));
const desktopInputs=['desktop/src','desktop/assets','desktop/resources','desktop/package.json','desktop/package-lock.json','desktop/tsconfig.json','desktop/scripts/build.mjs','desktop/scripts/icon.mjs','desktop/scripts/package-mac.mjs'];
execFileSync('git',['diff','--exit-code',macCandidate.sourceCommit,'HEAD','--',...desktopInputs],{stdio:'inherit'});
const androidInputs=['android/app/src/main','android/app/src/release','android/app/build.gradle','android/app/proguard-rules.pro','android/build.gradle','android/settings.gradle','android/gradle.properties','android/gradle','android/gradlew','android/gradlew.bat','desktop/resources/capabilities'];
execFileSync('git',['diff','--exit-code',android.source,'HEAD','--',...androidInputs],{stdio:'inherit'});
const root=path.resolve('promotion'),out=path.join(root,'out'),files=[];
fs.mkdirSync(out,{recursive:true});
function walk(dir){for(const e of fs.readdirSync(dir,{withFileTypes:true})){if(e.name==='out')continue;const f=path.join(dir,e.name);assert(!e.isSymbolicLink());if(e.isDirectory())walk(f);else if(e.isFile())files.push(f);}}
walk(root);
const find=name=>{const matches=files.filter(f=>path.basename(f)===name);assert.equal(matches.length,1,'Missing/duplicate '+name);return matches[0];};
async function hash(file){const h=crypto.createHash('sha256');for await(const chunk of fs.createReadStream(file))h.update(chunk);return h.digest('hex');}
const mac=read(find('mac-release-info.json'));
assert.equal(mac.version,version);assert.equal(mac.commit,macCandidate.sourceCommit);
for(const item of manifest.artifacts){
 assert.equal(path.basename(item.file),item.file);assert.match(item.sha256,/^[a-f0-9]{64}$/);assert(item.bytes>0);
 if(item.platform==='macos'){
  assert(item.file.startsWith('ChatGPT-Studio-'+version+'-mac-')||item.file==='ChatGPT-Profiles-'+version+'-mac-arm64.zip');
  assert(mac.artifacts.some(a=>a.file===item.file&&a.bytes===item.bytes&&a.sha256===item.sha256));
  const f=find(item.file);assert.equal(fs.statSync(f).size,item.bytes);assert.equal(await hash(f),item.sha256);fs.copyFileSync(f,path.join(out,item.file));
 }else{
  assert.equal(item.platform,'android');assert.equal(item.file,android.artifact.file);
  assert.equal(item.bytes,android.artifact.bytes);assert.equal(item.sha256,android.artifact.sha256);
 }
}
const apk=manifest.artifacts.find(a=>a.platform==='android');assert(apk);
const initialAssets=release().assets;
assert(initialAssets.some(a=>a.name===apk.file&&a.size===apk.bytes&&a.digest==='sha256:'+apk.sha256&&a.state==='uploaded'));
execFileSync('gh',['release','download',tag,'--repo',repo,'--pattern',apk.file,'--dir',out],{stdio:'inherit'});
const signed=path.join(out,apk.file),unsigned=find('app-release-unsigned.apk');
assert.equal(await hash(signed),apk.sha256);assert.equal(fs.statSync(signed).size,apk.bytes);
assert.equal(await hash(unsigned),android.ci.unsignedApkSha256);
const toolsRoot=path.join(process.env.ANDROID_HOME||process.env.ANDROID_SDK_ROOT||'/usr/local/lib/android/sdk','build-tools');
const sdk=fs.readdirSync(toolsRoot).sort().reverse().map(v=>path.join(toolsRoot,v)).find(p=>fs.existsSync(path.join(p,'apksigner'))&&fs.existsSync(path.join(p,'aapt2')));assert(sdk);
const signature=execFileSync(path.join(sdk,'apksigner'),['verify','--verbose','--print-certs',signed],{encoding:'utf8'});
console.log('Android SDK signature verification:\n'+signature);
assert.equal(android.certificateSha256,'8b6eef928fded91f291ad954e20976b2cc6011b55e8cbec0152695fe1f05cf19');
assert.match(signature,/^Number of signers: 1\s*$/m);
const certs=[...signature.matchAll(/^(?:Signer #\d+|V3\.0 Signer:) certificate SHA-256 digest: ([a-f0-9]{64})\s*$/gm)].map(m=>m[1]);
assert.deepEqual(certs,[android.certificateSha256]);assert.match(signature,/Verified using v3 scheme[^\n]*: true/);
const badging=execFileSync(path.join(sdk,'aapt2'),['dump','badging',signed],{encoding:'utf8'});
assert.match(badging,/package: name='com\.forcemind\.chatgpt\.profiles' versionCode='3000022' versionName='3\.0\.0-alpha\.22'/);
assert(!badging.includes('application-debuggable'));assert.match(badging,/application-label:'ChatGPT Studio'/);
const payloadCheck=JSON.parse(execFileSync('python3',['-c',`
import zipfile,sys,json,hashlib
with zipfile.ZipFile(sys.argv[1]) as a,zipfile.ZipFile(sys.argv[2]) as b:
 an=a.namelist();bn=b.namelist()
 assert len(an)==len(set(an)) and len(bn)==len(set(bn))
 added=set(bn)-set(an)
 assert added=={'META-INF/CHATGPT-.RSA','META-INF/CHATGPT-.SF','META-INF/MANIFEST.MF'}
 assert set(an)<=set(bn) and len(an)==218
 for n in an:assert a.read(n)==b.read(n),n
 print(json.dumps({'unchangedOriginalEntries':len(an),'addedSigningEntries':sorted(added)}))
`,unsigned,signed],{encoding:'utf8'}));
for(const name of ['mac-release-info.json','mac-update-verification.json','mac-recovery-verification.json','mac-ui-verification.json']){
 const f=find(name);assert.equal(read(f).version,version);fs.copyFileSync(f,path.join(out,name));
}
fs.copyFileSync('docs/releases/'+version+'.json',path.join(out,'release-info.json'));
fs.copyFileSync('docs/releases/'+version+'-android-signed-candidate.json',path.join(out,'android-build-info.json'));
fs.copyFileSync('docs/releases/'+version+'-notes.md',path.join(out,'release-notes.md'));
const all=[];
for(const file of fs.readdirSync(out))all.push({file,bytes:fs.statSync(path.join(out,file)).size,sha256:await hash(path.join(out,file))});
fs.writeFileSync(path.join(out,'SHA256SUMS.txt'),all.sort((a,b)=>a.file.localeCompare(b.file)).map(a=>a.sha256+'  '+a.file).join('\n')+'\n');
all.push({file:'SHA256SUMS.txt',bytes:fs.statSync(path.join(out,'SHA256SUMS.txt')).size,sha256:await hash(path.join(out,'SHA256SUMS.txt'))});
assert(initialAssets.every(a=>all.some(e=>e.file===a.name)),'Unknown draft assets');
for(const item of all){
 const current=release();assert(current.draft);assert.equal(current.target_commitish,manifest.sourceCommit);
 const existing=current.assets.find(a=>a.name===item.file);
 if(existing){assert.equal(existing.size,item.bytes);assert.equal(existing.digest,'sha256:'+item.sha256);assert.equal(existing.state,'uploaded');continue;}
 execFileSync('gh',['release','upload',tag,path.join(out,item.file),'--repo',repo],{stdio:'inherit'});
}
const final=release();assert(final.draft);assert.equal(final.assets.length,all.length);
for(const item of all){const a=final.assets.find(a=>a.name===item.file);assert(a);assert.equal(a.size,item.bytes);assert.equal(a.digest,'sha256:'+item.sha256);assert.equal(a.state,'uploaded');}
fs.writeFileSync(path.join(root,'promotion-verification.json'),JSON.stringify({version,sourceCommit:manifest.sourceCommit,publisherCommit:process.env.GITHUB_SHA,verifiedAt:new Date().toISOString(),draftVerified:true,releaseId:final.id,verifiedRuns,assets:all,android:{certificateSha256:android.certificateSha256,signature,badging,payloadCheck},desktopInputsUnchanged:true,androidInputsUnchanged:true,noRebuild:true,noSigningKeyUploaded:true,noBrowserTests:true},null,2)+'\n');
console.log('All original packages, Android signature and draft asset digests verified. Publication remains a separate step.');
