'use client';

import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { RotateCw, SlidersHorizontal } from 'lucide-react';

import { EmptyState } from '@/components/EmptyState';
import { TableCardList } from '@/components/TableCardList';
import { Button } from '@/components/ui/button';
import { Checkbox } from '@/components/ui/checkbox';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import { DataTable } from '@/components/data-table/DataTable';
import {
  columnTitleText,
  resolveColumnKey,
  type DataTableColumn,
  type DataTableExpandable,
  type DataTablePagination,
} from '@/components/data-table/types';

/**
 * 全局统一表格入口：按视口与列宽自动选择呈现方式，页面只写一份 columns。
 *
 * 三种模式：
 *  1. card   手机（≤768px）且可见列 ≥5 → 卡片列表，每条记录一张卡片；
 *  2. scroll 列宽之和 > 容器宽 → 保留每列 px 宽度并横向滚动；
 *  3. fill   列宽之和 ≤ 容器宽 → 列宽换算成百分比（合计 100%）铺满容器。
 *
 * 列宽代表「期望最小宽度」：容器放得下就等比铺满不留白，放不下就横向滚动，
 * 绝不把内容压到不可读。
 *
 * 交互：表头右缘可拖拽调宽；「列设置」可隐藏不需要看的列。两者都按页面写入
 * localStorage，刷新后保留。
 *
 * 渲染层已由 antd Table 迁移到自研 DataTable（shadcn/ui + Tailwind）。
 */

/** 移动端断点，与 providers.tsx 的 MOBILE_QUERY、globals.css 的 @media 保持一致 */
const MOBILE_QUERY = '(max-width: 768px)';
/** 可见列数达到该值时手机端走卡片模式（列太少时横向滚动反而更好读） */
const CARD_MIN_COLS = 5;
/** 拖拽列宽下限：再窄内容会截到无法辨认 */
const MIN_COL_WIDTH = 72;
/** 自动估算列宽上限：避免单列吞掉整行 */
const MAX_AUTO_WIDTH = 340;
/** 可见列数达到该值时才显示「列设置」按钮，避免窄表多出一个无关入口 */
const COLS_SETTING_MIN = 6;

type ColumnKey = string;
type RenderMode = 'fill' | 'scroll' | 'card';

interface ResizableTableProps<T> {
  /** 列定义（自研 DataTableColumn，与 antd ColumnType 同形） */
  columns?: Array<DataTableColumn<T>>;
  dataSource?: readonly T[];
  rowKey?: string | ((record: T) => string | number);
  loading?: boolean | { spinning?: boolean };
  pagination?: false | DataTablePagination;
  size?: 'small' | 'middle';
  /**
   * 与 antd 同形的滚动配置：`x` 由本组件接管（fill/scroll 自适配），
   * 这里只消费 `y` 作为表体最大高度，保留 `x` 仅为兼容既有页面写法。
   */
  scroll?: { x?: number | string; y?: number | string };
  /** 空态文案覆盖（与 antd locale 同形） */
  locale?: { emptyText?: React.ReactNode };
  /** 容器内联样式（少量页面用于限高） */
  style?: React.CSSProperties;
  /** 展开行配置，透传给 DataTable */
  expandable?: DataTableExpandable<T>;
  /** 初始展开所有行，透传给 DataTable */
  defaultExpandAllRows?: boolean;
  /** 容器类名 */
  className?: string;
  /**
   * 兼容签名：antd Table 的 onChange（分页/筛选/排序变化）。
   * 翻页时以 antd 形状回抛 `({ current, pageSize }, undefined, undefined)`。
   * 注意与 pagination.onChange 是两条并存入口（组件内合流，见 resolvedPagination），
   * 页面只应使用其一，同时传会双触发。
   */
  /* eslint-disable-next-line @typescript-eslint/no-explicit-any */
  onChange?: (...args: any[]) => void;
  /** 列宽 / 列显隐记忆的标识；不传时按当前页面路径区分 */
  resizeKey?: string;
  /** 是否允许拖拽列宽（默认 true） */
  resizable?: boolean;
  /** 手机端是否启用卡片模式（默认 true，可见列数 ≥ CARD_MIN_COLS 时生效） */
  cardOnMobile?: boolean;
  /** 行级属性（与 antd 同形；支持 onClick / className / title / draggable 等原生属性） */
  onRow?: (record: T, index: number) => React.HTMLAttributes<HTMLTableRowElement>;
}

