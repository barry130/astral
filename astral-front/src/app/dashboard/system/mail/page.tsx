'use client';

import { useState } from 'react';
import { Tabs } from '@/components/antd-compat';
import {
  MailOutlined, FileTextOutlined, SafetyOutlined, MobileOutlined,
  FileDoneOutlined, NotificationOutlined, ApiOutlined,
} from '@/components/antd-compat/icons';
import MailAccountPage from './account/page';
import MailTemplatePage from './template/page';
import MailPluginAuthPage from './plugin-auth/page';
import SmsProviderPage from './sms-provider/page';
import SmsTemplatePage from './sms-template/page';
import NoticeManagement from '@/components/admin/NoticeManagement';
import NotifyRulePage from './notify-rule/page';

/**
 * 消息中心页：邮箱帐户/邮箱模板/发信授权（EMAIL 本地渲染）、
 * 短信供应商/短信模板（SMS 供应商侧映射）、通知管理（统一通知表 sys_notice，
 * 广播公告 + 点对点站内信，与反馈管理/顶栏铃铛/轻听三端共用）、
 * 事件订阅规则（事件 × 渠道 → 模板 + 收件人，统一发布入口）。
 */
export default function MailPage() {
  const [activeTab, setActiveTab] = useState('account');

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
            { key: 'template', label: <span><FileTextOutlined /> 邮箱模板</span> },
            { key: 'auth', label: <span><SafetyOutlined /> 发信授权</span> },
            { key: 'smsProvider', label: <span><MobileOutlined /> 短信供应商</span> },
            { key: 'smsTemplate', label: <span><FileDoneOutlined /> 短信模板</span> },
            { key: 'notice', label: <span><NotificationOutlined /> 通知管理</span> },
            { key: 'rule', label: <span><ApiOutlined /> 订阅规则</span> },
          ]}
        />
      </div>

      <div style={{ padding: '24px' }}>
        {activeTab === 'account' && <MailAccountPage />}
        {activeTab === 'template' && <MailTemplatePage />}
        {activeTab === 'auth' && <MailPluginAuthPage />}
        {activeTab === 'smsProvider' && <SmsProviderPage />}
        {activeTab === 'smsTemplate' && <SmsTemplatePage />}
        {activeTab === 'notice' && <NoticeManagement />}
        {activeTab === 'rule' && <NotifyRulePage />}
      </div>
    </div>
  );
}
