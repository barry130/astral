'use client';

import { useState } from 'react';
import { useRouter } from 'next/navigation';
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
import { resolveSiteUrl } from '@/lib/site-url';

const ADMIN_BASE = resolveSiteUrl(
  process.env.NEXT_PUBLIC_ADMIN_URL ?? '',
  'http://localhost:3000',
).origin;

export function SessionMenu() {
  const router = useRouter();
  const { loggedIn, user, loading, error, canEdit, logout } = useSession();
  const [loginOpen, setLoginOpen] = useState(false);
  const displayName = user?.nickname || user?.username || '已登录';
  const initial = Array.from(displayName)[0]?.toUpperCase() ?? '·';
  return (
    <>
      {error ? (
        <span role="status" className="sr-only">
          {error}
        </span>
      ) : null}
      {loading && !loggedIn ? (
        <span aria-hidden className="h-10 w-14 shrink-0" />
      ) : !loggedIn ? (
        <Button
          type="button"
          variant="ghost"
          size="sm"
          onClick={() => setLoginOpen(true)}
          className="text-ink-soft hover:text-ink h-10 shrink-0 px-3 text-sm"
        >
          登录
        </Button>
      ) : (
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <button
              type="button"
              aria-label={`账号：${displayName}`}
              className="border-brand-line/70 bg-brand-wash text-brand hover:border-brand/35 hover:bg-brand/10 flex h-10 w-10 shrink-0 items-center justify-center rounded-full border text-sm font-medium transition-colors"
            >
              {initial}
            </button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="end" className="w-56">
            <DropdownMenuLabel className="text-ink-soft text-sm font-normal break-words">
              {displayName}
            </DropdownMenuLabel>
            {error ? <p className="text-destructive px-2 py-1.5 text-xs">{error}</p> : null}
            <DropdownMenuSeparator />
            {canEdit ? (
              <DropdownMenuItem asChild>
                <a
                  href={`${ADMIN_BASE}/dashboard/cms/post`}
                  target="_blank"
                  rel="noreferrer noopener"
                  className="cursor-pointer text-sm"
                >
                  内容管理后台
                </a>
              </DropdownMenuItem>
            ) : null}
            <DropdownMenuItem
              disabled={loading}
              onSelect={() => {
                void logout().then((outcome) => {
                  if (!outcome.localLoggedOut) {
                    toast.error(outcome.message);
                    return;
                  }
                  router.refresh();
                  if (outcome.upstreamRevoked) toast.success('已退出登录');
                  else toast.warning(outcome.message);
                });
              }}
              className="text-sm"
            >
              退出登录
            </DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenu>
      )}
      <LoginDialog open={loginOpen} onOpenChange={setLoginOpen} />
    </>
  );
}