/** 表头文案 → 默认列宽（命中关键词给固定宽度，否则按字宽估算） */
const HEADER_WIDTH_RULES: Array<[RegExp, number]> = [
  [/(时间|日期|datetime)/, 176],
  [/(操作|action)/i, 184],
  [/(md5|sha|hash|校验)/i, 140],
  [/(编号|id|端口|port|大小|数量|版本|序号|天数)/i, 104],
  [/(状态|类型|标签|角色|权限|渠道|分类|级别|平台|模式|方式|来源)/, 112],
  [/(名称|标题|描述|内容|备注|说明|路径|地址|url|ip|链接)/i, 208],
];

const estimateWidth = (title: unknown): number => {
  // 渲染函数型/节点型表头无法安全取值，给一个通用宽度
  if (typeof title === 'function' || (title !== null && typeof title === 'object')) return 132;
  const text = typeof title === 'string' ? title : typeof title === 'number' ? String(title) : '';
  for (const [pattern, fixed] of HEADER_WIDTH_RULES) {
    if (pattern.test(text)) return fixed;
  }
  let w = 28; // 表头内边距 + 余量
  for (const ch of text) w += ch.charCodeAt(0) > 255 ? 15 : 8.5;
  return Math.max(96, Math.min(Math.round(w), MAX_AUTO_WIDTH));
};

/** 操作列识别：固定 key 为 action，或表头文案含「操作」 */
const isActionCol = <T,>(col: DataTableColumn<T>): boolean =>
  col.key === 'action' || /操作|action/i.test(columnTitleText(col));

/**
 * 为实例派生独一无二的存储后缀：`base#<列数>:<列签名哈希>`。
 *
 * 同一路径下往往挂多张 ResizableTable（Tabs 页、多区块页），旧实现全部共用
 * `pathname` 一个 localStorage key：任何一张表保存列设置（哪怕只是把自己当前
 * 的空 hidden 写回）都会覆盖其他表的设置，刷新后「没勾选的字段又出来了」。
 * 这里用「完整列定义的 key 序列（含用户已隐藏的列）」做签名，同路径下列结构
 * 不同的表各占一个 key，互不干扰；单表页面的 key 仍完全确定，刷新不变。
 * 隐藏列不会改变签名（签名取全量列），切列设置面板不会导致 key 漂移。
 */
const deriveStorageSuffix = (base: string, colsWithKey: Array<{ key: ColumnKey }>): string => {
  if (colsWithKey.length === 0) return base;
  // djb2 字符串哈希：只依赖列 key 的稳定字符串，与列对象引用无关
  let h = 5381;
  for (const { key } of colsWithKey) {
    for (let i = 0; i < key.length; i += 1) {
      h = ((h * 33) ^ key.charCodeAt(i)) >>> 0;
    }
  }
  return `${base}#${colsWithKey.length}:${h.toString(36)}`;
};

