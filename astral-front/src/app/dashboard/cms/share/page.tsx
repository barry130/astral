'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { toast } from 'sonner';
import { Copy, RotateCcw, Search, Trash2 } from 'lucide-react';

import { cmsApi, loadCmsDicts, CmsShare } from '@/api/cms';
import { usePerm } from '@/lib/perm';
import { Badge } from '@/components/ui/badge';
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
import { confirmDialog } from '../../confirm';
import { CmsTable, formatDateTime, ListCard, PageHeader, TableStateRow } from '../shared';
import { ShareTargetBadge } from '../SharePanel';

/** Select 的「全部」哨兵值（Radix Select 不接受空字符串 value） */
const ALL = '__all__';

/**
 * 分享管理：全站分享链接一览。
 * 分享链接把「私密」内容以只读方式临时开放给未登录访客，这里负责统一查看与撤销。
 */
export default function CmsShareListPage() {
  const canEdit = usePerm()('admin:cms:content:edit');

  const [shares, setShares] = useState<CmsShare[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [keywordInput, setKeywordInput] = useState('');
  /** '' = 全部；'active' 仅有效；'expired' 仅已过期 */
  const [state, setState] = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const res = await cmsApi.shareList();
      setShares(res.data || []);
    } catch (e) {
      const message = (e as Error).message || '分享链接加载失败';
      setError(message);
      setShares([]);
      toast.error(message);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void loadCmsDicts().catch(() => {
      /* 字典拉取失败时回退展示原始值 */
    });
    void load();
  }, [load]);

  const revoke = async (share: CmsShare) => {
    const name = share.remark || share.targetTitle || `#${share.id}`;
    if (
      !(await confirmDialog({
        title: `确认撤销分享链接「${name}」？`,
        description: '撤销后已发出的链接立即失效。',
      }))
    )
      return;
    try {
      await cmsApi.revokeShare(share.id);
      toast.success('已撤销');
      void load();
    } catch (e) {
      toast.error((e as Error).message || '撤销失败');
    }
  };

  const copy = async (share: CmsShare) => {
    // url 由服务端按 blog_url 拼好；未配置时回退相对路径，仍可复制后自行拼域名
    const text = share.url || `/share/${share.token}`;
    try {
      await navigator.clipboard.writeText(text);
      toast.success('已复制');
    } catch {
      toast.error('复制失败，请手动选择链接文本');
    }
  };

  /** 前端过滤（接口一次返回全量，数据量小；避免为筛选再加接口参数）。输入即筛，无需回车 */
  const visible = useMemo(() => {
    const kw = keywordInput.trim().toLowerCase();
    return shares.filter((s) => {
      if (state === 'active' && s.expired) return false;
      if (state === 'expired' && !s.expired) return false;
      if (!kw) return true;
      return [s.remark, s.targetTitle, s.token].some((v) => (v || '').toLowerCase().includes(kw));
    });
  }, [shares, state, keywordInput]);

  const activeCount = shares.filter((s) => !s.expired).length;
  const firstLoading = loading && shares.length === 0;

  return (
    <div className="space-y-4">
      <PageHeader
        title="分享"
        description="把私密内容以只读链接临时开放给他人，可设置过期时间并随时撤销"
      />

      <ListCard
        toolbar={
          <>
            <div className="relative">
              <Search className="text-muted-foreground pointer-events-none absolute top-1/2 left-2.5 size-4 -translate-y-1/2" />
              <Input
                value={keywordInput}
                onChange={(e) => setKeywordInput(e.target.value)}
                placeholder="搜索备注 / 内容标题"
                className="w-60 pl-8"
              />
            </div>
            <Select value={state} onValueChange={(v) => setState(v === ALL ? '' : v)}>
              <SelectTrigger className="w-32">
                <SelectValue placeholder="全部状态" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value={ALL}>全部状态</SelectItem>
                <SelectItem value="active">有效</SelectItem>
                <SelectItem value="expired">已过期</SelectItem>
              </SelectContent>
            </Select>
            <Button
              variant="ghost"
              size="sm"
              className="text-muted-foreground ml-auto gap-1"
              onClick={() => {
                setKeywordInput('');
                setState('');
              }}
            >
              <RotateCcw className="size-3.5" />
              重置
            </Button>
            <span className="text-muted-foreground text-xs tabular-nums">
              共 {shares.length} 条 · 有效 {activeCount} 条
            </span>
          </>
        }
      >
        <CmsTable>
          <TableHeader>
            <TableRow className="hover:bg-transparent">
              <TableHead>内容</TableHead>
              <TableHead className="w-20">类型</TableHead>
              <TableHead className="w-44">备注</TableHead>
              <TableHead className="w-36">过期时间</TableHead>
              <TableHead className="w-20">状态</TableHead>
              <TableHead className="w-36">创建时间</TableHead>
              <TableHead className="w-24 text-right">操作</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {firstLoading ? (
              <TableStateRow colSpan={7} loading />
            ) : error ? (
              <TableStateRow colSpan={7} error={error} onRetry={() => void load()} />
            ) : visible.length === 0 ? (
              <TableStateRow
                colSpan={7}
                emptyText={shares.length === 0 ? '还没有分享链接' : '没有匹配的分享链接'}
                emptyHint={
                  shares.length === 0
                    ? '在文章或 Wiki 页面的编辑页底部「分享」面板里创建'
                    : '试着调整搜索关键词或状态筛选'
                }
              />
            ) : (
              visible.map((share) => (
                <TableRow key={share.id}>
                  <TableCell className="max-w-[280px]">
                    <span className="truncate font-medium" title={share.targetTitle || undefined}>
                      {share.targetTitle || `#${share.targetId}`}
                    </span>
                    <div
                      className="text-muted-foreground truncate font-mono text-xs"
                      title={share.url || `/share/${share.token}`}
                    >
                      {share.url || `/share/${share.token}`}
                    </div>
                  </TableCell>
                  <TableCell>
                    <ShareTargetBadge targetType={share.targetType} />
                  </TableCell>
                  <TableCell className="max-w-[180px]">
                    <span
                      className="text-muted-foreground truncate"
                      title={share.remark || undefined}
                    >
                      {share.remark || '-'}
                    </span>
                  </TableCell>
                  <TableCell className="text-muted-foreground">
                    {share.expireAt ? formatDateTime(share.expireAt) : '永久有效'}
                  </TableCell>
                  <TableCell>
                    {share.expired ? (
                      <Badge
                        variant="outline"
                        className="text-muted-foreground gap-1.5 font-normal"
                      >
                        <span
                          aria-hidden
                          className="size-1.5 shrink-0 rounded-full bg-current opacity-70"
                        />
                        已过期
                      </Badge>
                    ) : (
                      <Badge variant="success" className="gap-1.5 font-normal">
                        <span
                          aria-hidden
                          className="size-1.5 shrink-0 rounded-full bg-current opacity-70"
                        />
                        有效
                      </Badge>
                    )}
                  </TableCell>
                  <TableCell className="text-muted-foreground tabular-nums">
                    {formatDateTime(share.createTime)}
                  </TableCell>
                  <TableCell>
                    <div className="flex items-center justify-end gap-0.5">
                      <Button
                        variant="ghost"
                        size="icon"
                        className="text-muted-foreground size-8"
                        aria-label="复制链接"
                        title="复制链接"
                        onClick={() => void copy(share)}
                      >
                        <Copy className="size-4" />
                      </Button>
                      {canEdit && (
                        <Button
                          variant="ghost"
                          size="icon"
                          className="text-muted-foreground hover:text-destructive size-8"
                          aria-label="撤销"
                          title="撤销"
                          onClick={() => void revoke(share)}
                        >
                          <Trash2 className="size-4" />
                        </Button>
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
