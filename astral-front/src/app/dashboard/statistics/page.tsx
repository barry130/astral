'use client';

import { useState, useEffect, useCallback } from 'react';
import { Card,
  Row,
  Col,
  Statistic,
  DatePicker,
  Select,
  Spin,
  Tabs,
  Tag,
  Button,
  Drawer,
  Space,
  Typography } from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import ReactECharts from 'echarts-for-react';
import dayjs, { Dayjs } from 'dayjs';
import {
  statApi,
  loadUtOptions,
  utLabel,
  METRIC_OPTIONS,
  ApiTopItem,
  ApiTopSummary,
  DayOverview,
  ErrorSummaryItem,
  StatErrorLogItem,
} from '@/api/statistics';
import { ResizableTable } from '@/components/ResizableTable';

/** 平台下拉选项类型 */
type Option = { value: string; label: string };

const { Text } = Typography;

/** 千分位 */
const fmt = (n?: number | null) => (n ?? 0).toLocaleString('zh-CN');
/** 毫秒时长 → 人读格式 */
const fmtDuration = (ms?: number | null) => {
  const v = ms ?? 0;
  if (v < 1000) return `${v}ms`;
  if (v < 60000) return `${(v / 1000).toFixed(1)}s`;
  return `${Math.floor(v / 60000)}m${Math.round((v % 60000) / 1000)}s`;
};
/** 数值对比（今日 vs 昨日） */
const diffText = (today?: number | null, yesterday?: number | null) => {
  const t = today ?? 0;
  const y = yesterday ?? 0;
  if (y === 0) return t === 0 ? '持平' : '—';
  const pct = (((t - y) / y) * 100).toFixed(1);
  const up = t >= y;
  return (
    <Text type={up ? 'success' : 'danger'} style={{ fontSize: 12 }}>
      {up ? '↑' : '↓'} {Math.abs(Number(pct))}%
    </Text>
  );
};

export default function StatisticsPage() {
  return (
    <Card>
      <Tabs
        defaultActiveKey="device"
        items={[
          { key: 'device', label: '设备统计', children: <DeviceTab /> },
          { key: 'api', label: '接口统计', children: <ApiTab /> },
          { key: 'error', label: '错误统计', children: <ErrorTab /> },
        ]}
      />
    </Card>
  );
}

/* ============================== 设备统计 ============================== */

function DeviceTab() {
  const [loading, setLoading] = useState(false);
  const [date, setDate] = useState<Dayjs>(dayjs());
  const [ut, setUt] = useState('all');
  const [metric, setMetric] = useState('pv');
  /** 平台下拉选项（字典 stat_platform） */
  const [utOptions, setUtOptions] = useState<Option[]>([{ value: 'all', label: '全部平台' }]);
  const [today, setToday] = useState<DayOverview>();
  const [yesterday, setYesterday] = useState<DayOverview>();
  const [trend, setTrend] = useState<{ hours: string[]; today: number[]; yesterday: number[] }>();

  /** 加载平台选项（字典 stat_platform，失败回退内置） */
  useEffect(() => {
    let alive = true;
    loadUtOptions().then((opts) => {
      if (alive) setUtOptions(opts);
    });
    return () => {
      alive = false;
    };
  }, []);

  const fetchData = useCallback(async () => {
    setLoading(true);
    try {
      const d = date.format('YYYY-MM-DD');
      const [ov, tr] = await Promise.all([
        statApi.getOverview(d, ut),
        statApi.getTrend(metric, d, ut),
      ]);
      setToday(ov.data?.today);
      setYesterday(ov.data?.yesterday);
      setTrend(tr.data);
    } catch {
      // 接口异常由 client.ts 统一提示
    } finally {
      setLoading(false);
    }
  }, [date, ut, metric]);

  useEffect(() => {
    fetchData();
  }, [fetchData]);

  const rows: Array<{ key: string; label: string; field: keyof DayOverview; fmtFn?: (v?: number | null) => string }> = [
    { key: 'newDevices', label: '新增设备', field: 'newDevices' },
    { key: 'activeDevices', label: '活跃设备', field: 'activeDevices' },
    { key: 'totalDevices', label: '总设备数', field: 'totalDevices' },
    { key: 'pv', label: '页面访问(PV)', field: 'pv' },
    { key: 'visits', label: '访问次数', field: 'visits' },
    { key: 'launches', label: '启动次数', field: 'launches' },
    { key: 'avgDurationMs', label: '平均停留', field: 'avgDurationMs', fmtFn: fmtDuration },
    { key: 'errorCount', label: '错误次数', field: 'errorCount' },
  ];

  const trendOption = {
    tooltip: { trigger: 'axis' },
    legend: { data: ['今日', '昨日'], top: 0 },
    grid: { left: 48, right: 24, top: 40, bottom: 32 },
    xAxis: { type: 'category', data: trend?.hours ?? [] },
    yAxis: { type: 'value', name: METRIC_OPTIONS.find((m) => m.value === metric)?.label },
    series: [
      {
        name: '今日',
        type: 'line',
        smooth: true,
        data: trend?.today ?? [],
        itemStyle: { color: '#18181b' },
        areaStyle: { opacity: 0.08 },
      },
      {
        name: '昨日',
        type: 'line',
        smooth: true,
        data: trend?.yesterday ?? [],
        itemStyle: { color: '#91cc75' },
      },
    ],
  };

  return (
    <Spin spinning={loading}>
      <Space className="filter-bar" style={{ marginBottom: 16 }} wrap>
        <DatePicker value={date} onChange={(d) => d && setDate(d)} allowClear={false} />
        <Select value={ut} onChange={setUt} style={{ width: 140 }} options={utOptions} />
        <Button icon={<ReloadOutlined />} onClick={fetchData}>刷新</Button>
      </Space>

      <Row gutter={[12, 12]}>
        {rows.map((r) => (
          <Col key={r.key} xs={12} sm={8} md={6} xl={3}>
            <Card size="small">
              <Statistic
                title={r.label}
                value={r.fmtFn ? r.fmtFn(today?.[r.field] as number) : fmt(today?.[r.field] as number)}
              />
              <div style={{ marginTop: 4 }}>
                <Text type="secondary" style={{ fontSize: 12 }}>
                  昨日 {r.fmtFn ? r.fmtFn(yesterday?.[r.field] as number) : fmt(yesterday?.[r.field] as number)}
                </Text>{' '}
                {diffText(today?.[r.field] as number, yesterday?.[r.field] as number)}
              </div>
            </Card>
          </Col>
        ))}
      </Row>

      <Card size="small" style={{ marginTop: 16 }} title={null}>
        <Space style={{ marginBottom: 8 }}>
          <Text>趋势指标</Text>
          <Select value={metric} onChange={setMetric} style={{ width: 150 }} options={METRIC_OPTIONS} />
        </Space>
        <div className="chart-box" style={{ height: 340 }}>
          <ReactECharts option={trendOption} notMerge autoResize style={{ height: '100%' }} />
        </div>
      </Card>
    </Spin>
  );
}

