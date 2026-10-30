'use client';

import { useCallback, useEffect, useState } from 'react';
import { toast } from 'sonner';
import { Check, RotateCcw, Search, Trash2, X } from 'lucide-react';

import { cmsApi, cmsDictLabel, cmsDictOptions, loadCmsDicts, CmsComment } from '@/api/cms';
import { usePerm } from '@/lib/perm';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import { cn } from '@/lib/utils';
import {
  CmsTable,
  formatDateTime,
  ListCard,
  Pager,
  PageHeader,
  StatusBadge,
  TableStateRow,
} from '../shared';
import { confirmDialog } from '../../confirm';

const PAGE_SIZES = [10, 20, 50, 100];
const COMMENT_STATUS = 'cms_comment_status';
const TARGET_TYPE = 'cms_comment_target_type';
/** Select 的「全部」哨兵值（Radix Select 不接受空字符串 value） */
const ALL = '__all__';

/** 评论审核：默认只看待审核（pending），通过/拒绝即时生效 */
export default function CmsCommentPage() {
  const canEdit = usePerm()('admin:cms:comment:edit');

  const [keywordInput, setKeywordInput] = useState('');
  const [keyword, setKeyword] = useState('');
  const [status, setStatus] = useState('pending');
  const [targetType, setTargetType] = useState('');
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(PAGE_SIZES[0]);
  const [comments, setComments] = useState<CmsComment[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(
    async (targetPage: number, size: number = pageSize) => {
      setLoading(true);
      setError(null);
      try {
        const res = await cmsApi.commentPage({
          pageNum: targetPage,
          pageSize: size,
          status: status || undefined,
          targetType: targetType || undefined,
          keyword: keyword || undefined,
        });
        setComments(res.data?.records || []);
        setTotal(res.data?.total || 0);
      } catch (e) {
        const message = (e as Error).message || '评论加载失败';
        setError(message);
        setComments([]);
        setTotal(0);
        toast.error(message);
      } finally {
        setLoading(false);
      }
    },
    [status, targetType, keyword, pageSize],
  );

  useEffect(() => {
    void loadCmsDicts().catch(() => {
      /* 字典拉取失败时回退展示原始值 */
    });
  }, []);

  useEffect(() => {
    void load(page);
  }, [load, page]);

  const applyFilter = (apply: () => void) => {
    apply();
    setPage(1);
  };

  const audit = async (comment: CmsComment, next: 'approved' | 'rejected') => {
    try {
      await cmsApi.auditComment(comment.id, next);
      toast.success(`已${next === 'approved' ? '通过' : '拒绝'}`);
      void load(page);
    } catch (e) {
      toast.error((e as Error).message || '审核失败');
    }
  };

  const remove = async (comment: CmsComment) => {
    if (!(await confirmDialog({ title: '确认删除该评论？', description: '删除后不可恢复。' })))
      return;
    try {
      await cmsApi.deleteComment(comment.id);
      toast.success('已删除');
      if (comments.length === 1 && page > 1) setPage(page - 1);
      else void load(page);
    } catch (e) {
      toast.error((e as Error).message || '删除失败');
    }
  };

  const firstLoading = loading && comments.length === 0;

  return (
    <div className="space-y-4">
      <PageHeader
        title="评论"
        description="审核与管理全站评论；默认只看待审核队列，通过后才在前台可见"
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
                placeholder="搜索昵称 / 内容"
                className="w-56 pl-8"
              />
            </div>
            <Select
              value={status}
              onValueChange={(v) => applyFilter(() => setStatus(v === ALL ? '' : v))}
            >
              <SelectTrigger className="w-32">
                <SelectValue placeholder="状态" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value={ALL}>全部状态</SelectItem>
                {cmsDictOptions(COMMENT_STATUS).map((o) => (
                  <SelectItem key={o.value} value={o.value}>
                    {o.label}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
            <Select
              value={targetType}
              onValueChange={(v) => applyFilter(() => setTargetType(v === ALL ? '' : v))}
            >
              <SelectTrigger className="w-32">
                <SelectValue placeholder="目标类型" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value={ALL}>全部类型</SelectItem>
                {cmsDictOptions(TARGET_TYPE).map((o) => (
                  <SelectItem key={o.value} value={o.value}>
                    {o.label}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
            <Button
              variant="ghost"
              size="sm"
              className="text-muted-foreground ml-auto gap-1"
              onClick={() =>
                applyFilter(() => {
                  setKeywordInput('');
                  setKeyword('');
                  setStatus('pending');
                  setTargetType('');
                })
              }
            >
              <RotateCcw className="size-3.5" />
              重置
            </Button>
          </>
        }
        footer={
          total > 0 ? (
            <Pager
              page={page}
              pageSize={pageSize}
              total={total}
              onPageChange={setPage}
              onPageSizeChange={(s) => {
                setPageSize(s);
                setPage(1);
              }}
            />
          ) : undefined
        }
      >
        <CmsTable>
          <TableHeader>
            <TableRow className="hover:bg-transparent">
              <TableHead className="w-36">评论人</TableHead>
              <TableHead>评论内容</TableHead>
              <TableHead className="w-44">目标</TableHead>
              <TableHead className="w-24">状态</TableHead>
              <TableHead className="w-28">来源 IP</TableHead>
              <TableHead className="w-36">时间</TableHead>
              <TableHead className="w-32 text-right">操作</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody
            className={cn(loading && comments.length > 0 && 'opacity-60 transition-opacity')}
          >
            {firstLoading ? (
              <TableStateRow colSpan={7} loading />
            ) : error ? (
              <TableStateRow colSpan={7} error={error} onRetry={() => void load(page)} />
            ) : comments.length === 0 ? (
              <TableStateRow
                colSpan={7}
                emptyText={status === 'pending' ? '待审核队列已清空' : '没有符合条件的评论'}
                emptyHint={
                  status === 'pending'
                    ? '新评论提交后会出现在这里；「评论需审核」可在站点设置里关闭'
                    : '试着放宽状态或类型筛选'
                }
              />
            ) : (
              comments.map((comment) => (
                <TableRow key={comment.id}>
                  <TableCell>
                    <div className="truncate font-medium" title={comment.nickname || undefined}>
                      {comment.nickname || '-'}
                    </div>
                    {comment.email && (
                      <div className="text-muted-foreground truncate text-xs" title={comment.email}>
                        {comment.email}
                      </div>
                    )}
                  </TableCell>
                  {/* 待审评论优先保证可读性：给到两行正文而不是单行截断 */}
                  <TableCell className="max-w-[420px] min-w-[240px]">
                    <p
                      className="line-clamp-2 text-sm leading-relaxed whitespace-normal"
                      title={comment.content || undefined}
                    >
                      {comment.content || '-'}
                    </p>
                  </TableCell>
                  <TableCell>
                    <div className="truncate text-sm" title={comment.targetTitle || undefined}>
                      {comment.targetTitle || '-'}
                    </div>
                    <div className="text-muted-foreground truncate text-xs">
                      {cmsDictLabel(TARGET_TYPE, comment.targetType)}
                    </div>
                  </TableCell>
                  <TableCell>
                    <StatusBadge code={COMMENT_STATUS} value={comment.status} />
                  </TableCell>
                  <TableCell
                    className="text-muted-foreground truncate"
                    title={comment.ip || undefined}
                  >
                    {comment.ip || '-'}
                  </TableCell>
                  <TableCell className="text-muted-foreground tabular-nums">
                    {formatDateTime(comment.createTime)}
                  </TableCell>
                  <TableCell>
                    <div className="flex items-center justify-end gap-0.5">
                      {canEdit && (
                        <>
                          {comment.status === 'pending' && (
                            <Button
                              variant="ghost"
                              size="sm"
                              className="text-muted-foreground hover:text-foreground h-8 gap-1 px-2"
                              onClick={() => audit(comment, 'approved')}
                            >
                              <Check className="size-3.5" />
                              通过
                            </Button>
                          )}
                          {comment.status === 'pending' && (
                            <Button
                              variant="ghost"
                              size="sm"
                              className="text-muted-foreground hover:text-destructive h-8 gap-1 px-2"
                              onClick={() => audit(comment, 'rejected')}
                            >
                              <X className="size-3.5" />
                              拒绝
                            </Button>
                          )}
                          <Button
                            variant="ghost"
                            size="icon"
                            className="text-muted-foreground hover:text-destructive size-8"
                            aria-label="删除评论"
                            onClick={() => remove(comment)}
                          >
                            <Trash2 className="size-4" />
                          </Button>
                        </>
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
