'use client';

import React, { useMemo } from 'react';
import { Loader2 } from 'lucide-react';

import { EmptyState } from '@/components/EmptyState';
import { TablePagination } from '@/components/data-table/TablePagination';
import {
  columnTitleText,
  isBlankValue,
  resolveCellValue,
  resolveRowKey,
  type DataTableColumn,
  type DataTablePagination,
} from '@/components/data-table/types';

/**
 * 表格的移动端卡片形态。
 *
 * 手机（≤768px）上 5 列以上的表格无论怎么压缩都不可读——列宽会掉到十几像素，
 * 内容竖向折行（8 位 ID 折成 4 行）或整段被截断。因此宽表在手机端把每一行
 * 换成「一张记录卡片」：前两列做卡片头，其余列以「字段名 + 值」网格平铺，
 * 操作列固定在右上角。
 *
 * 列的 render 函数原样复用，徽章 / 按钮组 / 自定义渲染在卡片里同样有效；
 * ellipsis 列在卡片里显示完整值（不再截断），因为卡片高度不受列宽约束。
 * 渲染层已由 antd 迁移到 shadcn/ui + Tailwind。
 */

/** 操作列识别：固定 key 为 action，或表头文案含「操作」 */
const isActionCol = <T,>(col: DataTableColumn<T>): boolean =>
  col.key === 'action' || /操作|action/i.test(columnTitleText(col));

export interface TableCardListProps<T extends object> {
  dataSource?: readonly T[];
  columns?: Array<DataTableColumn<T>>;
  rowKey?: string | ((record: T) => string | number);
  loading?: boolean | { spinning?: boolean };
  pagination?: false | DataTablePagination;
  /** 卡片头展示的列数（默认 2：第一列做主标题，第二列做副标题） */
  headColumns?: number;
  /** 空态文案 */
  emptyDescription?: string;
}

export function TableCardList<T extends object>({
  dataSource = [],
  columns = [],
  rowKey = 'id',
  loading,
  pagination,
  headColumns = 2,
  emptyDescription = '暂无数据',
}: TableCardListProps<T>) {
  const cols = columns;

  /** 操作列独立于卡片头 / 网格之外，固定显示在卡片右上角 */
  const { actionCol, headCols, detailCols } = useMemo(() => {
    const action = cols.find(isActionCol);
    const rest = cols.filter((c) => c !== action);
    return {
      actionCol: action,
      headCols: rest.slice(0, headColumns),
      detailCols: rest.slice(headColumns),
    };
  }, [cols, headColumns]);

  /** 单元格：优先列的 render，否则显示原始值；空值统一占位 */
  const renderCell = (col: DataTableColumn<T>, record: T, index: number): React.ReactNode => {
    const value = resolveCellValue(record, col.dataIndex);
    const rendered = col.render ? col.render(value, record, index) : (value as React.ReactNode);
    return isBlankValue(rendered) ? <span className="rt-card-blank">—</span> : <>{rendered}</>;
  };

  const pageProps = pagination ? pagination : null;
  const total = pageProps?.total ?? dataSource.length;

  // loading 可能是 boolean 或对象；两种都视为「加载中」
  const isLoading = loading === true || (typeof loading === 'object' && loading !== null && loading !== undefined);

  return (
    <div className="rt-cards relative">
      {isLoading && (
        <div className="absolute inset-x-0 top-0 z-[2] flex justify-center pt-6">
          <Loader2 className="size-6 animate-spin text-muted-foreground" />
        </div>
      )}

      {/* 加载中不显示空态：翻页/筛选清空数据时若同时显示「暂无数据」，
          用户会把「正在加载」误读成「没有数据」 */}
      {dataSource.length === 0 && !isLoading ? (
        <EmptyState description={emptyDescription} padding={28} ariaLabel={emptyDescription} />
      ) : (
        <div className="rt-card-list">
          {dataSource.map((record, index) => (
            <div className="rt-card" key={resolveRowKey(record, index, rowKey)}>
              <div className="rt-card-head">
                <div className="rt-card-titles">
                  {headCols.map((col, i) => (
                    <div
                      key={columnTitleText(col) || i}
                      className={i === 0 ? 'rt-card-title' : 'rt-card-subtitle'}
                    >
                      {renderCell(col, record, index)}
                    </div>
                  ))}
                </div>
                {actionCol ? <div className="rt-card-action">{renderCell(actionCol, record, index)}</div> : null}
              </div>
              {detailCols.length > 0 ? (
                <dl className="rt-card-grid">
                  {detailCols.map((col, i) => {
                    const label = columnTitleText(col);
                    return (
                      <div className="rt-card-item" key={label || i}>
                        <dt className="rt-card-label">{label}</dt>
                        <dd className="rt-card-value">{renderCell(col, record, index)}</dd>
                      </div>
                    );
                  })}
                </dl>
              ) : null}
            </div>
          ))}
        </div>
      )}

      {pageProps && total > 0 && (
        <div className="rt-card-pagination">
          <TablePagination
            pagination={{
              ...pageProps,
              hideOnSinglePage: total <= (pageProps.pageSize ?? 10),
            }}
            total={total}
          />
        </div>
      )}
    </div>
  );
}