/* ============================== 接口统计 ============================== */

function ApiTab() {
  const [loading, setLoading] = useState(false);
  const [date, setDate] = useState<Dayjs>(dayjs());
  const [limit, setLimit] = useState(10);
  const [topData, setTopData] = useState<ApiTopItem[]>([]);
  const [summary, setSummary] = useState<ApiTopSummary>({ callCount: 0, successCount: 0, failureCount: 0, successRate: '0.00' });

  const fetchData = useCallback(async () => {
    setLoading(true);
    try {
      const res = await statApi.getApiTop(limit, date.format('YYYY-MM-DD'));
      setTopData(res.data?.list || []);
      setSummary(res.data?.summary || { callCount: 0, successCount: 0, failureCount: 0, successRate: '0.00' });
    } catch {
      // ignore
    } finally {
      setLoading(false);
    }
  }, [date, limit]);

  useEffect(() => {
    fetchData();
  }, [fetchData]);

  const totalCalls = summary.callCount || 0;
  const totalSuccess = summary.successCount || 0;
  const totalFailure = summary.failureCount || 0;
  const successRate = summary.successRate;

  const columns = [
    { title: 'API路径', dataIndex: 'apiPath', ellipsis: true },
    { title: '方法', dataIndex: 'apiMethod', width: 80 },
    {
      title: '调用次数',
      dataIndex: 'callCount',
      width: 110,
      sorter: (a: ApiTopItem, b: ApiTopItem) => a.callCount - b.callCount,
      render: (v: number) => fmt(v),
    },
    { title: '成功次数', dataIndex: 'successCount', width: 100, render: (v: number) => fmt(v) },
    {
      title: '失败次数',
      dataIndex: 'failureCount',
      width: 100,
      render: (v: number) => (v > 0 ? <Text type="danger">{fmt(v)}</Text> : fmt(v)),
    },
    { title: '平均耗时(ms)', dataIndex: 'avgTime', width: 120, sorter: (a: ApiTopItem, b: ApiTopItem) => a.avgTime - b.avgTime },
    { title: '最大耗时(ms)', dataIndex: 'maxTime', width: 120 },
  ];

  const barOption = {
    title: { text: 'Top 接口调用量', left: 'center', textStyle: { fontSize: 14 } },
    tooltip: { trigger: 'axis' },
    grid: { left: 60, right: 24, top: 40, bottom: 60 },
    xAxis: {
      type: 'category',
      data: topData.slice(0, 10).map((i) => i.apiPath),
      axisLabel: { rotate: 30, fontSize: 10 },
    },
    yAxis: { type: 'value' },
    series: [
      {
        data: topData.slice(0, 10).map((i) => i.callCount || 0),
        type: 'bar',
        itemStyle: { color: '#3f3f46', borderRadius: [4, 4, 0, 0] },
      },
    ],
  };

  const pieOption = {
    title: { text: '成功率分布', left: 'center', textStyle: { fontSize: 14 } },
    tooltip: { trigger: 'item', formatter: '{b}: {c} ({d}%)' },
    series: [
      {
        type: 'pie',
        radius: ['40%', '70%'],
        data: [
          { value: totalSuccess, name: '成功', itemStyle: { color: '#52c41a' } },
          { value: totalFailure, name: '失败', itemStyle: { color: '#ff4d4f' } },
        ],
      },
    ],
  };

  return (
    <Spin spinning={loading}>
      <Space className="filter-bar" style={{ marginBottom: 16 }} wrap>
        <DatePicker value={date} onChange={(d) => d && setDate(d)} allowClear={false} />
        <Select
          value={limit}
          onChange={setLimit}
          style={{ width: 120 }}
          options={[10, 20, 50].map((n) => ({ value: n, label: `Top ${n}` }))}
        />
        <Button icon={<ReloadOutlined />} onClick={fetchData}>刷新</Button>
      </Space>

      <Row gutter={12} style={{ marginBottom: 16 }}>
        <Col xs={{ span: 24 }} sm={{ span: 12 }} md={{ span: 8 }}><Card size="small"><Statistic title="总调用次数" value={fmt(totalCalls)} /></Card></Col>
        <Col xs={{ span: 24 }} sm={{ span: 12 }} md={{ span: 8 }}><Card size="small"><Statistic title="成功次数" value={fmt(totalSuccess)} valueStyle={{ color: '#52c41a' }} /></Card></Col>
        <Col xs={{ span: 24 }} sm={{ span: 12 }} md={{ span: 8 }}><Card size="small"><Statistic title="成功率" value={`${successRate}%`} /></Card></Col>
      </Row>

      <ResizableTable<ApiTopItem>
        rowKey={(r) => `${r.apiMethod}-${r.apiPath}`}
        columns={columns}
        dataSource={topData}
        pagination={false}
        size="small"
        style={{ marginBottom: 16 }}
        scroll={{ x: 'max-content' }}
      />

      <Row gutter={12}>
        <Col xs={{ span: 24 }} lg={{ span: 14 }}>
          <div className="chart-box" style={{ height: 320 }}>
            <ReactECharts option={barOption} notMerge autoResize style={{ height: '100%' }} />
          </div>
        </Col>
        <Col xs={{ span: 24 }} lg={{ span: 10 }}>
          <div className="chart-box" style={{ height: 320 }}>
            <ReactECharts option={pieOption} notMerge autoResize style={{ height: '100%' }} />
          </div>
        </Col>
      </Row>
    </Spin>
  );
}

