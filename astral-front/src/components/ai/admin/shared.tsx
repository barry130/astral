/**
 * AI 配置页的公共零件。
 *
 * 后台所有枚举文案都走数据字典（`ai_*` 系列），页面里不硬编码中文，
 * 否则运营改文案就得改代码。这里的 `useAiDict` 把「按需拉取 + 缓存」包成一次调用。
 */

'use client';

import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';

import { Card, Space, Tag, Tooltip } from '@/components/antd-compat';
import { ExclamationCircleOutlined } from '@/components/antd-compat/icons';

import { fetchDictOptions, type DictOption } from '@/api/dict';

/* ==================== 字典 ==================== */

/** 页面用到的全部 AI 字典编码（与 `V20261019001__ai_dict.sql` 一致） */
export const AI_DICT_CODES = [
  'ai_agent_type',
  'ai_agent_status',
  'ai_agent_version_status',
  'ai_client_type',
  'ai_provider_type',
  'ai_model_kind',
  'ai_tool_risk_level',
  'ai_tool_approval_policy',
  'ai_knowledge_source_type',
  'ai_knowledge_status',
  'ai_request_status',
  'ai_message_status',
  'ai_index_job_status',
  'ai_feedback_type',
  'ai_quota_scope_type',
] as const;

export interface AiDict {
  raw: Record<string, DictOption[]>;
  /** 取某字典下某值的展示文案；查不到时回落到原值 */
  label: (code: string, value?: string | null) => string;
  /** 直接拿 options，喂给 Select */
  options: (code: string) => DictOption[];
}

/** 模块级字典缓存：多个面板共用一份结果，避免每个组件实例重复拉 15 个字典 */
let aiDictCache: Record<string, DictOption[]> | null = null;
let aiDictPromise: Promise<Record<string, DictOption[]>> | null = null;

async function loadAiDicts(): Promise<Record<string, DictOption[]>> {
  if (aiDictCache) return aiDictCache;
  if (!aiDictPromise) {
    aiDictPromise = fetchDictOptions([...AI_DICT_CODES])
      .then((data) => {
        aiDictCache = data ?? {};
        return aiDictCache;
      })
      .catch(() => {
        // 失败不缓存：下次挂载重试；所有 label 会回落到原始枚举值，页面仍可用
        aiDictPromise = null;
        return {} as Record<string, DictOption[]>;
      });
  }
  return aiDictPromise;
}

export function useAiDict(): AiDict {
  const [raw, setRaw] = useState<Record<string, DictOption[]>>(aiDictCache ?? {});

  useEffect(() => {
    if (aiDictCache) return; // 已有缓存，不再请求
    let alive = true;
    loadAiDicts().then((data) => {
      if (alive) setRaw(data);
    });
    return () => {
      alive = false;
    };
  }, []);

  const label = useCallback(
    (code: string, value?: string | null) => {
      if (!value) return '-';
      return raw[code]?.find((item) => item.value === value)?.label ?? value;
    },
    [raw],
  );

  const options = useCallback((code: string) => raw[code] ?? [], [raw]);

  return useMemo(() => ({ raw, label, options }), [raw, label, options]);
}

/* ==================== 状态色 ==================== */

const TAG_COLOR: Record<string, string> = {
  // 通用
  ENABLED: 'success',
  DISABLED: 'default',
  DRAFT: 'warning',
  PUBLISHED: 'success',
  ARCHIVED: 'default',
  READY: 'success',
  PENDING: 'default',
  INDEXING: 'processing',
  RUNNING: 'processing',
  QUEUED: 'default',
  SUCCEEDED: 'success',
  FAILED: 'error',
  CANCELLED: 'default',
  WAITING_TOOL: 'processing',
  WAITING_APPROVAL: 'warning',
  // 提供方健康
  HEALTHY: 'success',
  UNHEALTHY: 'error',
  UNKNOWN: 'default',
  // 风险与审批
  READ: 'default',
  DRAFT_RISK: 'warning',
  HIGH: 'error',
  NONE: 'default',
  USER_CONFIRM: 'warning',
  ADMIN_CONFIRM: 'error',
};

export function statusColor(value?: string | null): string {
  if (!value) return 'default';
  return TAG_COLOR[value] ?? 'default';
}

export interface StatusTagProps {
  dict: AiDict;
  code: string;
  value?: string | null;
}

/** 状态标签：文案取自字典，颜色按枚举值映射 */
export function StatusTag({ dict, code, value }: StatusTagProps) {
  if (!value) return <Tag color="default">-</Tag>;
  return <Tag color={statusColor(value)}>{dict.label(code, value)}</Tag>;
}

/** 布尔开关的只读展示 */
export function BoolTag({ value, trueText = '开', falseText = '关' }: { value?: boolean; trueText?: string; falseText?: string }) {
  return <Tag color={value ? 'success' : 'default'}>{value ? trueText : falseText}</Tag>;
}

/* ==================== 代码/JSON 展示 ==================== */

