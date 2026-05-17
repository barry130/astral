import './globals.css';
import type { Metadata } from 'next';
import { Providers } from './providers';

/** 页面元数据配置 */
export const metadata: Metadata = {
  title: 'Astral Management System',
  description: 'Astral后台管理系统',
};

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
    <html lang="zh-CN">
      <body>
        <Providers>{children}</Providers>
      </body>
    </html>
  );
}