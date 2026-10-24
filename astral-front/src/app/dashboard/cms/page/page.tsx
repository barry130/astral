'use client';

import { useCallback, useEffect, useState } from 'react';
import Link from 'next/link';
import { toast } from 'sonner';
import { MoreHorizontal, Download, Pencil, Plus, SquarePen } from 'lucide-react';

import {
  cmsApi, cmsDictOptions, downloadCmsExport, loadCmsDicts, CmsWikiPage,
} from '@/api/cms';
import { usePerm } from '@/lib/perm';
import { Button } from '@/components/ui/button';
import {
  TableBody, TableCell, TableHead, TableHeader, TableRow,
} from '@/components/ui/table';
import {
  DropdownMenu, DropdownMenuContent, DropdownMenuItem,
  DropdownMenuSeparator, DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import {
  CmsTable, flattenTree, formatDateTime, IndentLabel, ListCard, PageHeader,
  StatusBadge, TableStateRow,
} from '../shared';

const CONTENT_STATUS = 'cms_content_status';

/** Wiki 页面管理：全量树 + 编辑入口 + 状态切换 + 删除（有子页面时后端拦截） */
export default function CmsWikiListPage() {
  const canEdit = usePerm()('admin:cms:content:edit');

  const [pages, setPages] = useState<CmsWikiPage[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const res = await cmsApi.pageTree();
      setPages(res.data || []);
    } catch (e) {
      const message = (e as Error).message || '页面加载失败';
      setError(message);
      setPages([]);
      toast.error(message);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void loadCmsDicts().catch(() => {/* 字典拉取失败时回退展示原始值 */});
    void load();
  }, [load]);

  const changeStatus = async (page: CmsWikiPage, next: string) => {
    try {
      await cmsApi.changePageStatus(page.id, next);
      toast.success('状态已更新');
      void load();
    } catch (e) {
      toast.error((e as Error).message || '状态更新失败');
    }
  };

  const remove = async (page: CmsWikiPage) => {
    if (!window.confirm(`确认删除页面「${page.title || page.id}」？`)) return;
    try {
      await cmsApi.deletePage(page.id);
      toast.success('已删除');
      void load();
    } catch (e) {
      // 有子页面时后端拦截，直接展示其消息
      toast.error((e as Error).message || '删除失败');
    }
  };

  const exportMarkdown = async (page: CmsWikiPage) => {
    const res = await downloadCmsExport('page', page.id);
    if (res.ok) toast.success('已开始下载');
    else toast.error(res.error || '导出失败');
  };

  const tree = flattenTree(pages);

  return (
    <div className="space-y-4">
      <PageHeader
        title="Wiki 页面"
        description="树形组织的知识库页面，支持父子层级与发布状态；正文使用 Markdown"
        actions={canEdit && (
          <Button asChild className="gap-1.5">
            <Link href="/dashboard/cms/page/new"><Plus className="size-4" />新建页面</Link>
          </Button>
        )}
      />

      <ListCard
        toolbar={(
          <p className="text-xs text-muted-foreground">
            共 {tree.length} 个页面 · 缩进表示父子层级
          </p>
        )}
      >
        <CmsTable>
          <TableHeader>
            <TableRow className="hover:bg-transparent">
              <TableHead>标题</TableHead>
              <TableHead className="w-24">状态</TableHead>
              <TableHead className="w-16 text-right">排序</TableHead>
              <TableHead className="w-16 text-right">浏览</TableHead>
              <TableHead className="w-36">更新时间</TableHead>
              <TableHead className="w-28 text-right">操作</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {loading ? (
              <TableStateRow colSpan={6} loading />
            ) : error ? (
              <TableStateRow colSpan={6} error={error} onRetry={() => void load()} />
            ) : tree.length === 0 ? (
              <TableStateRow
                colSpan={6}
                emptyText="还没有 Wiki 页面"
                emptyHint="Wiki 适合放使用说明、常见问题这类长期文档，可无限嵌套"
                emptyAction={canEdit ? (
                  <Button asChild size="sm" className="gap-1.5">
                    <Link href="/dashboard/cms/page/new"><Plus className="size-4" />新建页面</Link>
                  </Button>
                ) : undefined}
              />
            ) : tree.map(({ node, depth }) => (
              <TableRow key={node.id}>
                <TableCell className="max-w-[360px]">
                  <IndentLabel depth={depth} label={node.title} />
                  <div className="truncate text-xs text-muted-foreground"
                       style={{ paddingLeft: `${depth * 20}px` }}>
                    {node.slug ? `/${node.slug}` : '未设置别名，前台按 ID 访问'}
                  </div>
                </TableCell>
                <TableCell><StatusBadge code={CONTENT_STATUS} value={node.status} /></TableCell>
                <TableCell className="text-right text-muted-foreground tabular-nums">{node.sort ?? 0}</TableCell>
                <TableCell className="text-right text-muted-foreground tabular-nums">{node.views ?? 0}</TableCell>
                <TableCell className="text-muted-foreground tabular-nums">
                  {formatDateTime(node.updateTime)}
                </TableCell>
                <TableCell>
                  {canEdit && (
                    <div className="flex items-center justify-end gap-0.5">
                      <Button asChild variant="ghost" size="sm" className="h-8 gap-1 px-2">
                        <Link href={`/dashboard/cms/page/${node.id}`}><Pencil className="size-3.5" />编辑</Link>
                      </Button>
                      <DropdownMenu>
                        <DropdownMenuTrigger asChild>
                          <Button variant="ghost" size="icon" className="size-8 text-muted-foreground"
                                  aria-label="更多操作">
                            <MoreHorizontal className="size-4" />
                          </Button>
                        </DropdownMenuTrigger>
                        <DropdownMenuContent align="end" className="w-40">
                          <DropdownMenuItem asChild>
                            <Link href={`/dashboard/cms/page/new?parentId=${node.id}`}>
                              <SquarePen className="size-4" />新建子页面
                            </Link>
                          </DropdownMenuItem>
                          <DropdownMenuSeparator />
                          {cmsDictOptions(CONTENT_STATUS)
                            .filter((o) => o.value !== node.status)
                            .map((o) => (
                              <DropdownMenuItem key={o.value} onSelect={() => changeStatus(node, o.value)}>
                                设为{o.label}
                              </DropdownMenuItem>
                            ))}
                          <DropdownMenuSeparator />
                          <DropdownMenuItem onSelect={() => void exportMarkdown(node)}>
                            <Download className="size-4" />导出 Markdown
                          </DropdownMenuItem>
                          <DropdownMenuSeparator />
                          <DropdownMenuItem variant="destructive" onSelect={() => remove(node)}>
                            删除
                          </DropdownMenuItem>
                        </DropdownMenuContent>
                      </DropdownMenu>
                    </div>
                  )}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </CmsTable>
      </ListCard>
    </div>
  );
}
