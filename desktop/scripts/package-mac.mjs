import { cpSync, copyFileSync, mkdirSync, mkdtempSync, readdirSync, readFileSync, writeFileSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

if(process.platform!=='darwin')throw new Error('本轮仅提供 macOS 打包入口。');
const root=fileURLToPath(new URL('..',import.meta.url));
// Older updaters only accept this ZIP root and its internal bundle identity.
const bundleName='ChatGPT Profiles';
const pkg=JSON.parse(readFileSync(path.join(root,'package.json'),'utf8'));
// An APFS staging directory prevents AppleDouble sidecars on external drives from
// being mistaken for ASAR archives by the packaging integrity scanner.
const staging=mkdtempSync(path.join(tmpdir(),'chatgpt-profiles-package-'));
const runtime=path.join(staging,'runtime'),output=path.join(staging,'release');
cpSync(path.join(root,'node_modules/electron/dist'),runtime,{recursive:true,verbatimSymlinks:true,filter:source=>!path.basename(source).startsWith('._')});
execFileSync(process.execPath,[path.join(root,'node_modules/electron-builder/out/cli/cli.js'),'--mac','dmg','zip','--arm64','--publish','never',`--config.productName=${bundleName}`,`--config.electronDist=${runtime}`,`--config.directories.output=${output}`],{cwd:root,stdio:'inherit',env:{...process.env,ELECTRON_BUILDER_CACHE:process.env.ELECTRON_BUILDER_CACHE||path.join(tmpdir(),'chatgpt-profiles-builder-cache')}});
const release=path.join(root,'release');mkdirSync(release,{recursive:true});
const artifacts=[];
for(const file of readdirSync(output)){
  if(!/\.(dmg|zip|blockmap)$/.test(file)||file.startsWith('._'))continue;
  const source=path.join(output,file);copyFileSync(source,path.join(release,file));
  artifacts.push({file,sha256:createHash('sha256').update(readFileSync(source)).digest('hex')});
}
const info={version:pkg.version,architecture:'arm64',signing:'ad-hoc, not notarized',appPath:path.join(output,'mac-arm64',`${bundleName}.app`),artifacts};
writeFileSync(path.join(release,'build-info.json'),JSON.stringify(info,null,2)+'\n');
console.log(JSON.stringify(info,null,2));
