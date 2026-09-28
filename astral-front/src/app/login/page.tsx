'use client';

import { useState, useEffect, type FormEvent } from 'react';
import { useRouter } from 'next/navigation';
import { Loader2, User, Lock, ShieldCheck } from 'lucide-react';
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
  const [fieldErrors, setFieldErrors] = useState<{ username?: string; password?: string }>({});
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
      await login(username, encryptedPassword);
      clearPublicKeyCache();
      toast.success('登录成功');
      router.push('/dashboard');
    } catch (error: any) {
      toast.error(error.message || '登录失败');
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

        <div className="mt-6 text-center text-xs text-muted-foreground">默认账号: admin / admin</div>

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
