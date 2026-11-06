'use client';

import { useEffect, useState } from 'react';
import {
  Activity,
  Coffee,
  Database,
  Gauge,
  Info,
  Loader2,
  Radio,
  ShieldAlert,
  Zap,
} from 'lucide-react';

import { monitorApi, DashboardOverviewDTO } from '@/api/monitor';
import { Progress } from '@/components/ui/progress';
import { cn } from '@/lib/utils';
import { usePerm } from '@/lib/perm';
import { PageHeader } from '@/components/page-header';
import { SectionCard } from '@/components/section-card';
import { ResourceMeter } from '@/components/resource-meter';
import {
  STAT_TONE_BAR,
  STAT_TONE_TEXT,
  StatTile,
  ratioTone,
  usageTone,
} from '@/components/stat-card';
import { Sparkline } from '@/components/sparkline';
import { TrendBadge } from '@/components/trend-badge';
import { InfoList, InfoRow } from '@/components/info-list';

/** 轮询间隔：统计口径按天聚合，秒级刷新没有意义；系统指标 30 秒足以感知异常 */
const REFRESH_INTERVAL_MS = 30_000;

/* ------------------------------ 格式化工具 ------------------------------ */

/** 字节数 → 可读格式（B/KB/MB/GB/TB） */
function formatBytes(bytes: number): string {
  if (!bytes) return '0 B';
  const k = 1024;
  const sizes = ['B', 'KB', 'MB', 'GB', 'TB'];
  const i = Math.min(sizes.length - 1, Math.floor(Math.log(bytes) / Math.log(k)));
  return `${parseFloat((bytes / Math.pow(k, i)).toFixed(2))} ${sizes[i]}`;
}

/** 毫秒 → 可读运行时长（X天 X时 X分） */
function formatUptime(ms: number): string {
  const seconds = Math.floor((ms || 0) / 1000);
  const days = Math.floor(seconds / 86400);
  const hours = Math.floor((seconds % 86400) / 3600);
  const mins = Math.floor((seconds % 3600) / 60);
  return `${days}天 ${hours}时 ${mins}分`;
}

/** 数字千分位展示（空值兜底 0） */
function formatNumber(n?: number | null): string {
  return (n ?? 0).toLocaleString('zh-CN');
}

/** 可空字节数 → 可读格式；null 显示破折号（与「0 B」区分：前者是取不到，后者是真的空） */
function formatSize(bytes?: number | null): string {
  return bytes === null || bytes === undefined ? '—' : formatBytes(bytes);
}

/** 可空耗时（毫秒）；null 显示破折号 */
function formatMs(ms?: number | null): string {
  return ms === null || ms === undefined ? '—' : `${ms} ms`;
}

/* ------------------------------ 通用子组件 ------------------------------ */

/** 今日业务概览子项；容器用 1px 分隔而不是把每项再做成一张卡。 */
function OverviewItem({
  label,
  value,
  current,
  previous,
  invert,
  footnote,
}: {
  label: string;
  value: string;
  current?: number | null;
  previous?: number | null;
  invert?: boolean;
  /** 无环比数据时展示的替代说明 */
  footnote?: string;
}) {
  return (
    <div className="bg-card min-w-0 px-5 py-4 sm:px-6">
      <div className="text-muted-foreground text-xs">{label}</div>
      <div className="text-foreground mt-2 text-[24px] leading-none font-semibold tracking-[-0.025em] tabular-nums">
        {value}
      </div>
      <div className="mt-2.5 min-h-5">
        {footnote ? (
          <span className="text-muted-foreground text-[11px]">{footnote}</span>
        ) : (
          <TrendBadge current={current} previous={previous} invert={invert} />
        )}
      </div>
    </div>
  );
}

/**
 * 外部依赖连通性徽标
 * @param available true 正常 / false 异常 / null 未配置（三者文案不同，避免把「没配」误报成「故障」）
 */
function StatusBadge({
  available,
  pending = false,
}: {
  available: boolean | null | undefined;
  pending?: boolean;
}) {
  if (pending) return <span className="text-muted-foreground text-xs">等待数据</span>;
  if (available === null || available === undefined) {
    return <span className="text-muted-foreground text-xs">未配置</span>;
  }
  return (
    <span
      className={cn(
        'inline-flex items-center gap-1.5 text-xs',
        available ? 'text-[color:var(--color-success)]' : 'text-destructive',
      )}
    >
      <span
        className={cn(
          'size-1.5 rounded-full',
          available ? 'bg-[var(--color-success)]' : 'bg-destructive',
        )}
      />
      {available ? '连接正常' : '连接异常'}
    </span>
  );
}

