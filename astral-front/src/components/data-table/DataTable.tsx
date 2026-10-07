'use client';

import { Fragment, useEffect, useMemo, useState } from 'react';
import { ChevronRight, Loader2, ArrowUp, ArrowDown, ArrowUpDown } from 'lucide-react';

import { cn } from '@/lib/utils';
import { EmptyState } from '@/components/EmptyState';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';
import { TablePagination } from './TablePagination';
import {
  columnTitleText,
  isBlankValue,
  resolveCellValue,
  resolveColumnKey,
  resolveRowKey,
  type DataTableColumn,
  type DataTableProps,
} from './types';

/**
 * 自研数据表格（替代 antd Table 的渲染层）。
 *
 * 与 antd 的差异点，均为项目内未使用的特性，故刻意不实现：
 * 虚拟滚动、固定表头组、筛选下拉、服务端排序、单元格可编辑。
 * 已实现的能力：本地排序、展开行、**树形数据（依 `children` 字段自动识别）**、
 * 行点击、省略号、对齐、列宽、空态、加载遮罩、表体纵向滚动。
 */

/**
 * 对齐 / 省略号 / 宽度 的单元格类名拼装。
 * `numeric` 为自动识别出的数值列（见 resolveNumericColumn）：右对齐并锁定等宽数字，
 * 保证多行数字的个位对齐——这是表格「看起来专业」的关键一条。
 */
function cellClass<T>(col: DataTableColumn<T>, numeric = false, fixedLayout = false): string {
  return cn(
    col.align === 'center' && 'text-center',
    (col.align === 'right' || numeric) && 'text-right tabular-nums',
    // max-w-0 是 table-layout:auto 下的收缩 hack（让省略列可被压缩）；
    // fixed 布局下列宽由 colgroup 决定，max-w-0 会把列压瘪，只留 truncate
    col.ellipsis && (fixedLayout ? 'truncate' : 'max-w-0 truncate'),
    col.className,
  );
}

/**
 * 数值列自动识别：列已显式声明 align 时不介入（尊重调用方）；
 * 否则抽样该列前若干行的**原始值**，全为 number 才判为数值列。
 *
 * 之所以取原始值而非渲染结果：页面常写 `render: (v) => fmt(v)` 把数字格式化成
 * 带千分位的字符串，按渲染结果判断会漏判。
 * 之所以要求「全部样本都是 number」：混入任意文本就说明该列不是纯数量列
 * （如「版本号」「编号」这类标识），此时右对齐反而难读。
 */
function resolveNumericColumn<T>(col: DataTableColumn<T>, rows: readonly T[]): boolean {
  if (col.align) return false;
  let seen = 0;
  for (let i = 0; i < rows.length && seen < 20; i += 1) {
    const value = resolveCellValue(rows[i], col.dataIndex);
    if (isBlankValue(value)) continue;
    if (typeof value !== 'number') return false;
    seen += 1;
  }
  return seen > 0;
}

/**
 * 树形子节点解析（antd 树形表格的默认字段名就是 `children`）。
 *
 * 空数组按「无子节点」处理：菜单/权限这类树常把叶子写成 `children: []`，
 * 若按「有 children 就是父节点」判定，叶子行会渲染出一个点了没反应的箭头。
 */
function resolveChildren<T>(record: T): T[] | undefined {
  const kids = (record as { children?: unknown }).children;
  return Array.isArray(kids) && kids.length > 0 ? (kids as T[]) : undefined;
}