export function JsonBlock({ value, maxHeight = 220 }: { value?: string | null; maxHeight?: number }) {
  if (!value) return <span style={{ opacity: 0.5 }}>-</span>;
  let pretty = value;
  try {
    pretty = JSON.stringify(JSON.parse(value), null, 2);
  } catch {
    // 不是合法 JSON 就原样展示，方便排查后台填错的内容
  }
  return (
    <pre
      style={{
        margin: 0,
        padding: '8px 10px',
        borderRadius: 6,
        background: 'var(--muted, rgba(127,127,127,0.08))',
        fontSize: 12,
        lineHeight: 1.6,
        maxHeight,
        overflow: 'auto',
        whiteSpace: 'pre-wrap',
        wordBreak: 'break-word',
      }}
    >
      {pretty}
    </pre>
  );
}

/* ==================== 表单里的 JSON 校验 ==================== */

/** `Form.Item` 的 rules 用：允许空，但填了必须能解析成 JSON 对象 */
export const jsonObjectRule = {
  validator: async (_rule: unknown, value: unknown) => {
    if (value === undefined || value === null || value === '') return;
    if (typeof value !== 'string') return;
    try {
      const parsed = JSON.parse(value);
      if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
        throw new Error('需要是 JSON 对象');
      }
    } catch {
      throw new Error('不是合法的 JSON 对象');
    }
  },
};

/* ==================== 卡片外壳 ==================== */

export interface PanelProps {
  title?: React.ReactNode;
  extra?: React.ReactNode;
  children: React.ReactNode;
  bodyStyle?: React.CSSProperties;
  style?: React.CSSProperties;
}

export function Panel({ title, extra, children, bodyStyle, style }: PanelProps) {
  return (
    <Card title={title} extra={extra} style={style} bodyStyle={bodyStyle}>
      {children}
    </Card>
  );
}

/** 页面顶部说明条：一句话讲清楚这个页签在管什么 */
export function Hint({ children }: { children: React.ReactNode }) {
  return (
    <div
      style={{
        display: 'flex',
        gap: 8,
        alignItems: 'flex-start',
        padding: '9px 12px',
        marginBottom: 12,
        borderRadius: 8,
        fontSize: 12.5,
        lineHeight: 1.7,
        background: 'var(--muted, rgba(127,127,127,0.07))',
        color: 'var(--muted-foreground, inherit)',
      }}
    >
      <ExclamationCircleOutlined style={{ marginTop: 3, opacity: 0.6, flex: 'none' }} />
      <div>{children}</div>
    </div>
  );
}

/** 只读提示：无权限时统一显示，避免各页签各写一套 */
export function ReadOnlyNote({ show }: { show: boolean }) {
  if (!show) return null;
  return (
    <Tooltip title="当前账号只有查看权限">
      <Tag color="default">只读</Tag>
    </Tooltip>
  );
}

/** 空表格的占位文案 */
export function emptyText(text: string) {
  return <span style={{ opacity: 0.5, fontSize: 13 }}>{text}</span>;
}

/* ==================== 通用异步加载 ==================== */

export interface AsyncState<T> {
  data: T;
  loading: boolean;
  error?: string;
  reload: () => void;
}

/**
 * 最小化的「加载一次 + 手动刷新」封装。
 * 不用 SWR/React Query 是因为项目没有这两个依赖，引入会多一个运行期负担。
 */
export function useAsync<T>(loader: () => Promise<T>, initial: T, deps: unknown[] = []): AsyncState<T> {
  const [data, setData] = useState<T>(initial);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | undefined>();
  const [tick, setTick] = useState(0);
  const aliveRef = useRef(true);
  const loaderRef = useRef(loader);
  loaderRef.current = loader;

  useEffect(() => {
    aliveRef.current = true;
    return () => {
      aliveRef.current = false;
    };
  }, []);

  useEffect(() => {
    setLoading(true);
    setError(undefined);
    loaderRef
      .current()
      .then((value) => {
        if (aliveRef.current) setData(value);
      })
      .catch((err: unknown) => {
        if (aliveRef.current) setError(err instanceof Error ? err.message : '加载失败');
      })
      .finally(() => {
        if (aliveRef.current) setLoading(false);
      });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tick, ...deps]);

  const reload = useCallback(() => setTick((value) => value + 1), []);

  return { data, loading, error, reload };
}

/* ==================== 数字格式化 ==================== */

export function formatNumber(value?: number | null): string {
  if (value === undefined || value === null || !Number.isFinite(value)) return '-';
  return value.toLocaleString('zh-CN');
}

export function formatCost(value?: number | null, currency = 'CNY'): string {
  if (value === undefined || value === null || !Number.isFinite(value)) return '-';
  return `${value.toFixed(value >= 1 ? 2 : 4)} ${currency}`;
}

export function formatTime(value?: string | null): string {
  if (!value) return '-';
  return value.replace('T', ' ').slice(0, 19);
}

/** 截断长文本，避免表格被一行内容撑爆 */
export function truncate(value?: string | null, max = 60): string {
  if (!value) return '-';
  return value.length > max ? `${value.slice(0, max)}…` : value;
}
