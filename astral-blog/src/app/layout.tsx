import type { Metadata, Viewport } from 'next';
import './globals.css';
import { Providers } from '@/app/providers';
import { SessionProvider } from '@/components/session-provider';
import { SiteHeader } from '@/components/site-header';
import { SiteFooter } from '@/components/site-footer';
import { canEditContent, getSessionUser } from '@/lib/auth-server';
import { MarkdownEnhance } from '@/components/markdown-enhance';
import { loadSiteSettings, SITE_DESCRIPTION } from '@/lib/site';

/**
 * 视口与状态栏主题色。
 * 刻意不设 maximumScale / userScalable —— 禁用缩放会让低视力读者无法放大正文，
 * 是 Web Interface Guidelines 明确列出的反模式。正文用 clamp() 跟随视口放大即可。
 */
export const viewport: Viewport = {
  width: 'device-width',
  initialScale: 1,
  viewportFit: 'cover',
  // 与 --color-canvas (#f9f7f3) 一致，手机状态栏与纸色底连成一片
  themeColor: '#f9f7f3',
};

// 站点标题/描述来自后端公开设置接口（cmsGet 带 cache:'no-store'，路由按动态渲染处理）。
export async function generateMetadata(): Promise<Metadata> {
  const settings = await loadSiteSettings();
  return {
    title: {
      default: settings.siteTitle,
      template: `%s - ${settings.siteTitle}`,
    },
    // 后端未配置描述时用本地默认文案，避免站点完全丢失 SEO 描述
    description: settings.siteDescription || SITE_DESCRIPTION,
    // 图床（cdn.canace.cn 等）按 Referer 白名单防盗链，跨域请求一律不带 Referer 才能取到图
    other: { referrer: 'same-origin' },
  };
}

export default async function RootLayout({
  children,
}: Readonly<{ children: React.ReactNode }>) {
  const [settings, user] = await Promise.all([loadSiteSettings(), getSessionUser()]);
  return (
    <html lang="zh-CN">
      <body id="top" className="flex min-h-dvh flex-col bg-canvas text-ink-body antialiased">
        <a
          href="#main"
          className="sr-only focus:not-sr-only focus:fixed focus:left-4 focus:top-4 focus:z-50 focus:rounded-lg focus:border focus:border-line focus:bg-surface focus:px-3 focus:py-2 focus:text-[13px] focus:text-ink focus:shadow-card"
        >
          跳到正文
        </a>
        {/* 登录态在服务端算一次（读 Cookie 问后端），客户端只做展示与增量刷新 */}
        <SessionProvider
          initialLoggedIn={Boolean(user)}
          initialCanEdit={canEditContent(user)}
          initialUser={user}
        >
          <Providers>
            {/* 正文 HTML 由服务端渲染，代码块「复制」按钮走这里的事件委托 */}
            <MarkdownEnhance />
            <SiteHeader siteTitle={settings.siteTitle} />
            <main id="main" className="w-full flex-1">
              {children}
            </main>
            <SiteFooter footerText={settings.footerText} siteTitle={settings.siteTitle} />
          </Providers>
        </SessionProvider>
      </body>
    </html>
  );
}
