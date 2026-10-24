'use client';

import dayjs from 'dayjs';
import { ChevronLeft, ChevronRight } from 'lucide-react';

import { cmsDictLabel, CmsCategory, CmsDictCode, CmsTreeNode } from '@/api/cms';
import { EmptyState } from '@/components/EmptyState';
import { ErrorState } from '@/components/ErrorState';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card } from '@/components/ui/card';
import { Skeleton } from '@/components/ui/skeleton';
import {
  Table, TableCell, TableRow,
} from '@/components/ui/table';
import { cn } from '@/lib/utils';

/**
 * CMS 页面共用的视觉基元。
 *
 * 取向：冷灰底 + 青绿强调，靠「留白 / 细线 / 层级」而不是色块和装饰来区分信息。
 * 一律使用主题语义类（bg-background / text-muted-foreground / border-border …），
 * 不引入新的色值；列表与表单容器只在 cms 内复用，避免外溢影响其它模块。
 */

/** 页头：标题 + 一句说明，右侧放主操作 */
export function PageHeader({ title, description, actions }: {
  title: string;
  description?: string;
  actions?: React.ReactNode;
}) {
  return (
    <div className="flex flex-wrap items-start justify-between gap-x-6 gap-y-3">
      <div className="min-w-0 space-y-1.5">
        <h1 className="text-xl font-semibold tracking-tight text-foreground">{title}</h1>
        {description && (
          <p className="max-w-3xl text-sm leading-relaxed text-muted-foreground">{description}</p>
        )}
      </div>
      {actions && <div className="flex shrink-0 items-center gap-2">{actions}</div>}
    </div>
  );
}

/**
 * 状态徽标：文案走数据字典，仅「值 → 外观」是纯前端语义。
 * 用「浅色底 + 同色系文字 + 小圆点」而不是高饱和实心块：
 * 表格里同时出现多种状态时仍然安静，靠颜色差异一眼分辨。
 */
type StatusVariant = 'default' | 'secondary' | 'outline' | 'destructive' | 'success' | 'warning';

const STATUS_VARIANT: Record<string, StatusVariant> = {
  draft: 'outline',
  published: 'success',
  /** 私密：可见性受限但仍对外可访问，用 amber 与草稿的灰、已发布的绿区分开 */
  private: 'warning',
  hidden: 'secondary',
  pending: 'warning',
  approved: 'success',
  rejected: 'destructive',
};

/** 描边/次级两种「弱状态」用更浅的文字色，避免草稿、隐藏抢走已发布的注意力 */
const STATUS_CLASS: Partial<Record<StatusVariant, string>> = {
  outline: 'text-muted-foreground',
  secondary: 'text-muted-foreground',
};

export function StatusBadge({ code, value }: { code: CmsDictCode; value?: string | null }) {
  if (!value) return <span className="text-muted-foreground">-</span>;
  const variant = STATUS_VARIANT[value] || 'outline';
  return (
    <Badge variant={variant} className={cn('gap-1.5 font-normal', STATUS_CLASS[variant])}>
      <span aria-hidden className="size-1.5 shrink-0 rounded-full bg-current opacity-70" />
      {cmsDictLabel(code, value)}
    </Badge>
  );
}

/** 分页条：左侧总数与页码，右侧上/下一页（列表页信息密度适中即可） */
export function Pager({ page, pageSize, total, onPageChange }: {
  page: number;
  pageSize: number;
  total: number;
  onPageChange: (page: number) => void;
}) {
  const pages = Math.max(1, Math.ceil(total / pageSize));
  if (total === 0) return null;
  return (
    <div className="flex flex-wrap items-center justify-between gap-3 text-sm">
      <span className="text-muted-foreground tabular-nums">共 {total} 条 · 第 {page} / {pages} 页</span>
      <div className="flex items-center gap-1.5">
        <Button variant="outline" size="sm" className="gap-1" disabled={page <= 1}
                onClick={() => onPageChange(page - 1)}>
          <ChevronLeft className="size-3.5" />上一页
        </Button>
        <Button variant="outline" size="sm" className="gap-1" disabled={page >= pages}
                onClick={() => onPageChange(page + 1)}>
          下一页<ChevronRight className="size-3.5" />
        </Button>
      </div>
    </div>
  );
}

