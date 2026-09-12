'use client';

import { useState } from 'react';
import { Tabs } from 'antd';
import { MailOutlined, FileTextOutlined, SafetyOutlined } from '@ant-design/icons';
import MailAccountPage from './account/page';
import MailTemplatePage from './template/page';
import MailPluginAuthPage from './plugin-auth/page';

/**
 * 邮箱管理页面（合并邮箱帐户、邮箱模板、发信授权）
 * 三个 Tab 分别对应账户管理、模板管理、插件发信授权。
 */
export default function MailPage() {
  const [activeTab, setActiveTab] = useState('account');

  return (
    <div style={{ background: '#f5f5f5', minHeight: '100%' }}>
      <div style={{
        background: '#fff',
        padding: '16px 24px 0',
        borderBottom: '1px solid #e8e8e8',
        position: 'sticky',
        top: 64,
        zIndex: 100,
      }}>
        <Tabs
          activeKey={activeTab}
          onChange={setActiveTab}
          items={[
            { key: 'account', label: <span><MailOutlined /> 邮箱帐户</span> },
            { key: 'template', label: <span><FileTextOutlined /> 邮箱模板</span> },
            { key: 'auth', label: <span><SafetyOutlined /> 发信授权</span> },
          ]}
        />
      </div>

      <div style={{ padding: '24px' }}>
        {activeTab === 'account' && <MailAccountPage />}
        {activeTab === 'template' && <MailTemplatePage />}
        {activeTab === 'auth' && <MailPluginAuthPage />}
      </div>
    </div>
  );
}