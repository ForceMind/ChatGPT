import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, writeFileSync, readdirSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { randomUUID } from 'node:crypto';
import { StateStore, makeTab, validateState, initialState } from '../src/main/state';
import { isAllowedURL, partitionFor } from '../src/main/policy';

test('账号 UUID、标签归属和窗口状态在重启后保持；调用者不能修改内部状态',()=>{
  const dir=mkdtempSync(path.join(tmpdir(),'profiles-state-')),store=new StateStore(dir);
  const first=store.read(),id=randomUUID(),tab=makeTab(id,'https://chatgpt.com/c/sample');
  store.commit(s=>{s.profiles.push({id,name:'工作账号',email:'work@example.com',color:'#5275a0',activeTabId:tab.id,createdAt:Date.now()});s.tabs.push(tab);s.activeProfileId=id;s.bounds={width:1000,height:720,x:40,y:80};});
  const reopened=new StateStore(dir);assert.deepEqual(reopened.read(),store.read());
  assert.equal(reopened.read().profiles[0].id,first.profiles[0].id);
  const clone=reopened.read();clone.profiles[0].name='外部篡改';assert.notEqual(reopened.read().profiles[0].name,clone.profiles[0].name);
  assert.equal(partitionFor(id),partitionFor(reopened.read().activeProfileId));
});
test('损坏主文件时恢复有效备份并保留损坏原件',()=>{
  const dir=mkdtempSync(path.join(tmpdir(),'profiles-recovery-')),store=new StateStore(dir),before=store.read();
  store.commit(s=>{s.profiles[0].name='新的备注';});writeFileSync(store.file,'{broken');
  const recovered=new StateStore(dir);assert.deepEqual(recovered.read(),before);assert.match(recovered.recoveryNotice!,/备份/);
  const copy=readdirSync(dir).find(f=>f.startsWith('state.corrupt-'));assert.ok(copy);assert.equal(readFileSync(path.join(dir,copy),'utf8'),'{broken');
});
test('主文件和备份均无效时停止启动，原始文件保持不变',()=>{
  const dir=mkdtempSync(path.join(tmpdir(),'profiles-fail-closed-')),store=new StateStore(dir);
  writeFileSync(store.file,'bad-primary');writeFileSync(store.backup,'bad-backup');
  assert.throws(()=>new StateStore(dir),/不会自动重建/);assert.equal(readFileSync(store.file,'utf8'),'bad-primary');assert.equal(readFileSync(store.backup,'utf8'),'bad-backup');
});
test('跨账号活动标签、重复 ID 及危险网址不能写入状态文件',()=>{
  const dir=mkdtempSync(path.join(tmpdir(),'profiles-invariant-')),store=new StateStore(dir),before=readFileSync(store.file,'utf8');
  assert.throws(()=>store.commit(s=>{s.profiles[0].activeTabId=randomUUID();}));
  assert.throws(()=>store.commit(s=>{s.tabs.push({...s.tabs[0]});}));
  assert.throws(()=>store.commit(s=>{s.tabs[0].url='file:///etc/passwd';}));
  assert.equal(readFileSync(store.file,'utf8'),before);
  const state=initialState();state.tabs[0].profileId=randomUUID();assert.throws(()=>validateState(state));
});
test('仅接受精确的 HTTPS 站点；回环测试例外不进入正常模式',()=>{
  for(const url of ['https://chatgpt.com/','https://auth.openai.com/','https://accounts.google.com/'])assert.equal(isAllowedURL(url),true);
  for(const url of ['https://chatgpt.com.evil.test/','https://chatgpt.com@evil.test/','https://user:pass@chatgpt.com/','javascript:alert(1)','file:///private/key','http://chatgpt.com/','http://127.0.0.1:12345/'])assert.equal(isAllowedURL(url),false,url);
  assert.equal(isAllowedURL('http://127.0.0.1:12345/','http://127.0.0.1:12345'),true);
  assert.equal(isAllowedURL('http://127.0.0.1:54321/','http://127.0.0.1:12345'),false);
  assert.throws(()=>partitionFor('../../other-profile'));
});

test('旧账号兼容；语言与时区独立保存，非法偏好不覆盖状态',()=>{
 const dir=mkdtempSync(path.join(tmpdir(),'profiles-language-')),store=new StateStore(dir);const first=store.read();
 assert.equal(first.profiles[0].language,'zh-CN');assert.equal(first.profiles[0].timeZone,'system');
 const legacy=structuredClone(first);delete legacy.profiles[0].language;delete legacy.profiles[0].timeZone;assert.doesNotThrow(()=>validateState(legacy));
 store.commit(s=>{s.profiles[0].language='ja-JP';s.profiles[0].timeZone='America/New_York';});const restart=new StateStore(dir);assert.equal(restart.read().profiles[0].language,'ja-JP');assert.equal(restart.read().profiles[0].timeZone,'America/New_York');
 const before=readFileSync(store.file,'utf8');for(const value of ['Mars/Base','+08:00','../../bad',''])assert.throws(()=>store.commit(s=>{s.profiles[0].timeZone=value;}));assert.throws(()=>store.commit(s=>{s.profiles[0].language='not-a-locale';}));assert.equal(readFileSync(store.file,'utf8'),before);
});
