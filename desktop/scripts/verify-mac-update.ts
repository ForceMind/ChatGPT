/** Verify a real packaged ZIP in a disposable installation, never /Applications. */
import{createRequire}from'node:module';import{createHash}from'node:crypto';import{execFile,execFileSync,spawn}from'node:child_process';import{promisify}from'node:util';import{mkdtempSync,mkdirSync,realpathSync,readFileSync,writeFileSync,rmSync}from'node:fs';import{stat}from'node:fs/promises';import path from'node:path';import os from'node:os';import{fileURLToPath}from'node:url';
import{stageUpdate,verifyBundle}from'../src/main/update-install';
if(process.platform!=='darwin')throw Error('macOS only');
const desktop=fileURLToPath(new URL('..',import.meta.url)),pkg=JSON.parse(readFileSync(path.join(desktop,'package.json'),'utf8')),zip=path.join(desktop,'release',`ChatGPT-Profiles-${pkg.version}-mac-arm64.zip`),info=JSON.parse(readFileSync(path.join(desktop,'release/build-info.json'),'utf8')),root=mkdtempSync(path.join(realpathSync(os.tmpdir()),'profiles-full-update-')),run=promisify(execFile);
try{
 const current=path.join(root,'Installed.app'),updates=path.join(root,'updates');mkdirSync(path.join(current,'Contents/MacOS'),{recursive:true});mkdirSync(updates);
 execFileSync('xcrun',['clang','-arch','arm64','-x','c','-o',path.join(current,'Contents/MacOS/old-fixture'),'-'],{input:'int main(void){return 0;}'});
 writeFileSync(path.join(current,'Contents/Info.plist'),'<?xml version="1.0"?><plist version="1.0"><dict><key>CFBundleIdentifier</key><string>com.forcemind.chatgpt.profiles</string><key>CFBundleExecutable</key><string>old-fixture</string><key>CFBundleShortVersionString</key><string>3.0.0-alpha.11</string><key>CFBundleVersion</key><string>1</string><key>CFBundlePackageType</key><string>APPL</string></dict></plist>');
 execFileSync('/usr/bin/codesign',['--force','--deep','--sign','-',current],{stdio:'pipe'});
 const expected=info.artifacts.find((f:{file:string})=>f.file===path.basename(zip));if(!expected)throw Error('missing receipt');
 const begin=Date.now(),staged=await stageUpdate(zip,{url:`https://github.com/ForceMind/ChatGPT/releases/download/v${pkg.version}/${path.basename(zip)}`,size:(await stat(zip)).size,sha256:expected.sha256},pkg.version,current,updates,'3.0.0-alpha.11');
 const require=createRequire(path.join(desktop,'package.json')),asar=require('@electron/asar'),helperBytes=asar.extractFile(path.join(realpathSync(info.appPath),'Contents/Resources/app.asar'),'dist/main/update-installer'),helper=path.join(updates,'installer');
 const copyScript=path.join(root,'copy-from-asar.cjs'),copyData=path.join(root,'copy-data');mkdirSync(copyData);
 writeFileSync(copyScript,`const {app}=require('electron'),fs=require('node:fs');const [source,destination,data]=process.argv.slice(2);app.setPath('userData',data);app.whenReady().then(()=>{fs.copyFileSync(source,destination);fs.chmodSync(destination,0o700);app.exit(0);}).catch(e=>{console.error(e.message);app.exit(1);});`);
 await run(path.join(desktop,'node_modules/electron/dist/Electron.app/Contents/MacOS/Electron'),[copyScript,path.join(realpathSync(info.appPath),'Contents/Resources/app.asar/dist/main/update-installer'),helper,copyData],{timeout:30000});
 if(!readFileSync(helper).equals(helperBytes))throw Error('Electron ASAR helper copy differs');await run('/usr/bin/codesign',['--verify','--strict',helper]);
 const sleeper=spawn('/bin/sleep',['0.3']),result=path.join(updates,'install-result.json');await run(helper,[String(sleeper.pid),staged.bundle,staged.candidate,result,pkg.version,'no','3.0.0-alpha.11'],{timeout:120000});
 if(JSON.parse(readFileSync(result,'utf8')).ok!==true)throw Error('replacement failed');await verifyBundle(current,pkg.version);
 const originalAsar=readFileSync(path.join(realpathSync(info.appPath),'Contents/Resources/app.asar')),installedAsar=readFileSync(path.join(current,'Contents/Resources/app.asar'));
 if(!originalAsar.equals(installedAsar))throw Error('payload mismatch');
 if(!readFileSync(path.join(staged.candidate,'Contents/Info.plist'),'utf8').includes('3.0.0-alpha.11'))throw Error('old backup missing');
 const receipt={version:pkg.version,verifiedAt:new Date().toISOString(),scope:'real packaged ZIP, disposable old bundle, no personal account data; no live ChatGPT upgrade acceptance',zipSHA256:expected.sha256,asarSHA256:createHash('sha256').update(installedAsar).digest('hex'),helperSHA256:createHash('sha256').update(helperBytes).digest('hex'),elapsedMs:Date.now()-begin,checks:['ZIP digest and identity','full bundle signature and architecture','Electron fs.copyFileSync from shipping ASAR','parent exit wait','atomic swap','installed payload byte identity','previous bundle preserved']};
 const out=path.join(desktop,'release/alpha12-verification');mkdirSync(out,{recursive:true});writeFileSync(path.join(out,'packaged-update.json'),JSON.stringify(receipt,null,2)+'\n');console.log(JSON.stringify(receipt));
}finally{rmSync(root,{recursive:true,force:true});}
