import { build as bundle } from 'esbuild';
import { build as viteBuild } from 'vite';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import { copyFileSync, cpSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import {buildRecoveryNative} from './build-recovery-native.mjs';
const root = fileURLToPath(new URL('..', import.meta.url));
await bundle({ entryPoints: [path.join(root, 'src/main/main.ts'), path.join(root, 'src/main/preload.ts'),path.join(root,'src/main/update-notice-preload.ts')], outdir: path.join(root, 'dist/main'), outExtension: { '.js': '.cjs' }, bundle: true, platform: 'node', format: 'cjs', target: 'node22', external: ['electron'], sourcemap: true });
copyFileSync(path.join(root,'assets/icon.png'),path.join(root,'dist/main/icon.png'));
buildRecoveryNative(root);
if(process.platform==='darwin'){
 const helper=path.join(root,'dist/main/update-installer');
 execFileSync('xcrun',['clang','-O2','-Wall','-Wextra','-Werror','-mmacosx-version-min=13.0',path.join(root,'src/main/update-installer.c'),'-framework','CoreFoundation','-framework','Security','-o',helper],{stdio:'inherit'});
 execFileSync('/usr/bin/codesign',['--force','--sign','-',helper],{stdio:'inherit'});
}

execFileSync(process.execPath, ['--import', 'tsx', path.join(root, 'scripts/prepare-capabilities.ts')], { cwd: root, stdio: 'inherit' });
await viteBuild({ root: path.join(root, 'src/renderer'), base: './', build: { outDir: path.join(root, 'dist/renderer'), emptyOutDir: true, rollupOptions:{input:{main:path.join(root,'src/renderer/index.html'),converter:path.join(root,'src/renderer/converter.html'),updateNotice:path.join(root,'src/renderer/update-notice.html')}} } });
copyFileSync(path.join(root,'src/renderer/profile-empty.html'),path.join(root,'dist/renderer/profile-empty.html'));
for(const folder of ['cmaps','standard_fonts','wasm'])cpSync(path.join(root,'node_modules/pdfjs-dist',folder),path.join(root,'dist/renderer/pdfjs',folder),{recursive:true,filter:file=>!path.basename(file).startsWith('._')});
