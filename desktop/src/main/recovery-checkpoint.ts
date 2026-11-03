import type { PageResume, PageSnapshot } from '../shared/recovery';

/** One underlying operation per native page; overlapping requests become one final pass.
 * A timeout rejects the waiter but never releases the slot before the real work settles. */
export class RecoveryCheckpoints {
  private running = new Map<string, { pending: boolean; strict: boolean; promise: Promise<void> }>();
  constructor(private timeoutMs = 4000) {}
  run(key: string, strict: boolean, work: (strict: boolean, available: () => boolean) => Promise<void>): Promise<void> {
    let entry = this.running.get(key);
    if (entry) {
      entry.pending = true;
      entry.strict ||= strict;
    } else {
      const state = { pending: true, strict, promise: Promise.resolve() };
      state.promise = Promise.resolve().then(async () => {
        while (state.pending) {
          const forced = state.strict;
          state.pending = false;
          state.strict = false;
          let expired = false;
          const timer = setTimeout(() => {
            expired = true;
          }, this.timeoutMs);
          try {
            await work(forced, () => !expired);
            if (expired) throw Error('界面恢复记录处理超时，旧恢复记录已保留');
          } finally {
            clearTimeout(timer);
          }
        }
      });
      this.running.set(key, state);
      void state.promise
        .finally(() => {
          if (this.running.get(key) === state) this.running.delete(key);
        })
        .catch(() => {});
      entry = state;
    }
    let timer: ReturnType<typeof setTimeout>;
    return Promise.race([
      entry.promise,
      new Promise<never>((_, reject) => {
        timer = setTimeout(() => reject(Error('界面恢复记录处理超时，旧恢复记录已保留')), this.timeoutMs * 2);
      })
    ]).finally(() => clearTimeout(timer));
  }
}

export type PreparedRecoveryCheckpoint = {
  record: PageResume;
  canMerge: () => boolean;
  acknowledge: () => void;
  snapshot: () => Promise<{ page: PageSnapshot; acknowledge: () => void } | undefined>;
};

/** Coalesce only non-critical updates. Never race the underlying capture out of
 * the existing page queue: after the budget expires we save first, then await it. */
export async function commitRecoveryCheckpoint(
  captured: PreparedRecoveryCheckpoint,
  options: {
    available: () => boolean;
    save: (record: PageResume) => Promise<void>;
    promote: (page: PageSnapshot) => void;
    budgetMs?: number;
    now?: () => number;
  }
): Promise<void> {
  const persist = async (snapshot: Awaited<ReturnType<typeof captured.snapshot>>, resume: boolean) => {
    if (!options.available()) return false;
    await options.save(snapshot ? { ...captured.record, page: snapshot.page } : captured.record);
    if (!options.available()) return false;
    if (resume) captured.acknowledge();
    if (snapshot) {
      options.promote(snapshot.page);
      snapshot.acknowledge();
    }
    return true;
  };
  const snapshot = () =>
    Promise.resolve()
      .then(() => captured.snapshot())
      .catch(() => undefined);
  if (!options.available()) return;
  if (!captured.canMerge()) {
    if (!(await persist(undefined, true))) return;
    const next = await snapshot();
    if (next) await persist(next, false);
    return;
  }
  const now = options.now || (() => performance.now());
  const started = now();
  const budget = options.budgetMs ?? 200;
  const pending = snapshot();
  let timer: ReturnType<typeof setTimeout> | undefined;
  const first = await Promise.race([
    pending.then((value) => ({ kind: 'ready' as const, value })),
    new Promise<{ kind: 'budget' }>((resolve) => {
      timer = setTimeout(() => resolve({ kind: 'budget' }), budget);
    })
  ]).finally(() => clearTimeout(timer));
  if (first.kind === 'ready' && now() - started < budget) {
    await persist(first.value, true);
    return;
  }
  let saved: boolean;
  try {
    saved = await persist(undefined, true);
  } catch (error) {
    await pending;
    throw error;
  }
  if (!saved) {
    // Keep ownership until the original operation really settles, even if a
    // navigation/account change invalidated the result while waiting.
    await pending;
    return;
  }
  const next = await pending;
  if (next) await persist(next, false);
}
