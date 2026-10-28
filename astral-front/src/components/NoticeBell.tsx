'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { useRouter } from 'next/navigation';
import { Bell, Check, ChevronRight } from 'lucide-react';

import { request } from '@/api/client';
import type { SysNotice } from '@/api/notice';
import { noticeTypeLabel } from '@/api/notice';
import { cn } from '@/lib/utils';
import { parseNoticeLink } from '@/lib/notice-link';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';

/**
 * 顶栏消息提示铃铛
 * <p>数据源为统一通知（sys_notice）的 App 端消息中心接口（channel=pc，管理端会话同样放行），
 * 复用既有「已读由前端缓存判断」的机制：已读 id 记录在 localStorage，后端无已读状态。</p>
 * <p>通知接口异常时静默降级为无角标，不影响控制台使用；Popover 底部提供「全部通知」入口，
 * 跳转到「消息中心 → 通知管理」页签（/dashboard/system/mail?tab=notice）。</p>
 */

/** 已读记录的本地缓存键（PC 管理端收件箱） */
const READ_KEY = 'astral:notice-read:admin';
/** 本地最多保留的已读 id 数，防止 localStorage 无限增长 */
const READ_LIMIT = 500;

function loadReadIds(): Set<number> {
  try {
    const raw = localStorage.getItem(READ_KEY);
    if (!raw) return new Set();
    const arr = JSON.parse(raw);
    return new Set(Array.isArray(arr) ? arr.filter((v) => typeof v === 'number') : []);
  } catch {
    return new Set();
  }
}

function saveReadIds(set: Set<number>) {
  try {
    // 只保留最近 READ_LIMIT 个，避免长期膨胀
    const arr = Array.from(set).slice(-READ_LIMIT);
    localStorage.setItem(READ_KEY, JSON.stringify(arr));
  } catch {
    // localStorage 不可用（隐私模式等）：本次会话内仍生效，不持久化
  }
}

/** 通知类型 → 徽章配色（纯展示；两档口径：公告=蓝，其余一律按「通知」=灰） */
const TYPE_VARIANT: Record<string, 'default' | 'secondary' | 'outline' | 'success' | 'warning' | 'destructive'> = {
  announce: 'default',
};

function fmtTime(v?: string): string {
  if (!v) return '';
  return v.slice(0, 16).replace('T', ' ');
}

