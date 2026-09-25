'use client';

import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Button, Checkbox, Popover, Space, Table } from 'antd';
import type { ColumnType, ColumnsType, TableProps } from 'antd/es/table';
import { ReloadOutlined, SlidersOutlined } from '@ant-design/icons';
import { EmptyState } from '@/components/EmptyState';
import { TableCardList } from '@/components/TableCardList';

/**
 * 全局统一表格入口：按视口与列宽自动选择呈现方式，页面只写一份 columns。
 *
 * 三种模式：
 *  1. card   手机（≤768px）且可见列 ≥5 → 卡片列表，每条记录一张卡片；
 *  2. scroll 列宽之和 > 容器宽 → 保留每列 px 宽度并横向滚动；
 *  3. fill   列宽之和 ≤ 容器宽 → 列宽换算成百分比（合计 100%）铺满容器。
 *
 * 旧实现无条件把列宽压成「合计 100% 的百分比」并剥离 scroll.x，13 列的表在
 * 1290px 容器里每列只剩 ~80px：8 位 ID 被折成 4 行、表头「版本号」折成两行、
 * 「非强制」被截成「非强」。现在的规则是——列宽代表「期望最小宽度」：容器放得
 * 下就等比铺满不留白，放不下就横向滚动，绝不把内容压到不可读。
 *
 * 交互：表头右缘可拖拽调宽；「列设置」可隐藏不需要看的列（适合 13 列以上的宽表
 * 在窄屏上只看关键字段）。两者都按页面写入 localStorage，刷新后保留。
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

interface ResizableTableProps<T> extends TableProps<T> {
  /** 列宽 / 列显隐记忆的标识；不传时按当前页面路径区分 */
  resizeKey?: string;
  /** 是否允许拖拽列宽（默认 true） */
  resizable?: boolean;
  /** 手机端是否启用卡片模式（默认 true，可见列数 ≥ CARD_MIN_COLS 时生效） */
  cardOnMobile?: boolean;
}

/** 列的唯一标识：优先 key，其次 dataIndex，最后下标 */
const colKeyOf = <T,>(col: ColumnType<T>, index: number): ColumnKey => {
  if (col.key !== undefined && col.key !== null) return String(col.key);
  if (col.dataIndex) {
    return Array.isArray(col.dataIndex) ? col.dataIndex.join('.') : String(col.dataIndex);
  }
  return `col-${index}`;
};

/** 表头文案的可读名称（列设置面板用；渲染函数型表头退回 dataIndex） */
const colTitleText = (col: ColumnType<any>): string => {
  if (typeof col.title === 'string') return col.title;
  if (typeof col.title === 'number') return String(col.title);
  if (col.dataIndex) return String(Array.isArray(col.dataIndex) ? col.dataIndex.join('.') : col.dataIndex);
  return '列';
};