/* ============================== 错误统计 ============================== */

const ERROR_TYPE_COLORS: Record<string, string> = {
  js: 'blue',
  network: 'orange',
  biz: 'purple',
  crash: 'red',
};

function ErrorTab() {
  const [loading, setLoading] = useState(false);
  const [date, setDate] = useState<Dayjs>(dayjs());
  const [summary, setSummary] = useState<ErrorSummaryItem[]>([]);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [currentFingerprint, setCurrentFingerprint] = useState<string>();

  const fetchSummary = useCallback(async () => {
    setLoading(true);
    try {
      const res = await statApi.getErrorSummary(date.format('YYYY-MM-DD'));
      setSummary(res.data || []);
    } catch {
      // ignore
    } finally {
      setLoading(false);
    }
  }, [date]);

  useEffect(() => {
    fetchSummary();
  }, [fetchSummary]);

  const openDetail = (fingerprint: string) => {
    setCurrentFingerprint(fingerprint);
    setDrawerOpen(true);
  };

  const columns = [
    { title: '次数', dataIndex: 'count', width: 80, render: (v: number) => fmt(v) },
    { title: '影响设备', dataIndex: 'affectedDevices', width: 90, render: (v: number) => fmt(v) },
    {
      title: '类型',
      dataIndex: 'errorType',
      width: 90,
      render: (v: string) => <Tag color={ERROR_TYPE_COLORS[v] || 'default'}>{v}</Tag>,
    },
    { title: '样例信息', dataIndex: 'sampleMessage', ellipsis: true },
    { title: '版本', dataIndex: 'topAppVersion', width: 90 },
    { title: '首次', dataIndex: 'firstSeen', width: 160, render: (v: string) => (v ? dayjs(v).format('HH:mm:ss') : '-') },
    { title: '最近', dataIndex: 'lastSeen', width: 160, render: (v: string) => (v ? dayjs(v).format('HH:mm:ss') : '-') },
    {
      title: '操作',
      width: 90,
      render: (_: unknown, r: ErrorSummaryItem) => (
        <Button type="link" size="small" onClick={() => openDetail(r.fingerprint)}>
          明细
        </Button>
      ),
    },
  ];

  return (
    <Spin spinning={loading}>
      <Space className="filter-bar" style={{ marginBottom: 16 }} wrap>
        <DatePicker value={date} onChange={(d) => d && setDate(d)} allowClear={false} />
        <Button icon={<ReloadOutlined />} onClick={fetchSummary}>刷新</Button>
      </Space>

      <ResizableTable<ErrorSummaryItem>
        rowKey="fingerprint"
        columns={columns}
        dataSource={summary}
        pagination={{ pageSize: 10, showTotal: (t) => `共 ${t} 组` }}
        size="small"
        scroll={{ x: 'max-content' }}
      />

      <ErrorDetailDrawer
        open={drawerOpen}
        fingerprint={currentFingerprint}
        date={date}
        onClose={() => setDrawerOpen(false)}
      />
    </Spin>
  );
}

