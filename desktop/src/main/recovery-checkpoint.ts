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