/**
 * 列表容器：筛选区 / 表格 / 分页三层，用发丝线分隔而不是分成三张卡片。
 * 卡片自身去掉上下 padding，让表格行贴边；单元格的左右 4 与容器 4 对齐。
 */
export function ListCard({ toolbar, children, footer, className }: {
  toolbar?: React.ReactNode;
  children: React.ReactNode;
  footer?: React.ReactNode;
  className?: string;
}) {
  return (
    <Card className={cn('gap-0 overflow-hidden py-0', className)}>
      {toolbar && <div className="flex flex-wrap items-center gap-2 px-4 py-3">{toolbar}</div>}
      <div className={cn(toolbar && 'border-t border-border/60')}>{children}</div>
      {footer && <div className="border-t border-border/60 px-4 py-3">{footer}</div>}
    </Card>
  );
}

/**
 * 表单分区卡片：标题 + 说明固定在上方，下方一条发丝线把「区头」与字段分开。
 * 长表单用多张这样的卡片分段，避免所有字段堆在同一张卡里、失去层次。
 */
export function FormCard({ title, description, children, footer, className }: {
  title: string;
  description?: string;
  children: React.ReactNode;
  footer?: React.ReactNode;
  className?: string;
}) {
  return (
    <Card className={cn('gap-0 overflow-hidden py-0', className)}>
      <div className="space-y-1 px-5 py-4">
        <h2 className="text-sm font-semibold tracking-tight text-foreground">{title}</h2>
        {description && (
          <p className="text-xs leading-relaxed text-muted-foreground">{description}</p>
        )}
      </div>
      <div className="space-y-5 border-t border-border/60 px-5 py-5">{children}</div>
      {footer && <div className="border-t border-border/60 px-5 py-3">{footer}</div>}
    </Card>
  );
}

/**
 * 列表页统一表格节奏：表头压一层极淡底色（区分表头/表体），
 * 行内容上下留白比默认 +2px，长表格连读时不至于挤成一团。
 * `[&_td]` 选择器优先级高于单元格自带的 py-2.5，无需逐个改 TableCell。
 */
export function CmsTable({ className, ...props }: React.ComponentProps<typeof Table>) {
  return <Table className={cn('[&_th]:bg-muted/40 [&_td]:py-3', className)} {...props} />;
}

/** 首屏骨架：形状按列表的「主标题 + 次列 + 状态」排布，比转圈更能表达正在加载 */
function TableSkeletonRows({ rows = 6 }: { rows?: number }) {
  return (
    <div role="status" aria-busy="true" aria-label="数据加载中" className="space-y-4 py-4">
      {Array.from({ length: rows }).map((_, index) => (
        <div key={index} className="flex items-center gap-4">
          <Skeleton className="h-4 w-[28%] max-w-64" />
          <Skeleton className="h-4 w-[14%]" />
          <Skeleton className="h-4 w-[10%]" />
          <Skeleton className="h-4 flex-1" />
        </div>
      ))}
    </div>
  );
}

/**
 * 表格状态行（加载 / 错误 / 空态三合一）：列表页统一走这里，
 * 三种状态的高度与文案层级一致，翻页与筛选时不会突然跳动。
 */
export function TableStateRow({ colSpan, loading, error, onRetry, emptyText, emptyHint, emptyAction }: {
  colSpan: number;
  loading?: boolean;
  error?: string | null;
  onRetry?: () => void;
  emptyText?: string;
  emptyHint?: React.ReactNode;
  emptyAction?: React.ReactNode;
}) {
  return (
    <TableRow className="hover:bg-transparent">
      <TableCell colSpan={colSpan} className="whitespace-normal px-4 py-0">
        {loading ? (
          <TableSkeletonRows />
        ) : error ? (
          <ErrorState message={error} onRetry={onRetry} />
        ) : (
          <EmptyState
            description={emptyText || '暂无数据'}
            hint={emptyHint}
            action={emptyAction}
            padding={36}
          />
        )}
      </TableCell>
    </TableRow>
  );
}

