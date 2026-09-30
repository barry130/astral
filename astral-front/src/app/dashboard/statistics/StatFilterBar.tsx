'use client';

import { useEffect, useState } from 'react';
import { Button, DatePicker, Select, Space } from '@/components/antd-compat';
import { ReloadOutlined } from '@/components/antd-compat/icons';
import type { Dayjs } from 'dayjs';
import { statApi } from '@/api/statistics';

/** 下拉选项 */
export type Option = { value: string; label: string };

/** 「全部平台」哨兵值，与后端 ut=all 约定一致 */
export const UT_ALL = 'all';

/** 是否处于「全部平台」口径（空串按 all 处理） */
export const isAllPlatform = (ut: string) => !ut || ut === UT_ALL;

/**
 * 版本下拉数据源：随平台联动
 * <p>
 * 平台为「全部平台」时直接清空且不发请求 —— 版本必须依附于具体平台，
 * 跨平台混列会出现「同一版本号在 iOS 与 Android 语义不同」的误导。
 * </p>
 */
export function useVersionOptions(ut: string): { options: Option[]; loading: boolean } {
  const [options, setOptions] = useState<Option[]>([]);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (isAllPlatform(ut)) {
      setOptions([]);
      setLoading(false);
      return;
    }
    let alive = true;
    setLoading(true);
    statApi
      .getVersions(ut)
      .then((res) => {
        if (alive) setOptions((res.data ?? []).map((v) => ({ value: v, label: v })));
      })
      .catch(() => {
        if (alive) setOptions([]);
      })
      .finally(() => {
        if (alive) setLoading(false);
      });
    return () => {
      alive = false;
    };
  }, [ut]);

  return { options, loading };
}

export interface StatFilterBarProps {
  /** 统计日期 */
  date: Dayjs;
  onDateChange: (date: Dayjs) => void;
  /** 平台（ut） */
  ut: string;
  onUtChange: (ut: string) => void;
  /** 版本，空串表示「全部版本」 */
  version: string;
  onVersionChange: (version: string) => void;
  /** 平台选项（字典 stat_platform，页面级加载一次） */
  utOptions: Option[];
  /** 版本选项（跟随平台联动） */
  versionOptions: Option[];
  versionLoading?: boolean;
  onRefresh: () => void;
  /** 追加在版本之后的额外筛选项（如接口统计的 Top N） */
  extra?: React.ReactNode;
}

/**
 * 统计页统一筛选栏：日期 + 平台 + 版本 + 刷新
 * <p>
 * 三个 Tab 共用一套布局与联动规则，始终排在同一行。
 * </p>
 * <p>
 * 每个控件都必须给显式宽度：兼容层的 DatePicker 渲染的是带 {@code w-full} 的原生 input，
 * 而 flex 项的 {@code flex-basis: auto} 会取该 width，导致日期控件独占一整行、
 * 把后面的平台/版本/刷新挤到下一行。
 * </p>
 * <p>
 * 「全部平台」时版本下拉禁用并保持为空。
 * </p>
 */
export function StatFilterBar({
  date,
  onDateChange,
  ut,
  onUtChange,
  version,
  onVersionChange,
  utOptions,
  versionOptions,
  versionLoading,
  onRefresh,
  extra,
}: StatFilterBarProps) {
  const allPlatform = isAllPlatform(ut);

  return (
    <Space className="filter-bar" style={{ marginBottom: 16 }} wrap>
      <DatePicker
        value={date}
        onChange={(d) => d && onDateChange(d)}
        allowClear={false}
        style={{ width: 150 }}
      />
      <Select
        value={ut}
        onChange={(v) => onUtChange(v ? String(v) : UT_ALL)}
        style={{ width: 150 }}
        options={utOptions}
        placeholder="全部平台"
      />
      <Select
        value={version}
        onChange={(v) => onVersionChange(v ? String(v) : '')}
        style={{ width: 150 }}
        // 全部平台下不允许有版本：禁用 + 提示先选平台（值恒为空）
        placeholder={allPlatform ? '先选平台' : '全部版本'}
        allowClear
        disabled={allPlatform}
        loading={versionLoading}
        options={versionOptions}
        notFoundContent="该平台暂无版本数据"
      />
      {extra}
      <Button icon={<ReloadOutlined />} onClick={onRefresh}>
        刷新
      </Button>
    </Space>
  );
}
