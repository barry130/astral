/** Same-origin BFF JSON boundary with a finite deadline and caller cancellation. */
export async function requestJson(
  path: string,
  init: RequestInit = {},
): Promise<{ response: Response; body: unknown }> {
  if (!path.startsWith('/api/') || /[\\\r\n]/.test(path)) throw new Error('Invalid BFF path');
  const controller = new AbortController();
  const relayAbort = () => controller.abort(init.signal?.reason);
  if (init.signal?.aborted) relayAbort();
  else init.signal?.addEventListener('abort', relayAbort, { once: true });
  const timer = setTimeout(() => controller.abort(), 15_000);
  controller.signal.addEventListener('abort', () => clearTimeout(timer), { once: true });
  const headers = new Headers(init.headers);
  headers.set('accept', 'application/json');
  try {
    const response = await fetch(path, {
      ...init,
      headers,
      signal: controller.signal,
      cache: 'no-store',
      credentials: 'same-origin',
      redirect: 'error',
    });
    return { response, body: await response.json().catch(() => null) };
  } finally {
    clearTimeout(timer);
    init.signal?.removeEventListener('abort', relayAbort);
  }
}
