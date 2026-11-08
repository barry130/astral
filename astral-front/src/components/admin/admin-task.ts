export interface AdminTaskCallbacks<T> {
  onStart?: () => void;
  onSuccess?: (value: T) => void;
  onError?: (error: unknown) => void;
  onSettled?: () => void;
}

export interface AdminTask {
  run<T>(work: () => Promise<T>, callbacks?: AdminTaskCallbacks<T>): Promise<boolean>;
  /** Invalidates UI completion only; a dispatched mutation is not assumed rolled back. */
  invalidate(): void;
  isBusy(): boolean;
}

/** A latest-only read lane, or a single-flight mutation lane locked before validation starts. */
export function createAdminTask(mode: 'latest' | 'exclusive' = 'latest'): AdminTask {
  let generation = 0;
  let pending = 0;
  return {
    async run<T>(work: () => Promise<T>, callbacks: AdminTaskCallbacks<T> = {}) {
      if (mode === 'exclusive' && pending > 0) return false;
      const ticket = ++generation;
      pending += 1;
      const current = () => ticket === generation;
      try {
        callbacks.onStart?.();
        const value = await work();
        if (!current()) return false;
        callbacks.onSuccess?.(value);
        return true;
      } catch (error: unknown) {
        if (current()) callbacks.onError?.(error);
        return false;
      } finally {
        pending -= 1;
        if (current()) callbacks.onSettled?.();
      }
    },
    invalidate() {
      generation += 1;
    },
    isBusy() {
      return pending > 0;
    },
  };
}

export const numericSwitchBinding = {
  valuePropName: 'checked',
  getValueProps: (value: unknown) => ({ checked: Number(value) === 1 }),
  getValueFromEvent: (checked: boolean) => (checked ? 1 : 0),
} as const;

export interface AdminQuery<T extends object> {
  /** Explicit undefined clears an optional filter; an omitted property preserves it. */
  read(patch?: Partial<T>): T;
}

export function createAdminQuery<T extends object>(initial: T): AdminQuery<T> {
  let snapshot = { ...initial };
  return {
    read(patch: Partial<T> = {}) {
      snapshot = { ...snapshot, ...patch };
      return { ...snapshot };
    },
  };
}

export function adminErrorMessage(error: unknown, fallback: string): string {
  return error instanceof Error && error.message ? error.message : fallback;
}

/** Form validation is reported on fields, not as a second generic error toast. */
export function reportAdminError(error: unknown, fallback: string, report: (text: string) => void) {
  if (error && typeof error === 'object' && 'errorFields' in error) return;
  report(adminErrorMessage(error, fallback));
}

export function requireAdminSuccess<T>(
  response: { code: number; msg?: string; data: T },
  fallback: string,
): T {
  if (response.code !== 200) throw new Error(response.msg || fallback);
  return response.data;
}
