'use client';

import { useEffect, useState } from 'react';
import {
  Monitor,
  Info,
  LayoutDashboard,
  Rocket,
  Zap,
  Database,
  Loader2,
} from 'lucide-react';

import { monitorApi, SystemMonitorDTO, JvmMonitorDTO, BusinessMonitorDTO } from '@/api/monitor';
import { Card, CardHeader, CardTitle, CardContent } from '@/components/ui/card';
import { Progress } from '@/components/ui/progress';
import { cn } from '@/lib/utils';

/** 将字节数转换为可读格式（B/KB/MB/GB/TB） */
function formatBytes(bytes: number): string {
  if (bytes === 0) return '0 B';
  const k = 1024;
  const sizes = ['B', 'KB', 'MB', 'GB', 'TB'];
  const i = Math.floor(Math.log(bytes) / Math.log(k));
  return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
}

/** 将秒数转换为可读的运行时间格式（X天 X时 X分） */
function formatUptime(seconds: number): string {
  const days = Math.floor(seconds / 86400);
  const hours = Math.floor((seconds % 86400) / 3600);
  const mins = Math.floor((seconds % 3600) / 60);
  return `${days}天 ${hours}时 ${mins}分`;
}

/** 根据CPU使用率返回对应的颜色（红/黄/石墨黑） */
function getCpuColor(usage: number): string {
  if (usage > 80) return '#dc2626';
  if (usage > 60) return '#d97706';
  return '#18181b';
}

/** 根据内存使用率返回对应的颜色（红/黄/绿） */
function getMemoryColor(usage: number): string {
  if (usage > 85) return '#dc2626';
  if (usage > 70) return '#d97706';
  return '#16a34a';
}

/** 统计卡图标芯片：中性浅灰底 + 深灰图标（极简风，色彩只留给状态值与进度条） */
function StatIcon({ icon }: { icon: React.ReactNode }) {
  return (
    <div className="flex size-12 shrink-0 items-center justify-center rounded-xl bg-[rgba(24,24,27,0.04)]">
      <span className="inline-flex text-xl text-zinc-600">{icon}</span>
    </div>
  );
}

/** 移动端/响应式栅格单元 */
function GridCell({ className, children }: { className?: string; children: React.ReactNode }) {
  return <div className={cn('w-full', className)}>{children}</div>;
}

/**
 * 仪表盘页面组件
 * 实时监控系统运行状态，包括CPU、内存、JVM、业务指标等
 */
