'use client';

import { useState, useEffect, type FormEvent } from 'react';
import { useRouter } from 'next/navigation';
import { Loader2, User, Lock, ShieldCheck, KeyRound } from 'lucide-react';
import { toast } from 'sonner';

import { useAuth } from '@/context/AuthContext';
import { encryptPassword, clearPublicKeyCache } from '@/lib/crypto';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';

export default function LoginPage() {
  const [loading, setLoading] = useState(false);
  const [publicKeyLoading, setPublicKeyLoading] = useState(true);
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [totpCode, setTotpCode] = useState('');
  const [needTotp, setNeedTotp] = useState(false);
  const [fieldErrors, setFieldErrors] = useState<{ username?: string; password?: string; totpCode?: string }>({});
  const { login, isLogin, loading: authLoading } = useAuth();
  const router = useRouter();

  // 已登录用户直接跳转到首页（仪表盘），避免重复登录
  useEffect(() => {
    if (!authLoading && isLogin) {
      router.replace('/dashboard');
    }
  }, [authLoading, isLogin, router]);

  useEffect(() => {
    encryptPassword('test')
      .then(() => {
        setPublicKeyLoading(false);
      })
      .catch((error) => {
        console.error('加密初始化失败:', error);
        toast.error(error.message || '加密初始化失败，请确保后端服务已启动');
      });
  }, []);

  const onFinish = async (e: FormEvent) => {
    e.preventDefault();
    // 轻量校验（替代 antd Form rules）
    const errors: { username?: string; password?: string } = {};
    if (!username.trim()) errors.username = '请输入用户名';
    if (!password.trim()) errors.password = '请输入密码';
    setFieldErrors(errors);
    if (Object.keys(errors).length > 0) return;

    setLoading(true);
    try {
      const encryptedPassword = await encryptPassword(password);
      const encryptedTotp = needTotp && totpCode.trim() ? totpCode.trim() : undefined;
      const data = await login(username, encryptedPassword, encryptedTotp);
      clearPublicKeyCache();
      toast.success('登录成功');
      // 强制改密账号：登录后必须先到个人中心改密码（后端 must_change_password=1）
      router.push(data?.mustChangePassword === 1 ? '/dashboard/profile?force=1' : '/dashboard');
    } catch (error: any) {
      // AUTH010 = 该账号启用了 TOTP 二次验证，展开动态码输入框
      if (error?.errorCode === 'AUTH010') {
        setNeedTotp(true);
        toast.info('该账号已启用动态验证码，请输入验证器 App 中的 6 位动态码');
      } else {
        toast.error(error.message || '登录失败');
      }
    } finally {
      setLoading(false);
    }
  };

  // 校验登录状态期间不展示表单，避免闪现后跳转
  if (authLoading) {
    return (
      <div className="flex h-screen items-center justify-center">
        <Loader2 className="size-8 animate-spin text-muted-foreground" />
      </div>
    );
  }

  return (
    <div className="login-container">
      <div className="login-box fade-in-up">
        <div className="mb-8 text-center">
          <div
            className="mb-5 inline-flex size-14 items-center justify-center rounded-[14px] bg-primary shadow-[0_8px_20px_rgba(24,24,27,0.16)]"
          >
            <ShieldCheck className="size-6 text-primary-foreground" />
          </div>
          <h1 className="login-title">Astral</h1>
          <p className="login-subtitle">Astral 后台管理系统</p>
        </div>

        <form onSubmit={onFinish} autoComplete="off" noValidate>
          <div className="space-y-4">
            <div className="space-y-1.5">
              <div className="relative">
                <User className="pointer-events-none absolute left-3.5 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" />
                <Input
                  className="h-11 rounded-[10px] pl-10"
                  placeholder="用户名"
                  autoComplete="username"
                  value={username}
                  onChange={(e) => setUsername(e.target.value)}
                  aria-invalid={!!fieldErrors.username}
                />
              </div>
              {fieldErrors.username && <p className="text-xs text-destructive">{fieldErrors.username}</p>}
            </div>

            <div className="space-y-1.5">
              <div className="relative">
                <Lock className="pointer-events-none absolute left-3.5 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" />
                <Input
                  type="password"
                  className="h-11 rounded-[10px] pl-10"
                  placeholder="密码"
                  autoComplete="current-password"
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  aria-invalid={!!fieldErrors.password}
                />
              </div>
              {fieldErrors.password && <p className="text-xs text-destructive">{fieldErrors.password}</p>}
            </div>

            {needTotp && (
              <div className="space-y-1.5">
                <div className="relative">
                  <KeyRound className="pointer-events-none absolute left-3.5 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" />
                  <Input
                    className="h-11 rounded-[10px] pl-10"
                    placeholder="动态验证码（6 位）"
                    inputMode="numeric"
                    maxLength={6}
                    value={totpCode}
                    onChange={(e) => setTotpCode(e.target.value)}
                    autoComplete="one-time-code"
                  />
                </div>
                <p className="text-xs text-muted-foreground">来自验证器 App（Google Authenticator 等），30 秒刷新</p>
              </div>
            )}

            <Button
              type="submit"
              disabled={loading || publicKeyLoading}
              className="mt-4 h-11 w-full rounded-[10px] text-[15px] font-semibold"
            >
              {(loading || publicKeyLoading) && <Loader2 className="animate-spin" />}
              登 录
            </Button>
          </div>
        </form>

        <div className="mt-3 text-center">
          <a
            onClick={() => router.push('/')}
            className="cursor-pointer text-xs text-muted-foreground hover:text-foreground"
          >
            ← 返回首页
          </a>
        </div>
      </div>
    </div>
  );
}
