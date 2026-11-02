import test from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID, randomBytes, createCipheriv, createDecipheriv } from 'node:crypto';
import { mkdtempSync, readFileSync, writeFileSync, existsSync, rmSync, symlinkSync, unlinkSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { RecoveryStore, RecentlyClosedPages, resumeURL, type RecoveryCipher } from '../src/main/recovery-store';
import { validResume, validWorkspace, type PageResume } from '../src/shared/recovery';
import { RecoveryCheckpoints } from '../src/main/recovery-checkpoint';
function cipher(): RecoveryCipher {
  const key = randomBytes(32);
  return {
    encrypt: async (text) => {
      const iv = randomBytes(12),
        c = createCipheriv('aes-256-gcm', key, iv),
        body = Buffer.concat([c.update(text, 'utf8'), c.final()]);
      return Buffer.concat([iv, c.getAuthTag(), body]);
    },
    decrypt: async (bytes) => {
      const c = createDecipheriv('aes-256-gcm', key, bytes.subarray(0, 12));
      c.setAuthTag(bytes.subarray(12, 28));
      return Buffer.concat([c.update(bytes.subarray(28)), c.final()]).toString('utf8');
    }
  };
}
const record = (): PageResume => ({
  schema: 1,
  profileId: randomUUID(),
  tabId: randomUUID(),
  url: 'https://chatgpt.com/c/fixture',
  draft: '恢复草稿 secret-not-plaintext',
  scroll: 1234,
  ratio: 0.5,
  capturedAt: Date.now()
});
test('recovery stores encrypted bounded bytes, checks ownership/URL and recovers valid encrypted backups', async () => {
  const root = mkdtempSync(path.join(os.tmpdir(), 'recovery-store-'));
  try {
    const crypto = cipher(),
      s = new RecoveryStore(root, crypto),
      a = record();
    await s.save(a);
    const file = path.join(s.directory, a.profileId, a.tabId + '.bin');
    assert.equal(readFileSync(file).includes(Buffer.from(a.draft)), false);
    assert.deepEqual(await s.load(a.profileId, a.tabId, a.url), a);
    assert.equal(await s.load(a.profileId, a.tabId, 'https://chatgpt.com/c/another'), undefined);
    await s.save({ ...a, draft: 'second', capturedAt: a.capturedAt + 1 });
    writeFileSync(file, 'corrupt');
    assert.deepEqual(await s.load(a.profileId, a.tabId, a.url), a);
    writeFileSync(file + '.bak', 'also corrupt');
    await assert.rejects(s.load(a.profileId, a.tabId, a.url), /无法解密/);
    assert.equal(readFileSync(file, 'utf8'), 'corrupt');
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});
test('account removal cancels late encryption and pruning removes only expired owned records', async () => {
  const root = mkdtempSync(path.join(os.tmpdir(), 'recovery-store-'));
  try {
    const crypto = cipher();
    let release!: () => void, started!: () => void;
    const gate = new Promise<void>((r) => {
        release = r;
      }),
      began = new Promise<void>((r) => {
        started = r;
      });
    const s = new RecoveryStore(root, {
        ...crypto,
        encrypt: async (value) => {
          started();
          await gate;
          return crypto.encrypt(value);
        }
      }),
      a = record();
    const pending = s.save(a);
    await began;
    const removing = s.removeProfile(a.profileId);
    release();
    await Promise.all([pending, removing]);
    assert.equal(existsSync(path.join(s.directory, a.profileId)), false);
    const other = record();
    await s.save(other);
    s.prune([{ profileId: other.profileId, id: other.tabId }]);
    assert.deepEqual(await s.load(other.profileId, other.tabId, other.url), other);
    s.prune([]);
    assert.equal(await s.load(other.profileId, other.tabId, other.url), undefined);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});
test('swapped account records and symlink files cannot restore another account or expose outside bytes', async () => {
  const root = mkdtempSync(path.join(os.tmpdir(), 'recovery-store-'));
  try {
    const crypto = cipher(),
      s = new RecoveryStore(root, crypto),
      a = record(),
      b = record();
    await s.save(a);
    await s.save(b);
    const f = path.join(s.directory, b.profileId, b.tabId + '.bin');
    writeFileSync(f, readFileSync(path.join(s.directory, a.profileId, a.tabId + '.bin')));
    await assert.rejects(s.load(b.profileId, b.tabId, b.url), /无法解密/);
    unlinkSync(f);
    const outside = path.join(root, 'outside');
    writeFileSync(outside, 'private');
    symlinkSync(outside, f);
    await assert.rejects(s.load(b.profileId, b.tabId, b.url), /无法解密/);
    assert.equal(readFileSync(outside, 'utf8'), 'private');
    await assert.rejects(s.load('../outside', b.tabId, b.url), /归属/);
    assert.equal(validResume({ ...a, draft: 'x'.repeat(100001) }), false);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});
test('closed-page cache limits apply only to explicit closes, restore keeps instances and TTL expires without reloading open pages', () => {
  let now = 0;
  const destroyed: string[] = [];
  const cache = new RecentlyClosedPages(
    (id) => destroyed.push(id),
    () => now
  );
  try {
    for (let i = 0; i < 6; i++) cache.remember('closed-' + i);
    assert.deepEqual(destroyed, ['closed-0']);
    assert.equal(cache.take('closed-1'), true);
    now = 600001;
    cache.prune();
    assert.equal(destroyed.includes('closed-1'), false);
    assert.equal(destroyed.length, 5);
    assert.equal(cache.take('closed-5'), false);
  } finally {
    cache.dispose();
  }
});
test('recovery excludes login/temporary/cross-origin pages and validates bounded workspace state', () => {
  assert.equal(resumeURL('https://auth.openai.com/login'), undefined);
  assert.equal(resumeURL('https://chatgpt.com/auth/callback?code=secret'), undefined);
  assert.equal(resumeURL('https://chatgpt.com/?temporary-chat=true'), undefined);
  assert.equal(resumeURL('https://evil.test/'), undefined);
  assert.equal(resumeURL('https://chatgpt.com/c/test?token=redacted#part'), 'https://chatgpt.com/c/test');
  const value = {
    filter: 'all',
    query: '素材',
    sort: 'recent',
    layout: 'grid',
    libraryKind: 'skills',
    libraryFilter: 'all',
    libraryQuery: '',
    favoritesOnly: false,
    scroll: 0,
    libraryScroll: 0
  };
  assert.equal(validWorkspace({ panel: 'assets', workbench: value }), true);
  assert.equal(validWorkspace({ workbench: { ...value, selected: '../secret' } }), false);
  assert.equal(validWorkspace({ workbench: { ...value, query: 'x'.repeat(301) } }), false);
  assert.equal(validWorkspace({ panel: 'external' }), false);
});

test('workspace backup recovery preserves orphaned encrypted originals until explicit account clearing', async () => {
  const root = mkdtempSync(path.join(os.tmpdir(), 'recovery-store-'));
  try {
    const crypto = cipher(),
      a = record(),
      s = new RecoveryStore(root, crypto);
    await s.save(a);
    s.preserveOrphans();
    s.prune([]);
    const reopen = new RecoveryStore(root, crypto);
    reopen.prune([]);
    assert.deepEqual(await reopen.load(a.profileId, a.tabId, a.url), a);
    await reopen.removeProfile(a.profileId);
    assert.equal(existsSync(path.join(reopen.directory, a.profileId)), false);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test('optional inert page snapshots remain encrypted, bounded and tied to the exact recovery URL', async () => {
  const root = mkdtempSync(path.join(os.tmpdir(), 'snapshot-store-'));
  try {
    const s = new RecoveryStore(root, cipher()),
      a = record();
    a.page = {
      url: a.url,
      title: '私有标题',
      messages: [{ role: 'assistant', text: 'private transcript <script>alert(1)</script>' }],
      truncated: false,
      capturedAt: a.capturedAt
    };
    await s.save(a);
    assert.deepEqual(await s.load(a.profileId, a.tabId, a.url), a);
    assert.equal(
      readFileSync(path.join(s.directory, a.profileId, a.tabId + '.bin')).includes(Buffer.from('private transcript')),
      false
    );
    assert.equal(validResume({ ...a, page: { ...a.page, url: 'https://chatgpt.com/c/other' } }), false);
    assert.equal(validResume({ ...a, page: { ...a.page, image: 'https://evil.example/tracker' } }), false);
    assert.equal(
      validResume({ ...a, page: { ...a.page, messages: [{ role: 'assistant', text: 'x'.repeat(100001) }] } }),
      false
    );
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test('timestamp-only nested snapshots skip encryption and disk replacement while content changes still persist', async () => {
  const root = mkdtempSync(path.join(os.tmpdir(), 'recovery-deduplicate-'));
  try {
    const crypto = cipher();
    let encryptions = 0;
    const store = new RecoveryStore(root, {
      ...crypto,
      encrypt: async (text) => {
        encryptions++;
        return crypto.encrypt(text);
      }
    });
    const first = record();
    first.page = {
      url: first.url,
      title: '合成恢复快照',
      messages: [{ role: 'assistant', text: '原始合成回答' }],
      image: 'data:image/jpeg;base64,YQ==',
      truncated: false,
      capturedAt: first.capturedAt
    };
    const file = path.join(store.directory, first.profileId, first.tabId + '.bin');
    await store.save(first);
    const initialBytes = readFileSync(file);
    await store.save({ ...first, capturedAt: first.capturedAt + 1 });
    await store.save({
      ...first,
      capturedAt: first.capturedAt + 2,
      page: { ...first.page, capturedAt: first.capturedAt + 2 }
    });
    assert.equal(encryptions, 1);
    assert.deepEqual(readFileSync(file), initialBytes);
    assert.equal(existsSync(file + '.bak'), false);
    assert.deepEqual(await store.load(first.profileId, first.tabId, first.url), first);

    let latest: PageResume = {
      ...first,
      page: { ...first.page, messages: [{ role: 'assistant', text: '更新后的合成回答' }] }
    };
    await store.save(latest);
    assert.equal(encryptions, 2);
    assert.deepEqual(await store.load(first.profileId, first.tabId, first.url), latest);
    assert.deepEqual(readFileSync(file + '.bak'), initialBytes);
    latest = { ...latest, page: { ...latest.page!, image: 'data:image/jpeg;base64,Yg==' } };
    await store.save(latest);
    assert.equal(encryptions, 3);
    assert.deepEqual(await store.load(first.profileId, first.tabId, first.url), latest);
    latest = { ...latest, draft: '程序设置的合成草稿，未发送' };
    await store.save(latest);
    assert.equal(encryptions, 4);
    assert.deepEqual(await store.load(first.profileId, first.tabId, first.url), latest);
    await store.save({
      ...latest,
      capturedAt: latest.capturedAt + 20,
      page: { ...latest.page!, capturedAt: latest.capturedAt + 20 }
    });
    assert.equal(encryptions, 4);
    assert.equal(readFileSync(file).includes(Buffer.from(latest.draft)), false);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

function deferred() {
  let resolve!: () => void;
  let reject!: (reason: Error) => void;
  const promise = new Promise<void>((yes, no) => {
    resolve = yes;
    reject = no;
  });
  return { promise, resolve, reject };
}

test('same-page checkpoints coalesce overlapping requests into one strict final pass', async () => {
  const queue = new RecoveryCheckpoints();
  const first = deferred(),
    final = deferred(),
    firstStarted = deferred(),
    finalStarted = deferred();
  const passes: boolean[] = [];
  let active = 0,
    maximum = 0;
  const work = async (strict: boolean) => {
    maximum = Math.max(maximum, ++active);
    passes.push(strict);
    const current = passes.length === 1 ? first : final;
    (passes.length === 1 ? firstStarted : finalStarted).resolve();
    try {
      await current.promise;
    } finally {
      active--;
    }
  };
  const waiters = [queue.run('page-a', false, work)];
  await firstStarted.promise;
  waiters.push(queue.run('page-a', false, work), queue.run('page-a', true, work), queue.run('page-a', false, work));
  assert.deepEqual(passes, [false]);
  first.resolve();
  await finalStarted.promise;
  assert.deepEqual(passes, [false, true]);
  assert.equal(maximum, 1);
  let finished = false;
  const all = Promise.all(waiters).then(() => {
    finished = true;
  });
  await Promise.resolve();
  assert.equal(finished, false);
  final.resolve();
  await all;
  assert.deepEqual(passes, [false, true]);
  assert.equal(active, 0);
});

test('checkpoint keys isolate native pages and failures release only their own key for retry', async () => {
  const queue = new RecoveryCheckpoints();
  const first = deferred(),
    second = deferred(),
    firstStarted = deferred(),
    secondStarted = deferred();
  const passes: string[] = [];
  const a = queue.run('page-a', false, async (strict) => {
    assert.equal(strict, false);
    passes.push('a');
    firstStarted.resolve();
    await first.promise;
  });
  const b = queue.run('page-b', true, async (strict) => {
    assert.equal(strict, true);
    passes.push('b');
    secondStarted.resolve();
    await second.promise;
  });
  await Promise.all([firstStarted.promise, secondStarted.promise]);
  assert.deepEqual(passes, ['a', 'b']);
  second.resolve();
  await b;
  assert.equal(passes.length, 2);
  const rejected = assert.rejects(a, /synthetic capture failed/);
  first.reject(Error('synthetic capture failed'));
  await rejected;
  await queue.run('page-a', false, async () => {
    passes.push('a-retry');
  });
  assert.deepEqual(passes, ['a', 'b', 'a-retry']);
});

for (const settlement of ['resolve', 'reject'] as const) {
  test(`timed-out checkpoints invalidate old work without parallel replacement until it ${settlement}s`, async (t) => {
    t.mock.timers.enable({ apis: ['setTimeout'] });
    const queue = new RecoveryCheckpoints(20);
    const gate = deferred(),
      started = deferred();
    let available!: () => boolean;
    let calls = 0;
    const original = queue.run('slow-page', false, async (_strict, current) => {
      calls++;
      available = current;
      started.resolve();
      await gate.promise;
    });
    const originalRejected = assert.rejects(original, /恢复记录处理超时/);
    await started.promise;
    assert.equal(available(), true);
    t.mock.timers.tick(20);
    assert.equal(available(), false);
    const follower = queue.run('slow-page', true, async () => {
      calls++;
    });
    const followerRejected = assert.rejects(follower, /恢复记录处理超时|synthetic old failure/);
    t.mock.timers.tick(20);
    await originalRejected;
    const lateFollower = queue.run('slow-page', true, async () => {
      calls++;
    });
    const lateFollowerRejected = assert.rejects(lateFollower, /恢复记录处理超时|synthetic old failure/);
    await Promise.resolve();
    assert.equal(calls, 1);
    assert.equal(available(), false);
    if (settlement === 'resolve') gate.resolve();
    else gate.reject(Error('synthetic old failure'));
    await Promise.all([followerRejected, lateFollowerRejected]);
    assert.equal(calls, 1);
    await queue.run('slow-page', false, async (_strict, current) => {
      calls++;
      assert.equal(current(), true);
    });
    assert.equal(calls, 2);
    assert.equal(available(), false);
  });
}
