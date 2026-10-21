import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { EventEmitter } from 'node:events';
import { mkdtempSync, writeFileSync, readFileSync, rmSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { test, type TestContext } from 'node:test';
import type { DownloadItem, Session, WebContents, WebFrameMain } from 'electron';
import { AssetStore } from '../src/main/asset-store';
import { AssetDownloads } from '../src/main/asset-downloads';

class Item extends EventEmitter {
  savePath = ''; received = 0; total = 10; paused = false; ended = false; rejectPath = false;
  getFilename() { return 'report.html'; } getMimeType() { return 'text/html'; }
  getTotalBytes() { return this.total; } getReceivedBytes() { return this.received; }
  setSavePath(file: string) { if (this.rejectPath) throw new Error('native path rejected'); this.savePath = file; }
  isPaused() { return this.paused; } canResume() { return !this.ended; }
  pause() { this.paused = true; } resume() { this.paused = false; }
  cancel() { if (!this.ended) { this.ended = true; this.emit('done', {}, 'cancelled'); } }
  finish(content?: string) { if (content !== undefined) writeFileSync(this.savePath, content); this.ended = true; this.emit('done', {}, 'completed'); }
}
function fixture(t: TestContext) {
  const root = mkdtempSync(path.join(os.tmpdir(), 'profiles-asset-downloads-'));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  const store = new AssetStore(root), profile = randomUUID();
  const ses = new EventEmitter(); const errors: Error[] = []; let available = true; let name = '工作账号';
  const tracker = new AssetDownloads(ses as unknown as Session, profile, store, { profileAvailable: () => available, source: (_contents: WebContents, _frame: WebFrameMain | null) => ({ profileName: name, conversationURL: 'https://chatgpt.com/c/source?token=private' }), failed: e => errors.push(e) });
  const begin = (item = new Item()) => { ses.emit('will-download', {}, item as unknown as DownloadItem, {}, null); return item; };
  return { root, store, profile, ses, tracker, errors, begin, setAvailable: (v: boolean) => { available = v; }, setName: (v: string) => { name = v; } };
}
test('download observer captures fixed owner/source and completes real file archival', async t => {
  const { store, profile, tracker, begin, setName, errors } = fixture(t);
  const item = begin(); const id = tracker.activeIds()[0]; assert.ok(item.savePath.endsWith('.part'));
  setName('另一个前台账号'); item.received = 5; item.emit('updated', {}, 'progressing');
  item.finish('<h1>Downloaded bytes</h1>'); await tracker.whenIdle();
  const asset = store.get(profile, id); assert.equal(asset.status, 'ready'); assert.equal(asset.source.profileName, '工作账号');
  assert.equal(asset.source.conversationURL, 'https://chatgpt.com/c/source');
  assert.equal(readFileSync(store.resolveFile(profile, id), 'utf8'), '<h1>Downloaded bytes</h1>');
  assert.deepEqual(errors, []); assert.deepEqual(tracker.activeIds(), []); tracker.dispose();
});
test('pause/resume/cancel and late events never turn cancelled downloads into ready files', async t => {
  const { store, profile, tracker, begin } = fixture(t); const item = begin(); const id = tracker.activeIds()[0];
  tracker.pause(id); assert.equal(store.get(profile, id).status, 'paused');
  tracker.resume(id); assert.equal(store.get(profile, id).status, 'downloading');
  tracker.cancel(id); await tracker.whenIdle(); item.received = 999; item.emit('updated', {}, 'progressing'); item.emit('done', {}, 'completed');
  assert.equal(store.get(profile, id).status, 'cancelled'); assert.equal(store.get(profile, id).sha256, undefined);
  assert.throws(() => store.resolveFile(profile, id), /未就绪/); tracker.dispose();
});
test('cancelling during hash processing stops promotion without a false success notice', async t => {
  const { store, profile, tracker, begin, errors } = fixture(t); const item = begin(); const id = tracker.activeIds()[0];
  item.finish('x'.repeat(1_000_000)); tracker.cancel(id); await tracker.whenIdle();
  assert.equal(store.get(profile, id).status, 'cancelled'); assert.equal(store.get(profile, id).sha256, undefined); assert.deepEqual(errors, []); tracker.dispose();
});
test('a removed profile cannot receive a finished download or create another asset', async t => {
  const { store, profile, tracker, begin, setAvailable } = fixture(t); const item = begin(); const id = tracker.activeIds()[0];
  setAvailable(false); item.finish('orphan transfer'); await tracker.whenIdle();
  assert.equal(store.get(profile, id).status, 'cancelled'); assert.equal(begin().ended, true); assert.equal(store.list(profile).length, 1); tracker.dispose();
});
test('native path failure and missing completion bytes are persisted as failures', async t => {
  const { store, profile, tracker, begin, errors } = fixture(t); const broken = new Item(); broken.rejectPath = true; begin(broken);
  assert.equal(broken.ended, true); assert.equal(store.list(profile)[0].status, 'failed'); assert.equal(tracker.activeIds().length, 0);
  const item = begin(); const id = tracker.activeIds()[0]; item.finish(); await tracker.whenIdle();
  assert.equal(store.get(profile, id).status, 'failed'); assert.ok(errors.length >= 2); tracker.dispose();
});
test('disposing removes the session handler and cancels only its own live jobs', async t => {
  const { store, profile, ses, tracker, begin } = fixture(t); begin(); const id = tracker.activeIds()[0];
  tracker.dispose(); await tracker.whenIdle(); assert.equal(ses.listenerCount('will-download'), 0); assert.equal(store.get(profile, id).status, 'cancelled');
});
