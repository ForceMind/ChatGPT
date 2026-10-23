import {test} from 'node:test';
import assert from 'node:assert/strict';
import {mkdtempSync,rmSync,readFileSync,writeFileSync,mkdirSync,existsSync} from 'node:fs';
import os from 'node:os';import path from 'node:path';
import {randomBytes,randomUUID,createCipheriv,createDecipheriv} from 'node:crypto';
import {RecoveryAccess,type PresenceKey} from '../src/main/recovery-access';
import type {RecoveryCipher} from '../src/main/recovery-store';
function legacy():RecoveryCipher {const key=randomBytes(32);return{encrypt:async text=>{const iv=randomBytes(12),c=createCipheriv('aes-256-gcm',key,iv);return Buffer.concat([iv,c.update(text),c.final(),c.getAuthTag()]);},decrypt:async bytes=>{const d=createDecipheriv('aes-256-gcm',key,bytes.subarray(0,12));d.setAuthTag(bytes.subarray(-16));return Buffer.concat([d.update(bytes.subarray(12,-16)),d.final()]).toString();}};}
test('consent, refusal, retry and version changes preserve old encrypted bytes and never call native keys on ad-hoc builds',async()=>{
 const root=mkdtempSync(path.join(os.tmpdir(),'recovery-access-'));try{
  let calls=0,available=false,nativeCalls=0;const cipher=legacy(),old=await cipher.encrypt('private old draft');writeFileSync(path.join(root,'original.bin'),old);
  const native:PresenceKey={capabilities:()=>({touchID:true,signed:false}),unlock:async()=>{nativeCalls++;return randomBytes(32);}};
  const a=new RecoveryAccess(root,'alpha.13',cipher,async()=>{calls++;return available;},native);
  assert.equal(calls,0);assert.equal(a.needsConsent,true);a.defer();assert.equal(calls,0);await assert.rejects(a.cipher.encrypt('new draft'),/尚未授权/);
  await assert.rejects(a.authorize(),/钥匙串暂不可用/);assert.equal(a.status.phase,'error');assert.equal(existsSync(path.join(root,'recovery-access.json')),false);
  available=true;await a.authorize();assert.equal(a.status.phase,'ready');assert.equal(await a.cipher.decrypt(old),'private old draft');assert.deepEqual(readFileSync(path.join(root,'original.bin')),old);
  const encrypted=await a.cipher.encrypt('new private draft');assert.equal(await cipher.decrypt(encrypted),'new private draft');assert.equal(encrypted.includes(Buffer.from('new private draft')),false);
  await assert.rejects(a.enablePresence(),/Developer ID/);assert.equal(nativeCalls,0);
  assert.equal(new RecoveryAccess(root,'alpha.13',cipher,async()=>true,native).needsConsent,false);
  assert.equal(new RecoveryAccess(root,'alpha.14',cipher,async()=>true,native).needsConsent,true);
 }finally{rmSync(root,{recursive:true,force:true});}
});
test('system-protected key wraps old ciphertext and backups, authenticates changes, and refuses missing keys without replacing originals',async()=>{
 const root=mkdtempSync(path.join(os.tmpdir(),'recovery-presence-'));try{
  const cipher=legacy(),key=randomBytes(32);let refuse=false,createCalls=0;
  const native:PresenceKey={capabilities:()=>({touchID:true,signed:true}),unlock:async(_slot,create)=>{if(refuse)throw Error('user cancelled');if(create)createCalls++;return Buffer.from(key);}};
  const a=new RecoveryAccess(root,'13',cipher,async()=>true,native);await a.authorize();
  const folder=path.join(root,'recovery',randomUUID());mkdirSync(folder,{recursive:true});const file=path.join(folder,randomUUID()+'.bin'),old=await cipher.encrypt('protected draft');writeFileSync(file,old);writeFileSync(file+'.bak',old);
  refuse=true;await assert.rejects(a.enablePresence(),/cancelled/);assert.equal(a.status.protected,false);assert.deepEqual(readFileSync(file),old);
  refuse=false;await a.enablePresence();assert.equal(a.status.protected,true);assert.equal(createCalls,1);
  const wrapped=readFileSync(file);assert.notDeepEqual(wrapped,old);assert.equal(await a.cipher.decrypt(wrapped),'protected draft');assert.equal(await a.cipher.decrypt(readFileSync(file+'.bak')),'protected draft');
  const damaged=Buffer.from(wrapped);damaged[damaged.length-1]^=1;await assert.rejects(a.cipher.decrypt(damaged));a.dispose();
  const b=new RecoveryAccess(root,'14',cipher,async()=>true,native);assert.equal(b.needsConsent,true);refuse=true;await assert.rejects(b.authorize(),/cancelled/);assert.deepEqual(readFileSync(file),wrapped);await assert.rejects(b.cipher.decrypt(wrapped),/尚未授权/);
  refuse=false;await b.authorize();b.finishMigration();assert.equal(await b.cipher.decrypt(wrapped),'protected draft');assert.equal(createCalls,1);
  // Interrupted conversion keeps legacy records readable after authorization.
  writeFileSync(file+'.bak',old);b.finishMigration();assert.equal(await b.cipher.decrypt(readFileSync(file+'.bak')),'protected draft');
  const unsigned={...native,capabilities:()=>({touchID:true,signed:false})};const c=new RecoveryAccess(root,'15',cipher,async()=>true,unsigned);await assert.rejects(c.authorize(),/缺少稳定签名/);assert.deepEqual(readFileSync(file),wrapped);
 }finally{rmSync(root,{recursive:true,force:true});}
});
