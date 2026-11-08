'use client';

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { message } from '@/components/antd-compat';
import {
  adminErrorMessage,
  createAdminQuery,
  createAdminTask,
  reportAdminError,
} from './admin-task';

export function useAdminTask(mode: 'latest' | 'exclusive' = 'latest') {
  const task = useMemo(() => createAdminTask(mode), [mode]);
  useEffect(() => () => task.invalidate(), [task]);
  return task;
}

export function useAdminLoader(fallback: string) {
  const task = useAdminTask();
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const run = useCallback(
    <T>(work: () => Promise<T>, onSuccess: (value: T) => void) =>
      task.run(work, {
        onStart: () => {
          setLoading(true);
          setError('');
        },
        onSuccess,
        onError: (failure) => setError(adminErrorMessage(failure, fallback)),
        onSettled: () => setLoading(false),
      }),
    [task, fallback],
  );
  return { loading, error, run, invalidate: task.invalidate };
}

export function useAdminMutation(fallback: string) {
  const task = useAdminTask('exclusive');
  const [busy, setBusy] = useState(false);
  const run = useCallback(
    <T>(work: () => Promise<T>, onSuccess?: (value: T) => void) =>
      task
        .run(work, {
          onStart: () => setBusy(true),
          onSuccess,
          onError: (failure) => reportAdminError(failure, fallback, message.error),
          onSettled: () => setBusy(false),
        })
        .then(() => undefined),
    [task, fallback],
  );
  return { busy, run, invalidate: task.invalidate, isBusy: task.isBusy };
}

export function useAdminQuery<T extends object>(initial: T) {
  const query = useRef<ReturnType<typeof createAdminQuery<T>> | null>(null);
  if (!query.current) query.current = createAdminQuery(initial);
  return query.current;
}