/** 操作列识别：固定 key 为 action，或表头文案含「操作」 */
const isActionCol = (col: ColumnType<any>): boolean =>
  col.key === 'action' || /操作|action/i.test(colTitleText(col));

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
  const {
    columns,
    resizeKey,
    resizable = true,
    cardOnMobile = true,
    scroll,
    style,
    className,
    locale,
    loading,
    ...rest
  } = props;

  const suffix = resizeKey ?? (typeof window === 'undefined' ? 'server' : window.location.pathname);
  const widthKey = `astral:table-widths:${suffix}`;
  const hiddenKey = `astral:table-hidden:${suffix}`;

  /** 用户拖拽出的列宽（挂载后从 localStorage 恢复） */
  const [widths, setWidths] = useState<Record<ColumnKey, number>>({});
  /** 用户隐藏的列 */
  const [hidden, setHidden] = useState<Set<ColumnKey>>(new Set());
  const [hoverKey, setHoverKey] = useState<ColumnKey | null>(null);
  const [containerWidth, setContainerWidth] = useState(0);
  const [isMobile, setIsMobile] = useState(false);
  const wrapRef = useRef<HTMLDivElement>(null);
  const widthRef = useRef<Record<ColumnKey, number>>({});

  // ---------- 列 + 稳定 key（key 必须按原始下标取，隐藏列后 key 仍稳定） ----------
  const colsWithKey = useMemo<Array<{ col: ColumnType<T>; key: ColumnKey }>>(() => {
    const list = (columns ?? []) as unknown as ColumnType<T>[];
    return list.map((col, index) => ({ col, key: colKeyOf(col, index) }));
  }, [columns]);

  useEffect(() => {
    try {
      const raw = window.localStorage.getItem(widthKey);
      if (raw) setWidths(JSON.parse(raw) as Record<ColumnKey, number>);
      const rawHidden = window.localStorage.getItem(hiddenKey);
      if (rawHidden) setHidden(new Set(JSON.parse(rawHidden) as ColumnKey[]));
    } catch {
      /* 缓存损坏时忽略，退回自动估算 */
    }
  }, [widthKey, hiddenKey]);

  useEffect(() => {
    if (Object.keys(widths).length === 0) return;
    try {
      window.localStorage.setItem(widthKey, JSON.stringify(widths));
    } catch {
      /* 存储不可用时静默失败 */
    }
  }, [widths, widthKey]);

  useEffect(() => {
    try {
      window.localStorage.setItem(hiddenKey, JSON.stringify(Array.from(hidden)));
    } catch {
      /* ignore */
    }
  }, [hidden, hiddenKey]);

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
  }, []);

  /** 表格模式列：拖拽手柄 + 百分比 / px 宽度 */
  const resolvedColumns = useMemo<ColumnsType<T>>(() => {
    return visibleCols.map(({ col, key }) => {
      const title = col.title as React.ReactNode;
      const titleNode: React.ReactNode = resizable ? (
        <span className="rt-th-title">
          {title}
          <span
            role="separator"
            aria-label="拖拽调整列宽"
            className="rt-th-resize"
            onPointerDown={(e) => startDrag(e, key)}
            onPointerEnter={() => setHoverKey(key)}
            onPointerLeave={() => setHoverKey(null)}
            style={{ background: hoverKey === key ? 'rgba(74, 111, 165, 0.35)' : 'transparent' }}
          />
        </span>
      ) : (
        title
      );
      const width: number | string =
        mode === 'fill' && totalPx > 0
          ? `${((effectiveWidths[key] / totalPx) * 100).toFixed(4)}%`
          : effectiveWidths[key];
      return { ...col, key, width, title: titleNode } as ColumnType<T>;
    });
  }, [visibleCols, effectiveWidths, mode, totalPx, resizable, hoverKey, startDrag]);

  /** 卡片模式列：同一份列定义，去掉拖拽手柄 */
  const cardColumns = useMemo<ColumnsType<T>>(
    () => visibleCols.map(({ col, key }) => ({ ...col, key }) as ColumnType<T>),
    [visibleCols],
  );

  /** scroll.y 透传（部分页面固定表体高度）；scroll.x 由本组件接管 */
  const scrollY = typeof scroll === 'object' && scroll ? scroll.y : undefined;
  const resolvedScroll: TableProps<T>['scroll'] =
    mode === 'scroll'
      ? scrollY !== undefined
        ? { x: totalPx, y: scrollY }
        : { x: totalPx }
      : scrollY !== undefined
        ? { y: scrollY }
        : undefined;

  /**
   * 统一空状态：
   * - 默认渲染 EmptyState，全项目列表页共享同一套空态视觉；页面可用 locale.emptyText 覆盖
   * - 加载中隐藏「暂无数据」：翻页 / 筛选触发 loading 时不显示空态，避免把「正在加载」误读成「没有数据」
   */
  const tableLocale = useMemo(() => ({
    ...locale,
    emptyText: loading
      ? ''
      : (locale?.emptyText ?? <EmptyState description="暂无数据" padding={20} ariaLabel="暂无数据" />),
  }), [locale, loading]);

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

  const resetWidths = () => setWidths({});
  const showAllCols = () => setHidden(new Set());

  const colSettingsPanel = (
    <div className="rt-cols-panel">
      <div className="rt-cols-head">
        <span>列设置</span>
        <Space size={0}>
          <Button type="link" size="small" className="rt-cols-act" onClick={showAllCols}>全部</Button>
          <Button type="link" size="small" className="rt-cols-act" icon={<ReloadOutlined />} onClick={resetWidths}>
            重置
          </Button>
        </Space>
      </div>
      <div className="rt-cols-list">
        {toggleableCols.map(({ col, key }) => (
          <label key={key} className="rt-cols-item">
            <Checkbox checked={!hidden.has(key)} onChange={() => toggleCol(key)} />
            <span className="rt-cols-name">{colTitleText(col)}</span>
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
            <Popover trigger="click" placement="bottomRight" content={colSettingsPanel}>
              <Button size="small" icon={<SlidersOutlined />}>列设置</Button>
            </Popover>
          </div>
        ) : null}
        <TableCardList<T>
          dataSource={rest.dataSource}
          columns={cardColumns}
          rowKey={rest.rowKey}
          loading={loading}
          pagination={rest.pagination}
        />
      </div>
    );
  }

  return (
    <div className={wrapperClass} ref={wrapRef}>
      {visibleCols.length >= COLS_SETTING_MIN ? (
        <div className="rt-toolbar">
          <Popover trigger="click" placement="bottomRight" content={colSettingsPanel}>
            <Button size="small" icon={<SlidersOutlined />}>列设置</Button>
          </Popover>
        </div>
      ) : null}
      <Table<T>
        {...rest}
        columns={resolvedColumns}
        locale={tableLocale}
        scroll={resolvedScroll}
        tableLayout="fixed"
        style={{ width: '100%', ...style }}
      />
    </div>
  );
}
