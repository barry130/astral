/**
 * AI 助手配置台。
 *
 * 九个页签按「先配通道、再配智能体、最后看运行」的顺序排列：
 * 提供方 → 模型 → 路由 → 智能体版本 → 提示词 → 知识库 → 工具 → 额度 → 运行观测。
 *
 * 页签本身按权限裁剪：没有 `admin:ai:xxx:view` 就不显示对应页签，
 * 而不是显示后再报 403——那会让管理员以为功能坏了。
 */

'use client';

import React, { useMemo, useState } from 'react';

import { Alert, Card, Tabs, Tag } from '@/components/antd-compat';
import { SettingOutlined } from '@/components/antd-compat/icons';

import { usePerm } from '@/lib/perm';

import AgentPanel from '@/components/ai/admin/AgentPanel';
import AuditPanel from '@/components/ai/admin/AuditPanel';
import KnowledgePanel from '@/components/ai/admin/KnowledgePanel';
import OverviewPanel from '@/components/ai/admin/OverviewPanel';
import PromptPanel from '@/components/ai/admin/PromptPanel';
import ProviderPanel from '@/components/ai/admin/ProviderPanel';
import QuotaPanel from '@/components/ai/admin/QuotaPanel';
import RequestPanel from '@/components/ai/admin/RequestPanel';
import RoutePanel from '@/components/ai/admin/RoutePanel';
import ToolPanel from '@/components/ai/admin/ToolPanel';
import UsagePanel from '@/components/ai/admin/UsagePanel';
import { Hint } from '@/components/ai/admin/shared';

export default function AiConfigPage() {
  const hasPerm = usePerm();
  const [activeKey, setActiveKey] = useState('overview');

  const canViewPage = hasPerm('admin:ai:config:view');

  const tabs = useMemo(() => {
    const items: { key: string; label: string; children: React.ReactNode; visible: boolean }[] = [
      {
        key: 'overview',
        label: '总览',
        children: <OverviewPanel />,
        visible: true,
      },
      {
        key: 'provider',
        label: '提供方与模型',
        children: <ProviderPanel />,
        visible: hasPerm('admin:ai:provider:view') || hasPerm('admin:ai:model:view'),
      },
      {
        key: 'route',
        label: '模型路由',
        children: <RoutePanel />,
        visible: hasPerm('admin:ai:route:view'),
      },
      {
        key: 'agent',
        label: '智能体',
        children: <AgentPanel />,
        visible: hasPerm('admin:ai:agent:view'),
      },
      {
        key: 'prompt',
        label: '提示词',
        children: <PromptPanel />,
        visible: hasPerm('admin:ai:prompt:view'),
      },
      {
        key: 'knowledge',
        label: '知识库',
        children: <KnowledgePanel />,
        visible: hasPerm('admin:ai:knowledge:view'),
      },
      {
        key: 'tool',
        label: '工具',
        children: <ToolPanel />,
        visible: hasPerm('admin:ai:tool:view'),
      },
      {
        key: 'quota',
        label: '额度策略',
        children: <QuotaPanel />,
        visible: hasPerm('admin:ai:quota:view'),
      },
      {
        key: 'request',
        label: '请求记录',
        children: <RequestPanel />,
        visible: hasPerm('admin:ai:request:view') || hasPerm('admin:ai:conversation:view'),
      },
      {
        key: 'usage',
        label: '用量统计',
        children: <UsagePanel />,
        visible: hasPerm('admin:ai:usage:view'),
      },
      {
        key: 'audit',
        label: '操作审计',
        children: <AuditPanel />,
        visible: hasPerm('admin:ai:audit:view'),
      },
    ];

    return items.filter((item) => item.visible);
  }, [hasPerm]);

  if (!canViewPage) {
    return (
      <div>
        <PageHeading />
        <Card>
          <Alert
            type="warning"
            showIcon
            message="没有访问权限"
            description="AI 模块需要 admin:ai:config:view 权限。请联系超级管理员在「系统 → 角色权限」中授予。"
          />
        </Card>
      </div>
    );
  }

  return (
    <div>
      <PageHeading />
      <Tabs
        activeKey={activeKey}
        onChange={setActiveKey}
        items={tabs.map((item) => ({ key: item.key, label: item.label, children: item.children }))}
        tabBarExtraContent={
          <Tag color="default" style={{ marginRight: 4 }}>
            {tabs.length} 个可用页签
          </Tag>
        }
      />
    </div>
  );
}

function PageHeading() {
  return (
    <div style={{ marginBottom: 16 }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
        <SettingOutlined style={{ fontSize: 18, opacity: 0.75 }} />
        <h2 style={{ margin: 0, fontSize: 18, fontWeight: 600 }}>AI 助手配置</h2>
      </div>
      <p style={{ color: 'var(--muted-foreground, #909399)', margin: '6px 0 0', fontSize: 13 }}>
        提供方、模型、智能体版本、提示词、知识库与额度都在这里维护。智能体发布后才会被客户端使用，历史版本只读留档。
      </p>
      <div style={{ marginTop: 12 }}>
        <Hint>
          改完智能体配置记得「校验 → 发布」：只有 PUBLISHED 版本会生效，草稿改动不会影响线上。
          如需回滚，在版本列表里对目标历史版本点「回滚」，系统会以该版本为蓝本生成新版本再发布，审计链不会断。
        </Hint>
      </div>
    </div>
  );
}
