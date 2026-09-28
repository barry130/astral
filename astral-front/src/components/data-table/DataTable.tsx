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
 * 已实现的能力：本地排序、展开行、行点击、省略号、对齐、列宽、
 * 空态、加载遮罩、表体纵向滚动。
 */

/** 对齐 / 省略号 / 宽度 的单元格类名拼装 */
function cellClass<T>(col: DataTableColumn<T>): string {
  return cn(
    col.align === 'center' && 'text-center',
    col.align === 'right' && 'text-right',
    col.ellipsis && 'max-w-0 truncate',
    col.className,
  );
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

  const total = pagination ? (pagination.total ?? rows.length) : rows.length;

  return (
    <div className={cn('relative', className)}>
      <div
        className={cn(
          'relative overflow-hidden',
          bordered && 'rounded-lg border border-border',
        )}
      >
        <div className="overflow-x-auto" style={scrollY ? { maxHeight: scrollY, overflowY: 'auto' } : undefined}>
          <Table>
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

            <TableHeader className="sticky top-0 z-[1] bg-[var(--color-bg-base)]">
              <TableRow className="hover:bg-transparent">
                {hasExpand && <TableHead style={{ width: 40 }} />}
                {columns.map((col, i) => {
                  const key = resolveColumnKey(col, i);
                  const sortable = !!col.sorter;
                  const active = sortKey === key;
                  return (
                    <TableHead
                      key={key}
                      className={cn(
                        dense && 'h-8 px-2',
                        col.align === 'center' && 'text-center',
                        col.align === 'right' && 'text-right',
                        col.ellipsis && 'max-w-0 truncate',
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
                <TableRow className="hover:bg-transparent">
                  <TableCell colSpan={columns.length + (hasExpand ? 1 : 0)} className="p-0">
                    {emptyText ?? <EmptyState description="暂无数据" padding={20} ariaLabel="暂无数据" />}
                  </TableCell>
                </TableRow>
              ) : (
                sortedRows.map((record, index) => {
                  const key = resolveRowKey(record, index, rowKey);
                  const rowProps = onRow?.(record, index);
                  const isExpanded = expandedKeys.has(key);
                  return (
                    <Fragment key={key}>
                      <TableRow
                        {...rowProps}
                        className={cn(rowProps?.className, rowProps?.onClick && 'cursor-pointer')}
                      >
                        {hasExpand && (
                          <TableCell className={cn('pr-0', dense && 'px-2')}>
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
                          return (
                            <TableCell
                              key={resolveColumnKey(col, i)}
                              className={cn(cellClass(col), dense && 'p-2', extra?.className)}
                              title={extra?.title ?? (col.ellipsis && typeof rendered === 'string' ? rendered : undefined)}
                            >
                              {isBlankValue(rendered) ? <span className="text-muted-foreground/60">—</span> : (rendered as React.ReactNode)}
                            </TableCell>
                          );
                        })}
                      </TableRow>
                      {hasExpand && isExpanded && (!expandable!.rowExpandable || expandable!.rowExpandable(record)) && (
                        <TableRow className="hover:bg-transparent">
                          <TableCell colSpan={columns.length + 1} className="bg-[var(--color-bg-base)] p-4">
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
          <div className="absolute inset-0 z-[2] flex items-start justify-center bg-background/60 pt-16">
            <Loader2 className="size-6 animate-spin text-muted-foreground" />
          </div>
        )}
      </div>

      {pagination && <TablePagination pagination={pagination} total={total} />}
    </div>
  );
}
