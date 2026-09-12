'use client';

import NoticeManagement from '@/components/admin/NoticeManagement';

/**
 * 通知管理独立路由页（/dashboard/message）
 * <p>复用 NoticeManagement 面板；反馈管理页 Tabs 内也内嵌同一面板。</p>
 */
export default function MessagePage() {
  return <NoticeManagement />;
}
