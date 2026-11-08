import type { MetadataRoute } from 'next';

import { getSiteUrl } from '@/lib/site-url';
const SITE_URL = getSiteUrl();

// 与数据页一致：构建期不探测后端，运行时生成。
export const dynamic = 'force-dynamic';

export default function robots(): MetadataRoute.Robots {
  return {
    rules: [
      {
        userAgent: '*',
        allow: '/',
        // 后台转发与分享令牌页不应被搜索引擎收录
        disallow: ['/api/', '/share/'],
      },
    ],
    sitemap: `${SITE_URL}/sitemap.xml`,
    host: SITE_URL,
  };
}
