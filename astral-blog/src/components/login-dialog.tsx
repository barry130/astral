'use client';

import { useCallback, useState } from 'react';
import { toast } from 'sonner';
import { Button } from '@/components/ui/button';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { useSession } from '@/components/session-provider';

/**
 * 登录弹窗。
 *
 * 密码在这里以明文提交到本站 Route Handler（`/api/auth/login`），
 * 由服务端用后端 RSA 公钥加密后再转发 —— 浏览器包里因此不需要 jsencrypt，
 * 也不会有公钥获取失败后「密码已经输完却传不出去」的中间态。
 */

interface LoginDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

interface ResultEnvelope {
  code?: number;
  message?: string | null;
  errorCode?: string | null;
  data?: { mustChangePassword?: number | string | null } | null;
}

export function LoginDialog({ open, onOpenChange }: LoginDialogProps) {
  const { refresh } = useSession();
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [totpCode, setTotpCode] = useState('');
  const [needTotp, setNeedTotp] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const reset = useCallback(() => {
    setPassword('');
    setTotpCode('');
    setError(null);
    setNeedTotp(false);
  }, []);

  const handleOpenChange = (next: boolean) => {
    if (!next) reset();
    onOpenChange(next);
  };

  const submit = async (event: React.FormEvent) => {
    event.preventDefault();
    if (submitting) return;
    if (!username.trim() || !password) {
      setError('请输入用户名和密码。');
      return;
    }

    setSubmitting(true);
    setError(null);
    try {
      const res = await fetch('/api/auth/login', {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({
          username: username.trim(),
          password,
          ...(needTotp && totpCode.trim() ? { totpCode: totpCode.trim() } : {}),
        }),
      });
      const body = (await res.json().catch(() => null)) as ResultEnvelope | null;

      if (res.ok && body?.code === 200) {
        await refresh();
        toast.success('登录成功');
        handleOpenChange(false);
        setUsername('');
        return;
      }

      // 后端用错误码区分「要动态码」与「密码错」，文案统一由后端给
      if (body?.errorCode === 'AUTH010' || body?.errorCode === 'AUTH011') {
        setNeedTotp(true);
      }
      setError(body?.message || '登录失败，请稍后重试。');
    } catch {
      setError('网络异常，请稍后重试。');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent className="sm:max-w-[380px]">
        <DialogHeader>
          <DialogTitle>登录</DialogTitle>
          <DialogDescription>
            登录后可直接在文章页面编辑内容。
          </DialogDescription>
        </DialogHeader>

        <form onSubmit={submit} className="space-y-4">
          <div className="space-y-1.5">
            <Label htmlFor="login-username">用户名</Label>
            <Input
              id="login-username"
              value={username}
              onChange={(event) => setUsername(event.target.value)}
              autoComplete="username"
              autoFocus
            />
          </div>

          <div className="space-y-1.5">
            <Label htmlFor="login-password">密码</Label>
            <Input
              id="login-password"
              type="password"
              value={password}
              onChange={(event) => setPassword(event.target.value)}
              autoComplete="current-password"
            />
          </div>

          {needTotp ? (
            <div className="space-y-1.5">
              <Label htmlFor="login-totp">动态验证码</Label>
              <Input
                id="login-totp"
                value={totpCode}
                onChange={(event) => setTotpCode(event.target.value)}
                inputMode="numeric"
                autoComplete="one-time-code"
                placeholder="6 位数字"
              />
            </div>
          ) : null}

          {error ? (
            <p className="rounded-md border border-destructive/25 bg-destructive/5 px-3 py-2 text-[12.5px] leading-relaxed text-destructive">
              {error}
            </p>
          ) : null}

          <DialogFooter>
            <Button type="button" variant="ghost" onClick={() => handleOpenChange(false)}>
              取消
            </Button>
            <Button type="submit" disabled={submitting}>
              {submitting ? '登录中…' : '登录'}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
