'use client';

import { useCallback, useEffect, useState } from 'react';
import { toast } from 'sonner';
import { Copy, Link2, Plus, Trash2 } from 'lucide-react';

import {
  cmsApi, cmsDictLabel, loadCmsDicts, CmsShare,
} from '@/api/cms';
import { usePerm } from '@/lib/perm';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Skeleton } from '@/components/ui/skeleton';
import { Badge } from '@/components/ui/badge';
import { cn } from '@/lib/utils';
import { formatDateTime, FormCard } from './shared';

/** 分享目标类型（与 CmsShare.targetType 一致） */
type ShareTarget = 'post' | 'page';

/**
 * 分享面板：列出某个内容的分享链接，并支持新建 / 撤销 / 复制。
 *
 * 只在「编辑已存在的内容」时渲染 —— 新建时还没有 targetId，链接无从生成。
 * 文章表单与 Wiki 页面表单共用本组件，文案差异只在标题说明里。
 */
export default function SharePanel({ targetType, targetId }: {
  targetType: ShareTarget;
  targetId: number;
}) {
  const canEdit = usePerm()('admin:cms:content:edit');
  const [shares, setShares] = useState<CmsShare[]>([]);
  const [loading, setLoading] = useState(true);
  const [creating, setCreating] = useState(false);
  /** 新建表单是否展开（默认收起，避免编辑页右侧被表单塞满） */
  const [open, setOpen] = useState(false);
  const [remark, setRemark] = useState('');
  /** 与后端 @JsonFormat 一致的 yyyy-MM-dd HH:mm:ss；留空 = 永久 */
  const [expireAt, setExpireAt] = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const res = await cmsApi.shareListByTarget(targetType, targetId);
      setShares(res.data || []);
    } catch (e) {
      // 面板加载失败不阻塞主表单，只提示一次
      toast.error((e as Error).message || '分享链接加载失败');
      setShares([]);
    } finally {
      setLoading(false);
    }
  }, [targetType, targetId]);

  useEffect(() => {
    void loadCmsDicts().catch(() => {/* 字典拉取失败时回退展示原始值 */});
    void load();
  }, [load]);

  const create = async () => {
    // 后端要求 yyyy-MM-dd HH:mm:ss；这里只做最基本的形状校验，语义校验（不能早于当前时间）交给后端
    const trimmed = expireAt.trim();
    if (trimmed && !/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}(:\d{2})?$/.test(trimmed)) {
      toast.error('过期时间格式应为 yyyy-MM-dd HH:mm:ss');
      return;
    }
    // 后端要求秒级完整格式，省略秒时补 :00
    const normalized = trimmed && trimmed.length === 16 ? `${trimmed}:00` : trimmed;
    setCreating(true);
    try {
      await cmsApi.createShare({
        targetType,
        targetId,
        expireAt: normalized || null,
        remark: remark.trim() || undefined,
      });
      toast.success('分享链接已创建');
      setRemark('');
      setExpireAt('');
      setOpen(false);
      void load();
    } catch (e) {
      toast.error((e as Error).message || '创建失败');
    } finally {
      setCreating(false);
    }
  };

  const revoke = async (share: CmsShare) => {
    if (!window.confirm('确认撤销该分享链接？撤销后已发出的链接立即失效。')) return;
    try {
      await cmsApi.revokeShare(share.id);
      toast.success('已撤销');
      void load();
    } catch (e) {
      toast.error((e as Error).message || '撤销失败');
    }
  };

  const revokeAll = async () => {
    if (!window.confirm(`确认撤销该内容的全部 ${shares.length} 条分享链接？`)) return;
    try {
      const res = await cmsApi.revokeShareByTarget(targetType, targetId);
      toast.success(`已撤销 ${res.data ?? 0} 条`);
      void load();
    } catch (e) {
      toast.error((e as Error).message || '撤销失败');
    }
  };

  const copy = async (share: CmsShare) => {
    // url 由服务端按 blog_url 拼好；未配置站点地址时回退相对路径，仍可复制后自行拼域名
    const text = share.url || `/share/${share.token}`;
    try {
      await navigator.clipboard.writeText(text);
      toast.success('已复制');
    } catch {
      toast.error('复制失败，请手动选择链接文本');
    }
  };

  const activeCount = shares.filter((s) => !s.expired).length;

  return (
    <FormCard
      title="分享"
      description="生成只读链接，把内容临时开放给未登录访客；可随时撤销、可设过期时间"
      footer={canEdit && shares.length > 0 ? (
        <div className="flex items-center justify-between gap-2">
          <span className="text-xs text-muted-foreground tabular-nums">
            共 {shares.length} 条 · 有效 {activeCount} 条
          </span>
          <Button variant="ghost" size="sm" className="h-7 px-2 text-xs text-muted-foreground"
                  onClick={revokeAll}>
            撤销全部
          </Button>
        </div>
      ) : undefined}
    >
      {loading ? (
        <div role="status" aria-busy="true" aria-label="分享链接加载中" className="space-y-3">
          <Skeleton className="h-4 w-2/3" />
          <Skeleton className="h-4 w-1/2" />
        </div>
      ) : (
        <>
          {shares.length === 0 ? (
            <p className="text-sm text-muted-foreground">
              还没有分享链接。创建后可把链接发给需要查看的人，撤销即立即失效。
            </p>
          ) : (
            <ul className="divide-y divide-border/60">
              {shares.map((share) => (
                <li key={share.id} className={cn('space-y-1.5 py-2.5 first:pt-0 last:pb-0',
                  share.expired && 'opacity-60')}>
                  <div className="flex items-start justify-between gap-2">
                    <div className="min-w-0 space-y-0.5">
                      <p className="truncate text-sm text-foreground" title={share.remark || undefined}>
                        {share.remark || '未填备注'}
                      </p>
                      <p className="text-xs text-muted-foreground">
                        {share.expired ? (
                          <span className="text-muted-foreground">
                            已过期（{formatDateTime(share.expireAt)}）
                          </span>
                        ) : share.expireAt ? (
                          <>有效期至 {formatDateTime(share.expireAt)}</>
                        ) : (
                          '永久有效'
                        )}
                        {' · '}
                        {formatDateTime(share.createTime)} 创建
                      </p>
                    </div>
                    <div className="flex shrink-0 items-center gap-0.5">
                      <Button variant="ghost" size="icon" className="size-7 text-muted-foreground"
                              aria-label="复制链接" title="复制链接" onClick={() => void copy(share)}>
                        <Copy className="size-3.5" />
                      </Button>
                      {canEdit && (
                        <Button variant="ghost" size="icon" className="size-7 text-muted-foreground"
                                aria-label="撤销分享链接" title="撤销"
                                onClick={() => void revoke(share)}>
                          <Trash2 className="size-3.5" />
                        </Button>
                      )}
                    </div>
                  </div>
                  {share.url ? (
                    <button
                      type="button"
                      onClick={() => void copy(share)}
                      className="block w-full truncate rounded-md bg-muted/50 px-2 py-1 text-left font-mono text-[11px] text-muted-foreground transition-colors hover:text-foreground"
                      title="点击复制"
                    >
                      {share.url}
                    </button>
                  ) : (
                    <div className="space-y-1">
                      <p className="text-xs leading-relaxed text-muted-foreground">
                        请先在站点设置里配置博客前台地址（blog_url）
                      </p>
                      <button
                        type="button"
                        onClick={() => void copy(share)}
                        className="block w-full truncate rounded-md bg-muted/50 px-2 py-1 text-left font-mono text-[11px] text-muted-foreground transition-colors hover:text-foreground"
                        title="点击复制相对路径"
                      >
                        /share/{share.token}
                      </button>
                    </div>
                  )}
                </li>
              ))}
            </ul>
          )}

          {canEdit && (
            open ? (
              <div className="space-y-3 rounded-lg border border-border/60 p-3">
                <div className="space-y-1.5">
                  <Label htmlFor="share-remark" className="text-xs">备注</Label>
                  <Input id="share-remark" value={remark} maxLength={256}
                         onChange={(e) => setRemark(e.target.value)}
                         placeholder="用途说明，便于日后辨认，如：给小王临时看" />
                </div>
                <div className="space-y-1.5">
                  <Label htmlFor="share-expire" className="text-xs">过期时间</Label>
                  <Input id="share-expire" value={expireAt} onChange={(e) => setExpireAt(e.target.value)}
                         placeholder="yyyy-MM-dd HH:mm:ss，留空为永久" />
                </div>
                <div className="flex items-center justify-end gap-2">
                  <Button variant="ghost" size="sm" className="text-muted-foreground"
                          disabled={creating} onClick={() => setOpen(false)}>
                    取消
                  </Button>
                  <Button size="sm" disabled={creating} onClick={() => void create()}>
                    {creating ? '创建中…' : '创建链接'}
                  </Button>
                </div>
              </div>
            ) : (
              <Button variant="outline" size="sm" className="gap-1.5" onClick={() => setOpen(true)}>
                <Plus className="size-3.5" />新建分享链接
              </Button>
            )
          )}
        </>
      )}
    </FormCard>
  );
}

/**
 * 分享目标类型徽标（分享管理页用）：文案走字典 `cms_share_target_type`，
 * 未登录/字典未加载成功时回退展示原始值。
 */
export function ShareTargetBadge({ targetType }: { targetType: string }) {
  return (
    <Badge variant="outline" className="gap-1 font-normal text-muted-foreground">
      <Link2 className="size-3" />
      {cmsDictLabel('cms_share_target_type', targetType)}
    </Badge>
  );
}
