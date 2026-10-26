// Executed only by the disposable Windows CI upgrade check, using the shipping ASAR modules.
const {app}=require('electron'),fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict'),{createRequire}=require('node:module'),{createServer}=require('node:http');
if(process.platform!=='win32'||process.env.GITHUB_ACTIONS!=='true')throw Error('Isolated Windows CI only');
const config=JSON.parse(fs.readFileSync(process.argv[2],'utf8'));
const owned=p=>path.resolve(p).toLowerCase().startsWith(path.resolve(process.env.RUNNER_TEMP).toLowerCase()+path.sep);
if(!owned(config.root)||!owned(config.installed))throw Error('Non-owned CI path');
const data=path.join(config.root,'probe-data');fs.mkdirSync(data);app.setPath('userData',data);
app.whenReady().then(async()=>{
 const requirePacked=createRequire(path.join(config.archive,'package.json'));
 const {NsisUpdater}=requirePacked('electron-updater');
 const {WindowsUpdateManager}=requirePacked('./dist/main/windows-update-manager.cjs');
 const {createWindowsUpdateBackend,verifyWindowsInstaller,registeredWindowsDirectory}=requirePacked('./dist/main/windows-update-platform.cjs');
 const {execFileSync}=require('node:child_process');
 const ps=path.join(process.env.SystemRoot,'System32/WindowsPowerShell/v1.0/powershell.exe');
 const records=JSON.parse(execFileSync(ps,['-NoProfile','-NonInteractive','-Command',"[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false); ConvertTo-Json -InputObject @(Get-ItemProperty 'HKCU:\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\*' -ErrorAction SilentlyContinue | Where-Object {$_.DisplayName -eq 'ChatGPT Studio'} | Select-Object InstallLocation,UninstallString) -Compress"],{encoding:'utf8'}));
 assert.equal(registeredWindowsDirectory(config.installed,records,[process.env.APPDATA,process.env.RUNNER_TEMP]),path.dirname(config.installed));
 process.env.STUDIO_OLD_EXE=config.installed;
 const oldVersion=execFileSync(ps,['-NoProfile','-NonInteractive','-Command','[Diagnostics.FileVersionInfo]::GetVersionInfo($env:STUDIO_OLD_EXE).ProductVersion'],{encoding:'utf8'}).trim();assert.equal(oldVersion,'3.0.0-alpha.18');
 const feed=JSON.parse(fs.readFileSync(config.feed,'utf8')),installer=fs.readFileSync(config.installer);let requests=0;
 const server=createServer((q,r)=>{if(q.url.startsWith('/updates/windows/alpha.yml')){r.setHeader('Content-Type','text/yaml');r.end(JSON.stringify(feed));}else if(q.url==='/installer.exe'){requests++;r.setHeader('Content-Length',installer.length);r.end(installer);}else{r.writeHead(404);r.end();}});
 await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));const origin='http://127.0.0.1:'+server.address().port;
 const cfg=path.join(config.root,'app-update.yml');fs.writeFileSync(cfg,JSON.stringify({provider:'generic',url:origin+'/updates/windows/',updaterCacheDirName:'studio-native-'+path.basename(config.root)}));
 // The version comes from the real installed alpha.18 executable, not a fabricated downgrade.
 const adapter={version:oldVersion,name:'ChatGPT Profiles',isPackaged:true,appUpdateConfigPath:cfg,userDataPath:data,baseCachePath:path.join(config.root,'cache'),whenReady:()=>app.whenReady(),relaunch:()=>app.relaunch(),quit:()=>app.quit(),onQuit:handler=>app.once('quit',(_e,code)=>handler(code))};
 const native=new NsisUpdater({provider:'generic',url:origin+'/updates/windows/'},adapter);native.installDirectory=path.dirname(config.installed);
 const manager=new WindowsUpdateManager({dataDir:data,current:oldVersion,arch:process.arch,mode:'automatic',backend:createWindowsUpdateBackend(native,origin),fetch:()=>{throw Error('Unexpected portable download');},changed:()=>{},verifyInstaller:verifyWindowsInstaller,preflight:async()=>{assert(fs.existsSync(config.installed));}});
 await manager.check();assert.equal(manager.status.phase,'available');assert.equal(manager.manifest.version,feed.version);assert.equal(requests,0);
 await manager.download();assert.equal(manager.status.phase,'ready');assert.equal(requests,1);
 fs.writeFileSync(path.join(config.root,'prepared.json'),JSON.stringify({oldVersion,newVersion:feed.version,sourceCommit:config.sourceCommit,shippingModules:true,sdk:'6.8.11',requests,installerSHA256:feed.files.find(f=>f.url.endsWith('.exe')).sha256}));
 await manager.installOnQuit(false);
}).catch(error=>{console.error(error);app.exit(1);});