export default function NoticeBell({ buttonStyle: _buttonStyle }: { buttonStyle?: React.CSSProperties }) {
  const router = useRouter();
  const [notices, setNotices] = useState<SysNotice[]>([]);
  const [readIds, setReadIds] = useState<Set<number>>(() => loadReadIds());
  const [open, setOpen] = useState(false);
  const [detail, setDetail] = useState<SysNotice | null>(null);
  // astral:// 是宿主自定义协议，浏览器不认识，必须先解析成站内路由再渲染成按钮
  const detailLink = useMemo(() => parseNoticeLink(detail?.url), [detail?.url]);

  const load = useCallback(() => {
    // 管理端收件箱：不按 channel 过滤，广播 + 发给当前管理员的点对点全可见
    request
      .get('/api/v1/admin/message/inbox')
      .then((res: any) => setNotices(res.data || []))
      .catch(() => {
        // 通知接口异常（模块未启用 / 网络失败）：角标归零，不弹错误打扰
        setNotices([]);
      });
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  const unread = useMemo(
    () => notices.filter((n) => n.id != null && !readIds.has(n.id!)),
    [notices, readIds],
  );

  const markRead = (n: SysNotice) => {
    if (n.id == null || readIds.has(n.id)) return;
    const next = new Set(readIds);
    next.add(n.id);
    setReadIds(next);
    saveReadIds(next);
  };

  const markAllRead = () => {
    const next = new Set(readIds);
    notices.forEach((n) => {
      if (n.id != null) next.add(n.id);
    });
    setReadIds(next);
    saveReadIds(next);
  };

  return (
    <>
      <Popover
        open={open}
        onOpenChange={(v) => {
          setOpen(v);
          if (v) load(); // 展开时刷新一次，角标跟进最新通知
        }}
      >
        <PopoverTrigger asChild>
          <Button variant="ghost" size="icon" title="消息通知" aria-label="消息通知">
            <span className="relative">
              <Bell className="size-4" />
              {unread.length > 0 && (
                <span className="absolute -right-1.5 -top-1.5 flex h-4 min-w-4 items-center justify-center rounded-full bg-destructive px-1 text-[10px] font-semibold leading-none text-white">
                  {unread.length > 99 ? '99+' : unread.length}
                </span>
              )}
            </span>
          </Button>
        </PopoverTrigger>
        <PopoverContent align="end" className="w-[340px] max-w-[calc(100vw-2rem)] p-2">
          <div className="flex items-center justify-between px-1 pb-2">
            <span className="text-sm font-semibold">消息通知（{unread.length} 未读）</span>
            {unread.length > 0 && (
              <Button variant="link" size="sm" className="h-auto p-0" onClick={markAllRead}>
                <Check />
                全部已读
              </Button>
            )}
          </div>
          <div className="max-h-[380px] overflow-y-auto">
            {notices.length === 0 ? (
              <div className="flex flex-col items-center gap-2 px-4 py-8 text-sm text-muted-foreground">
                <Bell className="size-6 opacity-40" strokeWidth={1.5} aria-hidden />
                <span>暂无通知</span>
              </div>
            ) : (
              <ul className="space-y-0.5">
                {notices.map((n) => {
                  const isUnread = n.id != null && !readIds.has(n.id);
                  return (
                    <li
                      key={String(n.id)}
                      onClick={() => {
                        markRead(n);
                        setOpen(false);
                        // 带站内跳转目标的通知直接跳转；纯文本通知才开详情弹层
                        const link = parseNoticeLink(n.url);
                        if (link?.kind === 'internal') {
                          router.push(link.href);
                          return;
                        }
                        if (link?.kind === 'external') {
                          window.open(link.url, '_blank', 'noopener,noreferrer');
                          return;
                        }
                        setDetail(n);
                      }}
                      className="cursor-pointer rounded-md px-2 py-2 transition-colors hover:bg-accent"
                    >
                      <div className="flex items-center gap-1.5">
                        {isUnread && <span className="size-1.5 shrink-0 rounded-full bg-blue-500" aria-hidden />}
                        {n.isTop === 1 && <Badge variant="destructive">置顶</Badge>}
                        <span className={cn('truncate text-sm', isUnread ? 'font-semibold text-foreground' : 'text-foreground/90')}>
                          {n.title}
                        </span>
                      </div>
                      <div className="mt-1 flex items-center gap-1.5 pl-2 text-xs text-muted-foreground">
                        <Badge variant={TYPE_VARIANT[n.noticeType || ''] || 'secondary'} className="px-1.5 py-0">
                          {noticeTypeLabel(n.noticeType)}
                        </Badge>
                        <span>{fmtTime(n.createTime)}</span>
                      </div>
                    </li>
                  );
                })}
              </ul>
            )}
          </div>
          {/* 原 Popover 里只有列表、没有任何跳转入口（独立路由 /dashboard/message 已删除），
              这里补「全部通知」→ 消息中心的「通知管理」页签（tab 参数由该页读取） */}
          <div className="mt-1 border-t border-border pt-1">
            <Button
              variant="ghost"
              size="sm"
              className="w-full justify-center gap-1 font-normal"
              onClick={() => {
                setOpen(false);
                router.push('/dashboard/system/mail?tab=notice');
              }}
            >
              全部通知
              <ChevronRight className="size-3.5" aria-hidden />
            </Button>
          </div>
        </PopoverContent>
      </Popover>

      <Dialog open={detail != null} onOpenChange={(v) => !v && setDetail(null)}>
        <DialogContent className="max-w-[520px]">
          <DialogHeader>
            <DialogTitle>{detail?.title}</DialogTitle>
          </DialogHeader>
          <div className="flex items-center gap-2 text-xs text-muted-foreground">
            <Badge variant={TYPE_VARIANT[detail?.noticeType || ''] || 'secondary'}>
              {noticeTypeLabel(detail?.noticeType)}
            </Badge>
            <span>{fmtTime(detail?.createTime)}</span>
          </div>
          <div className="whitespace-pre-wrap leading-relaxed">{detail?.content}</div>
          {detailLink && (
            <div className="text-sm">
              {detailLink.kind === 'internal' && (
                <Button
                  variant="link"
                  className="h-auto p-0 text-primary"
                  onClick={() => {
                    // 先关弹层再路由：详情来源于列表快照，跳转后由目标页自己拉最新数据
                    setDetail(null);
                    router.push(detailLink.href);
                  }}
                >
                  {detailLink.label}
                  <ChevronRight className="size-3.5" aria-hidden />
                </Button>
              )}
              {detailLink.kind === 'external' && (
                <a href={detailLink.url} target="_blank" rel="noreferrer" className="break-all text-primary underline underline-offset-4">
                  {detailLink.url}
                </a>
              )}
              {detailLink.kind === 'unsupported' && (
                <span className="break-all text-muted-foreground">{detailLink.raw}</span>
              )}
            </div>
          )}
        </DialogContent>
      </Dialog>
    </>
  );
}
