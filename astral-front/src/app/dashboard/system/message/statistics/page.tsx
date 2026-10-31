'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import dynamic from 'next/dynamic';
import { toast } from 'sonner';
import { RefreshCw, Search } from 'lucide-react';

import {
  messageStatisticsApi,
  MessageDelivery,
  MessageStatistics,
  MessageStatisticsQuery,
  TrendPoint,
} from '@/api/message-statistics';
import { notifyRuleApi, NotifyEventVo } from '@/api/notify';
import { fetchDictOptions, dictLabel, DictOption } from '@/api/dict';
import { TablePagination } from '@/components/data-table/TablePagination';
import { formatDateTime } from '@/lib/format';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Badge } from '@/components/ui/badge';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { PageHeader } from '@/components/page-header';
import { RatioBar, StatCard } from '@/components/stat-card';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';

// echarts 体积大且非首屏，客户端动态加载（本文件有 'use client'，ssr:false 合法）
const ReactECharts = dynamic(() => import('echarts-for-react'), { ssr: false });

/** 渠道固定顺序与折线颜色（颜色只承载展示语义，文案一律取字典） */
const CHANNELS = ['EMAIL', 'SMS', 'INAPP'] as const;
const CHANNEL_COLOR: Record<string, string> = {
  EMAIL: '#2563eb',
  SMS: '#059669',
  INAPP: '#7c3aed',
};

/** Radix Select 的 value 不能为空串，用哨兵表示「不限」 */
const ALL = '__all__';

/** 页面内的筛选草稿（时间保持 datetime-local 原值，提交时再转格式） */
interface FilterState {
  start: string;
  end: string;
  channel: string;
  scene: string;
  status: string;
  pluginId: string;
  keyword: string;
}

const EMPTY_FILTER: FilterState = {
  start: '',
  end: '',
  channel: ALL,
  scene: ALL,
  status: ALL,
  pluginId: '',
  keyword: '',
};

/** datetime-local（yyyy-MM-ddTHH:mm）→ 后端要求的 yyyy-MM-dd HH:mm:ss */
const toParamTime = (value: string): string | undefined => {
  if (!value) return undefined;
  const normalized = value.replace('T', ' ');
  return normalized.length >= 19 ? normalized.slice(0, 19) : `${normalized.slice(0, 16)}:00`;
};

/** 把筛选草稿翻译成三个接口共用的查询参数 */
const buildQuery = (f: FilterState): MessageStatisticsQuery => {
  const q: MessageStatisticsQuery = {};
  const start = toParamTime(f.start);
  const end = toParamTime(f.end);
  if (start) q.start = start;
  if (end) q.end = end;
  if (f.channel !== ALL) q.channel = f.channel;
  if (f.scene !== ALL) q.scene = f.scene;
  if (f.status !== ALL) q.status = Number(f.status);
  if (f.pluginId.trim()) q.pluginId = f.pluginId.trim();
  if (f.keyword.trim()) q.keyword = f.keyword.trim();
  return q;
};

/** 千分位 */
const fmt = (n?: number | null) => (n ?? 0).toLocaleString('zh-CN');

/**
 * 消息统计（邮件 / 短信 / 站内信三渠道合并）。
 *
 * <p>概览、趋势、明细三个接口共用同一组筛选条件，任何筛选变化都会同时刷新三者，
 * 保证「上面看到的数字」与「下面看到的明细」口径一致。</p>
 */
