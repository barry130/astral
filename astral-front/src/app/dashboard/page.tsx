'use client';

import { useEffect, useState } from 'react';
import {
  Activity,
  Coffee,
  Cpu,
  Database,
  Gauge,
  HardDrive,
  Info,
  Loader2,
  MemoryStick,
  Radio,
  TrendingDown,
  TrendingUp,
  Zap,
} from 'lucide-react';

import { monitorApi, DashboardOverviewDTO } from '@/api/monitor';
import { Card, CardHeader, CardTitle, CardContent } from '@/components/ui/card';
import { Progress } from '@/components/ui/progress';
import { cn } from '@/lib/utils';

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

/* ------------------------------ 状态配色 ------------------------------ */

interface Tone {
  /** 数值文字色 */
  text: string;
  /** 进度条填充色 */
  bar: string;
}

const TONE_NORMAL: Tone = { text: 'text-foreground', bar: 'bg-primary' };
const TONE_SUCCESS: Tone = { text: 'text-emerald-600', bar: 'bg-emerald-500' };
const TONE_WARN: Tone = { text: 'text-amber-600', bar: 'bg-amber-500' };
const TONE_DANGER: Tone = { text: 'text-destructive', bar: 'bg-destructive' };

/**
 * 资源使用率配色
 * <p>正常区间沿用主题前景色，仅在越线时着色 —— 极简风，颜色只用来传递告警。</p>
 */
function usageTone(usage: number, warn: number, danger: number): Tone {
  if (usage > danger) return TONE_DANGER;
  if (usage > warn) return TONE_WARN;
  return TONE_NORMAL;
}

/** 比率型指标配色（成功率、命中率等）：越高越好，阈值方向与使用率相反 */
function ratioTone(rate: number, good: number, warn: number): Tone {
  if (rate >= good) return TONE_SUCCESS;
  if (rate >= warn) return TONE_WARN;
  return TONE_DANGER;
}

/* ------------------------------ 通用子组件 ------------------------------ */

/** 统计卡图标芯片：中性浅灰底 + 次级前景图标 */
function StatIcon({ icon }: { icon: React.ReactNode }) {
  return (
    <div className="flex size-12 shrink-0 items-center justify-center rounded-xl bg-[rgba(24,24,27,0.04)]">
      <span className="inline-flex text-xl text-muted-foreground">{icon}</span>
    </div>
  );
}

/** 响应式栅格单元（承载入场动画类） */
function GridCell({ className, children }: { className?: string; children: React.ReactNode }) {
  return <div className={cn('w-full', className)}>{children}</div>;
}

/** 顶部核心指标卡 */
function StatCard({
  label,
  value,
  icon,
  tone,
  progress,
  sub,
}: {
  label: string;
  value: React.ReactNode;
  icon: React.ReactNode;
  tone?: Tone;
  progress?: number;
  sub?: React.ReactNode;
}) {
  return (
    <Card className="stat-card h-full py-5">
      <CardContent className="flex items-start justify-between px-5">
        <div className="min-w-0">
          <div className="text-muted-foreground text-sm">{label}</div>
          <div
            className={cn(
              'mt-1 text-[28px] font-semibold leading-tight tabular-nums',
              tone?.text ?? 'text-foreground',
            )}
          >
            {value}
          </div>
          {progress !== undefined && (
            <Progress
              value={Math.min(100, Math.max(0, progress))}
              className="mt-3 h-1.5"
              indicatorClassName={cn('transition-all', tone?.bar)}
            />
          )}
          {sub && <div className="mt-1.5 text-xs text-muted-foreground/80">{sub}</div>}
        </div>
        <StatIcon icon={icon} />
      </CardContent>
    </Card>
  );
}

/**
 * 环比标签（今日 vs 昨日）
 * @param invert 指标越大越差时传 true（如错误次数），涨跌配色随之反转
 */
