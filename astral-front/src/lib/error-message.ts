/**
 * 未知类型错误的统一兜底文案提取。
 *
 * 背景：TypeScript `strict`（含 useUnknownInCatchVariables）下 catch 变量是
 * `unknown`，禁止直接访问 `.message`。过去大量 `catch (e: any)` 被 ESLint 9
 * 禁止（@typescript-eslint/no-explicit-any）。统一改用本工具：
 *
 *   catch (e) { toast.error(getErrorMessage(e, '加载失败')); }
 */
export function getErrorMessage(e: unknown, fallback = '操作失败'): string {
  if (e instanceof Error) return e.message || fallback;
  if (typeof e === 'string') return e || fallback;
  if (e && typeof e === 'object' && 'message' in e) {
    const m = (e as { message?: unknown }).message;
    if (typeof m === 'string' && m) return m;
  }
  return fallback;
}
