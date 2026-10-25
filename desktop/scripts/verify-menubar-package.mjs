import {execFileSync} from 'node:child_process';
import {mkdtempSync,readFileSync,writeFileSync,mkdirSync,rmSync,copyFileSync} from 'node:fs';
import {createHash} from 'node:crypto';
import {createRequire} from 'node:module';
import {tmpdir} from 'node:os';
import path from 'node:path';
import {fileURLToPath} from 'node:url';

if(process.platform!=='darwin')throw Error('Menu-bar package validation requires macOS');
const root=fileURLToPath(new URL('..',import.meta.url)),require=createRequire(import.meta.url),asar=require('@electron/asar');
const build=JSON.parse(readFileSync(path.join(root,'release/build-info.json'),'utf8'));
const archive=path.join(build.appPath,'Contents/Resources/app.asar');
const sha256=data=>createHash('sha256').update(data).digest('hex');
const resources=[['menuBarTemplate.png',18],['menuBarTemplate@2x.png',36]].map(([file,size])=>{
 const relative='dist/main/'+file,bytes=asar.extractFile(archive,relative);
 if(!bytes.equals(readFileSync(path.join(root,relative))))throw Error('Packaged menu-bar image differs from build: '+relative);
 if(bytes.subarray(0,8).toString('hex')!=='89504e470d0a1a0a'||bytes.readUInt32BE(16)!==size||bytes.readUInt32BE(20)!==size)throw Error('Menu-bar image has unexpected PNG dimensions: '+file);
 return{path:relative,width:size,height:size,sha256:sha256(bytes)};
});
const temp=mkdtempSync(path.join(tmpdir(),'studio-menu-bar-package-'));
try{
 const script=path.join(temp,'check.cjs'),report=path.join(temp,'result.json'),screenshot=path.join(temp,'macos-menu-bar-package.png'),data=path.join(temp,'data');mkdirSync(data);
 writeFileSync(script,`
const {app,nativeImage,Tray,Menu}=require('electron'),fs=require('node:fs'),{execFileSync}=require('node:child_process');
app.commandLine.appendSwitch('use-mock-keychain');app.setPath('userData',process.argv[4]);let tray;
app.whenReady().then(async()=>{
 const image=nativeImage.createFromPath(process.argv[2]);if(image.isEmpty())throw Error('Native image could not load from shipping ASAR');
 image.setTemplateImage(true);const size=image.getSize(),scales=image.getScaleFactors();
 if(size.width!==18||size.height!==18||!scales.includes(1)||!scales.includes(2)||!image.isTemplateImage())throw Error('Native template or Retina representations missing');
 const pixels=image.toBitmap({scaleFactor:1});let transparent=0,ink=0;
 for(let i=0;i<pixels.length;i+=4){if(pixels[i+3]===0)transparent++;else{ink++;if(pixels[i]||pixels[i+1]||pixels[i+2])throw Error('Template contains colored pixels');}}
 if(!transparent||!ink||pixels[3]!==0)throw Error('Template must contain a glyph on a transparent background');
 tray=new Tray(image);tray.setToolTip('ChatGPT Studio · isolated package validation');
 tray.setContextMenu(Menu.buildFromTemplate([{label:'ChatGPT Studio · 菜单栏打包验证',enabled:false}]));
 let bounds=tray.getBounds();for(let i=0;i<50&&(!bounds.width||!bounds.height);i++){await new Promise(resolve=>setTimeout(resolve,100));bounds=tray.getBounds();}
 if(bounds.width<=0||bounds.height<=0||tray.isDestroyed())throw Error('Packaged image did not create a native status item');
 execFileSync('/usr/sbin/screencapture',['-x',process.argv[5]],{timeout:15000});tray.destroy();
 fs.writeFileSync(process.argv[3],JSON.stringify({imageSize:size,scaleFactors:scales,template:image.isTemplateImage(),transparentPixels:transparent,glyphPixels:ink,bounds,destroyedOnExit:tray.isDestroyed(),browserWindowsCreated:false,personalDataRead:false}));app.exit(0);
}).catch(error=>{if(tray&&!tray.isDestroyed())tray.destroy();console.error(error.message);app.exit(1);});
`);
 execFileSync(path.join(root,'node_modules/electron/dist/Electron.app/Contents/MacOS/Electron'),[script,path.join(archive,'dist/main/menuBarTemplate.png'),report,data,screenshot],{timeout:30000,stdio:'pipe'});
 const native=JSON.parse(readFileSync(report,'utf8'));
 const receipt={version:build.version,commit:process.env.GITHUB_SHA,verifiedAt:new Date().toISOString(),scope:'Actual shipping ASAR image resources loaded by isolated Electron on macOS, real native Tray; full packaged main-process startup and physical-user dark-mode appearance are not verified here. No account spaces or personal Keychain items.',asarSHA256:sha256(readFileSync(archive)),resources,native,checks:['packaged image bytes match build','18px and 36px PNGs','ASAR native-image loading','template and Retina representations','black glyph with transparent background','native menu-bar status-item bounds','native Tray disposal']};
 const out=path.join(root,'release/verification');mkdirSync(out,{recursive:true});
 writeFileSync(path.join(out,'menu-bar-package.json'),JSON.stringify(receipt,null,2)+'\n');copyFileSync(screenshot,path.join(out,'macos-menu-bar-package.png'));
 console.log(JSON.stringify(receipt,null,2));
}finally{rmSync(temp,{recursive:true,force:true});}