/** 错误明细分页抽屉（同一 fingerprint） */
function ErrorDetailDrawer({
  open,
  fingerprint,
  date,
  onClose,
}: {
  open: boolean;
  fingerprint?: string;
  date: Dayjs;
  onClose: () => void;
}) {
  const [loading, setLoading] = useState(false);
  const [records, setRecords] = useState<StatErrorLogItem[]>([]);
  const [total, setTotal] = useState(0);
  const [pageNum, setPageNum] = useState(1);
  /** 平台下拉选项（字典 stat_platform，用于平台列值→文案） */
  const [utOptions, setUtOptions] = useState<Option[]>([{ value: 'all', label: '全部平台' }]);
  const pageSize = 10;

  /** 加载平台选项（字典 stat_platform，失败回退内置） */
  useEffect(() => {
    if (!open) return;
    let alive = true;
    loadUtOptions().then((opts) => {
      if (alive) setUtOptions(opts);
    });
    return () => {
      alive = false;
    };
  }, [open]);

  const fetchDetail = useCallback(async () => {
    if (!fingerprint || !open) return;
    setLoading(true);
    try {
      const res = await statApi.getErrorPage({
        pageNum,
        pageSize,
        fingerprint,
        // 明细窗口放宽到日期之后一天，保证当日完整
      });
      setRecords(res.data?.records || []);
      setTotal(res.data?.total || 0);
    } catch {
      // ignore
    } finally {
      setLoading(false);
    }
  }, [fingerprint, open, pageNum]);

  useEffect(() => {
    fetchDetail();
  }, [fetchDetail]);

  const columns = [
    { title: '时间', dataIndex: 'occurTime', width: 110, render: (v: string) => (v ? dayjs(v).format('MM-DD HH:mm:ss') : '-') },
    {
      title: '类型',
      dataIndex: 'errorType',
      width: 80,
      render: (v: string) => <Tag color={ERROR_TYPE_COLORS[v] || 'default'}>{v}</Tag>,
    },
    { title: '信息', dataIndex: 'message', ellipsis: true },
    { title: '页面', dataIndex: 'page', width: 150, ellipsis: true },
    { title: '平台', dataIndex: 'ut', width: 100, render: (v?: string) => utLabel(utOptions, v) },
    { title: '版本', dataIndex: 'appVersion', width: 80 },
    { title: 'IP 地址', dataIndex: 'ip', width: 130, render: (v?: string) => (v ? <Text code>{v}</Text> : '-') },
  ];

  return (
    <Drawer
      title={<Space><Text code style={{ fontSize: 12 }}>{fingerprint?.slice(0, 16)}…</Text><Text type="secondary">错误明细</Text></Space>}
      width={860}
      open={open}
      onClose={onClose}
      destroyOnClose
    >
      <ResizableTable<StatErrorLogItem>
        rowKey="id"
        columns={columns}
        dataSource={records}
        loading={loading}
        size="small"
        scroll={{ x: 'max-content' }}
        expandable={{
          expandedRowRender: (r) => (
            <div>
              <p><b>机型：</b>{r.model || '-'} / {r.os || '-'}</p>
              {r.stack ? (
                <pre style={{ maxHeight: 240, overflow: 'auto', background: '#f6f6f6', padding: 8, fontSize: 12 }}>
                  {r.stack}
                </pre>
              ) : null}
            </div>
          ),
          rowExpandable: (r) => Boolean(r.stack) || Boolean(r.model),
        }}
        pagination={{
          current: pageNum,
          pageSize,
          total,
          showSizeChanger: false,
          onChange: (p) => setPageNum(p),
          showTotal: (t) => `共 ${t} 条`,
        }}
      />
    </Drawer>
  );
}
