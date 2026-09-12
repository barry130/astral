'use client';

import React, { useEffect, useMemo, useRef, useState } from 'react';
import { Table } from 'antd';
import type { ColumnType, ColumnsType } from 'antd/es/table';
import type { TableProps } from 'antd';
import { EmptyState } from '@/components/EmptyState';

/**
 * 可拖拽列宽的表格（全局统一入口）
 *
 * 行为约定：
 * 1. 表头右边缘可左右拖拽调整列宽，结果按页面写入 localStorage，刷新后保留；
 * 2. 强制 tableLayout="fixed" 并移除 scroll.x —— 列宽统一换算成百分比（合计 100%）：
 *    table-layout:fixed 下浏览器按表宽精确分配百分比列宽，列宽之和无论多大
 *    都不会溢出出现左右滚动条，也不会出现铺不满的留白；容器变宽/变窄时列宽自动等比跟随；
 * 3. 拖拽调整的是列宽比例：某列变宽时其余列等比让位，总宽始终铺满；
 * 4. 列宽缺省时按表头文案自动估算，业务上也可在 columns 里显式给 width 覆盖。
 */

/** 列宽下限：再窄内容会挤成竖排 */
const MIN_COL_WIDTH = 56;
/** 自动估算列宽上限：避免单列吞掉整行 */
const MAX_AUTO_WIDTH = 340;

type ColumnKey = string;

interface ResizableTableProps<T> extends TableProps<T> {
  /** 列宽记忆的标识；不传时按当前页面路径区分 */
  resizeKey?: string;
  /** 是否允许拖拽列宽（默认 true） */
  resizable?: boolean;
}

/** 列的唯一标识：优先 key，其次 dataIndex，最后下标 */
const colKeyOf = <T,>(col: ColumnType<T>, index: number): ColumnKey => {
  if (col.key !== undefined && col.key !== null) return String(col.key);
  if (col.dataIndex) {
    return Array.isArray(col.dataIndex) ? col.dataIndex.join('.') : String(col.dataIndex);
  }
  return `col-${index}`;
};

/** 表头文案 → 默认列宽（命中关键词给固定宽度，否则按字宽估算） */
const HEADER_WIDTH_RULES: Array<[RegExp, number]> = [
  [/(时间|日期)/, 172],
  [/(操作|action)/i, 184],
  [/(名称|标题|描述|内容|备注|说明|路径|地址|url|ip)/i, 224],
  [/(状态|类型|标签|角色|权限|渠道|分类|级别|平台)/, 112],
  [/(编号|id|端口|port|大小|数量|版本)/i, 100],
];

const estimateWidth = (title: unknown): number => {
  // 渲染函数型表头无法安全取值，给一个通用宽度
  if (typeof title === 'function') return 132;
  const text = typeof title === 'string' ? title : typeof title === 'number' ? String(title) : '';
  for (const [pattern, fixed] of HEADER_WIDTH_RULES) {
    if (pattern.test(text)) return fixed;
  }
  let w = 28; // 表头内边距 + 余量
  for (const ch of text) w += ch.charCodeAt(0) > 255 ? 15 : 8.5;
  return Math.max(96, Math.min(Math.round(w), MAX_AUTO_WIDTH));
};

