import packageMetadata from '../../package.json';

const DEFAULT_API_BASE = 'http://localhost:27000';
const TIMEOUT_MS = 10_000;

/** Backend-only URL configuration; redirects may not carry credentials away. */
export function getApiBase(): string {
  try {
    const url = new URL(process.env.CMS_API_BASE || DEFAULT_API_BASE);
    if (
      !['http:', 'https:'].includes(url.protocol) ||
      url.username ||
      url.password ||
      url.search ||
      url.hash
    ) {
      return DEFAULT_API_BASE;
    }
    return url.href.replace(/\/+$/, '');
  } catch {
    return DEFAULT_API_BASE;
  }
}

export function fetchBackend(path: string, init: RequestInit = {}): Promise<Response> {
  if (!path.startsWith('/api/v1/') || /[\r\n\\]/.test(path)) {
    throw new Error('Invalid backend API path');
  }
  const headers = new Headers(init.headers);
  headers.set('accept', 'application/json');
  headers.set('X-App-Ut', 'web');
  headers.set('X-App-Version', packageMetadata.version);
  // The BFF cannot identify a reader's device/OS without inventing or trusting
  // arbitrary client data. Mark those fields honestly as unavailable.
  headers.set('X-Device', 'Astral Blog BFF');
  headers.set('X-OS', 'unknown');
  const timeout = AbortSignal.timeout(TIMEOUT_MS);
  const signal = init.signal ? AbortSignal.any([init.signal, timeout]) : timeout;
  return fetch(`${getApiBase()}${path}`, {
    ...init,
    headers,
    signal,
    cache: 'no-store',
    redirect: 'error',
  });
}
