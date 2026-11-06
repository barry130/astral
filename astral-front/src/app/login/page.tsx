'use client';

import { useState, useEffect, type FormEvent } from 'react';
import { useRouter } from 'next/navigation';
import { Activity, ArrowLeft, KeyRound, Loader2, Lock, ShieldCheck, User } from 'lucide-react';
import { toast } from 'sonner';

import { useAuth } from '@/context/AuthContext';
import { encryptPassword, clearPublicKeyCache } from '@/lib/crypto';
import { BrandLockup } from '@/components/brand';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';

const LOGIN_CAPABILITIES = [
  {
    icon: ShieldCheck,
    title: '统一身份',
    description: '管理账号与会话在同一入口安全接入。',
  },
  {
    icon: KeyRound,
    title: '权限边界',
    description: '按角色与权限进入对应工作区域。',
  },
  {
    icon: Activity,
    title: '运行审计',
    description: '服务状态与操作记录保持可追踪。',
  },
] as const;

export default function LoginPage() {
  const [loading, setLoading] = useState(false);
  const [publicKeyLoading, setPublicKeyLoading] = useState(true);
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [totpCode, setTotpCode] = useState('');
  const [needTotp, setNeedTotp] = useState(false);
  const [fieldErrors, setFieldErrors] = useState<{
    username?: string;
    password?: string;
    totpCode?: string;
  }>({});
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
    } catch (error: unknown) {
      // AUTH010 = 该账号启用了 TOTP 二次验证，展开动态码输入框
      const errorCode = (error as { errorCode?: string })?.errorCode;
      if (errorCode === 'AUTH010') {
        setNeedTotp(true);
        toast.info('该账号已启用动态验证码，请输入验证器 App 中的 6 位动态码');
      } else {
        toast.error(error instanceof Error ? error.message || '登录失败' : '登录失败');
      }
    } finally {
      setLoading(false);
    }
  };

  // 校验登录状态期间不展示表单，避免闪现后跳转
  if (authLoading) {
    return (
      <div className="bg-background flex min-h-dvh items-center justify-center">
        <Loader2 className="text-muted-foreground size-8 animate-spin" />
      </div>
    );
  }

  return (
    <div className="login-container">
      <section aria-labelledby="login-brand-title" className="login-brand-panel">
        <BrandLockup inverted subtitle="应用与运维管理平台" />

        <div className="login-brand-copy">
          <p className="text-sm font-medium text-[color:var(--color-nav-muted)]">
            面向团队的管理入口
          </p>
          <h1 id="login-brand-title" className="login-brand-title">
            <span className="block">每一次管理操作，</span>
            <span className="block">都有清晰边界。</span>
          </h1>
          <p className="login-brand-description">
            从身份验证到权限校验，再到服务监控与操作留痕， Astral
            将日常运维需要的信息收拢在同一个工作区。
          </p>

          <div className="login-flow" aria-label="平台能力">
            {LOGIN_CAPABILITIES.map(({ icon: Icon, title, description }) => (
              <div key={title} className="login-flow-item">
                <Icon
                  className="size-5 text-[color:var(--color-nav-accent)]"
                  strokeWidth={1.7}
                  aria-hidden="true"
                />
                <strong>{title}</strong>
                <span>{description}</span>
              </div>
            ))}
          </div>
        </div>

        <p className="login-brand-footer">Astral · 连接应用、数据与团队协作</p>
      </section>

      <main className="login-form-panel">
        <div className="login-box">
          <div className="mb-8">
            <p className="mb-2 text-sm font-medium text-[color:var(--color-brand-text)]">
              管理控制台
            </p>
            <h2 className="login-title">欢迎回来</h2>
            <p className="login-subtitle">使用管理员账号登录，继续处理今日工作。</p>
          </div>

          <form onSubmit={onFinish} autoComplete="off" noValidate>
            <div className="space-y-5">
              <div>
                <label htmlFor="login-username" className="login-field-label">
                  用户名
                </label>
                <div className="relative">
                  <User className="text-muted-foreground pointer-events-none absolute top-1/2 left-3.5 size-4 -translate-y-1/2" />
                  <Input
                    id="login-username"
                    name="username"
                    spellCheck={false}
                    className="h-12 pl-10 text-sm"
                    placeholder="请输入用户名"
                    autoComplete="username"
                    value={username}
                    onChange={(e) => setUsername(e.target.value)}
                    aria-invalid={!!fieldErrors.username}
                    aria-describedby={fieldErrors.username ? 'login-username-error' : undefined}
                  />
                </div>
                {fieldErrors.username && (
                  <p id="login-username-error" className="text-destructive mt-1.5 text-xs">
                    {fieldErrors.username}
                  </p>
                )}
              </div>

              <div>
                <label htmlFor="login-password" className="login-field-label">
                  密码
                </label>
                <div className="relative">
                  <Lock className="text-muted-foreground pointer-events-none absolute top-1/2 left-3.5 size-4 -translate-y-1/2" />
                  <Input
                    id="login-password"
                    name="password"
                    spellCheck={false}
                    type="password"
                    className="h-12 pl-10 text-sm"
                    placeholder="请输入密码"
                    autoComplete="current-password"
                    value={password}
                    onChange={(e) => setPassword(e.target.value)}
                    aria-invalid={!!fieldErrors.password}
                    aria-describedby={fieldErrors.password ? 'login-password-error' : undefined}
                  />
                </div>
                {fieldErrors.password && (
                  <p id="login-password-error" className="text-destructive mt-1.5 text-xs">
                    {fieldErrors.password}
                  </p>
                )}
              </div>

              {needTotp && (
                <div>
                  <label htmlFor="login-totp" className="login-field-label">
                    动态验证码
                  </label>
                  <div className="relative">
                    <KeyRound className="text-muted-foreground pointer-events-none absolute top-1/2 left-3.5 size-4 -translate-y-1/2" />
                    <Input
                      id="login-totp"
                      name="totpCode"
                      spellCheck={false}
                      className="h-12 pl-10 text-sm tracking-[0.2em]"
                      placeholder="6 位动态验证码"
                      inputMode="numeric"
                      maxLength={6}
                      value={totpCode}
                      onChange={(e) => setTotpCode(e.target.value)}
                      autoComplete="one-time-code"
                    />
                  </div>
                  <p className="text-muted-foreground mt-1.5 text-xs">
                    来自验证器 App（Google Authenticator 等），30 秒刷新
                  </p>
                </div>
              )}

              <Button
                type="submit"
                disabled={loading || publicKeyLoading}
                className="mt-2 h-12 w-full text-sm font-semibold"
              >
                {(loading || publicKeyLoading) && <Loader2 className="animate-spin" />}
                {publicKeyLoading ? '正在准备安全登录' : loading ? '正在登录' : '登录控制台'}
              </Button>
            </div>
          </form>

          <div className="mt-6 flex items-center justify-between gap-4 border-t border-[color:var(--color-border-light)] pt-5">
            <button
              type="button"
              onClick={() => router.push('/')}
              className="text-muted-foreground hover:text-foreground inline-flex cursor-pointer items-center gap-1.5 text-xs"
            >
              <ArrowLeft className="size-3.5" aria-hidden="true" />
              返回首页
            </button>
            <span className="text-muted-foreground inline-flex items-center gap-1.5 text-[11px]">
              <ShieldCheck className="size-3.5" aria-hidden="true" />
              密码经 RSA 加密后传输
            </span>
          </div>
        </div>
      </main>
    </div>
  );
}
