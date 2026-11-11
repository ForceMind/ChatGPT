import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import worker from './worker.mjs';

const key = 'a'.repeat(64);
const origin = 'https://studio-android-acceptance.pages.dev';
globalThis.FixedLengthStream = class {
  constructor(expected) {
    let count = 0;
    const stream = new TransformStream({
      transform(chunk, controller) {
        count += chunk.byteLength;
        if (count > expected) throw Error('Too many bytes');
        controller.enqueue(chunk);
      },
      flush() { if (count !== expected) throw Error('Too few bytes'); }
    });
    this.readable = stream.readable;
    this.writable = stream.writable;
  }
};

function fixture(broken = false) {
  const calls = [], pending = [];
  const metadata = { feed: { versionName: '2026.11.11-1200', size: 6 }, parts: [
    { path: '/private/part-000.bin', size: 3 }, { path: '/private/part-001.bin', size: 3 }
  ] };
  const env = {
    ACCEPTANCE_CODE_SHA256: createHash('sha256').update(key).digest('hex'),
    ASSETS: {
      async fetch(request) {
        const path = new URL(request.url).pathname;
        calls.push(path);
        if (path === '/private/source.json') return Response.json(metadata);
        const bytes = new TextEncoder().encode(path.endsWith('000.bin') ? 'abc' : 'def');
        return new Response(broken ? bytes.slice(0, 2) : bytes);
      }
    }
  };
  const context = { waitUntil(promise) { pending.push(promise); } };
  const get = (path, code, range) => {
    const headers = new Headers();
    if (code) headers.set('X-Studio-Acceptance', code);
    if (range) headers.set('Range', range);
    return worker.fetch(new Request(origin + path, { headers }), env, context);
  };
  return { get, calls, pending };
}

test('the private source rejects missing or wrong access codes and all direct asset requests', async () => {
  const { get, calls } = fixture();
  for (const [path, code] of [
    ['/android.json', undefined], ['/update.apk', 'b'.repeat(64)],
    ['/private/source.json', key], ['/private/part-000.bin', key], ['/', key]
  ]) assert.equal((await get(path, code)).status, 404);
  assert.deepEqual(calls, []);
});

test('authorized metadata and APK bytes use the guarded source and stream in order', async () => {
  const { get, calls, pending } = fixture();
  const feed = await get('/android.json', key);
  assert.equal(feed.status, 200);
  assert.equal((await feed.json()).size, 6);
  const download = await get('/update.apk', key);
  assert.equal(download.status, 200);
  assert.equal(await download.text(), 'abcdef');
  await Promise.all(pending);
  assert.deepEqual(calls, ['/private/source.json', '/private/source.json', '/private/part-000.bin', '/private/part-001.bin']);
  assert.match(download.headers.get('Cache-Control'), /no-store/);
});

test('a changed part aborts the streamed package instead of returning a successful truncated APK', async () => {
  const { get, pending } = fixture(true);
  const download = await get('/update.apk', key);
  await assert.rejects(download.arrayBuffer());
  await Promise.all(pending);
});


test('authenticated readback serves only an exact requested part with its original offset', async () => {
  const { get, pending } = fixture();
  assert.equal((await get('/update.apk', undefined, 'bytes=3-5')).status, 404);
  assert.equal((await get('/update.apk', key, 'bytes=2-5')).status, 416);
  const download = await get('/update.apk', key, 'bytes=3-5');
  assert.equal(download.status, 206);
  assert.equal(download.headers.get('Content-Range'), 'bytes 3-5/6');
  assert.equal(await download.text(), 'def');
  await Promise.all(pending);
});
