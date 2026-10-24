// 站点展示信息。
// 后端已提供公开站点设置接口（GET /api/v1/app/cms/setting，见 cms.ts 的 getSiteSettings）；
// 下列常量仅作**兜底默认值**——后端不可达或字段为空时使用，页面展示一律走 loadSiteSettings()。
import { cache } from 'react';
import { getSiteSettings, type SiteSettings } from '@/lib/cms';

export const SITE_TITLE = 'Astral Blog';
export const SITE_DESCRIPTION = 'Astral 站点的公开博客与 Wiki：文章、分类、标签与知识库。';
export const SITE_DESCRIPTION_SHORT = '记录、整理与分享。';
export const FOOTER_TEXT = 'Powered by astral-cms';

/** 兜底站点设置（后端接口失败时整体回退到本地默认值） */
export const FALLBACK_SITE_SETTINGS: SiteSettings = {
  siteTitle: SITE_TITLE,
  siteDescription: SITE_DESCRIPTION,
  footerText: FOOTER_TEXT,
  commentEnabled: true,
  commentNeedReview: true,
};

/** 单次请求内复用（layout / 页面 / 侧栏都会读，避免重复回源） */
const loadSiteSettingsCached = cache(async (): Promise<SiteSettings> => {
  const res = await getSiteSettings();
  return res.ok ? res.data : FALLBACK_SITE_SETTINGS;
});

/** 读取站点设置：失败时不抛异常，返回全部兜底值（RSC 与组件共用） */
export async function loadSiteSettings(): Promise<SiteSettings> {
  return loadSiteSettingsCached();
}
