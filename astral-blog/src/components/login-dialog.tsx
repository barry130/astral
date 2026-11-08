'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { useRouter } from 'next/navigation';
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
import { requestJson } from '@/lib/client-request';
import { parseResult, resultMessage } from '@/lib/result';

/** Passwords travel only to the same-origin BFF, which performs RSA encryption. */
export function LoginDialog({
  open,
  onOpenChange,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
}) {
  const router = useRouter();
  const { refresh } = useSession();
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [totpCode, setTotpCode] = useState('');
  const [needTotp, setNeedTotp] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const busy = useRef(false);
  const generation = useRef(0);
  const mounted = useRef(true);
  const controller = useRef<AbortController | null>(null);
  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
      generation.current += 1;
      controller.current?.abort();
    };
  }, []);
  useEffect(() => {
    if (!open) {
      generation.current += 1;
      controller.current?.abort();
      controller.current = null;
      busy.current = false;
      setSubmitting(false);
      setPassword('');
      setTotpCode('');
      setNeedTotp(false);
      setConfirming(false);
      setError(null);
    }
  }, [open]);
  const close = useCallback(
    (next: boolean) => {
      if (!next && busy.current) return;
      onOpenChange(next);
    },
    [onOpenChange],
  );
  const confirmSession = async (current: number) => {
    const verified = await refresh();
    if (!mounted.current || current !== generation.current) return;
    if (!verified) {
      setConfirming(true);
      setError('会话尚未确认。请重新确认会话，不要重复提交密码；也可取消后稍后重试。');
      return;
    }
    router.refresh();
    toast.success('登录成功');
    busy.current = false;
    setPassword('');
    setTotpCode('');
    setUsername('');
    setConfirming(false);
    close(false);
  };
  const submit = async (event: React.FormEvent) => {
    event.preventDefault();
    if (busy.current) return;
    if (
      !confirming &&
      (!username.trim() ||
        username.trim().length > 64 ||
        !password ||
        password.length > 128 ||
        (needTotp && !/^\d{6}$/.test(totpCode.trim())))
    ) {
      setError('请填写有效用户名、密码和所需的 6 位验证码。');
      return;
    }
    busy.current = true;
    const current = ++generation.current;
    controller.current = new AbortController();
    setSubmitting(true);
    setError(null);
    try {
      if (confirming) {
        await confirmSession(current);
        return;
      }
      const { response, body: raw } = await requestJson('/api/auth/login', {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        signal: controller.current.signal,
        body: JSON.stringify({
          username: username.trim(),
          password,
          ...(needTotp ? { totpCode: totpCode.trim() } : {}),
        }),
      });
      if (!mounted.current || current !== generation.current) return;
      const body = parseResult(raw);
      if (response.ok && body?.code === 200) {
        setPassword('');
        await confirmSession(current);
        return;
      }
      if (body?.errorCode === 'AUTH010' || body?.errorCode === 'AUTH011') setNeedTotp(true);
      setError(resultMessage(body, '登录失败，请稍后重试。'));
    } catch {
      if (mounted.current && current === generation.current) {
        setConfirming(true);
        setPassword('');
        setError('登录结果无法确认，请先确认会话或稍后重试。');
      }
    } finally {
      if (mounted.current && current === generation.current) {
        busy.current = false;
        setSubmitting(false);
      }
    }
  };
  return (
    <Dialog open={open} onOpenChange={close}>
      <DialogContent
        showCloseButton={!submitting}
        className="sm:max-w-[380px]"
        onEscapeKeyDown={(event) => {
          if (busy.current) event.preventDefault();
        }}
        onInteractOutside={(event) => {
          if (busy.current) event.preventDefault();
        }}
      >
        <DialogHeader>
          <DialogTitle>登录</DialogTitle>
          <DialogDescription>
            具有内容查看与编辑权限的管理员可在页面编辑。其他账号仅按其权限浏览。
          </DialogDescription>
        </DialogHeader>
        <form onSubmit={submit} aria-busy={submitting} className="space-y-4">
          <div className="space-y-1.5">
            <Label htmlFor="login-username">用户名</Label>
            <Input
              id="login-username"
              name="username"
              spellCheck={false}
              required
              maxLength={64}
              disabled={submitting || confirming}
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
              name="password"
              type="password"
              required={!confirming}
              maxLength={128}
              disabled={submitting || confirming}
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
                name="totpCode"
                spellCheck={false}
                required
                pattern="[0-9]{6}"
                maxLength={6}
                disabled={submitting || confirming}
                value={totpCode}
                onChange={(event) => setTotpCode(event.target.value)}
                inputMode="numeric"
                autoComplete="one-time-code"
                placeholder="6 位数字…"
              />
            </div>
          ) : null}
          {error ? (
            <p
              role="alert"
              className="border-destructive/25 bg-destructive/5 text-destructive rounded-md border px-3 py-2 text-sm leading-relaxed"
            >
              {error}
            </p>
          ) : null}
          <DialogFooter>
            <Button
              type="button"
              variant="ghost"
              disabled={submitting}
              onClick={() => close(false)}
            >
              取消
            </Button>
            <Button type="submit" disabled={submitting}>
              {submitting ? '确认中…' : confirming ? '重新确认会话' : '登录'}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
