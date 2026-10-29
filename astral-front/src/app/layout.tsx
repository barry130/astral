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
 *
 * 刻意不设 maximumScale / userScalable：禁用缩放会让低视力用户无法放大表单与表格，
 * 是 Web Interface Guidelines 明确列出的反模式。iOS 输入框聚焦的自动放大改用
 * 字号 ≥16px 规避（见 globals.css 表单控件段）。
 */
export const viewport: Viewport = {
  width: 'device-width',
  initialScale: 1,
  viewportFit: 'cover',
  // 深浅色各一条：否则手机状态栏在深色主题下仍是白底，与页面割裂
  themeColor: [
    { media: '(prefers-color-scheme: light)', color: '#ffffff' },
    { media: '(prefers-color-scheme: dark)', color: '#12151b' },
  ],
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
export default function RootLayout({ children }: { children: React.ReactNode }) {
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
