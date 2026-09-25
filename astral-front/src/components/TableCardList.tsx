'use client';

import React, { useMemo } from 'react';
import { Pagination, Spin } from 'antd';
import type { ColumnType, TableProps } from 'antd/es/table';
import { EmptyState } from '@/components/EmptyState';

/**
 * 表格的移动端卡片形态。
 *
 * 手机（≤768px）上 5 列以上的表格无论怎么压缩都不可读——列宽会掉到十几像素，
 * 内容竖向折行（8 位 ID 折成 4 行）或整段被截断。因此宽表在手机端把每一行
 * 换成「一张记录卡片」：前两列做卡片头，其余列以「字段名 + 值」网格平铺，
 * 操作列固定在右上角。
 *
 * 列的 render 函数原样复用，Tag / 按钮组 / 自定义渲染在卡片里同样有效；
 * ellipsis 列在卡片里显示完整值（不再截断），因为卡片高度不受列宽约束。
 */

type Cell = ColumnType<any>;

const titleOf = (col: Cell): string =>
  typeof col.title === 'string' ? col.title : typeof col.title === 'number' ? String(col.title) : '';

/** 操作列识别：固定 key 为 action，或表头文案含「操作」 */
const isActionCol = (col: Cell): boolean => col.key === 'action' || /操作|action/i.test(titleOf(col));

const valueOf = (record: any, dataIndex?: Cell['dataIndex']): unknown => {
  if (dataIndex == null) return undefined;
  if (Array.isArray(dataIndex)) {
    return dataIndex.reduce<any>((o, k) => (o == null ? undefined : o[k]), record);
  }
  return (record ?? {})[String(dataIndex)];
};

/** 空值：undefined / null / 空串 / 空数组 → 卡片里统一显示占位符 */
const isBlank = (v: unknown): boolean =>
  v === undefined || v === null || v === '' || (Array.isArray(v) && v.length === 0);

export interface TableCardListProps<T extends object> {
  dataSource?: TableProps<T>['dataSource'];
  columns?: TableProps<T>['columns'];
  rowKey?: TableProps<T>['rowKey'];
  loading?: TableProps<T>['loading'];
  pagination?: TableProps<T>['pagination'];
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
  const cols = columns as unknown as Cell[];

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

  const keyOf = (record: T, index: number): React.Key => {
    if (typeof rowKey === 'function') return String(rowKey(record));
    const v = (record as any)[rowKey];
    return isBlank(v) ? String(index) : String(v);
  };

  /** 单元格：优先列的 render，否则显示原始值；空值统一占位 */
  const renderCell = (col: Cell, record: T, index: number): React.ReactNode => {
    const value = valueOf(record, col.dataIndex);
    const rendered = col.render ? col.render(value as any, record, index) : value;
    return isBlank(rendered) ? <span className="rt-card-blank">—</span> : <>{rendered}</>;
  };

  const pageProps =
    pagination === false || typeof pagination !== 'object' || pagination === null ? null : pagination;
  const total = pageProps && typeof pageProps.total === 'number' ? pageProps.total : dataSource.length;

  // loading 可能是 boolean 或 SpinProps 对象；两种都视为「加载中」
  const isLoading = loading === true || (typeof loading === 'object' && loading !== null);

  return (
    <div className="rt-cards">
      <Spin spinning={isLoading}>
        {/* 加载中不显示空态：翻页/筛选清空数据时若同时显示「暂无数据」，
            用户会把「正在加载」误读成「没有数据」 */}
        {dataSource.length === 0 && !isLoading ? (
          <EmptyState description={emptyDescription} padding={28} ariaLabel={emptyDescription} />
        ) : (
          <div className="rt-card-list">
            {dataSource.map((record, index) => (
              <div className="rt-card" key={keyOf(record, index)}>
                <div className="rt-card-head">
                  <div className="rt-card-titles">
                    {headCols.map((col, i) => (
                      <div
                        key={titleOf(col) || i}
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
                      const label = titleOf(col);
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
      </Spin>

      {pageProps && total > 0 ? (
        <div className="rt-card-pagination">
          <Pagination
            {...pageProps}
            size="small"
            current={pageProps.current ?? 1}
            total={total}
            showSizeChanger={pageProps.showSizeChanger ?? false}
            hideOnSinglePage={total <= (pageProps.pageSize ?? 10)}
          />
        </div>
      ) : null}
    </div>
  );
}