/** 时间展示统一为 YYYY-MM-DD HH:mm */
export function formatDateTime(value?: string | null): string {
  return value ? dayjs(value).format('YYYY-MM-DD HH:mm') : '-';
}

export interface FlatTreeNode<T> {
  node: T;
  depth: number;
}

/** 树拍平为带深度的有序列表（表格缩进展示用） */
export function flattenTree<T extends CmsTreeNode>(nodes: T[] | undefined, depth = 0): Array<FlatTreeNode<T>> {
  const out: Array<FlatTreeNode<T>> = [];
  for (const node of nodes || []) {
    out.push({ node, depth });
    if (node.children?.length) out.push(...flattenTree(node.children as T[], depth + 1));
  }
  return out;
}

/** 分类选项：拍平后附带祖先名称链，用于「父 / 子」拼接展示与搜索 */
export interface CategoryOption {
  node: CmsCategory;
  depth: number;
  /** 祖先名称链（不含自身），顶级分类为空数组 */
  path: string[];
}

/** 分类树拍平（保持后端排序，父在前子在后） */
export function flattenCategoryOptions(
  nodes: CmsCategory[] | undefined,
  depth = 0,
  ancestors: string[] = [],
): CategoryOption[] {
  const out: CategoryOption[] = [];
  for (const node of nodes || []) {
    out.push({ node, depth, path: ancestors });
    if (node.children?.length) {
      out.push(...flattenCategoryOptions(node.children, depth + 1,
        [...ancestors, node.name || `#${node.id}`]));
    }
  }
  return out;
}

/** 收集节点及其全部子孙 id（编辑时从父可选集中排除，避免成环） */
export function collectSelfAndDescendantIds(node: CmsTreeNode): Set<number> {
  const ids = new Set<number>([node.id]);
  for (const child of node.children || []) {
    for (const id of collectSelfAndDescendantIds(child)) ids.add(id);
  }
  return ids;
}

/** 拉平后的树节点在表格里的缩进展示 */
export function IndentLabel({ depth, label }: { depth: number; label?: string }) {
  return (
    <div className="flex min-w-0 items-center" style={{ paddingLeft: `${depth * 20}px` }}>
      {depth > 0 && <span className="mr-1.5 shrink-0 text-muted-foreground/50">└</span>}
      <span className="truncate">{label || '-'}</span>
    </div>
  );
}

/**
 * 标签/分类等「多值」列的展示：最多展示 max 个，其余折叠成 +N（悬停看全量）。
 * `highlightFirst` 用于分类列：第一项是主分类，用 secondary 底色稍作强调。
 */
export function BadgeList({ labels, max = 2, empty = '-', highlightFirst = false, className }: {
  labels: string[];
  max?: number;
  empty?: string;
  highlightFirst?: boolean;
  className?: string;
}) {
  if (labels.length === 0) return <span className="text-muted-foreground">{empty}</span>;
  const visible = labels.slice(0, Math.max(1, max));
  const rest = labels.slice(visible.length);
  return (
    <div className={cn('flex flex-wrap items-center gap-1', className)}>
      {visible.map((label, index) => (
        <Badge
          key={`${label}-${index}`}
          variant={highlightFirst && index === 0 ? 'secondary' : 'outline'}
          className={cn(
            'max-w-40 min-w-0 font-normal',
            highlightFirst && index === 0 ? 'text-foreground' : 'text-muted-foreground',
          )}
          title={label}
        >
          <span className="truncate">{label}</span>
        </Badge>
      ))}
      {rest.length > 0 && (
        <Badge variant="outline" className="font-normal text-muted-foreground tabular-nums"
               title={rest.join('、')}>
          +{rest.length}
        </Badge>
      )}
    </div>
  );
}
