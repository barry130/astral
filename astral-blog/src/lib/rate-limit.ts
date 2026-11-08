/**
 * Optional, bounded single-process flood control, not an authorization boundary.
 * Cookie keys can be rotated; proxy keys are trusted only with explicit opt-in.
 * It does NOT solve the backend's shared-BFF-IP quota. Multi-instance and trusted
 * client-IP quota policy must be configured at the deployment/backend boundary.
 */
interface LimiterOptions {
  limit: number;
  windowMs: number;
  maxKeys?: number;
}
export interface LimitVerdict {
  allowed: boolean;
  retryAfterSeconds: number;
  remaining: number;
}
export interface SlidingWindowLimiter {
  check(key: string, now?: number): LimitVerdict;
  reset(): void;
}

export function createSlidingWindowLimiter(options: LimiterOptions): SlidingWindowLimiter {
  const { limit, windowMs } = options;
  const maxKeys = options.maxKeys ?? 5000;
  if (![limit, windowMs, maxKeys].every((value) => Number.isSafeInteger(value) && value > 0))
    throw new Error('Invalid limiter options');
  const hits = new Map<string, number[]>();
  let lastNow = 0;
  return {
    check(key: string, requestedNow = Date.now()): LimitVerdict {
      const now = Math.max(lastNow, requestedNow);
      lastNow = now;
      if (!hits.has(key) && hits.size >= maxKeys) {
        for (const [candidate, stamps] of hits) {
          const live = stamps.filter((stamp) => now - stamp < windowMs);
          if (live.length) hits.set(candidate, live);
          else hits.delete(candidate);
        }
        if (hits.size >= maxKeys) {
          // Refuse NEW keys rather than evict live quotas and permitting bypass.
          const retry = Math.min(
            ...Array.from(hits.values(), (stamps) => windowMs - (now - stamps[0])),
          );
          return {
            allowed: false,
            retryAfterSeconds: Math.max(1, Math.ceil(retry / 1000)),
            remaining: 0,
          };
        }
      }
      const stamps = (hits.get(key) ?? []).filter((stamp) => now - stamp < windowMs);
      const allowed = stamps.length < limit;
      if (allowed) stamps.push(now);
      hits.set(key, stamps);
      return {
        allowed,
        remaining: Math.max(0, limit - stamps.length),
        retryAfterSeconds: allowed
          ? 0
          : Math.max(1, Math.ceil((windowMs - (now - stamps[0])) / 1000)),
      };
    },
    reset() {
      hits.clear();
      lastNow = 0;
    },
  };
}

export const commentLimiter = createSlidingWindowLimiter({ limit: 5, windowMs: 600_000 });
export const COMMENT_COOKIE = 'blog_visitor';

export function resolveThrottleKey(request: Request): {
  key: string;
  issuedVisitorId: string | null;
} {
  if (process.env.BLOG_TRUST_PROXY === 'true') {
    const address =
      request.headers.get('x-real-ip')?.trim() ||
      request.headers.get('x-forwarded-for')?.split(',')[0]?.trim();
    if (address && /^[0-9a-fA-F:.]{1,64}$/.test(address))
      return { key: `ip:${address}`, issuedVisitorId: null };
  }
  const cookie = request.headers
    .get('cookie')
    ?.split(';')
    .map((item) => item.trim())
    .find((item) => item.startsWith(`${COMMENT_COOKIE}=`));
  const value = cookie?.slice(COMMENT_COOKIE.length + 1);
  if (value && /^[A-Za-z0-9_-]{8,64}$/.test(value))
    return { key: `cid:${value}`, issuedVisitorId: null };
  const created = crypto.randomUUID();
  return { key: `cid:${created}`, issuedVisitorId: created };
}
