import {execFileSync} from 'node:child_process';
import {mkdtempSync,readFileSync,readdirSync,writeFileSync,mkdirSync,rmSync} from 'node:fs';
import {createHash} from 'node:crypto';import {createRequire} from 'node:module';
import {tmpdir} from 'node:os';import path from 'node:path';import {fileURLToPath} from 'node:url';
const root=fileURLToPath(new URL('..',import.meta.url)),require=createRequire(import.meta.url),asar=require('@electron/asar');
const build=JSON.parse(readFileSync(path.join(root,'release/build-info.json'),'utf8'));
const plist=path.join(build.appPath,'Contents/Info.plist');
const field=name=>execFileSync('/usr/libexec/PlistBuddy',['-c','Print :'+name,plist],{encoding:'utf8'}).trim();
if(field('CFBundleDisplayName')!=='ChatGPT Studio'||field('CFBundleName')!=='ChatGPT Studio'||field('CFBundleIdentifier')!=='com.forcemind.chatgpt.profiles'||path.basename(build.appPath)!=='ChatGPT Studio.app')throw Error('Branding or persistent bundle identity mismatch');
const archive=path.join(build.appPath,'Contents/Resources/app.asar');let count=0;
function compare(folder){for(const entry of readdirSync(folder,{withFileTypes:true})){if(entry.name.startsWith('._'))continue;const file=path.join(folder,entry.name);if(entry.isDirectory())compare(file);else{const relative=path.relative(root,file);if(!readFileSync(file).equals(asar.extractFile(archive,relative)))throw Error('打包文件与源码构建不一致: '+relative);count++;}}}
compare(path.join(root,'dist'));
execFileSync('/usr/bin/codesign',['--verify','--deep','--strict',build.appPath],{stdio:'pipe'});
const temp=mkdtempSync(path.join(tmpdir(),'profiles-native-recovery-'));
try{
 const script=path.join(temp,'check.cjs'),report=path.join(temp,'result.json');mkdirSync(path.join(temp,'data'));
 writeFileSync(script,`const {app}=require('electron'),fs=require('node:fs');app.commandLine.appendSwitch('use-mock-keychain');app.setPath('userData',process.argv[4]);app.whenReady().then(async()=>{const n=require(process.argv[2]),caps=n.capabilities();if(caps.signed)throw Error('Isolated runtime unexpectedly has product key entitlements');let refused=false;try{await n.unlock('a'.repeat(64),true)}catch(e){refused=String(e.message).includes('-34018')}if(!refused)throw Error('Unsigned key access did not fail closed');fs.writeFileSync(process.argv[3],JSON.stringify({capabilities:caps,unsignedKeyRefused:true}));app.exit(0);}).catch(e=>{console.error(e.message);app.exit(1)});`);
 execFileSync(path.join(root,'node_modules/electron/dist/Electron.app/Contents/MacOS/Electron'),[script,path.join(archive,'dist/main/recovery-protection.node'),report,path.join(temp,'data')],{timeout:30000,stdio:'pipe'});
 const native=JSON.parse(readFileSync(report,'utf8'));
 const receipt={version:build.version,verifiedAt:new Date().toISOString(),scope:'isolated Electron, actual packaged ASAR/native module; no personal profiles or Keychain items; signed Touch ID key creation not verified',branding:{displayName:field('CFBundleDisplayName'),internalName:field('CFBundleName'),bundleId:field('CFBundleIdentifier'),applicationBundle:path.basename(build.appPath),legacyUpdateRoot:'ChatGPT Profiles.app',storageIdentity:'ChatGPT Profiles'},payloadFiles:count,asarSHA256:createHash('sha256').update(readFileSync(archive)).digest('hex'),...native,checks:['all dist payload bytes match','deep strict code signature','packaged native module loads from ASAR','ad-hoc key creation refused before SecItem access']};
 const out=path.join(root,'release/verification');mkdirSync(out,{recursive:true});writeFileSync(path.join(out,'recovery-package.json'),JSON.stringify(receipt,null,2)+'\n');console.log(JSON.stringify(receipt,null,2));
}finally{rmSync(temp,{recursive:true,force:true});}