export function ResizableTable<T extends object>(props: ResizableTableProps<T>) {
  const { columns, resizeKey, resizable = true, scroll, style, locale, loading, ...rest } = props;

  const storageKey = `astral:table-widths:${
    resizeKey ?? (typeof window === 'undefined' ? 'server' : window.location.pathname)
  }`;

  /** 用户拖拽出的列宽（挂载后从 localStorage 恢复） */
  const [widths, setWidths] = useState<Record<ColumnKey, number>>({});
  const [hoverKey, setHoverKey] = useState<ColumnKey | null>(null);
  const widthRef = useRef<Record<ColumnKey, number>>({});

  useEffect(() => {
    try {
      const raw = window.localStorage.getItem(storageKey);
      if (raw) setWidths(JSON.parse(raw) as Record<ColumnKey, number>);
    } catch {
      /* 缓存损坏时忽略，退回自动估算 */
    }
  }, [storageKey]);

  useEffect(() => {
    if (Object.keys(widths).length === 0) return;
    try {
      window.localStorage.setItem(storageKey, JSON.stringify(widths));
    } catch {
      /* 存储不可用时静默失败 */
    }
  }, [widths, storageKey]);

  /** 生效列宽：拖拽值 > columns 显式 width > 按表头估算 */
  const effectiveWidths = useMemo(() => {
    const result: Record<ColumnKey, number> = {};
    const list = (columns ?? []) as unknown as ColumnType<T>[];
    list.forEach((col, index) => {
      result[colKeyOf(col, index)] =
        widths[colKeyOf(col, index)] ??
        (typeof col.width === 'number' ? col.width : estimateWidth(col.title));
    });
    return result;
  }, [columns, widths]);

  useEffect(() => {
    widthRef.current = effectiveWidths;
  }, [effectiveWidths]);

  const startDrag = (e: React.PointerEvent, key: ColumnKey) => {
    e.preventDefault();
    e.stopPropagation();
    const startX = e.clientX;
    const startWidth = widthRef.current[key] ?? 120;
    // 表格总宽被等比拉伸/压缩后，实际渲染列宽与记录值存在比例差，
    // 按真实比例换算拖拽增量，保证手感与显示一致
    const th = e.currentTarget.closest('th');
    const renderedWidth = th ? th.getBoundingClientRect().width : startWidth;
    const scale = renderedWidth > 0 && startWidth > 0 ? renderedWidth / startWidth : 1;
    document.body.style.cursor = 'col-resize';
    document.body.style.userSelect = 'none';
    const onMove = (ev: PointerEvent) => {
      const next = Math.max(MIN_COL_WIDTH, Math.round(startWidth + (ev.clientX - startX) / scale));
      setWidths((prev) => ({ ...prev, [key]: next }));
    };
    const onUp = () => {
      window.removeEventListener('pointermove', onMove);
      window.removeEventListener('pointerup', onUp);
      document.body.style.cursor = '';
      document.body.style.userSelect = '';
    };
    window.addEventListener('pointermove', onMove);
    window.addEventListener('pointerup', onUp);
  };

  const resolvedColumns = useMemo<ColumnsType<T>>(() => {
    const list = (columns ?? []) as unknown as ColumnType<T>[];
    // 总宽必须恰好等于容器宽度：把 px 列宽换算成百分比（合计 100%）。
    // table-layout:fixed 下百分比列宽按表宽精确分配，之和再大也不会溢出；
    // 若继续传 px，列宽是硬值，之和大于表宽时浏览器不会压缩，表格会溢出容器。
    const total = Object.values(effectiveWidths).reduce((sum, w) => sum + w, 0);
    return list.map((col, index) => {
      const key = colKeyOf(col, index);
      const title = col.title as React.ReactNode;
      const titleNode: React.ReactNode = resizable ? (
        <span style={{ position: 'relative', display: 'block' }}>
          {title}
          <span
            role="separator"
            aria-label="拖拽调整列宽"
            onPointerDown={(e) => startDrag(e, key)}
            onPointerEnter={() => setHoverKey(key)}
            onPointerLeave={() => setHoverKey(null)}
            style={
              {
                position: 'absolute',
                right: -7,
                top: 0,
                bottom: 0,
                width: 9,
                cursor: 'col-resize',
                touchAction: 'none',
                borderRadius: 2,
                background: hoverKey === key ? 'rgba(74, 111, 165, 0.35)' : 'transparent',
              } as React.CSSProperties
            }
          />
        </span>
      ) : (
        title
      );
      const width: number | string | undefined =
        total > 0 ? `${(effectiveWidths[key] / total) * 100}%` : undefined;
      return { ...col, key, width, title: titleNode } as ColumnType<T>;
    });
  }, [columns, effectiveWidths, resizable, hoverKey, startDrag]);

  /**
   * 统一空状态：
   * - 默认渲染 EmptyState，全项目 19 个列表页共享同一套空态视觉；页面可用 locale.emptyText 覆盖
   * - 加载中隐藏「暂无数据」：翻页 / 筛选触发 loading 时不显示空态，避免把「正在加载」误读成「没有数据」
   */
  const tableLocale = useMemo(() => ({
    ...locale,
    emptyText: loading
      ? ''
      : (locale?.emptyText ?? <EmptyState description="暂无数据" padding={20} ariaLabel="暂无数据" />),
  }), [locale, loading]);

  return (
    <Table<T>
      {...rest}
      columns={resolvedColumns}
      locale={tableLocale}
      scroll={scroll ? { ...scroll, x: undefined } : undefined}
      tableLayout="fixed"
      style={{ width: '100%', ...style }}
    />
  );
}
