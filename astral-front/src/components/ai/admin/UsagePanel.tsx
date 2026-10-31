/**
 * 用量统计。
 *
 * 只做「花了多少、都花在哪」两件事：上方汇总与趋势，下方按请求拉明细。
 * token 与成本都按模型单价估算，不是账单——真实计费以提供方为准，页面上必须写清楚这点。
 */

'use client';

import React, { useCallback, useEffect, useMemo, useState } from 'react';

import dynamic from 'next/dynamic';

import {
  Button,
  Card,
  Empty,
  InputNumber,
  Select,
  Space,
  Spin,
  Tag,
  message,
} from '@/components/antd-compat';
import { ReloadOutlined } from '@/components/antd-compat/icons';
import { ResizableTable } from '@/components/ResizableTable';

import { aiAgentApi, aiUsageApi, type AiAgent, type AiUsage, type AiUsageSummary } from '@/api/ai';

import { Hint, formatCost, formatNumber, formatTime } from './shared';
import { StatCard } from '@/components/stat-card';

// echarts 体积大且本页非首屏，动态加载（本文件已有 'use client'）
const ReactECharts = dynamic(() => import('echarts-for-react'), {
  ssr: false,
  loading: () => <Spin />,
});

const PAGE_SIZE = 20;

export default function UsagePanel() {
  const [agents, setAgents] = useState<AiAgent[]>([]);
  const [agentId, setAgentId] = useState<number | undefined>();
  const [userId, setUserId] = useState<number | undefined>();
  const [summary, setSummary] = useState<AiUsageSummary | undefined>();
  const [rows, setRows] = useState<AiUsage[]>([]);
  const [total, setTotal] = useState(0);
  const [pageNum, setPageNum] = useState(1);
  const [loading, setLoading] = useState(false);
  const [summaryLoading, setSummaryLoading] = useState(false);

  useEffect(() => {
    aiAgentApi
      .list()
      .then((res) => setAgents(res.data ?? []))
      .catch(() => setAgents([]));
  }, []);

  const loadSummary = useCallback(async () => {
    setSummaryLoading(true);
    try {
      const res = await aiUsageApi.summary({ agentId, userId });
      setSummary(res.data);
    } catch (error) {
      message.error(error instanceof Error ? error.message : '用量汇总加载失败');
    } finally {
      setSummaryLoading(false);
    }
  }, [agentId, userId]);

  const loadRows = useCallback(
    async (page = pageNum) => {
      setLoading(true);
      try {
        const res = await aiUsageApi.list({ agentId, userId, pageNum: page, pageSize: PAGE_SIZE });
        setRows(res.data ?? []);
        // 后端该接口只返回列表，没有总数；用「本页满页」推断是否还有下一页
        setTotal(
          (page - 1) * PAGE_SIZE +
            (res.data?.length ?? 0) +
            (res.data && res.data.length === PAGE_SIZE ? 1 : 0),
        );
        setPageNum(page);
      } catch (error) {
        message.error(error instanceof Error ? error.message : '用量明细加载失败');
      } finally {
        setLoading(false);
      }
    },
    [agentId, userId, pageNum],
  );

  useEffect(() => {
    void loadSummary();
    void loadRows(1);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [agentId, userId]);

  const trendOption = useMemo(() => {
    const trend = summary?.trend ?? [];
    if (trend.length === 0) return null;
    return {
      grid: { left: 8, right: 12, top: 28, bottom: 8, containLabel: true },
      tooltip: { trigger: 'axis' },
      legend: { show: true, top: 0, itemWidth: 10, itemHeight: 10, textStyle: { fontSize: 11 } },
      xAxis: {
        type: 'category',
        data: trend.map((item) => item.date ?? ''),
        axisLine: { show: false },
        axisTick: { show: false },
        axisLabel: { fontSize: 11 },
      },
      yAxis: [
        {
          type: 'value',
          splitLine: { lineStyle: { opacity: 0.15 } },
          axisLabel: { fontSize: 11 },
        },
      ],
      series: [
        {
          name: '请求数',
          type: 'line',
          smooth: true,
          showSymbol: false,
          areaStyle: { opacity: 0.12 },
          data: trend.map((item) => item.requestCount ?? 0),
        },
        {
          name: '输入 token',
          type: 'line',
          smooth: true,
          showSymbol: false,
          data: trend.map((item) => item.inputTokens ?? 0),
        },
        {
          name: '输出 token',
          type: 'line',
          smooth: true,
          showSymbol: false,
          data: trend.map((item) => item.outputTokens ?? 0),
        },
      ],
    };
  }, [summary]);

  const agentOptions = useMemo(
    () =>
      agents
        .filter((item) => item.id !== undefined)
        .map((item) => ({ label: `${item.name ?? item.agentCode}`, value: item.id as number })),
    [agents],
  );

  const columns = [
    {
      key: 'id',
      title: '#',
      dataIndex: 'id',
      width: 80,
      render: (value: number) => <code style={{ fontSize: 12 }}>{value}</code>,
    },
    {
      key: 'agent',
      title: '智能体',
      dataIndex: 'agentName',
      width: 150,
      render: (value: string) => value ?? '-',
    },
    {
      key: 'model',
      title: '模型',
      dataIndex: 'modelCode',
      width: 150,
      render: (value: string) => <code style={{ fontSize: 11.5 }}>{value ?? '-'}</code>,
    },
    { key: 'user', title: '用户', dataIndex: 'userId', width: 80, align: 'right' as const },
    {
      key: 'in',
      title: '输入 token',
      dataIndex: 'inputTokens',
      width: 110,
      align: 'right' as const,
      render: (value: number) => formatNumber(value),
    },
    {
      key: 'out',
      title: '输出 token',
      dataIndex: 'outputTokens',
      width: 110,
      align: 'right' as const,
      render: (value: number) => formatNumber(value),
    },
    {
      key: 'cost',
      title: '估算成本',
      dataIndex: 'estimatedCost',
      width: 110,
      align: 'right' as const,
      render: (value: number, row: AiUsage) => formatCost(value, row.currency),
    },
    {
      key: 'status',
      title: '结算',
      dataIndex: 'status',
      width: 100,
      render: (value: string) => (
        <Tag
          color={value === 'SETTLED' ? 'success' : value === 'RELEASED' ? 'default' : 'processing'}
        >
          {value === 'SETTLED'
            ? '已结算'
            : value === 'RELEASED'
              ? '已释放'
              : value === 'RESERVED'
                ? '预占'
                : (value ?? '-')}
        </Tag>
      ),
    },
    {
      key: 'time',
      title: '时间',
      dataIndex: 'createTime',
      width: 150,
      render: (value: string) => (
        <span style={{ fontSize: 11.5, opacity: 0.6 }}>{formatTime(value)}</span>
      ),
    },
  ];

  return (
    <div>
      <Hint>
        成本按模型单价<strong>估算</strong>，用于横向比较谁在烧钱，不等于账单金额。
        筛选条件同时作用于上方汇总与下方明细，切换后趋势图会重算。
      </Hint>

      <div
        style={{
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
          marginBottom: 12,
          gap: 8,
          flexWrap: 'wrap',
        }}
      >
        <Space size={8} wrap>
          <Select
            size="small"
            allowClear
            placeholder="全部智能体"
            style={{ width: 190 }}
            value={agentId}
            onChange={(value) => setAgentId(value)}
            options={agentOptions}
          />
          <InputNumber
            size="small"
            min={1}
            placeholder="用户 ID"
            style={{ width: 120 }}
            value={userId ?? null}
            onChange={(value) => setUserId(value ?? undefined)}
          />
        </Space>
        <Button
          size="small"
          icon={<ReloadOutlined />}
          loading={loading || summaryLoading}
          onClick={() => {
            void loadSummary();
            void loadRows(1);
          }}
        >
          刷新
        </Button>
      </div>

      <div className="mb-3 grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
        <StatCard size="compact" label="请求数" value={formatNumber(summary?.requestCount ?? 0)} />
        <StatCard
          size="compact"
          label="输入 token"
          value={formatNumber(summary?.inputTokens ?? 0)}
        />
        <StatCard
          size="compact"
          label="输出 token"
          value={formatNumber(summary?.outputTokens ?? 0)}
        />
        <StatCard
          size="compact"
          label="估算成本"
          value={formatCost(summary?.estimatedCost ?? 0)}
          sub={summary?.currency ?? 'CNY'}
        />
      </div>

      <Card size="small" title="用量趋势" style={{ marginBottom: 12 }}>
        {summaryLoading ? (
          <div style={{ padding: 32, textAlign: 'center' }}>
            <Spin />
          </div>
        ) : trendOption ? (
          <ReactECharts option={trendOption} notMerge autoResize style={{ height: 240 }} />
        ) : (
          <Empty description="所选范围内没有用量数据" />
        )}
      </Card>

      <Card size="small" title="用量明细" bodyStyle={{ padding: 0 }}>
        {loading && rows.length === 0 ? (
          <div style={{ padding: 40, textAlign: 'center' }}>
            <Spin />
          </div>
        ) : rows.length === 0 ? (
          <Empty description="没有明细记录" />
        ) : (
          <ResizableTable
            resizeKey="ai-usage"
            rowKey="id"
            columns={columns}
            dataSource={rows}
            loading={loading}
            pagination={{
              current: pageNum,
              pageSize: PAGE_SIZE,
              total,
              onChange: (page) => void loadRows(page),
              showSizeChanger: false,
            }}
          />
        )}
      </Card>
    </div>
  );
}
