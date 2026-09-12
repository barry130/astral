import './globals.css';
import type { Metadata, Viewport } from 'next';
import { Providers } from './providers';

/** 页面元数据配置 */
export const metadata: Metadata = {
  title: 'Astral Management System',
  description: 'Astral后台管理系统',
};

/**
 * 视口配置：适配手机/平板等小屏设备
 * viewport-fit=cover 使页面延伸至安全区（刘海/圆角），配合 CSS env(safe-area-inset-*) 使用
 */
export const viewport: Viewport = {
  width: 'device-width',
  initialScale: 1,
  maximumScale: 1,
  viewportFit: 'cover',
  themeColor: '#4a6fa5',
};

/**
 * 主题防闪脚本：在水合前读取 localStorage / 系统偏好并设置 data-theme，
 * 避免深色模式用户先看到一帧浅色（FOUC）。必须在 <head> 里同步执行。
 */
const THEME_BOOTSTRAP_SCRIPT = `(function(){try{var t=localStorage.getItem('astral:theme');if(t!=='light'&&t!=='dark'){t=window.matchMedia('(prefers-color-scheme: dark)').matches?'dark':'light';}var r=document.documentElement;r.setAttribute('data-theme',t);if(t==='dark'){r.classList.add('dark');}}catch(e){}})();`;

/**
 * 根布局组件
 * 所有页面的最外层包裹，设置HTML语言、全局Provider等
 */
export default function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <html lang="zh-CN" suppressHydrationWarning>
      <head>
        <script dangerouslySetInnerHTML={{ __html: THEME_BOOTSTRAP_SCRIPT }} />
      </head>
      <body>
        <Providers>{children}</Providers>
      </body>
    </html>
  );
}