export default function MessageStatisticsPage() {
  const [draft, setDraft] = useState<FilterState>(EMPTY_FILTER);
  const [applied, setApplied] = useState<FilterState>(EMPTY_FILTER);
  const [days, setDays] = useState(7);
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(20);

  const [overview, setOverview] = useState<MessageStatistics>({});
  const [trend, setTrend] = useState<TrendPoint[]>([]);
  const [rows, setRows] = useState<MessageDelivery[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);

  /** 数据字典：渠道 + 发送状态（失败时回退原值） */
  const [dicts, setDicts] = useState<Record<string, DictOption[]>>({});
  const channelDict = dicts.notify_delivery_channel;
  const statusDict = dicts.notify_send_status;

  /** 事件码 → 事件名（拉不到就只显示原始 code，不显示空白） */
  const [events, setEvents] = useState<NotifyEventVo[]>([]);
  const eventName = useMemo(() => {
    const map = new Map<string, string>();
    events.forEach((e) => {
      if (e.code) map.set(e.code, e.name || e.code);
    });
    return map;
  }, [events]);

  useEffect(() => {
    fetchDictOptions(['notify_delivery_channel', 'notify_send_status'])
      .then(setDicts)
      .catch(() => {
        /* 字典失败时回退显示原始值 */
      });
    notifyRuleApi
      .events()
      .then((res) => setEvents(res.data || []))
      .catch(() => {
        /* 无规则查看权限时退化为显示事件码 */
      });
  }, []);

  const load = useCallback(async () => {
    const params = buildQuery(applied);
    setLoading(true);
    try {
      const [ov, tr, li] = await Promise.all([
        messageStatisticsApi.overview(params),
        messageStatisticsApi.trend({ ...params, days }),
        messageStatisticsApi.list({ ...params, pageNum: page, pageSize }),
      ]);
      setOverview(ov.data || {});
      setTrend(tr.data || []);
      setRows(li.data?.records || []);
      setTotal(li.data?.total || 0);
    } catch (e: unknown) {
      toast.error((e as Error)?.message || '加载消息统计失败');
    } finally {
      setLoading(false);
    }
  }, [applied, days, page, pageSize]);

  useEffect(() => {
    void load();
  }, [load]);

  /** 应用筛选：回到第一页并触发三个接口重查 */
  const applyFilters = () => {
    setPage(1);
    setApplied({ ...draft });
  };

  /** 重置筛选 */
  const resetFilters = () => {
    setDraft(EMPTY_FILTER);
    setPage(1);
    setApplied(EMPTY_FILTER);
  };

  /** 趋势折线：日期轴取自返回的稠密网格，缺渠道补 0 */
  const trendOption = useMemo(() => {
    const dates = Array.from(new Set(trend.map((p) => p.date))).sort();
    const series = CHANNELS.map((ch) => ({
      name: dictLabel(channelDict, ch, ch),
      type: 'line' as const,
      smooth: true,
      showSymbol: false,
      itemStyle: { color: CHANNEL_COLOR[ch] },
      data: dates.map((d) => trend.find((p) => p.date === d && p.channel === ch)?.total ?? 0),
    }));
    return {
      tooltip: { trigger: 'axis' as const },
      legend: { data: series.map((s) => s.name), bottom: 0 },
      grid: { left: 48, right: 20, top: 24, bottom: 56 },
      xAxis: { type: 'category' as const, data: dates },
      yAxis: { type: 'value' as const, minInterval: 1 },
      series,
    };
  }, [trend, channelDict]);

  const channelStats = overview.channels || [];
  const sceneStats = overview.scenes || [];
  const pluginStats = overview.plugins || [];

  return (
    <div className="space-y-4">
      <PageHeader title="消息统计" description="消息投递量、成功率与场景分布的综合统计" />
      {/* ---------------- 概览卡 ---------------- */}
      <div className="grid grid-cols-2 gap-3 md:grid-cols-4 xl:grid-cols-7">
        <StatCard size="compact" label="投递总数" value={fmt(overview.total)} />
        <StatCard size="compact" label="成功" value={fmt(overview.success)} tone="success" />
        <StatCard size="compact" label="失败" value={fmt(overview.fail)} tone="danger" />
        <StatCard
          size="compact"
          label="成功率"
          value={`${(overview.successRate ?? 0).toFixed(1)}%`}
        />
        <StatCard size="compact" label="今日投递" value={fmt(overview.todayTotal)} />
        <StatCard
          size="compact"
          label="今日成功"
          value={fmt(overview.todaySuccess)}
          tone="success"
        />
        <StatCard size="compact" label="今日失败" value={fmt(overview.todayFail)} tone="danger" />
      </div>

      {/* ---------------- 筛选 ---------------- */}
      <Card>
        <CardHeader className="pb-3">
          <CardTitle className="flex items-center gap-2 text-base">
            <Search className="size-4" /> 筛选条件
          </CardTitle>
          <CardDescription>概览、趋势、明细共用同一组条件，统计口径一致</CardDescription>
        </CardHeader>
        <CardContent className="space-y-3">
          <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-4">
            <div className="space-y-1.5">
              <Label className="text-muted-foreground text-xs">开始时间</Label>
              <Input
                type="datetime-local"
                value={draft.start}
                onChange={(e) => setDraft({ ...draft, start: e.target.value })}
              />
            </div>
            <div className="space-y-1.5">
              <Label className="text-muted-foreground text-xs">结束时间</Label>
              <Input
                type="datetime-local"
                value={draft.end}
                onChange={(e) => setDraft({ ...draft, end: e.target.value })}
              />
            </div>
            <div className="space-y-1.5">
              <Label className="text-muted-foreground text-xs">投递渠道</Label>
              <Select
                value={draft.channel}
                onValueChange={(v) => setDraft({ ...draft, channel: v })}
              >
                <SelectTrigger>
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value={ALL}>全部渠道</SelectItem>
                  {CHANNELS.map((ch) => (
                    <SelectItem key={ch} value={ch}>
                      {dictLabel(channelDict, ch, ch)}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
            <div className="space-y-1.5">
              <Label className="text-muted-foreground text-xs">场景（事件）</Label>
              <Select value={draft.scene} onValueChange={(v) => setDraft({ ...draft, scene: v })}>
                <SelectTrigger>
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value={ALL}>全部场景</SelectItem>
                  {events.map((e) => (
                    <SelectItem key={e.code} value={e.code}>
                      {e.name || e.code}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
            <div className="space-y-1.5">
              <Label className="text-muted-foreground text-xs">发送状态</Label>
              <Select value={draft.status} onValueChange={(v) => setDraft({ ...draft, status: v })}>
                <SelectTrigger>
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value={ALL}>全部状态</SelectItem>
                  {(statusDict || []).map((o) => (
                    <SelectItem key={o.value} value={o.value}>
                      {o.label}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
            <div className="space-y-1.5">
              <Label className="text-muted-foreground text-xs">发送方插件</Label>
              <Input
                placeholder="如 feedback / unknown"
                value={draft.pluginId}
                onChange={(e) => setDraft({ ...draft, pluginId: e.target.value })}
              />
            </div>
            <div className="space-y-1.5">
              <Label className="text-muted-foreground text-xs">关键词</Label>
              <Input
                placeholder="收件人 / 标题 / 正文"
                value={draft.keyword}
                onChange={(e) => setDraft({ ...draft, keyword: e.target.value })}
                onKeyDown={(e) => {
                  if (e.key === 'Enter') applyFilters();
                }}
              />
            </div>
            <div className="flex items-end gap-2">
              <Button onClick={applyFilters} disabled={loading} className="gap-1.5">
                <Search className="size-4" /> 查询
              </Button>
              <Button
                variant="outline"
                onClick={resetFilters}
                disabled={loading}
                className="gap-1.5"
              >
                <RefreshCw className="size-4" /> 重置
              </Button>
            </div>
          </div>
        </CardContent>
      </Card>

      {/* ---------------- 渠道 / 场景 / 插件 breakdown ---------------- */}
      <div className="grid gap-4 xl:grid-cols-3">
        <Card>
          <CardHeader className="pb-2">
            <CardTitle className="text-base">渠道分布</CardTitle>
            <CardDescription>三渠道合计 {fmt(overview.total)} 条</CardDescription>
          </CardHeader>
          <CardContent className="space-y-3">
            {channelStats.length === 0 ? (
              <p className="text-muted-foreground text-sm">暂无数据</p>
            ) : (
              channelStats.map((c) => (
                <div key={c.channel || 'unknown'} className="space-y-1">
                  <div className="flex items-center justify-between text-sm">
                    <span className="flex items-center gap-2">
                      <span
                        className="size-2 rounded-full"
                        style={{ background: CHANNEL_COLOR[c.channel || ''] || '#94a3b8' }}
                      />
                      {dictLabel(channelDict, c.channel, c.channel || '未知')}
                    </span>
                    <span className="tabular-nums">
                      {fmt(c.total)}
                      <span className="text-muted-foreground ml-2 text-xs">
                        成功 {fmt(c.success)} / 失败 {fmt(c.fail)}
                      </span>
                    </span>
                  </div>
                  <RatioBar
                    percent={overview.total ? (c.total / overview.total) * 100 : 0}
                    color={CHANNEL_COLOR[c.channel || ''] || '#94a3b8'}
                  />
                </div>
              ))
            )}
          </CardContent>
        </Card>

        <Card>
          <CardHeader className="pb-2">
            <CardTitle className="text-base">场景分布</CardTitle>
            <CardDescription>按事件码聚合，共 {sceneStats.length} 个场景</CardDescription>
          </CardHeader>
          <CardContent className="space-y-3">
            {sceneStats.length === 0 ? (
              <p className="text-muted-foreground text-sm">暂无数据</p>
            ) : (
              sceneStats.slice(0, 10).map((s) => (
                <div key={s.scene || 'unknown'} className="space-y-1">
                  <div className="flex items-center justify-between gap-2 text-sm">
                    <span className="truncate" title={s.scene}>
                      {eventName.get(s.scene || '') || s.scene || '未知'}
                    </span>
                    <span className="shrink-0 tabular-nums">{fmt(s.total)}</span>
                  </div>
                  <RatioBar
                    percent={sceneStats[0]?.total ? (s.total / sceneStats[0].total) * 100 : 0}
                    color="#0ea5e9"
                  />
                </div>
              ))
            )}
          </CardContent>
        </Card>

        <Card>
          <CardHeader className="pb-2">
            <CardTitle className="text-base">插件分布</CardTitle>
            <CardDescription>来源由发送方声明，历史数据归 unknown</CardDescription>
          </CardHeader>
          <CardContent className="space-y-3">
            {pluginStats.length === 0 ? (
              <p className="text-muted-foreground text-sm">暂无数据</p>
            ) : (
              pluginStats.map((p) => (
                <div key={p.pluginId || 'unknown'} className="space-y-1">
                  <div className="flex items-center justify-between gap-2 text-sm">
                    <span className="truncate">{p.pluginId || 'unknown'}</span>
                    <span className="shrink-0 tabular-nums">
                      {fmt(p.total)}
                      <span className="text-muted-foreground ml-2 text-xs">失败 {fmt(p.fail)}</span>
                    </span>
                  </div>
                  <RatioBar
                    percent={pluginStats[0]?.total ? (p.total / pluginStats[0].total) * 100 : 0}
                    color="#f59e0b"
                  />
                </div>
              ))
            )}
          </CardContent>
        </Card>
      </div>

      {/* ---------------- 趋势 ---------------- */}
      <Card>
        <CardHeader className="flex flex-row items-center justify-between space-y-0 pb-2">
          <div className="space-y-1">
            <CardTitle className="text-base">投递趋势</CardTitle>
            <CardDescription>按天 × 渠道，窗口内无数据的天补 0</CardDescription>
          </div>
          <Select
            value={String(days)}
            onValueChange={(v) => {
              setDays(Number(v));
              setPage(1);
            }}
          >
            <SelectTrigger className="w-[110px]">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="7">近 7 天</SelectItem>
              <SelectItem value="14">近 14 天</SelectItem>
              <SelectItem value="30">近 30 天</SelectItem>
            </SelectContent>
          </Select>
        </CardHeader>
        <CardContent>
          <div className="h-[300px]">
            <ReactECharts option={trendOption} style={{ height: '100%', width: '100%' }} notMerge />
          </div>
        </CardContent>
      </Card>

      {/* ---------------- 明细 ---------------- */}
      <Card>
        <CardHeader className="pb-2">
          <CardTitle className="text-base">投递明细</CardTitle>
          <CardDescription>三渠道合并、按发送时间倒序，共 {fmt(total)} 条</CardDescription>
        </CardHeader>
        <CardContent className="space-y-3">
          <div className="overflow-x-auto">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead className="w-[90px]">渠道</TableHead>
                  <TableHead className="min-w-[140px]">场景</TableHead>
                  <TableHead className="min-w-[160px]">收件人</TableHead>
                  <TableHead className="min-w-[220px]">标题 / 摘要</TableHead>
                  <TableHead className="w-[90px]">状态</TableHead>
                  <TableHead className="min-w-[160px]">失败原因</TableHead>
                  <TableHead className="w-[160px]">发送时间</TableHead>
                  <TableHead className="w-[110px]">插件</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {loading ? (
                  <TableRow>
                    <TableCell colSpan={8} className="text-muted-foreground text-center">
                      加载中…
                    </TableCell>
                  </TableRow>
                ) : rows.length === 0 ? (
                  <TableRow>
                    <TableCell colSpan={8} className="text-muted-foreground text-center">
                      暂无投递记录
                    </TableCell>
                  </TableRow>
                ) : (
                  rows.map((row, idx) => (
                    <TableRow key={`${row.channel}-${row.id}-${idx}`}>
                      <TableCell>
                        <Badge
                          variant="outline"
                          style={{ borderColor: CHANNEL_COLOR[row.channel || ''] || undefined }}
                        >
                          {dictLabel(channelDict, row.channel, row.channel || '未知')}
                        </Badge>
                      </TableCell>
                      <TableCell>
                        <div className="flex flex-col">
                          <span className="truncate" title={row.scene}>
                            {eventName.get(row.scene || '') || row.scene || '未知'}
                          </span>
                          {row.scene && eventName.get(row.scene) && (
                            <span className="text-muted-foreground text-xs">{row.scene}</span>
                          )}
                        </div>
                      </TableCell>
                      <TableCell className="max-w-[200px] truncate" title={row.recipient}>
                        {row.recipient || '-'}
                      </TableCell>
                      <TableCell className="max-w-[280px] truncate" title={row.title}>
                        {row.title || '-'}
                      </TableCell>
                      <TableCell>
                        <Badge variant={row.status === 1 ? 'default' : 'destructive'}>
                          {row.statusText || (row.status === 1 ? '成功' : '失败')}
                        </Badge>
                      </TableCell>
                      <TableCell
                        className="text-destructive max-w-[220px] truncate"
                        title={row.errorMsg}
                      >
                        {row.errorMsg || '-'}
                      </TableCell>
                      <TableCell className="text-muted-foreground whitespace-nowrap">
                        {formatDateTime(row.sendTime)}
                      </TableCell>
                      <TableCell className="text-muted-foreground">{row.pluginId || '-'}</TableCell>
                    </TableRow>
                  ))
                )}
              </TableBody>
            </Table>
          </div>

          <TablePagination
            className="border-t-0"
            pagination={{
              current: page,
              pageSize,
              showSizeChanger: true,
              showQuickJumper: true,
              pageSizeOptions: ['10', '20', '50', '100'],
              showTotal: (t) => `共 ${fmt(t)} 条`,
              onChange: (p, ps) => {
                setPage(p);
                setPageSize(ps);
              },
            }}
            total={total}
          />
        </CardContent>
      </Card>
    </div>
  );
}