export function DataTable<T extends object>({
  columns = [],
  dataSource = [],
  rowKey,
  loading,
  pagination,
  expandable,
  defaultExpandAllRows = false,
  size = 'middle',
  scrollY,
  emptyText,
  onRow,
  className,
  bordered = true,
}: DataTableProps<T>) {
  /** 静态排序状态（本地比较器排序；服务端排序由页面自行处理数据） */
  const [sortKey, setSortKey] = useState<string | null>(null);
  const [sortOrder, setSortOrder] = useState<'ascend' | 'descend'>('ascend');
  /** 展开行内部状态（未受控时使用） */
  const [innerExpanded, setInnerExpanded] = useState<Set<string>>(new Set());

  const rows = dataSource;

  /** 排序后的数据（仅有 sorter 的列参与） */
  const sortedRows = useMemo(() => {
    if (!sortKey) return rows;
    const idx = columns.findIndex((c, i) => resolveColumnKey(c, i) === sortKey);
    const col = columns[idx];
    if (!col?.sorter) return rows;
    const next = [...rows].sort(col.sorter);
    return sortOrder === 'descend' ? next.reverse() : next;
  }, [rows, columns, sortKey, sortOrder]);

  /** 数值列标记（与 columns 同序）：用于表头与单元格同步右对齐 */
  const numericCols = useMemo(() => columns.map((col) => resolveNumericColumn(col, rows)), [columns, rows]);

  /** 初始展开全部行：数据就绪后统一置为展开 */
  useEffect(() => {
    if (!defaultExpandAllRows || !expandable?.expandedRowRender) return;
    if (expandable.expandedRowKeys) return;
    setInnerExpanded(new Set(rows.map((r, i) => resolveRowKey(r, i, rowKey))));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [defaultExpandAllRows, rows.length, rowKey]);

  /** 表头点击：升序 → 降序 → 取消（与 antd 默认三态一致） */
  const toggleSort = (key: string) => {
    if (sortKey !== key) {
      setSortKey(key);
      setSortOrder('ascend');
      return;
    }
    if (sortOrder === 'ascend') {
      setSortOrder('descend');
      return;
    }
    setSortKey(null);
  };

  const expandedKeys = useMemo(() => {
    if (expandable?.expandedRowKeys) {
      return new Set(expandable.expandedRowKeys.map(String));
    }
    return innerExpanded;
  }, [expandable?.expandedRowKeys, innerExpanded]);

  const toggleExpand = (key: string, record: T) => {
    const isOpen = expandedKeys.has(key);
    if (!expandable?.expandedRowKeys) {
      setInnerExpanded((prev) => {
        const next = new Set(prev);
        if (isOpen) next.delete(key);
        else next.add(key);
        return next;
      });
    }
    expandable?.onExpand?.(!isOpen, record);
  };

  const dense = size === 'small';
  const isLoading = loading === true || (typeof loading === 'object' && loading?.spinning !== false && loading !== undefined);
  const hasExpand = !!expandable?.expandedRowRender;

  /**
   * 树形展开状态：与「展开详情行」的 innerExpanded **分开维护**。
   * 两者语义不同（一个是层级展开、一个是行下详情面板），
   * 共用一个 Set 时同一 key 会被两种展开互相干扰。
   */
  const [innerTreeExpanded, setInnerTreeExpanded] = useState<Set<string>>(new Set());

  /**
   * 是否树形数据：任一行带非空 `children` 即按树渲染（与 antd 同判据，无需额外开关）。
   * 非树形数据仍走原来的平铺路径，因此对存量列表页零影响。
   */
  const hasTreeData = useMemo(() => sortedRows.some((r) => resolveChildren(r) !== undefined), [sortedRows]);

  /**
   * 树形缩进与箭头所在的列：**第一个带 dataIndex 的列**。
   * 刻意不取「第 0 列」：列表常把拖拽手柄、选择框这类不承载数据的列排在首位
   * （菜单管理即「拖拽 | 菜单名称 | …」），箭头落进 50px 的手柄列会被挤扁。
   * 与 antd 一致——树形缩进作用在首个承载数据的列上。
   */
  const treeColumnIndex = useMemo(() => {
    const i = columns.findIndex((c) => c.dataIndex !== undefined && c.dataIndex !== null);
    return i >= 0 ? i : 0;
  }, [columns]);

  /**
   * 树 key：rowKey 缺省时用「父路径 + 本层下标」。
   * 直接用下标会在不同层级撞 key（父级第 0 个和子级第 0 个都叫 "0"），
   * 展开其中一个会把另一个也一起展开。
   */
  const treeKeyOf = (record: T, index: number, parentKey: string) =>
    rowKey === undefined ? `${parentKey}${index}/` : resolveRowKey(record, index, rowKey);

  /** 初始展开全部树节点（`defaultExpandAllRows` 对树形数据同样生效） */
  useEffect(() => {
    if (!defaultExpandAllRows || !hasTreeData) return;
    const keys: string[] = [];
    const walk = (nodes: readonly T[], parentKey: string) => {
      nodes.forEach((record, index) => {
        const kids = resolveChildren(record);
        if (!kids) return;
        const key = treeKeyOf(record, index, parentKey);
        keys.push(key);
        walk(kids, `${key}/`);
      });
    };
    walk(sortedRows, '');
    setInnerTreeExpanded(new Set(keys));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [defaultExpandAllRows, hasTreeData, sortedRows, rowKey]);

  /**
   * 可见行：顶层行按展开状态递归展开后的扁平序列（depth 供首列缩进用）。
   * 非树形数据等价于 sortedRows 的逐行映射，key 与原实现完全一致
   * （必须一致——`innerExpanded` 里存的就是 resolveRowKey 的结果）。
   */
  const visibleRows = useMemo(() => {
    type TreeRow = { record: T; index: number; key: string; depth: number; hasChildren: boolean };
    if (!hasTreeData) {
      return sortedRows.map<TreeRow>((record, index) => ({
        record,
        index,
        key: resolveRowKey(record, index, rowKey),
        depth: 0,
        hasChildren: false,
      }));
    }
    const out: TreeRow[] = [];
    const walk = (nodes: readonly T[], depth: number, parentKey: string) => {
      nodes.forEach((record, index) => {
        const kids = resolveChildren(record);
        const key = rowKey === undefined ? `${parentKey}${index}/` : resolveRowKey(record, index, rowKey);
        out.push({ record, index, key, depth, hasChildren: !!kids });
        if (kids && innerTreeExpanded.has(key)) walk(kids, depth + 1, `${key}/`);
      });
    };
    walk(sortedRows, 0, '');
    return out;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sortedRows, hasTreeData, rowKey, innerTreeExpanded]);

  const toggleTree = (key: string) => {
    setInnerTreeExpanded((prev) => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });
  };

  const total = pagination ? (pagination.total ?? rows.length) : rows.length;

  /**
   * 全列显式宽 → 固定布局（colgroup 是硬约束）。
   * ellipsis 列的收缩 hack（max-w-0）只在 auto 布局下需要——fixed 下它会反过来
   * 把列压瘪（宽屏 fill 模式百分比列宽 + max-w-0，配置键实测只剩表头宽），
   * 固定布局里省略只需 truncate，列宽由 <col> 保证。
   */
  const isFixedLayout = columns.length > 0 && columns.every((c) => c.width !== undefined);

  return (
    <div className={cn('relative', className)}>
      <div
        className={cn(
          'relative overflow-hidden',
          bordered &&
            'rounded-xl border border-border bg-card shadow-[0_1px_3px_rgba(24,24,27,0.05)] dark:shadow-none',
        )}
      >
        <div className="overflow-x-auto" style={scrollY ? { maxHeight: scrollY, overflowY: 'auto' } : undefined}>
          <Table
            className={
              // 所有列都声明了宽度时启用固定布局：table-layout:auto 下 <col> 宽度只是建议，
              // 浏览器会按单元格内容再分配——内容长的列（如描述）抢宽，省略号列被挤到只剩
              // 几像素（系统配置页「配置值」声明 200px 实测只分到 71px）。fixed 布局下
              // colgroup 宽度是硬约束，超宽内容按各列自身的 ellipsis/wrap 策略处理。
              // rt-body-wrap：底座 TableCell 自带 whitespace-nowrap，fixed 下非 ellipsis
              // 单元格的超宽文本会溢出画到相邻单元格上（表结构管理「注释」压到「类名」芯片
              // 实测），globals.css 里对该标记表格做单行裁切（不折行、不压邻格）。
              isFixedLayout ? 'table-fixed rt-body-wrap' : undefined
            }
          >
            {/* 列宽：fill 模式由 ResizableTable 传百分比，scroll 模式传 px */}
            <colgroup>
              {hasExpand && <col style={{ width: 40 }} />}
              {columns.map((col, i) => (
                <col
                  key={resolveColumnKey(col, i)}
                  style={
                    col.width !== undefined
                      ? { width: typeof col.width === 'number' ? `${col.width}px` : col.width }
                      : undefined
                  }
                />
              ))}
            </colgroup>

            {/* 表头吸顶：底色必须实心，否则滚动时行内容会从表头下方透出 */}
            <TableHeader className="sticky top-0 z-[1] bg-muted">
              {/* hover:bg-transparent 用 important 后缀：表头行不该响应数据行的悬停底色，
                  两者同属 hover 变体的 background-color，不加 ! 会由 Tailwind 的输出顺序决定谁生效 */}
              <TableRow className="hover:bg-transparent!">
                {hasExpand && <TableHead style={{ width: 40 }} />}
                {columns.map((col, i) => {
                  const key = resolveColumnKey(col, i);
                  const sortable = !!col.sorter;
                  const active = sortKey === key;
                  return (
                    <TableHead
                      key={key}
                      className={cn(
                        dense && 'h-9 px-3',
                        col.align === 'center' && 'text-center',
                        (col.align === 'right' || numericCols[i]) && 'text-right',
                        // 表头不参与省略：ellipsis 列在 table-layout:auto 下若表头也 max-w-0，
                        // min-content 归零，列会被其它不可收缩的列挤压到只剩一个字
                        // （参数管理页「配置值」实测）。省略只作用于正文单元格。
                      )}
                      aria-sort={active ? (sortOrder === 'ascend' ? 'ascending' : 'descending') : undefined}
                    >
                      {sortable ? (
                        <button
                          type="button"
                          onClick={() => toggleSort(key)}
                          className={cn(
                            'inline-flex cursor-pointer items-center gap-1 hover:text-foreground',
                            active && 'text-foreground',
                          )}
                          title={`按「${columnTitleText(col)}」排序`}
                        >
                          {col.title}
                          {active ? (
                            sortOrder === 'ascend' ? (
                              <ArrowUp className="size-3" />
                            ) : (
                              <ArrowDown className="size-3" />
                            )
                          ) : (
                            <ArrowUpDown className="size-3 opacity-40" />
                          )}
                        </button>
                      ) : (
                        col.title
                      )}
                    </TableHead>
                  );
                })}
              </TableRow>
            </TableHeader>

            <TableBody>
              {sortedRows.length === 0 && !isLoading ? (
                <TableRow className="hover:bg-transparent!">
                  <TableCell colSpan={columns.length + (hasExpand ? 1 : 0)} className="p-0">
                    {emptyText ?? <EmptyState description="暂无数据" padding={20} ariaLabel="暂无数据" />}
                  </TableCell>
                </TableRow>
              ) : (
                visibleRows.map(({ record, index, key, depth, hasChildren }) => {
                  const rowProps = onRow?.(record, index);
                  const isExpanded = expandedKeys.has(key);
                  return (
                    <Fragment key={key}>
                      <TableRow
                        {...rowProps}
                        className={cn(rowProps?.className, rowProps?.onClick && 'cursor-pointer')}
                      >
                        {hasExpand && (
                          <TableCell className={cn('pr-0', dense && 'px-3')}>
                            {(!expandable!.rowExpandable || expandable!.rowExpandable(record)) && (
                              <button
                                type="button"
                                onClick={(e) => {
                                  e.stopPropagation();
                                  toggleExpand(key, record);
                                }}
                                aria-expanded={isExpanded}
                                aria-label={isExpanded ? '收起' : '展开'}
                                className="flex size-6 cursor-pointer items-center justify-center rounded-sm hover:bg-accent"
                              >
                                <ChevronRight className={cn('size-4 transition-transform', isExpanded && 'rotate-90')} />
                              </button>
                            )}
                          </TableCell>
                        )}
                        {columns.map((col, i) => {
                          const value = resolveCellValue(record, col.dataIndex);
                          const rendered = col.render ? col.render(value, record, index) : (value as React.ReactNode);
                          const extra = col.onCell?.(record, index);
                          /**
                           * 树形缩进与展开箭头都落在**首列**（antd 树形表格的呈现方式）：
                           * 箭头放首列内而不是单开一列，叶子多的树才不会出现一列空箭头。
                           */
                          const treeCell = hasTreeData && i === treeColumnIndex;
                          return (
                            <TableCell
                              key={resolveColumnKey(col, i)}
                              className={cn(cellClass(col, numericCols[i], isFixedLayout), dense && 'px-3 py-1.5', extra?.className)}
                              title={extra?.title ?? (col.ellipsis && typeof rendered === 'string' ? rendered : undefined)}
                              style={treeCell && depth > 0 ? { paddingLeft: 12 + depth * 16 } : undefined}
                            >
                              {treeCell &&
                                (hasChildren ? (
                                  <button
                                    type="button"
                                    onClick={(e) => {
                                      e.stopPropagation();
                                      toggleTree(key);
                                    }}
                                    aria-expanded={innerTreeExpanded.has(key)}
                                    aria-label={innerTreeExpanded.has(key) ? '收起子菜单' : '展开子菜单'}
                                    className="mr-1 inline-flex size-5 shrink-0 cursor-pointer items-center justify-center rounded-sm align-middle hover:bg-accent"
                                  >
                                    <ChevronRight
                                      className={cn(
                                        'size-3.5 transition-transform',
                                        innerTreeExpanded.has(key) && 'rotate-90',
                                      )}
                                    />
                                  </button>
                                ) : (
                                  /* 叶子行占位：与箭头等宽，保证同层文字左边缘对齐 */
                                  <span className="mr-1 inline-block size-5 shrink-0 align-middle" aria-hidden />
                                ))}
                              {isBlankValue(rendered) ? <span className="text-muted-foreground/60">—</span> : (rendered as React.ReactNode)}
                            </TableCell>
                          );
                        })}
                      </TableRow>
                      {hasExpand && isExpanded && (!expandable!.rowExpandable || expandable!.rowExpandable(record)) && (
                        <TableRow className="hover:bg-transparent!">
                          <TableCell colSpan={columns.length + 1} className="bg-muted/60 p-4">
                            {expandable!.expandedRowRender!(record, index)}
                          </TableCell>
                        </TableRow>
                      )}
                    </Fragment>
                  );
                })
              )}
            </TableBody>
          </Table>
        </div>

        {/* 加载遮罩：二次加载时保留既有数据，避免表格高度跳变 */}
        {isLoading && (
          <div className="bg-background/65 absolute inset-0 z-[2] flex items-start justify-center pt-16 backdrop-blur-[1px]">
            <Loader2 className="size-6 animate-spin text-muted-foreground" />
          </div>
        )}
      </div>

      {pagination && <TablePagination pagination={pagination} total={total} />}
    </div>
  );
}
