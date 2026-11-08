/**
 * astral 统一响应壳 Result{code,errorCode?,msg,data,timestamp}。
 *
 * 后端契约固定使用 `msg`（见仓库根 AGENTS.md「统一响应体」）。博客自身的
 * Route Handler 历史上有少量地方返回 `message`，这里保留该字段仅用于兼容
 * 旧缓存/过渡期响应，读取时统一优先 `msg`。
 */
export interface ResultEnvelope<T = unknown> {
  code?: number;
  errorCode?: string | null;
  msg?: string | null;
  /** @deprecated 后端契约字段是 `msg`；仅兼容旧版 BFF / 旧后端响应。 */
  message?: string | null;
  data?: T | null;
  timestamp?: number | null;
}

/** 从任意 Result 形状中取人类可读的错误信息，取不到时返回 fallback。 */
export function resultMessage(body: unknown, fallback: string): string {
  if (!isRecord(body)) return fallback;
  const msg = typeof body.msg === 'string' ? body.msg.trim() : '';
  const legacy = typeof body.message === 'string' ? body.message.trim() : '';
  return msg || legacy || fallback;
}

export function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

/** Validate and project the envelope, never trusting JSON field types. */
export function parseResult(value: unknown): (ResultEnvelope & { code: number }) | null {
  if (
    !isRecord(value) ||
    typeof value.code !== 'number' ||
    !Number.isSafeInteger(value.code) ||
    (value.msg === undefined ? typeof value.message !== 'string' : typeof value.msg !== 'string') ||
    !Object.hasOwn(value, 'data') ||
    value.data === undefined ||
    typeof value.timestamp !== 'number' ||
    !Number.isSafeInteger(value.timestamp) ||
    value.timestamp < 0 ||
    (value.errorCode != null && typeof value.errorCode !== 'string')
  ) {
    return null;
  }
  return {
    code: value.code,
    msg: resultMessage(value, ''),
    errorCode: typeof value.errorCode === 'string' ? value.errorCode : undefined,
    data: value.data ?? null,
    timestamp: value.timestamp,
  };
}
