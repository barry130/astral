/**
 * 总览：一屏看清「能不能用、缺什么」。
 *
 * 管理员打开 AI 配置，最常问的是「为什么客户端发消息没反应」。
 * 这个页签的回答顺序就是排查顺序：插件开关 → 建表 → 密钥 → 提供方健康 → 智能体发布态。
 */

'use client';

import React from 'react';

import { Alert, Button, Card, Descriptions, Space, Spin, Statistic, Tag, Tooltip } from '@/components/antd-compat';
import {
  CheckCircleOutlined,
  CloseCircleOutlined,
  ExclamationCircleOutlined,
  ReloadOutlined,
} from '@/components/antd-compat/icons';

import { aiOverviewApi, type AiOverview } from '@/api/ai';

import { BoolTag, formatNumber, formatTime, Panel, statusColor, useAsync } from './shared';

export default function OverviewPanel() {
  const { data, loading, error, reload } = useAsync<AiOverview | undefined>(
    () => aiOverviewApi.get().then((res) => res.data),
    undefined,
  );

  if (loading && !data) {
    return (
      <div style={{ padding: 48, textAlign: 'center' }}>
        <Spin />
      </div>
    );
  }

  if (error) {
    return (
      <Alert
        type="error"
        showIcon
        message="总览加载失败"
        description={error}
        action={
          <Button size="small" onClick={reload}>
            重试
          </Button>
        }
      />
    );
  }

  if (!data) return null;

  const blocking = buildBlockingIssues(data);

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
      {blocking.length > 0 && (
        <Alert
          type="warning"
          showIcon
          message="还有配置没补齐，客户端暂时无法正常提问"
          description={
            <ul style={{ margin: '6px 0 0', paddingLeft: 18, fontSize: 12.5, lineHeight: 1.9 }}>
              {blocking.map((item) => (
                <li key={item}>{item}</li>
              ))}
            </ul>
          }
        />
      )}

      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(150px, 1fr))', gap: 12 }}>
        <StatCard title="提供方" value={data.providerCount} suffix={`/ ${data.enabledProviderCount ?? 0} 启用`} />
        <StatCard title="模型" value={data.modelCount} />
        <StatCard title="路由" value={data.routeCount} />
        <StatCard
          title="智能体"
          value={data.agentCount}
          suffix={`/ ${data.publishedAgentCount ?? 0} 已发布`}
        />
        <StatCard title="提示词模板" value={data.promptCount} />
        <StatCard title="知识源" value={data.knowledgeSourceCount} />
        <StatCard title="工具" value={data.toolCount} />
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(150px, 1fr))', gap: 12 }}>
        <StatCard title="今日请求" value={data.todayRequestCount} />
        <StatCard title="进行中" value={data.runningRequestCount} />
        <StatCard title="今日失败" value={data.failedRequestCount} danger={(data.failedRequestCount ?? 0) > 0} />
        <StatCard title="今日输入 Token" value={data.todayInputTokens} plain />
        <StatCard title="今日输出 Token" value={data.todayOutputTokens} plain />
      </div>

      <Panel
        title="运行环境"
        extra={
          <Button size="small" icon={<ReloadOutlined />} onClick={reload} loading={loading}>
            刷新
          </Button>
        }
      >
        <Descriptions
          column={2}
          bordered
          size="small"
          items={[
            {
              key: 'plugin',
              label: '插件状态',
              children: <BoolTag value={data.pluginEnabled} trueText="已启用" falseText="已停用" />,
            },
            {
              key: 'schema',
              label: '数据表',
              children: <BoolTag value={data.schemaReady} trueText="已就绪" falseText="未初始化" />,
            },
            {
              key: 'secret',
              label: '密钥加密',
              children: (
                <Space size={6}>
                  <BoolTag value={data.secretKeyConfigured} trueText="已配置" falseText="未配置" />
                  {!data.secretKeyConfigured && (
                    <Tooltip title="未配置 astral.plugins.ai.secret-key 时无法保存 API Key，只能用环境变量引用">
                      <ExclamationCircleOutlined style={{ opacity: 0.6 }} />
                    </Tooltip>
                  )}
                </Space>
              ),
            },
            {
              key: 'clients',
              label: '可用客户端',
              children: (
                <Space size={4} wrap>
                  {Array.from(
                    new Set((data.agents ?? []).flatMap((agent) => agent.clientTypes ?? [])),
                  ).map((client) => (
                    <Tag key={client} color="default">
                      {client}
                    </Tag>
                  ))}
                </Space>
              ),
            },
          ]}
        />
      </Panel>

      <Panel title="提供方健康">
        {(data.providerHealth ?? []).length === 0 ? (
          <div style={{ padding: '20px 0', textAlign: 'center', opacity: 0.55, fontSize: 13 }}>
            还没有配置提供方。到「提供方与模型」新建一个，再回到这里点「测试」。
          </div>
        ) : (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
            {(data.providerHealth ?? []).map((item) => (
              <div
                key={item.id}
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'space-between',
                  gap: 12,
                  padding: '9px 12px',
                  borderRadius: 8,
                  background: 'var(--muted, rgba(127,127,127,0.06))',
                  fontSize: 13,
                }}
              >
                <Space size={8}>
                  {item.healthStatus === 'HEALTHY' ? (
                    <CheckCircleOutlined style={{ color: 'var(--chart-2, #16a34a)' }} />
                  ) : item.healthStatus === 'UNHEALTHY' ? (
                    <CloseCircleOutlined style={{ color: 'var(--destructive, #dc2626)' }} />
                  ) : (
                    <ExclamationCircleOutlined style={{ opacity: 0.5 }} />
                  )}
                  <span style={{ fontWeight: 500 }}>{item.name}</span>
                  <Tag color={statusColor(item.healthStatus)}>{item.healthStatus ?? 'UNKNOWN'}</Tag>
                </Space>
                <span style={{ opacity: 0.6, fontSize: 12, textAlign: 'right' }}>
                  {item.healthMessage || '—'}
                  {item.lastHealthTime ? ` · ${formatTime(item.lastHealthTime)}` : ''}
                </span>
              </div>
            ))}
          </div>
        )}
      </Panel>

      <Panel title="智能体发布状态">
        {(data.agents ?? []).length === 0 ? (
          <div style={{ padding: '20px 0', textAlign: 'center', opacity: 0.55, fontSize: 13 }}>
            还没有智能体。种子数据会在插件启动时自动创建，若为空请检查启动日志里的 `[AiSeed]`。
          </div>
        ) : (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
            {(data.agents ?? []).map((agent) => {
              const published = agent.status === 'PUBLISHED' && agent.publishedVersionNo;
              return (
                <div
                  key={agent.id}
                  style={{
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'space-between',
                    gap: 12,
                    padding: '9px 12px',
                    borderRadius: 8,
                    border: '1px solid var(--border, rgba(127,127,127,0.14))',
                    fontSize: 13,
                  }}
                >
                  <Space size={8} wrap>
                    <span style={{ fontWeight: 500 }}>{agent.name}</span>
                    <code style={{ fontSize: 11.5, opacity: 0.55 }}>{agent.agentCode}</code>
                    <Tag color={statusColor(agent.status)}>{agent.status ?? '-'}</Tag>
                    {(agent.clientTypes ?? []).map((client) => (
                      <Tag key={client} color="default">
                        {client}
                      </Tag>
                    ))}
                  </Space>
                  <span style={{ fontSize: 12, opacity: 0.65 }}>
                    {published ? `已发布 v${agent.publishedVersionNo}` : '未发布'}
                  </span>
                </div>
              );
            })}
          </div>
        )}
      </Panel>
    </div>
  );
}

