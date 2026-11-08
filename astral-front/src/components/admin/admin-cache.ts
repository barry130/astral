/** Independent keyed read caching: deduplicate in-flight reads and invalidate all old completions. */
export function createAdminCache<T>() {
  let revision = 0;
  const values = new Map<number, T>();
  const pending = new Map<number, Promise<T | undefined>>();
  let snapshot = 0;
  const listeners = new Set<() => void>();
  const changed = () => {
    snapshot += 1;
    for (const listener of listeners) listener();
  };
  return {
    subscribe(listener: () => void) {
      listeners.add(listener);
      return () => {
        listeners.delete(listener);
      };
    },
    getSnapshot: (): number => snapshot,
    getServerSnapshot: (): number => 0,
    get: (key: number): T | undefined => values.get(key),
    isBusy: (key: number): boolean => pending.has(key),
    invalidate() {
      revision += 1;
      values.clear();
      pending.clear();
      changed();
    },
    load(key: number, work: () => Promise<T>): Promise<T | undefined> {
      if (!Number.isSafeInteger(key) || key <= 0) throw new Error('缓存条目标识无效');
      if (values.has(key)) return Promise.resolve(values.get(key));
      const existing = pending.get(key);
      if (existing) return existing;
      const ticket = revision;
      const job: Promise<T | undefined> = Promise.resolve()
        .then(async () => {
          if (ticket !== revision) return undefined;
          try {
            const value = await work();
            if (ticket !== revision) return undefined;
            values.set(key, value);
            changed();
            return value;
          } catch (error: unknown) {
            if (ticket !== revision) return undefined;
            throw error;
          }
        })
        .finally(() => {
          if (pending.get(key) === job) {
            pending.delete(key);
            changed();
          }
        });
      pending.set(key, job);
      changed();
      return job;
    },
  };
}