function TrendBadge({
  current,
  previous,
  invert = false,
}: {
  current?: number | null;
  previous?: number | null;
  invert?: boolean;
}) {
  const cur = current ?? 0;
  const prev = previous ?? 0;

  if (prev <= 0) {
    return (
      <span className="text-xs text-muted-foreground/70">{cur > 0 ? '昨日无数据' : '暂无数据'}</span>
    );
  }

  const delta = ((cur - prev) / prev) * 100;
  if (Math.abs(delta) < 0.05) {
    return <span className="text-xs text-muted-foreground/70">与昨日持平</span>;
  }

  const rising = delta > 0;
  const positive = invert ? !rising : rising;
  const Icon = rising ? TrendingUp : TrendingDown;

  return (
    <span
      className={cn(
        'inline-flex items-center gap-1 text-xs tabular-nums',
        positive ? 'text-emerald-600' : 'text-destructive',
      )}
    >
      <Icon className="size-3 shrink-0" />
      较昨日 {rising ? '+' : ''}
      {delta.toFixed(1)}%
    </span>
  );
}

/** 今日业务概览子项 */
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
    <div className="rounded-lg bg-[var(--color-bg-base)] p-4">
      <div className="mb-1 text-[13px] text-muted-foreground">{label}</div>
      <div className="text-xl font-semibold tabular-nums text-foreground">{value}</div>
      <div className="mt-1.5">
        {footnote ? (
          <span className="text-xs text-muted-foreground/70">{footnote}</span>
        ) : (
          <TrendBadge current={current} previous={previous} invert={invert} />
        )}
      </div>
    </div>
  );
}

/** 明细卡内的信息行 */
function InfoRow({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="flex items-center justify-between gap-4">
      <span className="shrink-0 text-[13px] text-muted-foreground">{label}</span>
      <span className="min-w-0 truncate text-right text-sm font-medium text-foreground">{children}</span>
    </div>
  );
}

/** 明细卡内的数值块 */
function DetailBlock({ label, value, hint }: { label: string; value: React.ReactNode; hint?: string }) {
  return (
    <div className="rounded-lg bg-[var(--color-bg-base)] p-4">
      <div className="mb-1 text-[13px] text-muted-foreground">{label}</div>
      <div className="text-lg font-semibold tabular-nums text-foreground">{value}</div>
      {hint && <div className="text-xs text-muted-foreground/80">{hint}</div>}
    </div>
  );
}

/**
 * 外部依赖连通性徽标
 * @param available true 正常 / false 异常 / null 未配置（三者文案不同，避免把「没配」误报成「故障」）
 */
