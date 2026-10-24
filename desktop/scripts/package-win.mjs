import { execFileSync } from 'node:child_process';
import { readFileSync, writeFileSync, readdirSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

if(process.platform!=='win32'||process.arch!=='x64')throw new Error('请在 Windows x64 构建环境运行。');
const root=fileURLToPath(new URL('..',import.meta.url));
const pkg=JSON.parse(readFileSync(path.join(root,'package.json'),'utf8'));
const output=path.join(root,'release/windows');
execFileSync(process.execPath,[path.join(root,'node_modules/electron-builder/out/cli/cli.js'),'--win','nsis','zip','--x64','--publish','never',`--config.directories.output=${output}`],{cwd:root,stdio:'inherit',env:{...process.env,CSC_IDENTITY_AUTO_DISCOVERY:'false'}});
const executableName=pkg.build.win.executableName||pkg.build.productName;
const exe=path.join(output,'win-unpacked',executableName+'.exe');
const bytes=readFileSync(exe);if(bytes.toString('ascii',0,2)!=='MZ'||bytes.readUInt16LE(bytes.readUInt32LE(0x3c)+4)!==0x8664)throw new Error('应用程序不是有效的 Windows x64 PE。');
const asar=createRequire(import.meta.url)('@electron/asar');
const archive=path.join(output,'win-unpacked/resources/app.asar');
if(JSON.parse(asar.extractFile(archive,'package.json').toString()).version!==pkg.version)throw new Error('包内版本不一致。');
const hash=data=>createHash('sha256').update(data).digest('hex');
const payload={};
function check(dir){for(const entry of readdirSync(dir,{withFileTypes:true})){const file=path.join(dir,entry.name);if(entry.isDirectory())check(file);else if(!entry.name.startsWith('._')){const relative=path.relative(root,file),key=relative.split(path.sep).join('/');const source=readFileSync(file),packed=asar.extractFile(archive,relative);if(!source.equals(packed))throw new Error('包内文件不一致：'+key);payload[key]=hash(packed);}}}
check(path.join(root,'dist'));
const artifacts=readdirSync(output).filter(file=>/\.(exe|zip|blockmap)$/.test(file)&&!file.startsWith('._')).map(file=>{const data=readFileSync(path.join(output,file));return{file,bytes:data.length,sha256:hash(data)};});
if(!artifacts.some(a=>a.file.endsWith('-setup.exe'))||!artifacts.some(a=>a.file.endsWith('.zip')))throw new Error('安装包或 ZIP 缺失。');
const info={version:pkg.version,platform:'win32',architecture:'x64',signing:'unsigned',sourceCommit:process.env.GITHUB_SHA??null,application:'win-unpacked/'+executableName+'.exe',payload,artifacts};
writeFileSync(path.join(output,'windows-build-info.json'),JSON.stringify(info,null,2)+'\n');
writeFileSync(path.join(output,'SHA256SUMS.txt'),artifacts.map(a=>a.sha256+'  '+a.file).join('\n')+'\n');
console.log(JSON.stringify(info,null,2));
