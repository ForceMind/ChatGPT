/** Verify shipping canonical and legacy update ZIPs without personal account data. */
import{createRequire}from'node:module';import{createHash}from'node:crypto';import{execFile,execFileSync,spawn}from'node:child_process';import{promisify}from'node:util';import{mkdtempSync,mkdirSync,realpathSync,readFileSync,writeFileSync,rmSync,existsSync}from'node:fs';import{stat}from'node:fs/promises';import path from'node:path';import os from'node:os';import{fileURLToPath}from'node:url';
import{stageUpdate,verifyBundle}from'../src/main/update-install';
if(process.platform!=='darwin')throw Error('macOS only');
const desktop=fileURLToPath(new URL('..',import.meta.url)),pkg=JSON.parse(readFileSync(path.join(desktop,'package.json'),'utf8')),info=JSON.parse(readFileSync(path.join(desktop,'release/build-info.json'),'utf8')),root=mkdtempSync(path.join(realpathSync(os.tmpdir()),'studio-full-update-')),run=promisify(execFile);
const require=createRequire(path.join(desktop,'package.json')),asar=require('@electron/asar'),originalAsar=readFileSync(path.join(realpathSync(info.appPath),'Contents/Resources/app.asar'));
const hash=(b:Buffer)=>createHash('sha256').update(b).digest('hex');
const field=(bundle:string,key:string)=>execFileSync('/usr/libexec/PlistBuddy',['-c','Print:'+key,path.join(bundle,'Contents/Info.plist')],{encoding:'utf8'}).trim();
function fixture(directory:string,name:string){const current=path.join(directory,name+'.app');mkdirSync(path.join(current,'Contents/MacOS'),{recursive:true});execFileSync('xcrun',['clang','-arch','arm64','-x','c','-o',path.join(current,'Contents/MacOS/old-fixture'),'-'],{input:'int main(void){return 0;}'});writeFileSync(path.join(current,'Contents/Info.plist'),'<?xml version="1.0"?><plist version="1.0"><dict><key>CFBundleIdentifier</key><string>com.forcemind.chatgpt.profiles</string><key>CFBundleExecutable</key><string>old-fixture</string><key>CFBundleName</key><string>ChatGPT Profiles</string><key>CFBundleShortVersionString</key><string>3.0.0-alpha.18</string><key>CFBundleVersion</key><string>1</string><key>CFBundlePackageType</key><string>APPL</string></dict></plist>');execFileSync('/usr/bin/codesign',['--force','--deep','--sign','-',current],{stdio:'pipe'});return current;}
try{
 const begin=Date.now(),helperBytes=asar.extractFile(path.join(realpathSync(info.appPath),'Contents/Resources/app.asar'),'dist/main/update-installer'),helper=path.join(root,'installer');
 const copyScript=path.join(root,'copy-from-asar.cjs'),copyData=path.join(root,'copy-data');mkdirSync(copyData);
 writeFileSync(copyScript,`const {app}=require('electron'),fs=require('node:fs');app.commandLine.appendSwitch('use-mock-keychain');const [source,destination,data]=process.argv.slice(2);app.setPath('userData',data);app.whenReady().then(()=>{fs.copyFileSync(source,destination);fs.chmodSync(destination,0o700);app.exit(0);}).catch(e=>{console.error(e.message);app.exit(1);});`);
 await run(path.join(desktop,'node_modules/electron/dist/Electron.app/Contents/MacOS/Electron'),[copyScript,path.join(realpathSync(info.appPath),'Contents/Resources/app.asar/dist/main/update-installer'),helper,copyData],{timeout:30000});
 if(!readFileSync(helper).equals(helperBytes))throw Error('Electron ASAR helper copy differs');await run('/usr/bin/codesign',['--verify','--strict',helper]);
 const legacyZip=process.env.STUDIO_LEGACY_UPDATE_ZIP;if(!legacyZip)throw Error('Pinned alpha.18 package required for backwards compatibility acceptance');
 if(hash(readFileSync(legacyZip))!=='b270a9ff763345205ade0b16a20599acf435e1dc2904273f7b95eadd1c89dccb')throw Error('alpha.18 package digest mismatch');
 const oldDirectory=path.join(root,'alpha18');await run('/usr/bin/ditto',['-x','-k',legacyZip,oldDirectory],{timeout:180000});
 const legacyHelper=path.join(root,'legacy-installer'),legacyHelperBytes=asar.extractFile(path.join(oldDirectory,'ChatGPT Profiles.app/Contents/Resources/app.asar'),'dist/main/update-installer');
 if(hash(legacyHelperBytes)!=='06f93c1c549a18a28f65a9d96f361fb5491d3c37db620de89007bf19b47e62aa')throw Error('Pinned alpha.18 helper differs');
 writeFileSync(legacyHelper,legacyHelperBytes,{mode:0o700});await run('/usr/bin/codesign',['--verify','--strict',legacyHelper]);
 const cases=[];
 for(const compatibility of [false,true]){
  const dir=path.join(root,compatibility?'legacy':'canonical');mkdirSync(dir);const current=fixture(dir,compatibility?'ChatGPT Profiles':'ChatGPT Studio'),updates=path.join(dir,'updates');mkdirSync(updates);
  const data=path.join(dir,'account-data.json'),dataBytes=Buffer.from('{"fixture":"existing account identity"}');writeFileSync(data,dataBytes);
  const file=`ChatGPT-${compatibility?'Profiles':'Studio'}-${pkg.version}-mac-arm64.zip`,zip=path.join(desktop,'release',file),expected=info.artifacts.find((f:{file:string})=>f.file===file);if(!expected)throw Error('Missing archive receipt');
  const listing=execFileSync('/usr/bin/unzip',['-Z1',zip],{encoding:'utf8',maxBuffer:8*1024*1024}).split('\n').filter(Boolean);
  if(compatibility&&listing.some(n=>!n.startsWith('ChatGPT Profiles.app/')||n.startsWith('/')||n.split('/').includes('..')||n.includes('\\')))throw Error('Legacy alpha.18 ZIP allowlist rejects compatibility archive');
  const staged=await stageUpdate(zip,{url:`https://github.com/ForceMind/ChatGPT/releases/download/v${pkg.version}/${file}`,size:(await stat(zip)).size,sha256:expected.sha256},pkg.version,current,updates,'3.0.0-alpha.18');
  const sleeper=spawn('/bin/sleep',['0.3']),result=path.join(updates,'install-result.json');await run(compatibility?legacyHelper:helper,[String(sleeper.pid),staged.bundle,staged.candidate,result,pkg.version,'no','3.0.0-alpha.18'],{timeout:120000});
  if(JSON.parse(readFileSync(result,'utf8')).ok!==true)throw Error('Replacement failed');await verifyBundle(current,pkg.version);
  let installed=current;
  if(compatibility){installed=path.join(dir,'ChatGPT Studio.app');const leaving=spawn('/bin/sleep',['0.3']);await run(helper,['--rename-bundle',String(leaving.pid),current,installed,pkg.version,path.join(updates,'name-migration-result.json'),'no'],{timeout:120000});if(existsSync(current))throw Error('Legacy visible path still exists after rename');}
  await verifyBundle(installed,pkg.version);
  const installedAsar=readFileSync(path.join(installed,'Contents/Resources/app.asar'));if(!originalAsar.equals(installedAsar))throw Error('Installed payload differs');
  if(field(installed,'CFBundleName')!=='ChatGPT Studio'||field(installed,'CFBundleExecutable')!=='ChatGPT Studio'||field(installed,'CFBundleDisplayName')!=='ChatGPT Studio')throw Error('System-visible branding differs');
  if(!readFileSync(path.join(staged.candidate,'Contents/Info.plist'),'utf8').includes('3.0.0-alpha.18')||!readFileSync(data).equals(dataBytes))throw Error('Previous bundle or independent account data changed');
  cases.push({archive:file,zipSHA256:expected.sha256,legacyAlpha18Installer:compatibility,installedBundle:path.basename(installed),systemName:field(installed,'CFBundleName'),executable:field(installed,'CFBundleExecutable'),previousBundlePreserved:true,accountDataUnchanged:true});
 }
 const receipt={version:pkg.version,verifiedAt:new Date().toISOString(),scope:'shipping ZIPs, pinned alpha.18 helper and disposable old bundles; fixture account data only, no personal Keychain or live ChatGPT acceptance',asarSHA256:hash(originalAsar),helperSHA256:hash(helperBytes),legacyAlpha18HelperSHA256:hash(legacyHelperBytes),elapsedMs:Date.now()-begin,cases,checks:['canonical and legacy ZIP digests and signatures','legacy alpha.18 fixed archive root','actual published alpha.18 native installer','parent exit wait and atomic swap','signed no-overwrite rename to Studio.app','installed payload byte identity','system name and executable','previous app and account fixture preserved']};
 const out=path.join(desktop,'release/verification');mkdirSync(out,{recursive:true});writeFileSync(path.join(out,'packaged-update.json'),JSON.stringify(receipt,null,2)+'\n');console.log(JSON.stringify(receipt));
}finally{rmSync(root,{recursive:true,force:true});}