function StatusBadge({ available }: { available: boolean | null | undefined }) {
  if (available === null || available === undefined) {
    return <span className="text-xs font-normal text-muted-foreground/70">未配置</span>;
  }
  return (
    <span
      className={cn(
        'inline-flex items-center gap-1 text-xs font-normal',
        available ? 'text-emerald-600' : 'text-destructive',
      )}
    >
      <span className={cn('size-1.5 rounded-full', available ? 'bg-emerald-500' : 'bg-destructive')} />
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
  /** 首屏加载状态 */
  const [loading, setLoading] = useState(true);
  /** 仪表盘总览数据 */
  const [data, setData] = useState<DashboardOverviewDTO | null>(null);
  /** 最近一次成功刷新的时间（用于提示数据新鲜度） */
  const [updatedAt, setUpdatedAt] = useState<Date | null>(null);

  /** 组件挂载时加载数据，并设置定时刷新 */
  useEffect(() => {
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
  }, []);

  if (loading) {
    return (
      <div className="my-24 flex justify-center">
        <Loader2 className="size-8 animate-spin text-muted-foreground" />
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
  const activeConnections = business?.activeConnections ?? null;

  const successRate = Number(apiSummary?.successRate ?? 0);
  const rateTone = ratioTone(successRate, 99, 95);

  /** 连接池水位 = 活跃 / 上限。上限取不到（非 Hikari 或池未初始化）时为 0，前端不画进度条 */
  const poolUsage =
    database?.maxConnections && database.maxConnections > 0
      ? ((database.activeConnections ?? 0) / database.maxConnections) * 100
      : 0;
  const poolTone = usageTone(poolUsage, 70, 90);

  /** Redis 命中率：服务刚起、尚无读写记录时为 null，此时不画进度条 */
  const hitRate = redis?.hitRate ?? null;
  const hitTone = hitRate === null ? TONE_NORMAL : ratioTone(hitRate, 90, 70);

  return (
    <div>
      <div className="mb-6">
        <h2 className="page-title mb-2">仪表盘</h2>
        <p className="m-0 text-muted-foreground">
          实时监控系统运行状态与今日业务表现
          {updatedAt && (
            <span className="ml-2 text-muted-foreground/70">
              · 每 30 秒自动刷新，最近更新 {updatedAt.toLocaleTimeString('zh-CN', { hour12: false })}
            </span>
          )}
        </p>
      </div>

      {/* 核心资源指标：4 列响应式栅格 */}
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <GridCell className="fade-in-up stagger-1">
          <StatCard
            label="CPU 使用率"
            value={`${cpuUsage.toFixed(1)}%`}
            icon={<Cpu />}
            tone={usageTone(cpuUsage, 60, 80)}
            progress={cpuUsage}
          />
        </GridCell>

        <GridCell className="fade-in-up stagger-2">
          <StatCard
            label="堆内存使用率"
            value={`${memoryUsage.toFixed(1)}%`}
            icon={<MemoryStick />}
            tone={usageTone(memoryUsage, 70, 85)}
            progress={memoryUsage}
            sub={`${formatBytes(system?.usedMemory ?? 0)} / ${formatBytes(system?.totalMemory ?? 0)}`}
          />
        </GridCell>

        <GridCell className="fade-in-up stagger-3">
          <StatCard
            label="磁盘使用率"
            value={`${diskUsage.toFixed(1)}%`}
            icon={<HardDrive />}
            tone={usageTone(diskUsage, 75, 90)}
            progress={diskUsage}
          />
        </GridCell>

        <GridCell className="fade-in-up stagger-4">
          <StatCard
            label="活跃连接"
            value={activeConnections === null ? '—' : String(activeConnections)}
            icon={<Radio />}
            sub={activeConnections === null ? '当前不可用' : '正在处理请求的线程数'}
          />
        </GridCell>
      </div>

      {/* 今日业务概览：全宽卡，6 个核心指标带昨日环比 */}
      <Card className="fade-in-up stagger-5 mt-4">
        <CardHeader>
          <CardTitle className="flex items-center gap-2 text-[15px]">
            <Activity className="size-4 text-muted-foreground" />
            今日业务概览
            <span className="ml-auto text-xs font-normal text-muted-foreground">
              {today?.date || new Date().toLocaleDateString('zh-CN')}
            </span>
          </CardTitle>
        </CardHeader>
        <CardContent>
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-6">
            <OverviewItem
              label="页面访问(PV)"
              value={formatNumber(today?.pv)}
              current={today?.pv}
              previous={yesterday?.pv}
            />
            <OverviewItem
              label="访问次数"
              value={formatNumber(today?.visits)}
              current={today?.visits}
              previous={yesterday?.visits}
            />
            <OverviewItem
              label="活跃设备"
              value={formatNumber(today?.activeDevices)}
              current={today?.activeDevices}
              previous={yesterday?.activeDevices}
            />
            <OverviewItem
              label="新增设备"
              value={formatNumber(today?.newDevices)}
              current={today?.newDevices}
              previous={yesterday?.newDevices}
            />
            <OverviewItem
              label="错误次数"
              value={formatNumber(today?.errorCount)}
              current={today?.errorCount}
              previous={yesterday?.errorCount}
              invert
            />
            <OverviewItem
              label="序列配置"
              value={formatNumber(business?.configCount)}
              footnote={`累计设备 ${formatNumber(today?.totalDevices)}`}
            />
          </div>
        </CardContent>
      </Card>

      {/* 明细区：接口健康度 / JVM 监控 / 运行环境 */}
      <div className="mt-4 grid grid-cols-1 gap-4 lg:grid-cols-3">
        <Card className="fade-in-up stagger-6">
          <CardHeader>
            <CardTitle className="flex items-center gap-2 text-[15px]">
              <Gauge className="size-4 text-muted-foreground" />
              接口健康度
            </CardTitle>
          </CardHeader>
          <CardContent className="space-y-4">
            <DetailBlock label="今日调用总量" value={formatNumber(apiSummary?.callCount)} hint="全部接口合计" />
            <div className="rounded-lg bg-[var(--color-bg-base)] p-4">
              <div className="flex items-baseline justify-between">
                <span className="text-[13px] text-muted-foreground">调用成功率</span>
                <span className={cn('text-lg font-semibold tabular-nums', rateTone.text)}>
                  {successRate.toFixed(2)}%
                </span>
              </div>
              <Progress
                value={successRate}
                className="mt-2 h-1.5"
                indicatorClassName={cn('transition-all', rateTone.bar)}
              />
              <div className="mt-3 flex justify-between text-xs text-muted-foreground">
                <span>成功 {formatNumber(apiSummary?.successCount)}</span>
                <span>失败 {formatNumber(apiSummary?.failureCount)}</span>
              </div>
            </div>
          </CardContent>
        </Card>

        <Card className="fade-in-up stagger-6">
          <CardHeader>
            <CardTitle className="flex items-center gap-2 text-[15px]">
              <Coffee className="size-4 text-muted-foreground" />
              JVM 监控
            </CardTitle>
          </CardHeader>
          <CardContent>
            <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
              <DetailBlock
                label="堆内存使用"
                value={formatBytes(jvm?.heapUsed ?? 0)}
                hint={`上限 ${formatBytes(jvm?.heapMax ?? 0)}`}
              />
              <DetailBlock label="GC 次数" value={formatNumber(jvm?.gcCount)} hint="累计回收次数" />
              <DetailBlock label="活跃线程数" value={formatNumber(jvm?.threadCount)} hint="JVM 线程总数" />
              <DetailBlock label="JVM 运行时长" value={formatUptime(jvm?.uptime ?? 0)} />
            </div>
          </CardContent>
        </Card>

        <Card className="fade-in-up stagger-6">
          <CardHeader>
            <CardTitle className="flex items-center gap-2 text-[15px]">
              <Info className="size-4 text-muted-foreground" />
              运行环境
            </CardTitle>
          </CardHeader>
          <CardContent className="space-y-3">
            <InfoRow label="系统运行时间">{formatUptime(system?.uptime ?? 0)}</InfoRow>
            <InfoRow label="JDK 版本">{jvm?.jdkVersion || '—'}</InfoRow>
            <InfoRow label="JVM 名称">{jvm?.jvmName || '—'}</InfoRow>
            <InfoRow label="线程总数">{formatNumber(system?.threadCount)}</InfoRow>
          </CardContent>
        </Card>
      </div>

      {/* 外部依赖：数据库 / Redis */}
      <div className="mt-4 grid grid-cols-1 gap-4 lg:grid-cols-2">
        <Card className="fade-in-up stagger-6">
          <CardHeader>
            <CardTitle className="flex items-center gap-2 text-[15px]">
              <Database className="size-4 text-muted-foreground" />
              数据库
              <span className="ml-auto">
                <StatusBadge available={database?.available} />
              </span>
            </CardTitle>
          </CardHeader>
          <CardContent className="space-y-4">
            <div className="text-sm text-muted-foreground">
              {database?.productName
                ? `${database.productName}${database.productVersion ? ` ${database.productVersion}` : ''}`
                : '—'}
              {database?.poolName && (
                <span className="ml-2 text-xs text-muted-foreground/70">连接池 {database.poolName}</span>
              )}
            </div>

            <div className="rounded-lg bg-[var(--color-bg-base)] p-4">
              <div className="flex items-baseline justify-between">
                <span className="text-[13px] text-muted-foreground">连接池水位</span>
                <span className={cn('text-lg font-semibold tabular-nums', poolTone.text)}>
                  {formatNumber(database?.activeConnections)} / {formatNumber(database?.maxConnections)}
                </span>
              </div>
              <Progress
                value={poolUsage}
                className="mt-2 h-1.5"
                indicatorClassName={cn('transition-all', poolTone.bar)}
              />
              <div className="mt-3 flex justify-between text-xs text-muted-foreground">
                <span>空闲 {formatNumber(database?.idleConnections)}</span>
                <span>总计 {formatNumber(database?.totalConnections)}</span>
                <span className={cn((database?.waitingThreads ?? 0) > 0 && 'text-amber-600')}>
                  等待 {formatNumber(database?.waitingThreads)}
                </span>
              </div>
            </div>

            <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
              <DetailBlock label="数据量" value={formatSize(database?.databaseSize)} />
              <DetailBlock label="探测耗时" value={formatMs(database?.pingMs)} hint="含取连接 + 一次查询" />
            </div>
          </CardContent>
        </Card>

        <Card className="fade-in-up stagger-6">
          <CardHeader>
            <CardTitle className="flex items-center gap-2 text-[15px]">
              <Zap className="size-4 text-muted-foreground" />
              Redis
              <span className="ml-auto">
                <StatusBadge available={redis?.available} />
              </span>
            </CardTitle>
          </CardHeader>
          <CardContent className="space-y-4">
            <div className="text-sm text-muted-foreground">
              {redis?.version ? `v${redis.version}` : '—'}
              {redis?.mode && <span className="ml-2 text-xs text-muted-foreground/70">{redis.mode}</span>}
              {redis?.databaseIndex !== null && redis?.databaseIndex !== undefined && (
                <span className="ml-2 text-xs text-muted-foreground/70">DB {redis.databaseIndex}</span>
              )}
            </div>

            <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
              <DetailBlock
                label="内存占用"
                value={formatSize(redis?.usedMemory)}
                hint={`峰值 ${formatSize(redis?.usedMemoryPeak)}`}
              />
              <DetailBlock
                label="Key 数量"
                value={formatNumber(redis?.totalKeys)}
                hint={`客户端 ${formatNumber(redis?.connectedClients)}`}
              />
            </div>

            <div className="rounded-lg bg-[var(--color-bg-base)] p-4">
              <div className="flex items-baseline justify-between">
                <span className="text-[13px] text-muted-foreground">缓存命中率</span>
                <span className={cn('text-lg font-semibold tabular-nums', hitTone.text)}>
                  {hitRate === null ? '—' : `${hitRate.toFixed(2)}%`}
                </span>
              </div>
              {hitRate !== null && (
                <Progress
                  value={hitRate}
                  className="mt-2 h-1.5"
                  indicatorClassName={cn('transition-all', hitTone.bar)}
                />
              )}
              <div className="mt-3 flex justify-between text-xs text-muted-foreground">
                <span>命中 {formatNumber(redis?.keyspaceHits)}</span>
                <span>未命中 {formatNumber(redis?.keyspaceMisses)}</span>
              </div>
            </div>

            <div className="flex flex-wrap gap-x-4 gap-y-1 text-xs text-muted-foreground/80">
              <span>每秒 {formatNumber(redis?.instantaneousOpsPerSec)} 命令</span>
              <span>碎片率 {redis?.memFragmentationRatio ?? '—'}</span>
              <span>探测 {formatMs(redis?.pingMs)}</span>
            </div>
          </CardContent>
        </Card>
      </div>
    </div>
  );
}
