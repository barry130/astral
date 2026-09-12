'use client';

import React from 'react';
import { Skeleton } from 'antd';

/**
 * 表格骨架屏
 *
 * 首次加载（尚无数据）时使用，比「暂无数据 + 转圈」更准确地表达「还在加载」。
 * 翻页 / 筛选触发的二次加载仍建议用 Table 的 loading（会保留已有数据）。
 */
export interface TableSkeletonProps {
  /** 数据行数 */
  rows?: number;
  /** 列数 */
  columns?: number;
  /** 为 true 时渲染；false 不渲染任何内容 */
  loading?: boolean;
  /** 首行渲染为表头样式 */
  withHeader?: boolean;
}

export function TableSkeleton({
  rows = 8,
  columns = 6,
  loading = true,
  withHeader = true,
}: TableSkeletonProps) {
  if (!loading) return null;

  const totalRows = withHeader ? rows + 1 : rows;

  return (
    <div role="status" aria-busy="true" aria-label="数据加载中">
      {Array.from({ length: totalRows }).map((_, r) => (
        <div
          key={r}
          style={{
            display: 'flex',
            gap: 16,
            padding: '9px 14px',
            fontWeight: withHeader && r === 0 ? 600 : 400,
          }}
        >
          {Array.from({ length: columns }).map((_, c) => (
            <Skeleton
              key={c}
              active
              title={false}
              paragraph={{ rows: 1, width: c === 0 ? '34%' : '14%' }}
              style={{ margin: 0, width: c === 0 ? '34%' : '14%' }}
            />
          ))}
        </div>
      ))}
    </div>
  );
}
