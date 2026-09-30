'use client';

import { ChevronLeft, ChevronRight, ChevronsLeft, ChevronsRight } from 'lucide-react';
import { useEffect, useState } from 'react';

import { cn } from '@/lib/utils';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import type { DataTablePagination } from './types';

/**
 * 自研分页器（替代 antd Pagination）。
 *
 * 覆盖项目实际用到的能力：页码、每页条数切换、快速跳页、单页隐藏。
 * 分页是受控组件——页码不动本地 state，全部通过 onChange 回调上抛，
 * 保持与迁移前「服务端分页」的既有语义一致。
 */

const DEFAULT_PAGE_SIZES = ['10', '20', '50', '100'];

interface TablePaginationProps {
  pagination: DataTablePagination;
  /** 数据总条数（缺省时取 pagination.total） */
  total: number;
  className?: string;
}

/** 生成页码序列：首页、末页、当前及邻页，其余折叠为省略号（'...'） */
function buildPageList(current: number, pageCount: number): Array<number | '...'> {
  if (pageCount <= 7) return Array.from({ length: pageCount }, (_, i) => i + 1);
  const pages = new Set<number>([1, pageCount, current, current - 1, current + 1]);
  const sorted = Array.from(pages)
    .filter((p) => p >= 1 && p <= pageCount)
    .sort((a, b) => a - b);
  const result: Array<number | '...'> = [];
  sorted.forEach((p, i) => {
    if (i > 0 && p - (sorted[i - 1] as number) > 1) result.push('...');
    result.push(p);
  });
  return result;
}

export function TablePagination({ pagination, total, className }: TablePaginationProps) {
  const pageSize = pagination.pageSize ?? 10;
  const current = pagination.current ?? 1;
  const pageCount = Math.max(1, Math.ceil((total || 0) / Math.max(1, pageSize)));
  const pageSizes = pagination.pageSizeOptions ?? DEFAULT_PAGE_SIZES;

  /** 快速跳页输入框的本地值（未提交前不触发请求） */
  const [jumpValue, setJumpValue] = useState('');

  useEffect(() => {
    setJumpValue('');
  }, [current, pageSize]);

  if (pagination.hideOnSinglePage && pageCount <= 1) return null;

  const goto = (page: number) => {
    const next = Math.min(Math.max(1, page), pageCount);
    if (next === current) return;
    pagination.onChange?.(next, pageSize);
  };

  const changePageSize = (nextSize: number) => {
    // 换页长后回到第 1 页，避免越界到空页
    pagination.onShowSizeChange?.(1, nextSize);
    pagination.onChange?.(1, nextSize);
  };

  return (
    /* 与表格之间用一条分隔线连接，让分页器成为表格的一部分而不是漂浮在下方；
       分隔线颜色与表体行间线同源（border-border/60），
       DataTable 已把「最后一行」的底线去掉，这里不会出现双线 */
    <div className={cn('border-border/60 flex flex-wrap items-center justify-end gap-3 border-t py-3 text-sm', className)}>
      <span className="text-muted-foreground">
        {pagination.showTotal
          ? pagination.showTotal(total, [(current - 1) * pageSize + 1, Math.min(current * pageSize, total)])
          : `共 ${total} 条`}{' '}
        · 第 {current}/{pageCount} 页
      </span>

      {pagination.showSizeChanger && (
        <label className="flex items-center gap-1.5 text-muted-foreground">
          每页
          <select
            value={String(pageSize)}
            onChange={(e) => changePageSize(Number(e.target.value))}
            className="h-8 cursor-pointer rounded-md border border-input bg-transparent px-2 text-sm outline-none focus-visible:border-ring focus-visible:ring-[3px] focus-visible:ring-ring/50"
            aria-label="每页条数"
          >
            {pageSizes.map((s) => (
              <option key={s} value={s}>
                {s}
              </option>
            ))}
          </select>
          条
        </label>
      )}

      <div className="flex items-center gap-1">
        <Button
          variant="outline"
          size="icon"
          className="size-8"
          disabled={current <= 1}
          onClick={() => goto(1)}
          aria-label="首页"
        >
          <ChevronsLeft className="size-4" />
        </Button>
        <Button
          variant="outline"
          size="icon"
          className="size-8"
          disabled={current <= 1}
          onClick={() => goto(current - 1)}
          aria-label="上一页"
        >
          <ChevronLeft className="size-4" />
        </Button>

        {buildPageList(current, pageCount).map((p, i) =>
          p === '...' ? (
            <span key={`gap-${i}`} className="px-1 text-muted-foreground">
              …
            </span>
          ) : (
            <Button
              key={p}
              variant={p === current ? 'default' : 'outline'}
              size="icon"
              className="size-8 tabular-nums"
              onClick={() => goto(p)}
              aria-current={p === current ? 'page' : undefined}
            >
              {p}
            </Button>
          ),
        )}

        <Button
          variant="outline"
          size="icon"
          className="size-8"
          disabled={current >= pageCount}
          onClick={() => goto(current + 1)}
          aria-label="下一页"
        >
          <ChevronRight className="size-4" />
        </Button>
        <Button
          variant="outline"
          size="icon"
          className="size-8"
          disabled={current >= pageCount}
          onClick={() => goto(pageCount)}
          aria-label="末页"
        >
          <ChevronsRight className="size-4" />
        </Button>

        {pagination.showQuickJumper && (
          <label className="ml-1 flex items-center gap-1.5 text-muted-foreground">
            跳至
            <Input
              className="h-8 w-14 text-center"
              value={jumpValue}
              onChange={(e) => setJumpValue(e.target.value.replace(/[^\d]/g, ''))}
              onKeyDown={(e) => {
                if (e.key === 'Enter' && jumpValue) goto(Number(jumpValue));
              }}
              onBlur={() => {
                if (jumpValue) goto(Number(jumpValue));
              }}
              aria-label="跳至页码"
            />
            页
          </label>
        )}
      </div>
    </div>
  );
}
