/**
 * 站点公开地址的唯一解析处（canonical / OpenGraph / sitemap / robots 共用）。
 *
 * 为什么收成一个函数：三处原本各写一遍 `process.env.NEXT_PUBLIC_SITE_URL || 'http://localhost:3001'`，
 * 只有 layout 的 metadataBase 做了 URL 合法性兜底，sitemap/robots 没有 —— 环境变量写错时
 * （漏协议、混进空格、写成 javascript:）会静默生成一整套不可抓取的绝对地址，而且没有任何报错。
 * 这里统一校验：非 http(s) 或解析失败一律退回本地默认值，保证产物始终是合法绝对 URL。
 */

export const DEFAULT_SITE_URL = 'http://localhost:3001';

/** 解析站点地址；非法配置退回默认值而不是抛错（metadata 阶段抛错会让整站 500）。 */
export function resolveSiteUrl(
  raw: string | undefined = process.env.NEXT_PUBLIC_SITE_URL,
  fallback = DEFAULT_SITE_URL,
): URL {
  const candidate = (raw ?? '').trim();
  if (!candidate) return new URL(fallback);
  let parsed: URL;
  try {
    parsed = new URL(candidate);
  } catch {
    return new URL(fallback);
  }
  if (
    (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') ||
    parsed.username ||
    parsed.password
  ) {
    return new URL(fallback);
  }
  // The blog is served at the origin root (there is no Next basePath).
  return new URL(parsed.origin);
}

/** 去掉末尾斜杠的绝对地址，供 `${siteUrl}${path}` 拼接。 */
export function getSiteUrl(): string {
  return resolveSiteUrl().href.replace(/\/+$/, '');
}
