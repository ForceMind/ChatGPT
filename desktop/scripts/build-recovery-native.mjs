import {existsSync,realpathSync} from 'node:fs';
import {homedir} from 'node:os';
import path from 'node:path';
import {execFileSync} from 'node:child_process';
export function buildRecoveryNative(root){
 if(process.platform!=='darwin')return;
 const includes=[process.env.NODE_INCLUDE_DIR,path.resolve(realpathSync(process.execPath),'../../include/node'),path.join(homedir(),'Library/Caches/node-gyp',process.versions.node,'include/node'),'/opt/homebrew/include/node'].filter(Boolean);
 const include=includes.find(dir=>existsSync(path.join(dir,'node_api.h')));
 if(!include)throw Error('构建恢复原生组件需要 Node-API 头文件；请设置 NODE_INCLUDE_DIR。');
 const target=path.join(root,'dist/main/recovery-protection.node');
 execFileSync('xcrun',['clang++','-std=c++17','-fobjc-arc','-dynamiclib','-undefined','dynamic_lookup','-O2','-Wall','-Wextra','-Werror','-mmacosx-version-min=13.0','-I',include,path.join(root,'src/main/recovery-protection.mm'),'-framework','Foundation','-framework','LocalAuthentication','-framework','Security','-o',target],{stdio:'inherit'});
 execFileSync('/usr/bin/codesign',['--force','--sign','-',target],{stdio:'inherit'});
}