/* ------------------------------ 页面组件 ------------------------------ */

/**
 * 仪表盘页面组件
 * <p>
 * 数据来自聚合接口 {@code GET /api/v1/admin/monitor/dashboard}，一次请求覆盖
 * 系统资源、JVM、业务规模、今日/昨日概览与接口调用汇总，30 秒轮询一次。
 * </p>
 */
export default function DashboardPage() {
  /** 首页数据全部来自 admin:monitor:view 保护的聚合接口：无此权限时不做无意义轮询，直接给明确提示 */
  const hasPerm = usePerm();
  const canViewMonitor = hasPerm('admin:monitor:view');
  /** 首屏加载状态 */
  const [loading, setLoading] = useState(true);
  /** 仪表盘总览数据 */
  const [data, setData] = useState<DashboardOverviewDTO | null>(null);
  /** 最近一次成功刷新的时间（用于提示数据新鲜度） */
  const [updatedAt, setUpdatedAt] = useState<Date | null>(null);

  /** 组件挂载时加载数据，并设置定时刷新 */
  useEffect(() => {
    // 无 admin:monitor:view 时不发请求：否则每 30 秒产生一次必然 403 的轮询
    if (!canViewMonitor) {
      setLoading(false);
      return;
    }
    // alive 守卫：卸载后异步回调不再 setState，同时 clearInterval 停掉轮询
    let alive = true;
    /** 轮询属后台心跳，用 silent 避免顶部加载条每 30 秒闪一次 */
    const loadData = async () => {
      try {
        const res = await monitorApi.getDashboard({ silent: true });
        if (!alive) return;
        setData(res.data);
        setUpdatedAt(new Date());
      } catch {
        // 静默轮询失败不弹错，交由下一次 tick 重试
      } finally {
        if (alive) setLoading(false);
      }
    };

    loadData();
    const timer = setInterval(loadData, REFRESH_INTERVAL_MS);
    return () => {
      alive = false;
      clearInterval(timer);
    };
  }, [canViewMonitor]);

  /** KPI 迷你走势：保留最近 24 个采样点（30 秒一次 ≈ 12 分钟） */
  const [series, setSeries] = useState<{
    cpu: number[];
    mem: number[];
    disk: number[];
    req: number[];
  }>({ cpu: [], mem: [], disk: [], req: [] });
  useEffect(() => {
    if (!data) return;
    const push = (arr: number[], v: number) => [...arr, v].slice(-24);
    const sys = data.system;
    const biz = data.business;
    setSeries((prev) => ({
      cpu: push(prev.cpu, sys?.cpuUsage ?? 0),
      mem: push(prev.mem, sys?.memoryUsage ?? 0),
      disk: push(prev.disk, sys?.diskUsage ?? 0),
      req: push(prev.req, biz?.activeRequests ?? 0),
    }));
  }, [data]);

  if (!canViewMonitor) {
    return (
      <div className="my-24 flex flex-col items-center gap-3 text-center">
        <ShieldAlert className="text-muted-foreground size-10" />
        <p className="text-foreground text-base font-medium">无仪表盘查看权限</p>
        <p className="text-muted-foreground max-w-md text-sm">
          首页展示的是系统与业务监控指标，需要{' '}
          <code className="bg-muted rounded px-1">admin:monitor:view</code> 权限。
          请联系管理员在「角色权限」中为你的角色勾选该权限。
        </p>
      </div>
    );
  }

  if (loading) {
    return (
      <div className="my-24 flex justify-center">
        <Loader2 className="text-muted-foreground size-8 animate-spin" />
      </div>
    );
  }

  const system = data?.system;
  const jvm = data?.jvm;
  const business = data?.business;
  const today = data?.today;
  const yesterday = data?.yesterday;
  const apiSummary = data?.api;
  const database = data?.database;
  const redis = data?.redis;

  const cpuUsage = system?.cpuUsage ?? 0;
  const memoryUsage = system?.memoryUsage ?? 0;
  const diskUsage = system?.diskUsage ?? 0;
  const activeRequests = business?.activeRequests ?? 0;
  const cpuTone = usageTone(cpuUsage, 60, 80);
  const memoryTone = usageTone(memoryUsage, 70, 85);
  const diskTone = usageTone(diskUsage, 75, 90);

  const successRate = Number(apiSummary?.successRate ?? 0);
  const hasApiSamples = (apiSummary?.callCount ?? 0) > 0;
  const rateTone = hasApiSamples ? ratioTone(successRate, 99, 95) : 'normal';

  /** 连接池水位 = 活跃 / 上限。上限取不到（非 Hikari 或池未初始化）时不画进度条 */
  const hasPoolLimit = Boolean(database?.maxConnections && database.maxConnections > 0);
  const poolUsage = hasPoolLimit
    ? ((database?.activeConnections ?? 0) / (database?.maxConnections ?? 1)) * 100
    : 0;
  const poolTone = usageTone(poolUsage, 70, 90);

  /** Redis 命中率：服务刚起、尚无读写记录时为 null，此时不画进度条 */
  const hitRate = redis?.hitRate ?? null;
  const hitTone = hitRate === null ? 'normal' : ratioTone(hitRate, 90, 70);

  /** 健康结论只根据真实阈值与依赖状态推导；未配置不等于故障，无数据不声称健康。 */
  const resourceTones = [cpuTone, memoryTone, diskTone, poolTone];
  const critical =
    resourceTones.includes('danger') ||
    database?.available === false ||
    redis?.available === false ||
    (hasApiSamples && successRate < 95);
  const needsAttention =
    resourceTones.includes('warn') ||
    (database?.waitingThreads ?? 0) > 0 ||
    (hasApiSamples && successRate < 99);
  const health = !data
    ? {
        label: '等待监控数据',
        description: '尚未取得有效采样，当前不对服务健康状态下结论。',
        tone: 'muted' as const,
      }
    : critical
      ? {
          label: '发现异常指标',
          description: '存在资源越线、接口成功率过低或依赖连接异常，请优先检查对应区域。',
          tone: 'danger' as const,
        }
      : needsAttention
        ? {
            label: '部分指标需关注',
            description: '核心服务仍可用，部分指标已接近阈值，建议结合下方明细持续观察。',
            tone: 'warning' as const,
          }
        : {
            label: '核心服务运行平稳',
            description: '主机资源、接口质量与已配置依赖均未触发当前告警阈值。',
            tone: 'normal' as const,
          };

  const number = (value?: number | null) => (data ? formatNumber(value) : '—');
  const bytes = (value?: number | null) => (data ? formatBytes(value ?? 0) : '—');

  return (
    <div className="dashboard-overview space-y-5">
      <PageHeader
        eyebrow="工作台 / 系统概览"
        title="运行概览"
        description="优先关注主机与依赖健康，再查看业务流量和运行时明细。"
        actions={
          <div className="text-muted-foreground flex items-center gap-2 text-xs">
            <Activity aria-hidden className="text-primary size-3.5" />
            <span>
              {updatedAt
                ? `${updatedAt.toLocaleTimeString('zh-CN', { hour12: false })} 更新`
                : '等待首次采样'}
            </span>
            <span aria-hidden className="bg-border h-3 w-px" />
            <span>30 秒轮询</span>
          </div>
        }
      />

      {/* 主工作区：健康结论 + 三项主机资源 + 接口质量，不再使用等权 KPI 卡阵。 */}
      <div className="grid gap-5 xl:grid-cols-[minmax(0,1.65fr)_minmax(340px,0.75fr)]">
        <section
          aria-labelledby="system-health-title"
          className="rounded-[14px] border border-[color:var(--color-nav-border)] bg-[var(--color-nav-bg)] p-5 text-[color:var(--color-nav-text)] shadow-[var(--shadow-md)] sm:p-7"
        >
          <div className="flex flex-col gap-5 sm:flex-row sm:items-start sm:justify-between">
            <div className="max-w-xl">
              <div className="flex items-center gap-2 text-xs text-[color:var(--color-nav-muted)]">
                <span
                  className={cn(
                    'size-2 rounded-full',
                    health.tone === 'muted' && 'bg-[var(--color-nav-muted)]',
                    health.tone === 'normal' && 'bg-[#62c5a5]',
                    health.tone === 'warning' && 'bg-[#e2ad55]',
                    health.tone === 'danger' && 'bg-[#df6b6b]',
                  )}
                />
                系统健康
              </div>
              <h2
                id="system-health-title"
                className="mt-3 text-[28px] leading-tight font-semibold tracking-[-0.035em] sm:text-[32px]"
              >
                {health.label}
              </h2>
              <p className="mt-3 text-[13px] leading-6 text-[color:var(--color-nav-muted)]">
                {health.description}
              </p>
            </div>
            <div className="shrink-0 border-l-2 border-[color:var(--color-nav-border)] pl-4 sm:text-right">
              <p className="text-[11px] text-[color:var(--color-nav-muted)]">系统运行时间</p>
              <p className="mt-1.5 text-sm font-medium tabular-nums">
                {data ? formatUptime(system?.uptime ?? 0) : '—'}
              </p>
            </div>
          </div>

          <div className="mt-7 grid gap-6 border-t border-[color:var(--color-nav-border)] pt-6 sm:grid-cols-3 sm:gap-0">
            <ResourceMeter
              inverted
              label="CPU 使用率"
              value={data ? `${cpuUsage.toFixed(1)}%` : '—'}
              percent={data ? cpuUsage : undefined}
              detail={data ? '告警阈值 60% / 80%' : '等待采样'}
              series={series.cpu}
              tone={cpuTone}
              className="sm:pr-6"
            />
            <ResourceMeter
              inverted
              label="内存使用率"
              value={data ? `${memoryUsage.toFixed(1)}%` : '—'}
              percent={data ? memoryUsage : undefined}
              detail={
                data
                  ? `${formatBytes(system?.usedMemory ?? 0)} / ${formatBytes(system?.totalMemory ?? 0)}`
                  : '等待采样'
              }
              series={series.mem}
              tone={memoryTone}
              className="border-t border-[color:var(--color-nav-border)] pt-6 sm:border-t-0 sm:border-l sm:px-6 sm:pt-0"
            />
            <ResourceMeter
              inverted
              label="磁盘使用率"
              value={data ? `${diskUsage.toFixed(1)}%` : '—'}
              percent={data ? diskUsage : undefined}
              detail={data ? '告警阈值 75% / 90%' : '等待采样'}
              series={series.disk}
              tone={diskTone}
              className="border-t border-[color:var(--color-nav-border)] pt-6 sm:border-t-0 sm:border-l sm:pt-0 sm:pl-6"
            />
          </div>
        </section>

        <SectionCard
          title={
            <span className="flex items-center gap-2">
              <Gauge className="text-muted-foreground size-4" aria-hidden />
              接口质量
            </span>
          }
          description="今日全部接口调用汇总"
          className="min-w-0"
          contentClassName="flex flex-1 flex-col"
        >
          <div>
            <div className="flex items-end justify-between gap-4">
              <div>
                <p className="text-muted-foreground text-xs">调用成功率</p>
                <p
                  className={cn(
                    'mt-2 text-[40px] leading-none font-semibold tracking-[-0.045em] tabular-nums',
                    STAT_TONE_TEXT[rateTone],
                  )}
                >
                  {hasApiSamples ? `${successRate.toFixed(2)}%` : '—'}
                </p>
              </div>
              <span className="text-muted-foreground pb-1 text-[11px]">
                {hasApiSamples ? '目标 ≥ 99%' : '暂无调用采样'}
              </span>
            </div>
            {hasApiSamples && (
              <Progress
                value={successRate}
                className="mt-4 h-1.5"
                indicatorClassName={cn(
                  'transition-[width] duration-300 ease-out',
                  STAT_TONE_BAR[rateTone],
                )}
              />
            )}
          </div>

          <div className="mt-6 grid grid-cols-3 gap-px overflow-hidden rounded-lg bg-[var(--color-border-light)]">
            {[
              ['调用总量', number(apiSummary?.callCount)],
              ['成功', number(apiSummary?.successCount)],
              ['失败', number(apiSummary?.failureCount)],
            ].map(([label, value]) => (
              <div key={label} className="bg-[var(--color-bg-tint)] px-3 py-3.5">
                <p className="text-muted-foreground text-[11px]">{label}</p>
                <p className="text-foreground mt-1.5 truncate text-base font-semibold tabular-nums">
                  {value}
                </p>
              </div>
            ))}
          </div>

          <div className="mt-auto flex items-center justify-between gap-4 border-t border-[color:var(--color-border-light)] pt-5">
            <div className="flex items-center gap-3">
              <Radio className="text-primary size-4" aria-hidden />
              <div>
                <p className="text-muted-foreground text-[11px]">当前并发请求</p>
                <p className="text-foreground mt-1 text-lg font-semibold tabular-nums">
                  {number(activeRequests)}
                </p>
              </div>
            </div>
            <Sparkline values={series.req} width={104} height={34} className="text-primary" />
          </div>
        </SectionCard>
      </div>

      {/* 今日业务：一条完整数据带，环比与指标保持同一阅读路径。 */}
      <SectionCard
        title="今日业务"
        description="与昨日同口径数据对比；错误次数按下降为正向变化。"
        actions={
          <span className="text-muted-foreground text-xs">
            {today?.date || new Date().toLocaleDateString('zh-CN')}
          </span>
        }
        contentClassName="px-0"
      >
        <div className="grid grid-cols-2 gap-px border-t border-[color:var(--color-border-light)] bg-[var(--color-border-light)] md:grid-cols-3 xl:grid-cols-6">
          <OverviewItem
            label="页面访问（PV）"
            value={number(today?.pv)}
            current={today?.pv}
            previous={yesterday?.pv}
          />
          <OverviewItem
            label="访问次数"
            value={number(today?.visits)}
            current={today?.visits}
            previous={yesterday?.visits}
          />
          <OverviewItem
            label="活跃设备"
            value={number(today?.activeDevices)}
            current={today?.activeDevices}
            previous={yesterday?.activeDevices}
          />
          <OverviewItem
            label="新增设备"
            value={number(today?.newDevices)}
            current={today?.newDevices}
            previous={yesterday?.newDevices}
          />
          <OverviewItem
            label="错误次数"
            value={number(today?.errorCount)}
            current={today?.errorCount}
            previous={yesterday?.errorCount}
            invert
          />
          <OverviewItem
            label="序列配置"
            value={number(business?.configCount)}
            footnote={data ? `累计设备 ${formatNumber(today?.totalDevices)}` : '等待采样'}
          />
        </div>
      </SectionCard>

      {/* 次级监控：JVM 为主，环境信息为辅，宽度不再等分。 */}
      <div className="grid gap-5 lg:grid-cols-[minmax(0,1.25fr)_minmax(300px,0.75fr)]">
        <SectionCard
          title={
            <span className="flex items-center gap-2">
              <Coffee className="text-muted-foreground size-4" aria-hidden />
              JVM 运行时
            </span>
          }
          description="堆内存、垃圾回收与线程采样"
        >
          <div className="grid gap-3 sm:grid-cols-2">
            <StatTile
              label="堆内存使用"
              value={bytes(jvm?.heapUsed)}
              hint={data ? `上限 ${formatBytes(jvm?.heapMax ?? 0)}` : '等待采样'}
            />
            <StatTile label="GC 次数" value={number(jvm?.gcCount)} hint="累计回收次数" />
            <StatTile label="活跃线程数" value={number(jvm?.threadCount)} hint="JVM 线程总数" />
            <StatTile label="JVM 运行时长" value={data ? formatUptime(jvm?.uptime ?? 0) : '—'} />
          </div>
        </SectionCard>

        <SectionCard
          title={
            <span className="flex items-center gap-2">
              <Info className="text-muted-foreground size-4" aria-hidden />
              运行环境
            </span>
          }
          description="当前进程与主机信息"
        >
          <InfoList gap="md" className="divide-y divide-[var(--color-border-light)]">
            <InfoRow label="系统运行时间" className="pb-3">
              {data ? formatUptime(system?.uptime ?? 0) : '—'}
            </InfoRow>
            <InfoRow label="JDK 版本" className="pb-3">
              {jvm?.jdkVersion || '—'}
            </InfoRow>
            <InfoRow label="JVM 名称" className="pb-3">
              {jvm?.jvmName || '—'}
            </InfoRow>
            <InfoRow label="系统线程总数">{number(system?.threadCount)}</InfoRow>
          </InfoList>
        </SectionCard>
      </div>

      {/* 外部依赖合并为一个工作区，以分隔线组织数据库与缓存。 */}
      <SectionCard
        title="基础依赖"
        description="连通性、连接池与缓存效率；未配置不会被视为连接故障。"
        contentClassName="px-0"
      >
        <div className="grid border-t border-[color:var(--color-border-light)] lg:grid-cols-2">
          <section className="p-5 sm:p-6 lg:border-r lg:border-[color:var(--color-border-light)]">
            <div className="flex items-center justify-between gap-4">
              <h3 className="flex items-center gap-2 text-sm font-semibold">
                <Database className="text-muted-foreground size-4" aria-hidden />
                数据库
              </h3>
              <StatusBadge available={database?.available} pending={!data} />
            </div>
            <p className="text-muted-foreground mt-2 truncate text-xs">
              {data
                ? `${database?.productName || '—'}${database?.productVersion ? ` ${database.productVersion}` : ''}`
                : '等待依赖探测'}
            </p>

            <div className="mt-6 rounded-lg bg-[var(--color-bg-tint)] p-4">
              <div className="flex items-baseline justify-between gap-3">
                <span className="text-muted-foreground text-xs">连接池水位</span>
                <span
                  className={cn('text-lg font-semibold tabular-nums', STAT_TONE_TEXT[poolTone])}
                >
                  {data
                    ? `${formatNumber(database?.activeConnections)} / ${formatNumber(database?.maxConnections)}`
                    : '—'}
                </span>
              </div>
              {hasPoolLimit && (
                <Progress
                  value={poolUsage}
                  className="mt-3 h-1.5"
                  indicatorClassName={cn(
                    'transition-[width] duration-300 ease-out',
                    STAT_TONE_BAR[poolTone],
                  )}
                />
              )}
              <div className="text-muted-foreground mt-3 flex flex-wrap justify-between gap-2 text-[11px]">
                <span>空闲 {number(database?.idleConnections)}</span>
                <span>总计 {number(database?.totalConnections)}</span>
                <span
                  className={cn(
                    (database?.waitingThreads ?? 0) > 0 && 'text-[color:var(--color-warning-text)]',
                  )}
                >
                  等待 {number(database?.waitingThreads)}
                </span>
              </div>
            </div>

            <InfoList gap="sm" className="mt-5">
              <InfoRow label="连接池">{database?.poolName || '—'}</InfoRow>
              <InfoRow label="数据量">{formatSize(database?.databaseSize)}</InfoRow>
              <InfoRow label="探测耗时">{formatMs(database?.pingMs)}</InfoRow>
            </InfoList>
          </section>

          <section className="border-t border-[color:var(--color-border-light)] p-5 sm:p-6 lg:border-t-0">
            <div className="flex items-center justify-between gap-4">
              <h3 className="flex items-center gap-2 text-sm font-semibold">
                <Zap className="text-muted-foreground size-4" aria-hidden />
                Redis
              </h3>
              <StatusBadge available={redis?.available} pending={!data} />
            </div>
            <p className="text-muted-foreground mt-2 truncate text-xs">
              {data
                ? `${redis?.version ? `v${redis.version}` : '—'}${redis?.mode ? ` · ${redis.mode}` : ''}${redis?.databaseIndex !== null && redis?.databaseIndex !== undefined ? ` · DB ${redis.databaseIndex}` : ''}`
                : '等待依赖探测'}
            </p>

            <div className="mt-6 rounded-lg bg-[var(--color-bg-tint)] p-4">
              <div className="flex items-baseline justify-between gap-3">
                <span className="text-muted-foreground text-xs">缓存命中率</span>
                <span className={cn('text-lg font-semibold tabular-nums', STAT_TONE_TEXT[hitTone])}>
                  {hitRate === null ? '—' : `${hitRate.toFixed(2)}%`}
                </span>
              </div>
              {hitRate !== null && (
                <Progress
                  value={hitRate}
                  className="mt-3 h-1.5"
                  indicatorClassName={cn(
                    'transition-[width] duration-300 ease-out',
                    STAT_TONE_BAR[hitTone],
                  )}
                />
              )}
              <div className="text-muted-foreground mt-3 flex justify-between gap-3 text-[11px]">
                <span>命中 {number(redis?.keyspaceHits)}</span>
                <span>未命中 {number(redis?.keyspaceMisses)}</span>
              </div>
            </div>

            <InfoList gap="sm" className="mt-5">
              <InfoRow label="内存 / 峰值">
                {formatSize(redis?.usedMemory)} / {formatSize(redis?.usedMemoryPeak)}
              </InfoRow>
              <InfoRow label="Key / 客户端">
                {number(redis?.totalKeys)} / {number(redis?.connectedClients)}
              </InfoRow>
              <InfoRow label="命令 / 碎片率 / 探测">
                {number(redis?.instantaneousOpsPerSec)} 每秒 · {redis?.memFragmentationRatio ?? '—'}{' '}
                · {formatMs(redis?.pingMs)}
              </InfoRow>
            </InfoList>
          </section>
        </div>
      </SectionCard>
    </div>
  );
}
