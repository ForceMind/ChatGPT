import assert from 'node:assert/strict';
import { randomUUID, createHash } from 'node:crypto';
import { mkdtempSync, readFileSync, writeFileSync, rmSync, readdirSync, existsSync, unlinkSync, symlinkSync, renameSync, mkdirSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { test, type TestContext } from 'node:test';
import { AssetStore, safeAssetFilename, validateAssetLibrary } from '../src/main/asset-store';

function fixture(t: TestContext) {
  const root = mkdtempSync(path.join(os.tmpdir(), 'profiles-assets-'));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  const profile = randomUUID(), other = randomUUID();
  const store = new AssetStore(root);
  function input(name = 'source.txt', content = 'Original file\n原始数据') { const file = path.join(root, name); writeFileSync(file, content); return file; }
  return { root, profile, other, store, input };
}
test('imports real bytes without altering the source and persists digest and account scope', async t => {
  const { root, profile, other, store, input } = fixture(t);
  const source = input(); const original = readFileSync(source);
  const record = await store.importFile(profile, source, { kind: 'import', profileName: '工作账号' });
  assert.equal(record.status, 'ready'); assert.equal(record.size, original.length);
  assert.equal(record.sha256, createHash('sha256').update(original).digest('hex'));
  assert.deepEqual(readFileSync(source), original);
  assert.deepEqual(readFileSync(await store.verifyFile(profile, record.id)), original);
  assert.throws(() => store.get(other, record.id), /不存在/);
  assert.throws(() => store.resolveFile(other, record.id), /不存在/);
  assert.throws(() => store.rename(other, record.id, 'leaked.txt'), /不存在/);
  assert.throws(() => store.setTrashed(other, record.id, true), /不存在/);
  assert.deepEqual(store.list(other), []);
  assert.equal(new AssetStore(root).get(profile, record.id).sha256, record.sha256);
  assert.ok(!readFileSync(store.indexFile, 'utf8').includes(source));
});
test('same filenames in parallel never overwrite each other or lose index entries', async t => {
  const { store, profile } = fixture(t);
  const a = store.reserve(profile, 'report.html', 'text/html', { kind: 'download' });
  const b = store.reserve(profile, 'report.html', 'text/html', { kind: 'download' });
  writeFileSync(store.pendingPath(profile, a.id), '<h1>A</h1>');
  writeFileSync(store.pendingPath(profile, b.id), '<h1>B</h1>');
  await Promise.all([store.complete(profile, a.id), store.complete(profile, b.id)]);
  assert.notEqual(store.resolveFile(profile, a.id), store.resolveFile(profile, b.id));
  assert.equal(readFileSync(store.resolveFile(profile, a.id), 'utf8'), '<h1>A</h1>');
  assert.equal(readFileSync(store.resolveFile(profile, b.id), 'utf8'), '<h1>B</h1>');
  assert.equal(store.list(profile).filter(x => x.status === 'ready').length, 2);
});
test('download source strips credentials and keeps the original conversation association', async t => {
  const { store, profile } = fixture(t);
  const a = store.reserve(profile, '../../report.html', 'text/html; charset=UTF-8', { kind: 'download', title: '原始对话', conversationURL: 'https://chatgpt.com/c/thread-123?token=secret&signature=private#fragment' });
  assert.equal(a.fileName, 'report.html'); assert.equal(a.mime, 'text/html'); assert.equal(a.kind, 'web');
  assert.equal(a.source.conversationURL, 'https://chatgpt.com/c/thread-123');
  assert.ok(!readFileSync(store.indexFile, 'utf8').includes('secret'));
  const p = store.pendingPath(profile, a.id); assert.ok(p.startsWith(store.filesDirectory + path.sep));
  writeFileSync(p, '<p>source</p>'); await store.complete(profile, a.id);
  await assert.rejects(store.complete(profile, a.id), /重复完成/);
  assert.equal(store.get(profile, a.id).status, 'ready');
  assert.equal(store.reserve(profile, 'auth.txt', 'text/plain', { kind: 'download', conversationURL: 'https://chatgpt.com/api/auth/session?token=private' }).source.conversationURL, undefined);
});
test('editing metadata and reversible trash preserve the original file and extension', async t => {
  const { root, store, profile, input } = fixture(t);
  const a = await store.importFile(profile, input('original.md'));
  const file = store.resolveFile(profile, a.id);
  store.rename(profile, a.id, 'renamed.md'); store.setTags(profile, a.id, ['重点','产品','重点']); store.setFavorite(profile, a.id, true);
  assert.deepEqual(store.get(profile, a.id).tags, ['重点','产品']);
  assert.throws(() => store.rename(profile, a.id, 'fake.pdf'), /扩展名/);
  assert.throws(() => store.rename(profile, a.id, '../escape.md'), /路径/);
  store.setTrashed(profile, a.id, true); assert.ok(existsSync(file));
  assert.throws(() => store.resolveFile(profile, a.id), /回收站/);
  const restarted = new AssetStore(root); assert.ok(restarted.get(profile, a.id).trashedAt);
  restarted.setTrashed(profile, a.id, false);
  assert.equal(restarted.resolveFile(profile, a.id), file);
  assert.equal(restarted.get(profile, a.id).name, 'renamed.md');
  assert.equal(restarted.get(profile, a.id).favorite, true);
});
test('derived files belong to their original account and survive restart with lineage', async t => {
  const { root, store, profile, other, input } = fixture(t);
  const original = await store.importFile(profile, input('source.md'));
  const output = input('result.html', '<h1>Derived output fixture</h1>');
  const result = await store.importFile(profile, output, { kind: 'conversion', fromAssetId: original.id, conversionFormat: 'HTML' }, 'converted.html', 'text/html');
  assert.equal(result.source.fromAssetId, original.id); assert.notEqual(result.id, original.id);
  assert.equal(store.get(profile, original.id).status, 'ready');
  await assert.rejects(store.importFile(other, output, { kind: 'conversion', fromAssetId: original.id }), /不存在/);
  assert.equal(new AssetStore(root).get(profile, result.id).source.fromAssetId, original.id);
  const invalid = store.read(); invalid.assets[0].source = { kind: 'conversion', fromAssetId: result.id };
  assert.throws(() => validateAssetLibrary(invalid), /索引/);
});
test('unfinished tasks become interrupted on restart and are never claimed ready', t => {
  const { root, store, profile } = fixture(t);
  const a = store.reserve(profile, 'partial.html', 'text/html', { kind: 'download' }, 100);
  const partial = store.pendingPath(profile, a.id); writeFileSync(partial, 'part');
  store.progress(profile, a.id, 4, 100, 'paused');
  assert.throws(() => store.setTrashed(profile, a.id, true), /先取消/);
  const restarted = new AssetStore(root); assert.equal(restarted.get(profile, a.id).status, 'interrupted');
  assert.equal(restarted.get(profile, a.id).sha256, undefined);
  assert.ok(existsSync(partial)); assert.throws(() => restarted.resolveFile(profile, a.id), /未就绪/);
});
test('valid backup recovery preserves the corrupt index and all asset bytes', async t => {
  const { root, store, profile, input } = fixture(t);
  const a = await store.importFile(profile, input());
  const file = store.resolveFile(profile, a.id); const expected = readFileSync(file);
  store.setFavorite(profile, a.id, true); writeFileSync(store.indexFile, '{broken');
  const restored = new AssetStore(root); assert.ok(restored.recoveryNotice);
  assert.equal(restored.get(profile, a.id).status, 'ready'); assert.deepEqual(readFileSync(file), expected);
  assert.ok(readdirSync(restored.directory).some(n => n.startsWith('library.corrupt-')));
  writeFileSync(restored.indexFile, 'broken'); writeFileSync(restored.backupFile, 'broken too');
  assert.throws(() => new AssetStore(root), /不会清空/); assert.deepEqual(readFileSync(file), expected);
});
test('missing indexes do not silently create an empty library over retained files', async t => {
  const { root, store, profile, input } = fixture(t); const a = await store.importFile(profile, input());
  const file = store.resolveFile(profile, a.id); unlinkSync(store.indexFile); unlinkSync(store.backupFile);
  assert.throws(() => new AssetStore(root), /索引和备份均缺失/);
  assert.ok(existsSync(file)); assert.ok(!existsSync(store.indexFile));
});
test('external modification and missing files stop verified reads', async t => {
  const { store, profile, input } = fixture(t);
  const modified = await store.importFile(profile, input('a.txt', 'original'));
  writeFileSync(store.resolveFile(profile, modified.id), 'modified');
  await assert.rejects(store.verifyFile(profile, modified.id), /摘要/);
  assert.equal(store.get(profile, modified.id).status, 'changed');
  const missing = await store.importFile(profile, input('b.txt', 'kept'));
  unlinkSync(store.resolveFile(profile, missing.id));
  await assert.rejects(store.verifyFile(profile, missing.id)); assert.equal(store.get(profile, missing.id).status, 'missing');
  assert.equal(store.list(profile).length, 2);
});
test('leaf and parent symlinks cannot expose or replace outside files', async t => {
  const { root, store, profile, input } = fixture(t);
  const outside = input('private.txt', 'outside secret'); const a = await store.importFile(profile, input());
  const file = store.resolveFile(profile, a.id); unlinkSync(file); symlinkSync(outside, file);
  await assert.rejects(store.verifyFile(profile, a.id), /符号链接/);
  assert.equal(readFileSync(outside, 'utf8'), 'outside secret');
  await assert.rejects(store.importFile(profile, file), /ELOOP/);
  const b = await store.importFile(profile, input('b.txt'));
  const folder = path.dirname(store.resolveFile(profile, b.id)); renameSync(folder, folder + '.hold');
  const externalDir = path.join(root, 'outside-dir'); mkdirSync(externalDir); symlinkSync(externalDir, folder, 'dir');
  assert.throws(() => store.resolveFile(profile, b.id), /目录链接/); assert.deepEqual(readdirSync(externalDir), []);
});
test('filenames are bounded and persisted traversal metadata is rejected', t => {
  const { store, profile } = fixture(t);
  const safe = safeAssetFilename('a/../' + '图'.repeat(1000) + '.png');
  assert.ok(Buffer.byteLength(safe) <= 180); assert.ok(safe.endsWith('.png')); assert.ok(!safe.includes('/'));
  const a = store.reserve(profile, '.'+'图'.repeat(1000)+'.png', 'image/png', { kind: 'download' });
  assert.ok(Buffer.byteLength(a.fileName) <= 180); assert.ok(!a.fileName.startsWith('.'));
  const raw = store.read(); raw.assets[0].fileName = '../../private'; assert.throws(() => validateAssetLibrary(raw), /索引/);
  const broken = store.read(); (broken.assets[0].source as unknown as Record<string, unknown>).profileName = { unexpected: true };
  assert.throws(() => validateAssetLibrary(broken), /索引/);
});
test('batch metadata changes are atomic when an ID belongs to another account',async t=>{
  const {store,profile,other,input}=fixture(t);const a=await store.importFile(profile,input());const b=await store.importFile(other,input('other.txt'));
  assert.throws(()=>store.editMany(profile,[a.id,b.id],{favorite:true}),/不存在/);
  assert.equal(store.get(profile,a.id).favorite,false);assert.equal(store.get(other,b.id).favorite,false);
  store.editMany(profile,[a.id],{favorite:true,trashed:true});assert.equal(store.get(profile,a.id).favorite,true);assert.ok(store.get(profile,a.id).trashedAt);
  assert.throws(()=>store.editMany(profile,[a.id],{}),/未指定/);
});
