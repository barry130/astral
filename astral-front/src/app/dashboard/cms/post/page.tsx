'use client';

import { useCallback, useEffect, useState } from 'react';
import Link from 'next/link';
import { toast } from 'sonner';
import { MoreHorizontal, Download, Pencil, Plus, RotateCcw, Search } from 'lucide-react';

import {
  cmsApi,
  cmsDictOptions,
  downloadCmsExport,
  loadCmsDicts,
  CmsCategory,
  CmsPost,
} from '@/api/cms';
import { usePerm } from '@/lib/perm';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Badge } from '@/components/ui/badge';
import { TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { cn } from '@/lib/utils';
import {
  BadgeList,
  CmsTable,
  flattenCategoryOptions,
  formatDateTime,
  ListCard,
  Pager,
  PageHeader,
  StatusBadge,
  TableStateRow,
} from '../shared';
import { confirmDialog } from '../../confirm';

const PAGE_SIZES = [10, 20, 50, 100];
const CONTENT_STATUS = 'cms_content_status';
/** Select 的「全部」哨兵值（Radix Select 不接受空字符串 value） */
const ALL = '__all__';

/** 文章分类名（多分类优先；缺失时回退兼容字段 categoryName） */
function pickCategoryNames(post: CmsPost): string[] {
  const names = (post.categories || []).map((c) => c.name).filter(Boolean);
  if (names.length > 0) return names;
  return post.categoryName ? [post.categoryName] : [];
}

/** 文章管理：列表 + 筛选 + 状态切换 + 软删 */
export default function CmsPostListPage() {
  const canEdit = usePerm()('admin:cms:content:edit');

  const [keywordInput, setKeywordInput] = useState('');
  const [keyword, setKeyword] = useState('');
  const [status, setStatus] = useState('');
  const [categoryId, setCategoryId] = useState('');
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(PAGE_SIZES[0]);
  const [posts, setPosts] = useState<CmsPost[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [categories, setCategories] = useState<CmsCategory[]>([]);

  const load = useCallback(
    async (targetPage: number, size: number = pageSize) => {
      setLoading(true);
      setError(null);
      try {
        const res = await cmsApi.postPage({
          pageNum: targetPage,
          pageSize: size,
          keyword: keyword || undefined,
          status: status || undefined,
          // 后端按关系表匹配「包含该分类」，前端保持单选即可
          categoryId: categoryId ? Number(categoryId) : undefined,
        });
        setPosts(res.data?.records || []);
        setTotal(res.data?.total || 0);
      } catch (e) {
        const message = (e as Error).message || '文章列表加载失败';
        setError(message);
        setPosts([]);
        setTotal(0);
        toast.error(message);
      } finally {
        setLoading(false);
      }
    },
    [keyword, status, categoryId, pageSize],
  );

  useEffect(() => {
    void load(page);
  }, [load, page]);

  useEffect(() => {
    void loadCmsDicts().catch(() => {
      /* 字典拉取失败时回退展示原始值 */
    });
    cmsApi
      .categoryTree()
      .then((res) => setCategories(res.data || []))
      .catch(() => {
        /* 分类筛选项加载失败不阻塞列表 */
      });
  }, []);

  /** 修改筛选项：回到第 1 页重新查询 */
  const applyFilter = (apply: () => void) => {
    apply();
    setPage(1);
  };

  const resetFilter = () => {
    applyFilter(() => {
      setKeywordInput('');
      setKeyword('');
      setStatus('');
      setCategoryId('');
    });
  };

  const changeStatus = async (post: CmsPost, next: string) => {
    try {
      await cmsApi.changePostStatus(post.id, next);
      toast.success('状态已更新');
      void load(page);
    } catch (e) {
      toast.error((e as Error).message || '状态更新失败');
    }
  };

  const remove = async (post: CmsPost) => {
    if (!(await confirmDialog({ title: `确认删除文章「${post.title || post.id}」？` }))) return;
    try {
      await cmsApi.deletePost(post.id);
      toast.success('已删除');
      // 当前页删空后回退一页，避免停在空页
      if (posts.length === 1 && page > 1) setPage(page - 1);
      else void load(page);
    } catch (e) {
      toast.error((e as Error).message || '删除失败');
    }
  };

  const exportMarkdown = async (post: CmsPost) => {
    const res = await downloadCmsExport('post', post.id);
    if (res.ok) toast.success('已开始下载');
    else toast.error(res.error || '导出失败');
  };

  const categoryOptions = flattenCategoryOptions(categories);
  const firstLoading = loading && posts.length === 0;

  return (
    <div className="space-y-4">
      <PageHeader
        title="文章"
        description="管理博客文章：一篇可归属多个分类，配合标签、Markdown 正文与发布状态组织内容"
        actions={
          canEdit && (
            <Button asChild className="gap-1.5">
              <Link href="/dashboard/cms/post/new">
                <Plus className="size-4" />
                新建文章
              </Link>
            </Button>
          )
        }
      />

      <ListCard
        toolbar={
          <>
            <div className="relative">
              <Search className="text-muted-foreground pointer-events-none absolute top-1/2 left-2.5 size-4 -translate-y-1/2" />
              <Input
                value={keywordInput}
                onChange={(e) => setKeywordInput(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter') applyFilter(() => setKeyword(keywordInput.trim()));
                }}
                placeholder="搜索标题 / 摘要"
                className="w-60 pl-8"
              />
            </div>
            <Select
              value={status}
              onValueChange={(v) => applyFilter(() => setStatus(v === ALL ? '' : v))}
            >
              <SelectTrigger className="w-32">
                <SelectValue placeholder="全部状态" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value={ALL}>全部状态</SelectItem>
                {cmsDictOptions(CONTENT_STATUS).map((o) => (
                  <SelectItem key={o.value} value={o.value}>
                    {o.label}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
            <Select
              value={categoryId}
              onValueChange={(v) => applyFilter(() => setCategoryId(v === ALL ? '' : v))}
            >
              <SelectTrigger className="w-44">
                <SelectValue placeholder="按分类筛选" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value={ALL}>全部分类</SelectItem>
                {categoryOptions.map(({ node, depth }) => (
                  <SelectItem key={node.id} value={String(node.id)}>
                    {'\u00A0'.repeat(depth * 2)}
                    {depth > 0 ? '└ ' : ''}
                    {node.name || `#${node.id}`}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
            <Button
              variant="ghost"
              size="sm"
              className="text-muted-foreground ml-auto gap-1"
              onClick={resetFilter}
            >
              <RotateCcw className="size-3.5" />
              重置
            </Button>
          </>
        }
        footer={<Pager
              page={page}
              pageSize={pageSize}
              total={total}
              onPageChange={setPage}
              onPageSizeChange={(s) => {
                setPageSize(s);
                setPage(1);
              }}
            />}
      >
        <CmsTable>
          <TableHeader>
            <TableRow className="hover:bg-transparent">
              <TableHead>标题</TableHead>
              <TableHead className="w-40">分类</TableHead>
              <TableHead className="w-40">标签</TableHead>
              <TableHead className="w-24">状态</TableHead>
              <TableHead className="w-16 text-right">浏览</TableHead>
              <TableHead className="w-36">发布时间</TableHead>
              <TableHead className="w-28 text-right">操作</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody className={cn(loading && posts.length > 0 && 'opacity-60 transition-opacity')}>
            {firstLoading ? (
              <TableStateRow colSpan={7} loading />
            ) : error ? (
              <TableStateRow colSpan={7} error={error} onRetry={() => void load(page)} />
            ) : posts.length === 0 ? (
              <TableStateRow
                colSpan={7}
                emptyText="还没有文章"
                emptyHint="新建一篇 Markdown 文章，保存后可在列表里切换发布状态"
                emptyAction={
                  canEdit ? (
                    <Button asChild size="sm" className="gap-1.5">
                      <Link href="/dashboard/cms/post/new">
                        <Plus className="size-4" />
                        新建文章
                      </Link>
                    </Button>
                  ) : undefined
                }
              />
            ) : (
              posts.map((post) => (
                <TableRow key={post.id}>
                  <TableCell className="max-w-[360px]">
                    <div className="flex min-w-0 items-center gap-1.5">
                      {post.isTop && (
                        <Badge
                          variant="secondary"
                          className="text-muted-foreground shrink-0 px-1.5 font-normal"
                        >
                          置顶
                        </Badge>
                      )}
                      <span className="truncate font-medium" title={post.title || undefined}>
                        {post.title || '-'}
                      </span>
                    </div>
                    <div className="text-muted-foreground truncate text-xs">
                      {post.slug ? `/${post.slug}` : '未设置别名，前台按 ID 访问'}
                    </div>
                  </TableCell>
                  <TableCell>
                    <BadgeList
                      labels={pickCategoryNames(post)}
                      max={2}
                      empty="未分类"
                      highlightFirst
                    />
                  </TableCell>
                  <TableCell>
                    <BadgeList
                      labels={(post.tags || []).map((t) => t.name || `#${t.id}`)}
                      max={2}
                    />
                  </TableCell>
                  <TableCell>
                    <StatusBadge code={CONTENT_STATUS} value={post.status} />
                  </TableCell>
                  <TableCell className="text-muted-foreground text-right tabular-nums">
                    {post.views ?? 0}
                  </TableCell>
                  <TableCell className="text-muted-foreground">
                    {formatDateTime(post.publishedAt)}
                  </TableCell>
                  <TableCell>
                    <div className="flex items-center justify-end gap-0.5">
                      {canEdit && (
                        <Button asChild variant="ghost" size="sm" className="h-8 gap-1 px-2">
                          <Link href={`/dashboard/cms/post/${post.id}`}>
                            <Pencil className="size-3.5" />
                            编辑
                          </Link>
                        </Button>
                      )}
                      {canEdit && (
                        <DropdownMenu>
                          <DropdownMenuTrigger asChild>
                            <Button
                              variant="ghost"
                              size="icon"
                              className="text-muted-foreground size-8"
                              aria-label="更多操作"
                            >
                              <MoreHorizontal className="size-4" />
                            </Button>
                          </DropdownMenuTrigger>
                          <DropdownMenuContent align="end" className="w-36">
                            {cmsDictOptions(CONTENT_STATUS)
                              .filter((o) => o.value !== post.status)
                              .map((o) => (
                                <DropdownMenuItem
                                  key={o.value}
                                  onSelect={() => changeStatus(post, o.value)}
                                >
                                  设为{o.label}
                                </DropdownMenuItem>
                              ))}
                            <DropdownMenuSeparator />
                            <DropdownMenuItem onSelect={() => void exportMarkdown(post)}>
                              <Download className="size-4" />
                              导出 Markdown
                            </DropdownMenuItem>
                            <DropdownMenuSeparator />
                            <DropdownMenuItem variant="destructive" onSelect={() => remove(post)}>
                              删除
                            </DropdownMenuItem>
                          </DropdownMenuContent>
                        </DropdownMenu>
                      )}
                    </div>
                  </TableCell>
                </TableRow>
              ))
            )}
          </TableBody>
        </CmsTable>
      </ListCard>
    </div>
  );
}
