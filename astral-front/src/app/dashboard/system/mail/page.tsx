'use client';

import { Suspense, useState } from 'react';
import { useSearchParams } from 'next/navigation';
import { Tabs } from '@/components/antd-compat';
import {
  MailOutlined, FileTextOutlined, MobileOutlined,
  NotificationOutlined, ApiOutlined,
} from '@/components/antd-compat/icons';
import MailAccountPage from './account/page';
import MessageTemplatePage from './message-template/page';
import SmsProviderPage from './sms-provider/page';
import NoticeManagement from '@/components/admin/NoticeManagement';
import NotifyRulePage from './notify-rule/page';

/** 页签 key 白名单（同时作为 ?tab= 深链的合法取值；template/smsTemplate 为合并前的旧深链别名） */
const TAB_KEYS = ['account', 'messageTemplate', 'smsProvider', 'notice', 'rule', 'template', 'smsTemplate'] as const;

/** 旧深链 ?tab=template|smsTemplate 统一落到合并后的消息模板页 */
const TAB_ALIAS: Record<string, string> = { template: 'messageTemplate', smsTemplate: 'messageTemplate' };

/**
 * 消息中心页：邮箱帐户/消息模板（邮箱 + 站内信 + 短信合并，投递渠道多选）、
 * 短信供应商、通知管理（统一通知表 sys_notice，
 * 广播公告 + 点对点站内信，与反馈管理/顶栏铃铛/轻听三端共用）、
 * 事件订阅规则（事件 × 渠道 → 模板 + 收件人，统一发布入口）。
 *
 * <p>支持 `?tab=` 深链（如顶栏铃铛的「全部通知」→ `/dashboard/system/mail?tab=notice`）：
 * 原 /dashboard/message 独立路由已删除，入口收敛到本页「通知管理」页签 + 顶栏铃铛。</p>
 */
function MailCenter() {
  const searchParams = useSearchParams();
  const tabParam = searchParams.get('tab');
  // 只接受白名单内的 key，非法/缺省一律落到「邮箱帐户」，避免脏参数导致空白页
  const rawTab = tabParam && (TAB_KEYS as readonly string[]).includes(tabParam) ? tabParam : 'account';
  const initialTab = TAB_ALIAS[rawTab] || rawTab;
  const [activeTab, setActiveTab] = useState(initialTab);

  return (
    <div style={{ background: 'var(--color-bg-base)', minHeight: '100%' }}>
      <div style={{
        background: 'var(--color-bg-white)',
        padding: '16px 24px 0',
        borderBottom: '1px solid var(--color-border)',
        position: 'sticky',
        top: 56,
        zIndex: 100,
      }}>
        <Tabs
          activeKey={activeTab}
          onChange={setActiveTab}
          items={[
            { key: 'account', label: <span><MailOutlined /> 邮箱帐户</span> },
            { key: 'messageTemplate', label: <span><FileTextOutlined /> 消息模板</span> },
            { key: 'smsProvider', label: <span><MobileOutlined /> 短信供应商</span> },
            { key: 'notice', label: <span><NotificationOutlined /> 通知管理</span> },
            { key: 'rule', label: <span><ApiOutlined /> 订阅规则</span> },
          ]}
        />
      </div>

      <div style={{ padding: '24px' }}>
        {activeTab === 'account' && <MailAccountPage />}
        {activeTab === 'messageTemplate' && <MessageTemplatePage />}
        {activeTab === 'smsProvider' && <SmsProviderPage />}
        {activeTab === 'notice' && <NoticeManagement />}
        {activeTab === 'rule' && <NotifyRulePage />}
      </div>
    </div>
  );
}

/**
 * useSearchParams 在静态预渲染时要求包裹 Suspense（Next 16 App Router），
 * 与 dashboard/profile 页的处理方式保持一致。
 */
export default function MailPage() {
  return (
    <Suspense fallback={<div style={{ padding: 24 }} />}>
      <MailCenter />
    </Suspense>
  );
}
