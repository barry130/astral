import * as React from 'react';

import { cn } from '@/lib/utils';

/** 样式化原生表格（shadcn table）：配合业务侧自建分页/排序逻辑使用 */
function Table({ className, ...props }: React.ComponentProps<'table'>) {
  return (
    <div data-slot="table-container" className="relative w-full overflow-x-auto">
      <table data-slot="table" className={cn('w-full caption-bottom text-sm', className)} {...props} />
    </div>
  );
}

/**
 * 表头：底部分界线刻意保持 --color-border（#e4e4e7）的常规深浅，不跟着行间线一起调浅。
 * 表头只有 bg-muted 一层极淡底色（#f7f7f8 对 #ffffff），若连分界线也压到 #f0f0f1，
 * 表头与表体就会糊成一片、整张表失去结构感——这是上一轮改动踩过的坑。
 */
function TableHeader({ className, ...props }: React.ComponentProps<'thead'>) {
  return (
    <thead
      data-slot="table-header"
      className={cn('[&_tr]:border-b [&_tr]:border-border', className)}
      {...props}
    />
  );
}

/**
 * 表体：行分隔线统一由这里下发（而不是 TableRow 自带），
 * 这样「表头线」与「行间线」的颜色来源唯一、不会互相覆盖。
 *
 * 行间线取 --color-border-light（浅色 #f0f0f1 / 暗色 #212731），比表头线浅一档：
 * 表头线是 --color-border（#e4e4e7），行间线是 --color-border-light（#f0f0f1）。
 * 两者刻意不同色——表头只有 bg-muted（#f7f7f8）这一层极淡底色，
 * 若行间线也压到同色，表头与表体就糊成一片、整张表失去结构感。
 */
function TableBody({ className, ...props }: React.ComponentProps<'tbody'>) {
  return (
    <tbody
      data-slot="table-body"
      className={cn(
        '[&_tr]:border-b [&_tr]:border-[color:var(--color-border-light)] [&_tr:last-child]:border-0',
        className,
      )}
      {...props}
    />
  );
}

function TableFooter({ className, ...props }: React.ComponentProps<'tfoot'>) {
  return (
    <tfoot
      data-slot="table-footer"
      className={cn('bg-muted/50 border-t font-medium [&>tr]:last:border-b-0', className)}
      {...props}
    />
  );
}

/**
 * 数据行：行间用「淡分隔线」而不是重边框，悬停给一层比表头更明显的浅灰底。
 * 分隔线的宽度与颜色由 TableHeader / TableBody 下发（见上），本组件不写 border，
 * 否则会在同一元素上产生两个 border-color 声明、互相覆盖。
 * 不采用斑马纹——本项目是极简中性色系，斑马纹在长表格里反而显得脏，
 * 且会和 hover / 选中态互相干扰。
 */
function TableRow({ className, ...props }: React.ComponentProps<'tr'>) {
  return (
    <tr
      data-slot="table-row"
      className={cn('hover:bg-accent/60 transition-colors', className)}
      {...props}
    />
  );
}

/**
 * 表头单元格：13px / 600 字重 / 次级文字色。
 * 字色用 --color-text-secondary（浅色 #52525b、暗色 #9aa3b0，两套主题都有 ≥4.5:1），
 * 比 text-muted-foreground 更实，表头才有「标题」的分量。
 */
function TableHead({ className, ...props }: React.ComponentProps<'th'>) {
  return (
    <th
      data-slot="table-head"
      className={cn(
        'text-[color:var(--color-text-secondary)] h-11 px-4 text-left align-middle text-[13px] font-semibold whitespace-nowrap [&:has([role=checkbox])]:pr-0',
        className,
      )}
      {...props}
    />
  );
}

function TableCell({ className, ...props }: React.ComponentProps<'td'>) {
  return (
    <td
      data-slot="table-cell"
      className={cn('px-4 py-2.5 align-middle whitespace-nowrap [&:has([role=checkbox])]:pr-0', className)}
      {...props}
    />
  );
}

function TableCaption({ className, ...props }: React.ComponentProps<'caption'>) {
  return (
    <caption
      data-slot="table-caption"
      className={cn('text-muted-foreground mt-4 text-sm', className)}
      {...props}
    />
  );
}

export { Table, TableHeader, TableBody, TableFooter, TableHead, TableRow, TableCell, TableCaption };