export default function DashboardPage() {
  /** 加载状态 */
  const [loading, setLoading] = useState(true);
  /** 系统监控数据 */
  const [system, setSystem] = useState<SystemMonitorDTO | null>(null);
  /** JVM监控数据 */
  const [jvm, setJvm] = useState<JvmMonitorDTO | null>(null);
  /** 业务监控数据 */
  const [business, setBusiness] = useState<BusinessMonitorDTO | null>(null);

  /** 组件挂载时加载数据，并设置5秒定时刷新 */
  useEffect(() => {
    // alive 守卫：卸载后异步回调不再 setState，同时 clearInterval 停掉轮询
    let alive = true;
    /** 并行加载所有监控数据；轮询属后台心跳，用 silent 避免顶部加载条每 5 秒闪一次 */
    const loadData = async () => {
      try {
        const [sysRes, jvmRes, bizRes] = await Promise.all([
          monitorApi.getSystemInfo({ silent: true }),
          monitorApi.getJvmInfo({ silent: true }),
          monitorApi.getBusinessInfo({ silent: true }),
        ]);
        if (!alive) return;
        setSystem(sysRes.data);
        setJvm(jvmRes.data);
        setBusiness(bizRes.data);
      } catch {
        // 静默轮询失败不弹错，交由下一次 tick 重试
      } finally {
        if (alive) setLoading(false);
      }
    };

    loadData();
    const interval = setInterval(loadData, 5000);
    return () => {
      alive = false;
      clearInterval(interval);
    };
  }, []);

  if (loading) {
    return (
      <div className="my-24 flex justify-center">
        <Loader2 className="size-8 animate-spin text-muted-foreground" />
      </div>
    );
  }

  return (
    <div>
      <div className="mb-6">
        <h2 className="page-title mb-2">仪表盘</h2>
        <p className="m-0 text-muted-foreground">实时监控系统运行状态</p>
      </div>

      {/* 指标卡：4 列响应式栅格 */}
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <GridCell className="fade-in-up stagger-1">
          <Card className="stat-card h-full py-5">
            <CardContent className="flex items-start justify-between px-5">
              <div className="min-w-0">
                <div className="text-muted-foreground text-sm">CPU 使用率</div>
                <div
                  className="mt-1 text-[28px] font-semibold leading-tight tabular-nums"
                  style={{ color: getCpuColor(system?.cpuUsage || 0) }}
                >
                  {(system?.cpuUsage || 0).toFixed(1)}%
                </div>
                <Progress
                  value={Math.min(100, system?.cpuUsage || 0)}
                  className="mt-3 h-1.5"
                  indicatorClassName="transition-all"
                  style={{ '--progress-color': getCpuColor(system?.cpuUsage || 0) } as React.CSSProperties}
                />
              </div>
              <StatIcon icon={<LayoutDashboard />} />
            </CardContent>
          </Card>
        </GridCell>

        <GridCell className="fade-in-up stagger-2">
          <Card className="stat-card h-full py-5">
            <CardContent className="flex items-start justify-between px-5">
              <div className="min-w-0">
                <div className="text-muted-foreground text-sm">堆内存使用率</div>
                <div
                  className="mt-1 text-[28px] font-semibold leading-tight tabular-nums"
                  style={{ color: getMemoryColor(system?.memoryUsage || 0) }}
                >
                  {(system?.memoryUsage || 0).toFixed(1)}%
                </div>
                <Progress
                  value={Math.min(100, system?.memoryUsage || 0)}
                  className="mt-3 h-1.5"
                  indicatorClassName="transition-all"
                />
              </div>
              <StatIcon icon={<Monitor />} />
            </CardContent>
          </Card>
        </GridCell>

        <GridCell className="fade-in-up stagger-3">
          <Card className="stat-card h-full py-5">
            <CardContent className="flex items-start justify-between px-5">
              <div className="min-w-0">
                <div className="text-muted-foreground text-sm">总生成数</div>
                <div className="mt-1 text-[28px] font-semibold leading-tight tabular-nums text-foreground">
                  {business?.sequenceGenerationTotal || 0}
                </div>
                <div className="mt-1 text-xs text-muted-foreground/80">活跃配置: {business?.configCount || 0}</div>
              </div>
              <StatIcon icon={<Rocket />} />
            </CardContent>
          </Card>
        </GridCell>

        <GridCell className="fade-in-up stagger-4">
          <Card className="stat-card h-full py-5">
            <CardContent className="flex items-start justify-between px-5">
              <div className="min-w-0">
                <div className="text-muted-foreground text-sm">QPS</div>
                <div className="mt-1 text-[28px] font-semibold leading-tight tabular-nums text-foreground">
                  {Math.round(business?.sequenceGenerationQps || 0)}
                </div>
                <div className="mt-1 text-xs text-muted-foreground/80">每秒序列生成数</div>
              </div>
              <StatIcon icon={<Zap />} />
            </CardContent>
          </Card>
        </GridCell>
      </div>

      {/* JVM + 系统信息 */}
      <div className="mt-4 grid grid-cols-1 gap-4 lg:grid-cols-2">
        <Card className="fade-in-up stagger-5">
          <CardHeader>
            <CardTitle className="flex items-center gap-2 text-[15px]">
              <Database className="size-4 text-zinc-500" />
              JVM 监控
            </CardTitle>
          </CardHeader>
          <CardContent>
            <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
              <div className="rounded-lg bg-[var(--color-bg-base)] p-4">
                <div className="mb-1 text-[13px] text-muted-foreground">堆内存使用</div>
                <div className="text-lg font-semibold text-foreground">{formatBytes(jvm?.heapUsed || 0)}</div>
                <div className="text-xs text-muted-foreground/80">/ {formatBytes(jvm?.heapMax || 0)}</div>
              </div>
              <div className="rounded-lg bg-[var(--color-bg-base)] p-4">
                <div className="mb-1 text-[13px] text-muted-foreground">堆内存使用率</div>
                <div className="text-lg font-semibold text-foreground">{jvm?.heapUsage?.toFixed(1) || 0}%</div>
                <Progress value={jvm?.heapUsage || 0} className="mt-2 h-1.5" />
              </div>
              <div className="rounded-lg bg-[var(--color-bg-base)] p-4">
                <div className="mb-1 text-[13px] text-muted-foreground">线程数</div>
                <div className="text-lg font-semibold text-foreground">{jvm?.threadCount || 0}</div>
              </div>
              <div className="rounded-lg bg-[var(--color-bg-base)] p-4">
                <div className="mb-1 text-[13px] text-muted-foreground">GC 次数</div>
                <div className="text-lg font-semibold text-foreground">{jvm?.gcCount || 0}</div>
              </div>
            </div>
          </CardContent>
        </Card>

        <Card className="fade-in-up stagger-6">
          <CardHeader>
            <CardTitle className="flex items-center gap-2 text-[15px]">
              <Info className="size-4 text-zinc-500" />
              系统信息
            </CardTitle>
          </CardHeader>
          <CardContent>
            <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
              <div className="rounded-lg bg-[var(--color-bg-base)] p-4">
                <div className="mb-1 text-[13px] text-muted-foreground">磁盘使用率</div>
                <div
                  className="text-2xl font-semibold"
                  style={{ color: (system?.diskUsage || 0) > 80 ? '#dc2626' : 'var(--color-text)' }}
                >
                  {system?.diskUsage?.toFixed(1) || 0}%
                </div>
                <Progress
                  value={system?.diskUsage || 0}
                  className="mt-2 h-1.5"
                  indicatorClassName={cn((system?.diskUsage || 0) > 80 ? 'bg-destructive' : 'bg-emerald-500')}
                />
              </div>
              <div className="rounded-lg bg-[var(--color-bg-base)] p-4">
                <div className="mb-1 text-[13px] text-muted-foreground">运行时间</div>
                <div className="text-lg font-semibold text-foreground">{formatUptime((system?.uptime || 0) / 1000)}</div>
              </div>
            </div>
          </CardContent>
        </Card>
      </div>
    </div>
  );
}