export function ResizableTable<T extends object>(props: ResizableTableProps<T>) {
  const {
    columns,
    resizeKey,
    resizable = true,
    cardOnMobile = true,
    className,
    loading,
    pagination,
    size,
    scroll,
    locale,
    style,
    expandable,
    defaultExpandAllRows,
    dataSource,
    rowKey,
    onRow,
    onChange: onTableChange,
  } = props;
  /** 表体纵向滚动高度（x 由本组件接管，忽略） */
  const scrollY = typeof scroll?.y === 'number' ? scroll.y : undefined;

  /**
   * 分页统一出口：DataTable 的分页器只认 pagination.onChange（见 TablePagination），
   * 而不少页面沿用 antd 习惯把 onChange 直接写在表格标签上——不在这里合流的话，
   * 这些页面的分页器就是死的（点了翻页没反应）。
   */
  const resolvedPagination =
    pagination
      ? {
          ...pagination,
          onChange: (page: number, pageSize: number) => {
            pagination.onChange?.(page, pageSize);
            onTableChange?.({ current: page, pageSize }, undefined, undefined);
          },
        }
      : pagination;

  const baseSuffix = resizeKey ?? (typeof window === 'undefined' ? 'server' : window.location.pathname);

  /** 用户拖拽出的列宽（挂载后从 localStorage 恢复） */
  const [widths, setWidths] = useState<Record<ColumnKey, number>>({});
  /** 用户隐藏的列 */
  const [hidden, setHidden] = useState<Set<ColumnKey>>(new Set());
  /**
   * 是否已从 localStorage 恢复过宽/列设置。
   * 挂载时 initial state 是默认值（widths={} / hidden=空），若保存 effect 在恢复 setState 生效前
   * 先跑，会把空默认值写回 localStorage，覆盖用户已保存的列设置（尤其在 dev + StrictMode 双跑 effect
   * 时必现，表现为「列设置一刷新就回到默认」）。恢复完成前不写持久化即可根治。
   */
  const [hydrated, setHydrated] = useState(false);
  const [containerWidth, setContainerWidth] = useState(0);
  const [isMobile, setIsMobile] = useState(false);
  const wrapRef = useRef<HTMLDivElement>(null);
  const widthRef = useRef<Record<ColumnKey, number>>({});

  // ---------- 列 + 稳定 key（key 必须按原始下标取，隐藏列后 key 仍稳定） ----------
  const colsWithKey = useMemo<Array<{ col: DataTableColumn<T>; key: ColumnKey }>>(() => {
    const list = (columns ?? []) as Array<DataTableColumn<T>>;
    return list.map((col, index) => ({ col, key: resolveColumnKey(col, index) }));
  }, [columns]);

  // ---------- 存储 key：同路径多表按列签名区分，避免互相覆盖 ----------
  const storageSuffix = useMemo(
    () => deriveStorageSuffix(baseSuffix, colsWithKey),
    // baseSuffix 是 resizeKey 或 pathname（字符串常量/稳定 prop），列签名只随列定义变化
    [baseSuffix, colsWithKey],
  );
  const widthKey = `astral:table-widths:${storageSuffix}`;
  const hiddenKey = `astral:table-hidden:${storageSuffix}`;

  useEffect(() => {
    try {
      const raw = window.localStorage.getItem(widthKey);
      if (raw) setWidths(JSON.parse(raw) as Record<ColumnKey, number>);
      const rawHidden = window.localStorage.getItem(hiddenKey);
      if (rawHidden) setHidden(new Set(JSON.parse(rawHidden) as ColumnKey[]));
    } catch {
      /* 缓存损坏时忽略，退回自动估算 */
    } finally {
      // 恢复完成后才允许持久化；详见 hydrated 注释
      setHydrated(true);
    }
  }, [widthKey, hiddenKey]);

  useEffect(() => {
    if (!hydrated || Object.keys(widths).length === 0) return;
    try {
      window.localStorage.setItem(widthKey, JSON.stringify(widths));
    } catch {
      /* 存储不可用时静默失败 */
    }
  }, [widths, widthKey, hydrated]);

  useEffect(() => {
    if (!hydrated) return;
    try {
      window.localStorage.setItem(hiddenKey, JSON.stringify(Array.from(hidden)));
    } catch {
      /* ignore */
    }
  }, [hidden, hiddenKey, hydrated]);

  // ---------- 容器宽度：ResizeObserver 实测，决定 fill / scroll ----------
  useEffect(() => {
    const el = wrapRef.current;
    if (!el || typeof ResizeObserver === 'undefined') return;
    const measure = () => setContainerWidth(el.clientWidth);
    measure();
    const ro = new ResizeObserver(measure);
    ro.observe(el);
    return () => ro.disconnect();
  }, []);

  // ---------- 视口断点：手机端卡片模式 ----------
  useEffect(() => {
    const mq = window.matchMedia(MOBILE_QUERY);
    const apply = () => setIsMobile(mq.matches);
    apply();
    mq.addEventListener('change', apply);
    return () => mq.removeEventListener('change', apply);
  }, []);

  /** 生效列宽：拖拽值 > columns 显式 width > 按表头估算 */
  const effectiveWidths = useMemo(() => {
    const result: Record<ColumnKey, number> = {};
    colsWithKey.forEach(({ col, key }) => {
      result[key] = widths[key] ?? (typeof col.width === 'number' ? col.width : estimateWidth(col.title));
    });
    return result;
  }, [colsWithKey, widths]);

  useEffect(() => {
    widthRef.current = effectiveWidths;
  }, [effectiveWidths]);

  /** 当前渲染的可见列 */
  const visibleCols = useMemo(() => colsWithKey.filter(({ key }) => !hidden.has(key)), [colsWithKey, hidden]);

  /** 可见列的宽度总和（px） */
  const totalPx = useMemo(
    () => visibleCols.reduce((sum, { key }) => sum + (effectiveWidths[key] ?? 0), 0),
    [visibleCols, effectiveWidths],
  );

  const mode: RenderMode =
    isMobile && cardOnMobile && visibleCols.length >= CARD_MIN_COLS
      ? 'card'
      : containerWidth === 0
        ? 'fill'
        : totalPx > containerWidth
          ? 'scroll'
          : 'fill';

  const startDrag = useCallback((e: React.PointerEvent, key: ColumnKey) => {
    e.preventDefault();
    e.stopPropagation();
    const startX = e.clientX;
    const startWidth = widthRef.current[key] ?? 120;
    // 表格总宽被等比拉伸/压缩后，实际渲染列宽与记录值存在比例差，
    // 按真实比例换算拖拽增量，保证手感与显示一致
    const th = (e.currentTarget as HTMLElement).closest('th');
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
  }, []);

  /** 表格模式列：拖拽手柄 + 百分比 / px 宽度 */
  const resolvedColumns = useMemo<Array<DataTableColumn<T>>>(() => {
    return visibleCols.map(({ col, key }) => {
      const title = col.title as React.ReactNode;
      const titleNode: React.ReactNode = resizable ? (
        <span className="rt-th-title">
          {title}
          {/* 手柄的显隐与颜色由 .rt-th-resize 的 :hover 规则控制（见 globals.css），
              不再内联写死颜色——写死的 rgba 深色在暗色主题下不可见 */}
          <span
            role="separator"
            aria-label="拖拽调整列宽"
            className="rt-th-resize"
            onPointerDown={(e) => startDrag(e, key)}
          />
        </span>
      ) : (
        title
      );
      const width: number | string =
        mode === 'fill' && totalPx > 0
          ? `${((effectiveWidths[key] / totalPx) * 100).toFixed(4)}%`
          : effectiveWidths[key];
      return { ...col, key, width, title: titleNode };
    });
  }, [visibleCols, effectiveWidths, mode, totalPx, resizable, startDrag]);

  /** 卡片模式列：同一份列定义，去掉拖拽手柄 */
  const cardColumns = useMemo<Array<DataTableColumn<T>>>(
    () => visibleCols.map(({ col, key }) => ({ ...col, key })),
    [visibleCols],
  );

  /**
   * 统一空状态：
   * - 默认渲染 EmptyState，全项目列表页共享同一套空态视觉
   * - 加载中隐藏「暂无数据」：翻页 / 筛选触发 loading 时不显示空态，
   *   避免把「正在加载」误读成「没有数据」
   */
  const emptyNode = loading
    ? null
    : (locale?.emptyText ?? <EmptyState description="暂无数据" padding={20} ariaLabel="暂无数据" />);

  // ---------- 列设置面板 ----------
  const toggleCol = (key: ColumnKey) =>
    setHidden((prev) => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });

  /** 可隐藏的列：首列（通常 ID，定位记录用）与操作列固定显示 */
  const toggleableCols = useMemo(
    () => colsWithKey.slice(1).filter(({ col }) => !isActionCol(col)),
    [colsWithKey],
  );

  const colSettingsPanel = (
    <div className="rt-cols-panel">
      <div className="rt-cols-head">
        <span>列设置</span>
        <span className="flex items-center">
          <Button
            variant="link"
            size="sm"
            className="rt-cols-act h-auto p-0"
            onClick={() => setHidden(new Set())}
          >
            全部
          </Button>
          <Button
            variant="link"
            size="sm"
            className="rt-cols-act h-auto p-0"
            onClick={() => setWidths({})}
          >
            <RotateCw className="size-3" />
            重置
          </Button>
        </span>
      </div>
      <div className="rt-cols-list">
        {toggleableCols.map(({ col, key }) => (
          <label key={key} className="rt-cols-item">
            <Checkbox checked={!hidden.has(key)} onCheckedChange={() => toggleCol(key)} />
            <span className="rt-cols-name">{columnTitleText(col)}</span>
          </label>
        ))}
      </div>
    </div>
  );

  const wrapperClass = `rt-table rt-table--${mode}${className ? ` ${className}` : ''}`;

  if (mode === 'card') {
    return (
      <div className={wrapperClass} ref={wrapRef}>
        {visibleCols.length >= COLS_SETTING_MIN ? (
          <div className="rt-toolbar">
            <Popover>
              <PopoverTrigger asChild>
                <Button size="sm" variant="outline">
                  <SlidersHorizontal className="size-3.5" />
                  列设置
                </Button>
              </PopoverTrigger>
              <PopoverContent align="end">{colSettingsPanel}</PopoverContent>
            </Popover>
          </div>
        ) : null}
        <TableCardList<T>
          dataSource={dataSource}
          columns={cardColumns}
          rowKey={rowKey}
          loading={loading}
          pagination={resolvedPagination}
        />
      </div>
    );
  }

  return (
    <div className={wrapperClass} ref={wrapRef} style={style}>
      {visibleCols.length >= COLS_SETTING_MIN ? (
        <div className="rt-toolbar">
          <Popover>
            <PopoverTrigger asChild>
              <Button size="sm" variant="outline">
                <SlidersHorizontal className="size-3.5" />
                列设置
              </Button>
            </PopoverTrigger>
            <PopoverContent align="end">{colSettingsPanel}</PopoverContent>
          </Popover>
        </div>
      ) : null}
      <DataTable<T>
        columns={resolvedColumns}
        dataSource={dataSource}
        rowKey={rowKey}
        loading={loading}
        pagination={resolvedPagination}
        size={size}
        scrollY={scrollY}
        expandable={expandable}
        defaultExpandAllRows={defaultExpandAllRows}
        emptyText={emptyNode}
        onRow={onRow}
        bordered={false}
      />
    </div>
  );
}
