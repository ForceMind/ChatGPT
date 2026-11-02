import test from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID, randomBytes, createCipheriv, createDecipheriv } from 'node:crypto';
import { mkdtempSync, readFileSync, writeFileSync, existsSync, rmSync, symlinkSync, unlinkSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { RecoveryStore, RecentlyClosedPages, resumeURL, type RecoveryCipher } from '../src/main/recovery-store';
import { validResume, validWorkspace, type PageResume, type PageSnapshot } from '../src/shared/recovery';
import {
  RecoveryCheckpoints,
  commitRecoveryCheckpoint,
  type PreparedRecoveryCheckpoint
} from '../src/main/recovery-checkpoint';
import { collectCheckpoint } from '../src/main/page-checkpoint';
import type { WebContents } from 'electron';
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

function preparedCheckpoint(canMerge = true, withPrevious = true) {
  const source = record();
  const previous: PageSnapshot = {
    url: source.url,
    title: 'Owned checkpoint fixture',
    messages: [{ role: 'assistant', text: 'Already persisted synthetic reply' }],
    image: 'data:image/jpeg;base64,YQ==',
    truncated: false,
    capturedAt: source.capturedAt
  };
  if (withPrevious) source.page = previous;
  const next: PageSnapshot = {
    ...previous,
    messages: [{ role: 'assistant', text: 'New synthetic viewport reply' }],
    image: 'data:image/jpeg;base64,Yg==',
    capturedAt: previous.capturedAt + 1
  };
  const original = structuredClone(source),
    originalNext = structuredClone(next);
  for (const page of [previous, next]) {
    page.messages.forEach(Object.freeze);
    Object.freeze(page.messages);
    Object.freeze(page);
  }
  Object.freeze(source);
  const snapshotGate = deferred(),
    snapshotStarted = deferred(),
    saveStarted = deferred(),
    resumeAcknowledged = deferred();
  const attempted: PageResume[] = [],
    persisted: PageResume[] = [],
    promoted: PageSnapshot[] = [];
  const events: string[] = [];
  const state = { available: true, resumeAcks: 0, imageAcks: 0, now: 0 };
  const hooks: { save?: (value: PageResume, index: number) => Promise<void> } = {};
  const captured: PreparedRecoveryCheckpoint = {
    record: source,
    canMerge: () => canMerge,
    acknowledge: () => {
      state.resumeAcks++;
      events.push('resume-ack');
      resumeAcknowledged.resolve();
    },
    snapshot: async () => {
      events.push('snapshot');
      snapshotStarted.resolve();
      await snapshotGate.promise;
      return {
        page: next,
        acknowledge: () => {
          state.imageAcks++;
          events.push('image-ack');
        }
      };
    }
  };
  const options = {
    now: () => state.now,
    available: () => state.available,
    save: async (value: PageResume) => {
      const index = attempted.push(value) - 1;
      events.push('save:' + index);
      saveStarted.resolve();
      await hooks.save?.(value, index);
      persisted.push(value);
      events.push('stored:' + index);
    },
    promote: (page: PageSnapshot) => {
      promoted.push(page);
      events.push('promote');
    }
  };
  const assertUnchanged = () => {
    assert.deepEqual(source, original);
    assert.deepEqual(next, originalNext);
  };
  return {
    source,
    previous,
    next,
    captured,
    options,
    state,
    hooks,
    attempted,
    persisted,
    promoted,
    events,
    snapshotGate,
    snapshotStarted,
    saveStarted,
    resumeAcknowledged,
    assertUnchanged
  };
}

const checkpointTurn = () => new Promise<void>((resolve) => setImmediate(resolve));

test('mergeable snapshot ready at 199ms makes one write and acknowledges only after that write', async (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const fixture = preparedCheckpoint();
  const saveGate = deferred();
  fixture.hooks.save = () => saveGate.promise;
  const operation = commitRecoveryCheckpoint(fixture.captured, fixture.options);
  await fixture.snapshotStarted.promise;
  fixture.state.now = 199;
  t.mock.timers.tick(199);
  assert.equal(fixture.attempted.length, 0);
  assert.equal(fixture.state.resumeAcks, 0);
  fixture.snapshotGate.resolve();
  await fixture.saveStarted.promise;
  assert.equal(fixture.attempted.length, 1);
  assert.equal(fixture.attempted[0].page, fixture.next);
  assert.equal(fixture.state.resumeAcks, 0);
  assert.equal(fixture.state.imageAcks, 0);
  assert.deepEqual(fixture.promoted, []);
  saveGate.resolve();
  await operation;
  t.mock.timers.tick(1);
  assert.equal(fixture.persisted.length, 1);
  assert.equal(fixture.state.resumeAcks, 1);
  assert.equal(fixture.state.imageAcks, 1);
  assert.deepEqual(fixture.promoted, [fixture.next]);
  assert.deepEqual(fixture.events, ['snapshot', 'save:0', 'stored:0', 'resume-ack', 'promote', 'image-ack']);
  fixture.assertUnchanged();
});

test('snapshot still pending at 200ms writes the reading state with the old image before one late image write', async (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const fixture = preparedCheckpoint();
  let settled = false;
  const operation = commitRecoveryCheckpoint(fixture.captured, fixture.options).then(() => {
    settled = true;
  });
  await fixture.snapshotStarted.promise;
  t.mock.timers.tick(199);
  assert.equal(fixture.attempted.length, 0);
  t.mock.timers.tick(1);
  await fixture.resumeAcknowledged.promise;
  assert.equal(settled, false);
  assert.deepEqual(fixture.persisted, [fixture.source]);
  assert.equal(fixture.persisted[0].page, fixture.previous);
  assert.equal(fixture.state.resumeAcks, 1);
  assert.equal(fixture.state.imageAcks, 0);
  fixture.snapshotGate.resolve();
  await operation;
  assert.equal(fixture.persisted.length, 2);
  assert.equal(fixture.persisted[1].page, fixture.next);
  assert.equal(fixture.state.resumeAcks, 1);
  assert.equal(fixture.state.imageAcks, 1);
  assert.deepEqual(fixture.events, [
    'snapshot',
    'save:0',
    'stored:0',
    'resume-ack',
    'save:1',
    'stored:1',
    'promote',
    'image-ack'
  ]);
  fixture.assertUnchanged();
});

for (const reason of ['critical draft or strict checkpoint', 'initial record'] as const) {
  test(`${reason} is persisted and acknowledged before starting any image work`, async () => {
    const fixture = preparedCheckpoint(false, reason !== 'initial record');
    const saveGate = deferred();
    fixture.hooks.save = async (_value, index) => {
      if (index === 0) await saveGate.promise;
    };
    const operation = commitRecoveryCheckpoint(fixture.captured, fixture.options);
    await fixture.saveStarted.promise;
    assert.deepEqual(fixture.events, ['save:0']);
    assert.equal(fixture.state.resumeAcks, 0);
    assert.equal(fixture.attempted[0].page, reason === 'initial record' ? undefined : fixture.previous);
    saveGate.resolve();
    await fixture.snapshotStarted.promise;
    assert.deepEqual(fixture.persisted, [fixture.source]);
    assert.equal(fixture.state.resumeAcks, 1);
    assert.deepEqual(fixture.events, ['save:0', 'stored:0', 'resume-ack', 'snapshot']);
    fixture.snapshotGate.resolve();
    await operation;
    assert.equal(fixture.persisted.length, 2);
    assert.equal(fixture.persisted[1].page, fixture.next);
    assert.equal(fixture.state.imageAcks, 1);
    fixture.assertUnchanged();
  });
}

for (const canMerge of [true, false]) {
  test(`rejected image capture preserves the current encrypted record and old image (merge=${canMerge})`, async () => {
    const fixture = preparedCheckpoint(canMerge);
    const operation = commitRecoveryCheckpoint(fixture.captured, fixture.options);
    await fixture.snapshotStarted.promise;
    fixture.snapshotGate.reject(Error('synthetic capture rejection'));
    await operation;
    assert.deepEqual(fixture.persisted, [fixture.source]);
    assert.equal(fixture.persisted[0].page, fixture.previous);
    assert.equal(fixture.state.resumeAcks, 1);
    assert.equal(fixture.state.imageAcks, 0);
    assert.deepEqual(fixture.promoted, []);
    fixture.assertUnchanged();
  });
}

for (const canMerge of [true, false]) {
  test(`failed first save propagates without acknowledging or promoting (merge=${canMerge})`, async () => {
    const fixture = preparedCheckpoint(canMerge);
    const failure = Error('synthetic encrypted save failure');
    fixture.hooks.save = async () => {
      throw failure;
    };
    const rejected = assert.rejects(
      commitRecoveryCheckpoint(fixture.captured, fixture.options),
      (error) => error === failure
    );
    if (canMerge) {
      await fixture.snapshotStarted.promise;
      fixture.snapshotGate.resolve();
    }
    await rejected;
    assert.equal(fixture.attempted.length, 1);
    assert.deepEqual(fixture.persisted, []);
    assert.equal(fixture.state.resumeAcks, 0);
    assert.equal(fixture.state.imageAcks, 0);
    assert.deepEqual(fixture.promoted, []);
    assert.equal(fixture.events.includes('snapshot'), canMerge);
    fixture.assertUnchanged();
  });
}

test('a failed budget write retains the queue slot until the original image settles, then propagates the save error', async (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const fixture = preparedCheckpoint();
  const queue = new RecoveryCheckpoints();
  const failure = Error('synthetic budget save failure');
  fixture.hooks.save = async () => {
    throw failure;
  };
  let settled = false,
    replacements = 0;
  const operation = queue.run('fixture-page', false, () => commitRecoveryCheckpoint(fixture.captured, fixture.options));
  void operation.then(
    () => {
      settled = true;
    },
    () => {
      settled = true;
    }
  );
  const rejected = assert.rejects(operation, (error) => error === failure);
  await fixture.snapshotStarted.promise;
  t.mock.timers.tick(200);
  await checkpointTurn();
  assert.equal(fixture.attempted.length, 1);
  assert.equal(settled, false);
  const follower = queue.run('fixture-page', true, async () => {
    replacements++;
  });
  const followerRejected = assert.rejects(follower, (error) => error === failure);
  await checkpointTurn();
  assert.equal(replacements, 0);
  fixture.snapshotGate.resolve();
  await Promise.all([rejected, followerRejected]);
  assert.deepEqual(fixture.persisted, []);
  assert.equal(fixture.state.resumeAcks, 0);
  assert.equal(fixture.state.imageAcks, 0);
  assert.deepEqual(fixture.promoted, []);
  await queue.run('fixture-page', false, async () => {
    replacements++;
  });
  assert.equal(replacements, 1);
  fixture.assertUnchanged();
});

test('a failed late image save preserves the already durable draft acknowledgement but never promotes the image', async (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const fixture = preparedCheckpoint();
  const failure = Error('synthetic late save failure');
  fixture.hooks.save = async (_value, index) => {
    if (index === 1) throw failure;
  };
  const operation = commitRecoveryCheckpoint(fixture.captured, fixture.options);
  const rejected = assert.rejects(operation, (error) => error === failure);
  await fixture.snapshotStarted.promise;
  t.mock.timers.tick(200);
  await fixture.resumeAcknowledged.promise;
  fixture.snapshotGate.resolve();
  await rejected;
  assert.equal(fixture.attempted.length, 2);
  assert.deepEqual(fixture.persisted, [fixture.source]);
  assert.equal(fixture.state.resumeAcks, 1);
  assert.equal(fixture.state.imageAcks, 0);
  assert.deepEqual(fixture.promoted, []);
  fixture.assertUnchanged();
});

test('an invalid checkpoint never starts capture or persistence', async () => {
  const fixture = preparedCheckpoint();
  fixture.state.available = false;
  await commitRecoveryCheckpoint(fixture.captured, fixture.options);
  assert.deepEqual(fixture.events, []);
});

for (const timing of ['before ready image', 'at budget', 'after budget save'] as const) {
  test(`availability lost ${timing} prevents late writes and confirmations`, async (t) => {
    t.mock.timers.enable({ apis: ['setTimeout'] });
    const fixture = preparedCheckpoint();
    let settled = false;
    const operation = commitRecoveryCheckpoint(fixture.captured, fixture.options).then(() => {
      settled = true;
    });
    await fixture.snapshotStarted.promise;
    if (timing === 'after budget save') {
      t.mock.timers.tick(200);
      await fixture.resumeAcknowledged.promise;
    } else t.mock.timers.tick(199);
    fixture.state.available = false;
    if (timing === 'at budget') {
      t.mock.timers.tick(1);
      await checkpointTurn();
      assert.equal(settled, false);
    }
    const existingWrites = timing === 'after budget save' ? 1 : 0;
    fixture.snapshotGate.resolve();
    await operation;
    assert.equal(fixture.attempted.length, existingWrites);
    assert.equal(fixture.state.resumeAcks, existingWrites);
    assert.equal(fixture.state.imageAcks, 0);
    assert.deepEqual(fixture.promoted, []);
    fixture.assertUnchanged();
  });
}

for (const canMerge of [true, false]) {
  test(`availability lost during encryption prevents late acknowledgements (merge=${canMerge})`, async () => {
    const fixture = preparedCheckpoint(canMerge);
    const saveGate = deferred();
    fixture.hooks.save = () => saveGate.promise;
    const operation = commitRecoveryCheckpoint(fixture.captured, fixture.options);
    if (canMerge) {
      await fixture.snapshotStarted.promise;
      fixture.snapshotGate.resolve();
    }
    await fixture.saveStarted.promise;
    fixture.state.available = false;
    saveGate.resolve();
    await operation;
    assert.equal(fixture.attempted.length, 1);
    assert.equal(fixture.state.resumeAcks, 0);
    assert.equal(fixture.state.imageAcks, 0);
    assert.deepEqual(fixture.promoted, []);
    assert.equal(fixture.events.includes('snapshot'), canMerge);
    fixture.assertUnchanged();
  });
}

for (const elapsed of [200, 250]) {
  test(`ready snapshot after ${elapsed}ms cannot bypass the monotonic budget when its timer has not fired`, async (t) => {
    t.mock.timers.enable({ apis: ['setTimeout'] });
    const fixture = preparedCheckpoint();
    const operation = commitRecoveryCheckpoint(fixture.captured, fixture.options);
    await fixture.snapshotStarted.promise;
    // Model a blocked main thread: monotonic time advances, but the budget
    // timer's callback has not had an event-loop turn to run.
    fixture.state.now = elapsed;
    fixture.snapshotGate.resolve();
    await operation;
    assert.equal(fixture.persisted.length, 2);
    assert.equal(fixture.persisted[0].page, fixture.previous);
    assert.equal(fixture.persisted[1].page, fixture.next);
    assert.equal(fixture.state.resumeAcks, 1);
    assert.equal(fixture.state.imageAcks, 1);
    t.mock.timers.tick(200);
    assert.equal(fixture.persisted.length, 2);
    fixture.assertUnchanged();
  });
}

test('checkpoint classification merges only acknowledged viewport changes from the same known document and owner', async () => {
  const origin = 'http://127.0.0.1:18080';
  const identity = record();
  let url = origin + '/c/checkpoint-fixture';
  const observed = { document: 'owned-document-1', draft: 1, viewport: 1, conversation: 1, known: true };
  const reading = { draft: 'Already saved synthetic draft', scroll: 0, ratio: 0 };
  const previous: PageSnapshot = {
    url,
    title: 'Owned snapshot',
    messages: [{ role: 'assistant', text: 'Owned answer' }],
    image: 'data:image/jpeg;base64,YQ==',
    truncated: false,
    capturedAt: Date.now()
  };
  const contents = {
    isDestroyed: () => false,
    isCrashed: () => false,
    isLoadingMainFrame: () => false,
    getURL: () => url,
    executeJavaScriptInIsolatedWorld: async (world: number) => {
      assert.equal(world, 1017);
      return { ...observed };
    },
    executeJavaScript: async (code: string) => {
      if (/\bfunction\s+pageState\s*\(/.test(code)) return { ...reading };
      assert.match(code, /\bfunction\s+readConversation\s*\(/);
      return { title: previous.title, messages: previous.messages, truncated: false };
    },
    capturePage: async () => {
      throw Error('Synthetic unavailable viewport; keep the old image');
    }
  } as unknown as WebContents;
  const collect = (strict = false, alternative?: string, profileId = identity.profileId, tabId = identity.tabId) =>
    collectCheckpoint(contents, profileId, tabId, previous, strict, () => true, origin, alternative);
  const first = (await collect())!;
  assert.equal(first.canMerge(), false);
  // A capture attempt makes the 30s snapshot boundary ineligible. A quiet
  // alternative-draft change below must therefore trigger collection itself.
  assert.equal(await first.snapshot(), undefined);
  observed.viewport++;
  assert.equal((await collect())!.canMerge(), false);
  first.acknowledge();
  const viewport = (await collect())!;
  assert.equal(viewport.canMerge(), true);
  viewport.acknowledge();
  assert.equal(await collect(), undefined);

  reading.draft = 'Programmatically changed synthetic draft';
  observed.draft++;
  observed.viewport++;
  const draft = (await collect())!;
  assert.equal(draft.canMerge(), false);
  draft.acknowledge();
  assert.equal(await collect(), undefined);
  const alternative = (await collect(false, 'Owned conflicting draft'))!;
  assert.equal(alternative.record.alternativeDraft, 'Owned conflicting draft');
  assert.equal(alternative.canMerge(), false);
  alternative.acknowledge();
  assert.equal(await collect(false, 'Owned conflicting draft'), undefined);
  observed.viewport++;
  assert.equal((await collect(false, 'Owned conflicting draft'))!.canMerge(), true);
  assert.equal((await collect(true, 'Owned conflicting draft'))!.canMerge(), false);
  assert.equal((await collect(false, 'Owned conflicting draft', randomUUID()))!.canMerge(), false);
  assert.equal((await collect(false, 'Owned conflicting draft', identity.profileId, randomUUID()))!.canMerge(), false);
  assert.equal((await collect())!.canMerge(), false, 'removing a conflicting draft is also critical');
  observed.known = false;
  assert.equal((await collect(false, 'Owned conflicting draft'))!.canMerge(), false);
  observed.known = true;
  observed.document = 'owned-document-2';
  assert.equal((await collect(false, 'Owned conflicting draft'))!.canMerge(), false);
  assert.equal(viewport.canMerge(), false, 'an old document cannot retain merge permission');
  url = origin + '/c/another-owned-conversation';
  assert.equal((await collect(false, 'Owned conflicting draft'))!.canMerge(), false);
});

test('schema-1 encrypted records created before checkpoint coalescing remain readable without new metadata', async () => {
  const root = mkdtempSync(path.join(os.tmpdir(), 'recovery-legacy-checkpoint-'));
  try {
    const crypto = cipher(),
      store = new RecoveryStore(root, crypto),
      legacy = record();
    legacy.alternativeDraft = 'Synthetic older conflicting draft';
    legacy.page = {
      url: legacy.url,
      title: 'Old owned snapshot',
      messages: [{ role: 'assistant', text: 'Old owned text' }],
      image: 'data:image/jpeg;base64,YQ==',
      truncated: false,
      capturedAt: legacy.capturedAt
    };
    assert.equal(await store.load(legacy.profileId, legacy.tabId, legacy.url), undefined);
    const file = path.join(store.directory, legacy.profileId, legacy.tabId + '.bin');
    writeFileSync(file, await crypto.encrypt(JSON.stringify(legacy)));
    assert.deepEqual(await store.load(legacy.profileId, legacy.tabId, legacy.url), legacy);
    assert.equal(readFileSync(file).includes(Buffer.from(legacy.draft)), false);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test('availability lost during real recovery encryption preserves old ciphertext and permits an identical later retry', async () => {
  const root = mkdtempSync(path.join(os.tmpdir(), 'recovery-checkpoint-cancel-'));
  try {
    const crypto = cipher(),
      started = deferred(),
      gate = deferred();
    let available = true,
      delay = false,
      encryptions = 0;
    const store = new RecoveryStore(root, {
      ...crypto,
      encrypt: async (value) => {
        encryptions++;
        if (delay) {
          started.resolve();
          await gate.promise;
        }
        return crypto.encrypt(value);
      }
    });
    const previous = record();
    await store.save(previous);
    const file = path.join(store.directory, previous.profileId, previous.tabId + '.bin');
    const before = readFileSync(file);
    const next = { ...previous, draft: 'Cancelled synthetic draft', capturedAt: previous.capturedAt + 1 };
    delay = true;
    const pending = store.save(next, () => available);
    await started.promise;
    available = false;
    gate.resolve();
    await pending;
    assert.deepEqual(readFileSync(file), before);
    assert.equal(existsSync(file + '.bak'), false);
    assert.deepEqual(await store.load(previous.profileId, previous.tabId, previous.url), previous);
    available = true;
    await store.save(next, () => available);
    assert.equal(encryptions, 3);
    assert.deepEqual(await store.load(next.profileId, next.tabId, next.url), next);
    assert.deepEqual(readFileSync(file + '.bak'), before);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});
