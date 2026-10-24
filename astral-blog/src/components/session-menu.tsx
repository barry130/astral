'use client';

import { useState } from 'react';
import { toast } from 'sonner';
import { Button } from '@/components/ui/button';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { LoginDialog } from '@/components/login-dialog';
import { useSession } from '@/components/session-provider';

/** 管理端地址：博客与管理端是两个应用，默认本地 3000 端口 */
const ADMIN_BASE = (process.env.NEXT_PUBLIC_ADMIN_URL || 'http://localhost:3000').replace(
  /\/+$/,
  '',
);

/**
 * 顶栏右侧的账号区。
 *
 * 未登录时只有一个安静的「登录」文字按钮（博客是公开站点，不抢访客注意力）；
 * 已登录则显示昵称首字头像，点开可以登出或跳去管理后台。
 */
export function SessionMenu() {
  const { loggedIn, user, loading, logout } = useSession();
  const [loginOpen, setLoginOpen] = useState(false);

  if (loading && !loggedIn) {
    // 首屏查询登录态时占位，避免按钮从「登录」闪成头像
    return <span aria-hidden className="h-10 w-14 shrink-0 sm:h-7" />;
  }

  if (!loggedIn) {
    return (
      <>
        <Button
          type="button"
          variant="ghost"
          size="sm"
          onClick={() => setLoginOpen(true)}
          className="h-10 shrink-0 px-3 text-[13px] text-ink-soft hover:text-ink sm:h-7 sm:px-2 sm:text-[12.5px]"

        >
          登录
        </Button>
        <LoginDialog open={loginOpen} onOpenChange={setLoginOpen} />
      </>
    );
  }

  const displayName = user?.nickname || user?.username || '已登录';
  const initial = displayName.slice(0, 1).toUpperCase();

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <button
          type="button"
          aria-label={`账号：${displayName}`}
          className="flex h-10 w-10 shrink-0 items-center justify-center rounded-full border border-brand-line/70 bg-brand-wash text-[12px] font-medium text-brand transition-colors hover:border-brand/35 hover:bg-brand/10 sm:h-7 sm:w-7"

        >
          {initial}
        </button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="w-44">
        <DropdownMenuLabel className="text-[12px] font-normal text-ink-soft">
          {displayName}
        </DropdownMenuLabel>
        <DropdownMenuSeparator />
        <DropdownMenuItem asChild>
          {/* 管理端是另一个应用（默认 3000 端口），地址可用 NEXT_PUBLIC_ADMIN_URL 覆盖 */}
          <a
            href={`${ADMIN_BASE}/dashboard/cms/post`}
            target="_blank"
            rel="noreferrer"
            className="cursor-pointer text-[12.5px]"
          >
            内容管理后台
          </a>
        </DropdownMenuItem>
        <DropdownMenuItem
          onSelect={() => {
            void logout().then(() => toast.success('已退出登录'));
          }}
          className="text-[12.5px]"
        >
          退出登录
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
