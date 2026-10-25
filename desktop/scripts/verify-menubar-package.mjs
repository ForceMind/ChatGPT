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
 const script=path.join(temp,'check.cjs'),report=path.join(temp,'result.json'),screenshot=path.join(temp,'macos-menu-bar-package.png'),data=path.join(temp,'data'),page=path.join(temp,'fixture.html');mkdirSync(data);
 writeFileSync(page,`<!doctype html><meta charset="utf-8"><meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'"><title>ChatGPT Studio menu-bar package check</title><body style="font:16px -apple-system,sans-serif;padding:40px;color:#171717;background:#fff"><h1>菜单栏资源打包核验</h1><p>此窗口是 Actions 中的独立夹具，仅加载包内图标。</p><p>没有启动产品主程序、ChatGPT 网页或账号空间。</p></body>`);
 writeFileSync(script,`
const {app,nativeImage,Tray,Menu,BrowserWindow,screen}=require('electron'),fs=require('node:fs'),{execFileSync}=require('node:child_process');
app.commandLine.appendSwitch('use-mock-keychain');app.setPath('userData',process.argv[4]);let tray,probeWindow;
app.whenReady().then(async()=>{
 const image=nativeImage.createFromPath(process.argv[2]);if(image.isEmpty())throw Error('Native image could not load from shipping ASAR');
 image.setTemplateImage(true);const size=image.getSize(),scales=image.getScaleFactors();
 if(size.width!==18||size.height!==18||!scales.includes(1)||!scales.includes(2)||!image.isTemplateImage())throw Error('Native template or Retina representations missing');
 const pixels=image.toBitmap({scaleFactor:1});let transparent=0,ink=0;
 for(let i=0;i<pixels.length;i+=4){if(pixels[i+3]===0)transparent++;else{ink++;if(pixels[i]||pixels[i+1]||pixels[i+2])throw Error('Template contains colored pixels');}}
 if(!transparent||!ink||pixels[3]!==0)throw Error('Template must contain a glyph on a transparent background');
 probeWindow=new BrowserWindow({width:720,height:480,show:false,webPreferences:{nodeIntegration:false,contextIsolation:true,sandbox:true}});
 await probeWindow.loadFile(process.argv[6]);
 probeWindow.show();app.focus({steal:true});probeWindow.focus();
 tray=new Tray(image);tray.setToolTip('ChatGPT Studio · isolated package validation');
 tray.setContextMenu(Menu.buildFromTemplate([{label:'ChatGPT Studio · 菜单栏打包验证',enabled:false}]));
 const onMenuBar=bounds=>screen.getAllDisplays().some(({bounds:b,workArea:w})=>bounds.width>0&&bounds.height>0&&w.y>b.y&&bounds.x>=b.x&&bounds.x+bounds.width<=b.x+b.width&&bounds.y>=b.y&&bounds.y+bounds.height<=w.y+1);
 let bounds=tray.getBounds();for(let i=0;i<60&&!onMenuBar(bounds);i++){await new Promise(resolve=>setTimeout(resolve,100));bounds=tray.getBounds();}
 if(!onMenuBar(bounds)||tray.isDestroyed())throw Error('Native status item is outside the visible menu bar: '+JSON.stringify({bounds,displays:screen.getAllDisplays().map(d=>({bounds:d.bounds,workArea:d.workArea}))}));
 execFileSync('/usr/sbin/screencapture',['-x',process.argv[5]],{timeout:15000});tray.destroy();
 fs.writeFileSync(process.argv[3],JSON.stringify({imageSize:size,scaleFactors:scales,template:image.isTemplateImage(),transparentPixels:transparent,glyphPixels:ink,bounds,displays:screen.getAllDisplays().map(d=>({bounds:d.bounds,workArea:d.workArea})),onVisibleMenuBar:onMenuBar(bounds),destroyedOnExit:tray.isDestroyed(),fixtureWindowCount:BrowserWindow.getAllWindows().length}));probeWindow.destroy();app.exit(0);
}).catch(error=>{if(tray&&!tray.isDestroyed())tray.destroy();if(probeWindow&&!probeWindow.isDestroyed())probeWindow.destroy();console.error(error.message);app.exit(1);});
`);
 try{execFileSync(path.join(root,'node_modules/electron/dist/Electron.app/Contents/MacOS/Electron'),[script,path.join(archive,'dist/main/menuBarTemplate.png'),report,data,screenshot,page],{timeout:30000,stdio:'pipe',encoding:'utf8'});}catch(error){throw Error(error.stderr?.trim()||error.message);}
 const native=JSON.parse(readFileSync(report,'utf8'));
 const receipt={version:build.version,commit:process.env.GITHUB_SHA,verifiedAt:new Date().toISOString(),scope:'Actual shipping ASAR images loaded by isolated Electron on macOS with one local fixture window, real native Tray in the visible menu bar; full packaged main-process startup and physical-user dark-mode appearance are not verified here. No account spaces or personal Keychain items.',asarSHA256:sha256(readFileSync(archive)),resources,native,checks:['packaged image bytes match build','18px and 36px PNGs','ASAR native-image loading','template and Retina representations','black glyph with transparent background','native status item within the visible menu bar','native Tray disposal']};
 const out=path.join(root,'release/verification');mkdirSync(out,{recursive:true});
 writeFileSync(path.join(out,'menu-bar-package.json'),JSON.stringify(receipt,null,2)+'\n');copyFileSync(screenshot,path.join(out,'macos-menu-bar-package.png'));
 console.log(JSON.stringify(receipt,null,2));
}finally{rmSync(temp,{recursive:true,force:true});}
