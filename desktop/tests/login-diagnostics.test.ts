import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtempSync,readFileSync,rmSync,writeFileSync} from 'node:fs';
import os from 'node:os';import path from 'node:path';import {randomUUID} from 'node:crypto';
import {LoginDiagnostics,diagnosticOrigin} from '../src/main/login-diagnostics';
test('login links retain only origin, never OAuth parameters, credentials or path tokens',()=>{
 const dir=mkdtempSync(path.join(os.tmpdir(),'login-records-'));try{
  const store=new LoginDiagnostics(dir),profile=randomUUID();const record=store.add(profile,'redirect','https://user:password@example.test/secret-path?code=oauth-secret#id_token=fragment-secret','https://auth.openai.com/token-secret?state=state-secret');
  assert.equal(record.destination,'https://example.test');assert.equal(record.source,'https://auth.openai.com');store.result(record.id,'cancelled');
  const raw=readFileSync(path.join(dir,'login-links.json'),'utf8');for(const secret of ['password','oauth-secret','fragment-secret','token-secret','state-secret','secret-path'])assert.ok(!raw.includes(secret));
  const restart=new LoginDiagnostics(dir);assert.equal(restart.list()[0].result,'cancelled');assert.equal(restart.list()[0].destination,record.destination);restart.clear();assert.deepEqual(new LoginDiagnostics(dir).list(),[]);
  assert.equal(diagnosticOrigin('custom://secret?token=abc'),'custom:（内容已隐藏）');assert.equal(diagnosticOrigin('data:text/plain,private'),'data:（内容已隐藏）');assert.equal(diagnosticOrigin('malformed?secret'),'（无法识别的链接）');
 }finally{rmSync(dir,{recursive:true,force:true});}
});
test('login link history is bounded and invalid saved records fail visibly',()=>{
 const dir=mkdtempSync(path.join(os.tmpdir(),'login-records-'));try{const s=new LoginDiagnostics(dir),profile=randomUUID();for(let n=0;n<205;n++)s.add(profile,'popup',`https://host-${n}.test/`,'https://chatgpt.com');assert.equal(s.list().length,200);assert.equal(new LoginDiagnostics(dir).list().length,200);assert.throws(()=>s.add('bad','popup','https://example.test',''),/账号/);writeFileSync(path.join(dir,'login-links.json'),'{}');assert.throws(()=>new LoginDiagnostics(dir),/无法读取/);}finally{rmSync(dir,{recursive:true,force:true});}
});
