/**
 * 用量统计。
 *
 * 只做「花了多少、都花在哪」两件事：上方汇总与趋势，下方按请求拉明细。
 * token 与成本都按模型单价估算，不是账单——真实计费以提供方为准，页面上必须写清楚这点。
 */

'use client';

import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';

import dynamic from 'next/dynamic';

import {
  Button,
  Card,
  Empty,
  Form,
  Input,
  InputNumber,
  Modal,
  Select,
  Space,
  Spin,
  Tag,
  message,
} from '@/components/antd-compat';
import { ReloadOutlined } from '@/components/antd-compat/icons';
import { ResizableTable } from '@/components/ResizableTable';

import { aiAgentApi, aiUsageApi, type AiAgent, type AiUsage, type AiUsageSummary } from '@/api/ai';

import { Hint, formatCost, formatNumber, formatTime, useAiDict } from './shared';
import { StatCard } from '@/components/stat-card';
import { usePerm } from '@/lib/perm';

// echarts 体积大且本页非首屏，动态加载（本文件已有 'use client'）
const ReactECharts = dynamic(() => import('echarts-for-react'), {
  ssr: false,
  loading: () => <Spin />,
});

const PAGE_SIZE = 20;

const dateText = (date: Date) =>
  `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
function initialRange() {
  const end = new Date();
  const start = new Date(end);
  start.setDate(start.getDate() - 29);
  return { from: dateText(start), to: dateText(end) };
}

export default function UsagePanel() {
  const hasPerm = usePerm();
  const dict = useAiDict();
  const [reconciling, setReconciling] = useState<AiUsage>();
  const [saving, setSaving] = useState(false);
  const [form] = Form.useForm<{ inputTokens: number; outputTokens: number; note: string }>();
  const [agents, setAgents] = useState<AiAgent[]>([]);
  const [agentId, setAgentId] = useState<number | undefined>();
  const [userId, setUserId] = useState<number | undefined>();
  const [summary, setSummary] = useState<AiUsageSummary | undefined>();
  const [rows, setRows] = useState<AiUsage[]>([]);
  const [total, setTotal] = useState(0);
  const [pageNum, setPageNum] = useState(1);
  const [loading, setLoading] = useState(false);
  const [summaryLoading, setSummaryLoading] = useState(false);
  const [range, setRange] = useState(initialRange);
  const [draftRange, setDraftRange] = useState(range);
  const summaryGeneration = useRef(0);
  const rowsGeneration = useRef(0);

  useEffect(() => {
    aiAgentApi
      .list()
      .then((res) => setAgents(res.data ?? []))
      .catch(() => setAgents([]));
  }, []);

  const loadSummary = useCallback(async () => {
    const generation = ++summaryGeneration.current;
    setSummaryLoading(true);
    try {
      const res = await aiUsageApi.summary({ agentId, userId, ...range });
      if (generation !== summaryGeneration.current) return;
      setSummary(res.data);
    } catch (error) {
      if (generation !== summaryGeneration.current) return;
      setSummary(undefined);
      message.error(error instanceof Error ? error.message : '用量汇总加载失败');
    } finally {
      if (generation === summaryGeneration.current) setSummaryLoading(false);
    }
  }, [agentId, userId, range]);

  const loadRows = useCallback(
    async (page = pageNum) => {
      const generation = ++rowsGeneration.current;
      setLoading(true);
      try {
        const res = await aiUsageApi.list({
          agentId,
          userId,
          ...range,
          pageNum: page,
          pageSize: PAGE_SIZE,
        });
        if (generation !== rowsGeneration.current) return;
        setRows(res.data ?? []);
        // 后端该接口只返回列表，没有总数；用「本页满页」推断是否还有下一页
        setTotal(
          (page - 1) * PAGE_SIZE +
            (res.data?.length ?? 0) +
            (res.data && res.data.length === PAGE_SIZE ? 1 : 0),
        );
        setPageNum(page);
      } catch (error) {
        if (generation !== rowsGeneration.current) return;
        setRows([]);
        message.error(error instanceof Error ? error.message : '用量明细加载失败');
      } finally {
        if (generation === rowsGeneration.current) setLoading(false);
      }
    },
    [agentId, userId, pageNum, range],
  );

  useEffect(() => {
    void loadSummary();
    void loadRows(1);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [agentId, userId, range]);

  useEffect(
    () => () => {
      summaryGeneration.current++;
      rowsGeneration.current++;
    },
    [],
  );

  const applyRange = () => {
    const days = (Date.parse(draftRange.to) - Date.parse(draftRange.from)) / 86400000;
    if (!Number.isFinite(days) || days < 0 || days > 365) {
      message.error('请选择起止日期，单次查询最多 366 天');
      return;
    }
    setRange({ ...draftRange });
  };

  const reconcile = async () => {
    if (saving || reconciling?.id === undefined) return;
    try {
      const values = await form.validateFields();
      setSaving(true);
      await aiUsageApi.reconcile(reconciling.id, values);
      setReconciling(undefined);
      message.success('实际用量已核对并结算');
      await Promise.all([loadRows(), loadSummary()]);
    } catch (error) {
      if (error instanceof Error) message.error(error.message);
    } finally {
      setSaving(false);
    }
  };

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
        <Tag color={value === 'UNKNOWN' ? 'warning' : 'default'}>
          {dict.label('ai_usage_status', value)}
        </Tag>
      ),
    },
    {
      key: 'reconciliation',
      title: '核对',
      width: 170,
      render: (_: unknown, row: AiUsage) =>
        row.status === 'UNKNOWN' && hasPerm('admin:ai:usage:reconcile') ? (
          <Button
            size="small"
            onClick={() => {
              form.resetFields();
              setReconciling(row);
            }}
          >
            核对用量
          </Button>
        ) : row.reconciledAt ? (
          <span title={row.reconciliationNote}>
            已由 #{row.reconciledBy} 核对 · {formatTime(row.reconciledAt)}
          </span>
        ) : (
          '—'
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
        筛选条件同时作用于上方汇总与下方明细，切换后趋势图会重算。 当前统计日期：{range.from} 至{' '}
        {range.to}（含首尾日期，单次最多 366 天）。可选择历史日期核对旧记录。
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
          <Input
            type="date"
            aria-label="用量开始日期"
            value={draftRange.from}
            onChange={(event) =>
              setDraftRange((previous) => ({ ...previous, from: event.target.value }))
            }
            style={{ width: 150 }}
          />
          <span>至</span>
          <Input
            type="date"
            aria-label="用量结束日期"
            value={draftRange.to}
            onChange={(event) =>
              setDraftRange((previous) => ({ ...previous, to: event.target.value }))
            }
            style={{ width: 150 }}
          />
          <Button size="small" onClick={applyRange}>
            查询日期
          </Button>
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
          value={
            summary?.costs?.length
              ? summary.costs
                  .map((cost) => formatCost(cost.settledAmount, cost.currency))
                  .join(' / ')
              : formatCost(0)
          }
          sub="按币种分别统计已结算用量"
        />
      </div>
      {summary?.costs?.some((cost) => cost.unknownStages > 0 || cost.reservedAmount > 0) && (
        <Hint>
          {summary.costs
            .map(
              (cost) =>
                `${cost.currency} 待结算 ${formatCost(cost.reservedAmount, cost.currency)}，未知阶段 ${cost.unknownStages} 个`,
            )
            .join('；')}
          。未知用量保留预占，须依据上游账单核对。
        </Hint>
      )}

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
      <Modal
        title={`核对用量 #${reconciling?.id ?? ''}`}
        open={Boolean(reconciling)}
        onCancel={() => {
          if (!saving) setReconciling(undefined);
        }}
        onOk={() => void reconcile()}
        confirmLoading={saving}
        cancelButtonProps={{ disabled: saving }}
        okText="保存核对结果"
        cancelText="取消"
        destroyOnClose
      >
        <Hint>
          仅核对已结束请求的未知用量。按上游账单填写实际 token；确认未发生调用时可填
          0。保存后保留操作者、时间和依据，不会重试模型。
        </Hint>
        <Form form={form} layout="vertical" disabled={saving}>
          <Form.Item
            name="inputTokens"
            label="实际输入 token"
            rules={[{ required: true, message: '请输入实际用量' }]}
          >
            <InputNumber min={0} max={2147483647} precision={0} />
          </Form.Item>
          <Form.Item
            name="outputTokens"
            label="实际输出 token"
            rules={[{ required: true, message: '请输入实际用量' }]}
          >
            <InputNumber min={0} max={2147483647} precision={0} />
          </Form.Item>
          <Form.Item
            name="note"
            label="核对依据"
            rules={[{ required: true, whitespace: true, message: '请输入核对依据' }]}
          >
            <Input.TextArea rows={3} maxLength={1000} />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