/* ==================== 局部组件 ==================== */

function StatCard({
  title,
  value,
  suffix,
  plain,
  danger,
}: {
  title: string;
  value?: number;
  suffix?: string;
  plain?: boolean;
  danger?: boolean;
}) {
  return (
    <Card size="small" bodyStyle={{ padding: '12px 14px' }}>
      <Statistic
        title={<span style={{ fontSize: 12.5, opacity: 0.65 }}>{title}</span>}
        value={plain ? formatNumber(value) : (value ?? 0)}
        valueStyle={{
          fontSize: 20,
          fontWeight: 600,
          color: danger ? 'var(--destructive, #dc2626)' : undefined,
        }}
        suffix={suffix ? <span style={{ fontSize: 11.5, opacity: 0.55 }}>{suffix}</span> : undefined}
      />
    </Card>
  );
}

/** 把「缺什么」整理成人话，按修复顺序排列 */
function buildBlockingIssues(data: AiOverview): string[] {
  const issues: string[] = [];
  if (!data.pluginEnabled) issues.push('AI 插件处于停用状态，到「插件管理」启用它。');
  if (!data.schemaReady) issues.push('数据表尚未初始化，检查启动日志里的建表结果。');
  if ((data.providerCount ?? 0) === 0) issues.push('没有任何提供方，先在「提供方与模型」里新建一个。');
  if ((data.modelCount ?? 0) === 0) issues.push('提供方下没有模型，客户端将无模型可用。');
  if ((data.routeCount ?? 0) === 0) issues.push('没有模型路由，智能体版本无法绑定路由。');
  if ((data.publishedAgentCount ?? 0) === 0) issues.push('没有已发布的智能体，客户端不会出现入口。');
  const unhealthy = (data.providerHealth ?? []).filter((item) => item.healthStatus === 'UNHEALTHY');
  if (unhealthy.length > 0) {
    issues.push(`有 ${unhealthy.length} 个提供方健康检查未通过：${unhealthy.map((i) => i.name).join('、')}。`);
  }
  return issues;
}
