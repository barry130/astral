'use client';

import { Suspense, useCallback, useEffect, useState } from 'react';
import { useSearchParams, useRouter } from 'next/navigation';
import { toast } from 'sonner';
import QRCode from 'qrcode';
import {
  User as UserIcon,
  KeyRound,
  ShieldCheck,
  MonitorSmartphone,
  Lock,
} from 'lucide-react';

import { useAuth } from '@/context/AuthContext';
import { profileApi, ProfileInfo, ProfileSession, TotpSetupInfo } from '@/api/profile';
import { encryptPassword } from '@/lib/crypto';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { Badge } from '@/components/ui/badge';
import {
  Table, TableBody, TableCell, TableHead, TableHeader, TableRow,
} from '@/components/ui/table';

/**
 * 个人中心：基本资料 / 修改密码 / TOTP 二次验证 / 我的会话
 * 后端 /api/v1/admin/profile/**（登录即可，不挂权限码）。
 */
export default function ProfilePage() {
  return (
    <Suspense fallback={<div className="py-10 text-center text-muted-foreground">加载中…</div>}>
      <ProfileContent />
    </Suspense>
  );
}

function ProfileContent() {
  const searchParams = useSearchParams();
  const router = useRouter();
  const { logout } = useAuth();

  const [info, setInfo] = useState<ProfileInfo | null>(null);
  const [loading, setLoading] = useState(true);
  const [savingPassword, setSavingPassword] = useState(false);
  const [oldPassword, setOldPassword] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');

  const [totpInfo, setTotpInfo] = useState<TotpSetupInfo | null>(null);
  const [totpQrDataUrl, setTotpQrDataUrl] = useState<string>('');
  const [totpCode, setTotpCode] = useState('');
  const [totpBusy, setTotpBusy] = useState(false);
  const [disablePassword, setDisablePassword] = useState('');

  const [sessions, setSessions] = useState<ProfileSession[]>([]);
  const [sessionsLoading, setSessionsLoading] = useState(false);

  const loadMe = useCallback(async () => {
    setLoading(true);
    try {
      const res = await profileApi.me();
      setInfo(res.data);
    } catch (e: any) {
      toast.error(e.message || '加载个人资料失败');
    } finally {
      setLoading(false);
    }
  }, []);

  const loadSessions = useCallback(async () => {
    setSessionsLoading(true);
    try {
      const res = await profileApi.sessions(1, 50);
      setSessions(res.data?.records || []);
    } catch (e: any) {
      toast.error(e.message || '加载会话失败');
    } finally {
      setSessionsLoading(false);
    }
  }, []);

  useEffect(() => {
    void loadMe();
    void loadSessions();
  }, [loadMe, loadSessions]);

  /** 强制改密账号：force=1 时未改密码前不提供其它入口 */
  const forceChange = searchParams.get('force') === '1' && info?.mustChangePassword === true;

  const handleChangePassword = async () => {
    if (!oldPassword || !newPassword || !confirmPassword) {
      toast.error('请填写完整');
      return;
    }
    if (newPassword !== confirmPassword) {
      toast.error('两次输入的新密码不一致');
      return;
    }
    // 与后端 PasswordPolicy 一致的本地预检（后端为准，这里只做即时反馈）
    if (newPassword.length < 8 || !/[a-zA-Z]/.test(newPassword) || !/\d/.test(newPassword)) {
      toast.error('密码至少 8 位，且必须同时包含字母和数字');
      return;
    }
    setSavingPassword(true);
    try {
      const [oldEnc, newEnc] = await Promise.all([encryptPassword(oldPassword), encryptPassword(newPassword)]);
      await profileApi.changePassword(oldEnc, newEnc);
      toast.success('密码已修改，请重新登录');
      await logout();
      router.push('/login');
    } catch (e: any) {
      toast.error(e.message || '修改失败');
    } finally {
      setSavingPassword(false);
    }
  };

  const handleTotpSetup = async () => {
    setTotpBusy(true);
    try {
      const res = await profileApi.totpSetup();
      setTotpInfo(res.data ?? null);
      if (res.data?.otpauthUri) {
        setTotpQrDataUrl(await QRCode.toDataURL(res.data.otpauthUri, { width: 220 }));
      }
    } catch (e: any) {
      toast.error(e.message || '生成密钥失败');
    } finally {
      setTotpBusy(false);
    }
  };

  const handleTotpEnable = async () => {
    if (!totpCode.trim()) {
      toast.error('请输入验证器 App 中的 6 位动态码');
      return;
    }
    setTotpBusy(true);
    try {
      await profileApi.totpEnable(totpCode.trim());
      toast.success('TOTP 已启用，下次登录需要输入动态验证码');
      setTotpInfo(null);
      setTotpCode('');
      setTotpQrDataUrl('');
      await loadMe();
    } catch (e: any) {
      toast.error(e.message || '验证失败');
    } finally {
      setTotpBusy(false);
    }
  };

  const handleTotpDisable = async () => {
    if (!disablePassword) {
      toast.error('请输入登录密码确认关闭');
      return;
    }
    setTotpBusy(true);
    try {
      const enc = await encryptPassword(disablePassword);
      await profileApi.totpDisable(enc);
      toast.success('TOTP 已关闭');
      setDisablePassword('');
      await loadMe();
    } catch (e: any) {
      toast.error(e.message || '关闭失败');
    } finally {
      setTotpBusy(false);
    }
  };

  const handleRevokeSession = async (token: string) => {
    try {
      await profileApi.revokeSession(token);
      toast.success('会话已下线');
      await loadSessions();
    } catch (e: any) {
      toast.error(e.message || '下线失败');
    }
  };

  if (loading) {
    return <div className="py-10 text-center text-muted-foreground">加载中…</div>;
  }

  return (
    <div className="space-y-4">
      {forceChange && (
        <Card className="border-destructive">
          <CardContent className="flex items-center gap-3 py-4 text-sm text-destructive">
            <Lock className="size-4 shrink-0" />
            当前账号仍在使用初始/临时密码，必须先修改密码才能使用其它功能。
          </CardContent>
        </Card>
      )}

      <Tabs defaultValue={forceChange ? 'password' : 'info'}>
        <TabsList>
          <TabsTrigger value="info" disabled={forceChange}>基本资料</TabsTrigger>
          <TabsTrigger value="password">修改密码</TabsTrigger>
          <TabsTrigger value="totp" disabled={forceChange}>动态验证码</TabsTrigger>
          <TabsTrigger value="sessions" disabled={forceChange}>我的会话</TabsTrigger>
        </TabsList>

        {/* ---------------- 基本资料 ---------------- */}
        <TabsContent value="info">
          <Card>
            <CardHeader>
              <CardTitle className="flex items-center gap-2"><UserIcon className="size-4" />基本资料</CardTitle>
              <CardDescription>账号归属信息由管理员维护，此处仅展示</CardDescription>
            </CardHeader>
            <CardContent className="space-y-2 text-sm">
              <Row label="用户名" value={info?.username} />
              <Row label="昵称" value={info?.nickname || '-'} />
              <Row label="邮箱" value={info?.email || '-'} />
              <Row label="用户类型" value={info?.userType} />
              <Row label="最后登录" value={info?.loginTime ? `${info.loginTime}（IP ${info.loginIp || '-'}）` : '-'} />
              <Row label="密码上次修改" value={info?.pwdUpdateTime || '-'} />
              <Row label="动态验证码" value={
                <Badge variant={info?.totpEnabled ? 'default' : 'secondary'}>
                  {info?.totpEnabled ? '已启用' : '未启用'}
                </Badge>
              } />
            </CardContent>
          </Card>
        </TabsContent>

        {/* ---------------- 修改密码 ---------------- */}
        <TabsContent value="password">
          <Card>
            <CardHeader>
              <CardTitle className="flex items-center gap-2"><KeyRound className="size-4" />修改密码</CardTitle>
              <CardDescription>
                至少 8 位，须同时包含字母和数字；修改成功后所有会话失效，需重新登录
              </CardDescription>
            </CardHeader>
            <CardContent className="max-w-sm space-y-4">
              <div className="space-y-1.5">
                <Label htmlFor="old-password">当前密码</Label>
                <Input id="old-password" type="password" value={oldPassword}
                       onChange={(e) => setOldPassword(e.target.value)} autoComplete="current-password" />
              </div>
              <div className="space-y-1.5">
                <Label htmlFor="new-password">新密码</Label>
                <Input id="new-password" type="password" value={newPassword}
                       onChange={(e) => setNewPassword(e.target.value)} autoComplete="new-password" />
              </div>
              <div className="space-y-1.5">
                <Label htmlFor="confirm-password">确认新密码</Label>
                <Input id="confirm-password" type="password" value={confirmPassword}
                       onChange={(e) => setConfirmPassword(e.target.value)} autoComplete="new-password" />
              </div>
              <Button onClick={handleChangePassword} disabled={savingPassword}>
                {savingPassword ? '提交中…' : '修改密码'}
              </Button>
            </CardContent>
          </Card>
        </TabsContent>

        {/* ---------------- TOTP ---------------- */}
        <TabsContent value="totp">
          <Card>
            <CardHeader>
              <CardTitle className="flex items-center gap-2"><ShieldCheck className="size-4" />动态验证码（TOTP）</CardTitle>
              <CardDescription>
                启用后登录除密码外还需输入验证器 App 的 6 位动态码，可显著降低密码泄露的风险
              </CardDescription>
            </CardHeader>
            <CardContent className="max-w-md space-y-4 text-sm">
              {info?.totpEnabled ? (
                <div className="space-y-3">
                  <Badge>已启用</Badge>
                  <div className="space-y-1.5">
                    <Label htmlFor="disable-password">输入登录密码以关闭</Label>
                    <Input id="disable-password" type="password" value={disablePassword}
                           onChange={(e) => setDisablePassword(e.target.value)} autoComplete="current-password" />
                  </div>
                  <Button variant="destructive" onClick={handleTotpDisable} disabled={totpBusy}>
                    关闭动态验证码
                  </Button>
                </div>
              ) : totpInfo ? (
                <div className="space-y-3">
                  <p>1. 用验证器 App（Google Authenticator / 1Password 等）扫码，或手动输入密钥：</p>
                  {totpQrDataUrl && (
                    // eslint-disable-next-line @next/next/no-img-element
                    <img src={totpQrDataUrl} alt="TOTP 绑定二维码" className="rounded-md border" width={220} height={220} />
                  )}
                  <p className="font-mono text-xs break-all rounded bg-muted px-2 py-1">{totpInfo.secret}</p>
                  <div className="space-y-1.5">
                    <Label htmlFor="totp-code">2. 输入 App 上的 6 位动态码确认绑定</Label>
                    <Input id="totp-code" inputMode="numeric" maxLength={6} value={totpCode}
                           onChange={(e) => setTotpCode(e.target.value)} placeholder="如 123456" />
                  </div>
                  <div className="flex gap-2">
                    <Button onClick={handleTotpEnable} disabled={totpBusy}>确认启用</Button>
                    <Button variant="ghost" onClick={() => { setTotpInfo(null); setTotpQrDataUrl(''); setTotpCode(''); }}>
                      取消
                    </Button>
                  </div>
                </div>
              ) : (
                <div className="space-y-3">
                  <Badge variant="secondary">未启用</Badge>
                  <Button onClick={handleTotpSetup} disabled={totpBusy}>
                    {totpBusy ? '生成中…' : '生成密钥并绑定'}
                  </Button>
                </div>
              )}
            </CardContent>
          </Card>
        </TabsContent>

        {/* ---------------- 我的会话 ---------------- */}
        <TabsContent value="sessions">
          <Card>
            <CardHeader>
              <CardTitle className="flex items-center gap-2"><MonitorSmartphone className="size-4" />我的在线会话</CardTitle>
              <CardDescription>当前账号的所有登录会话，可下线非本人的设备</CardDescription>
            </CardHeader>
            <CardContent>
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>登录 IP</TableHead>
                    <TableHead>登录时间</TableHead>
                    <TableHead>过期时间</TableHead>
                    <TableHead className="w-24">操作</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {sessionsLoading ? (
                    <TableRow><TableCell colSpan={4} className="text-center text-muted-foreground">加载中…</TableCell></TableRow>
                  ) : sessions.length === 0 ? (
                    <TableRow><TableCell colSpan={4} className="text-center text-muted-foreground">无会话</TableCell></TableRow>
                  ) : sessions.map((s) => (
                    <TableRow key={s.id}>
                      <TableCell>{s.loginIp || '-'}</TableCell>
                      <TableCell>{s.createTime || '-'}</TableCell>
                      <TableCell>{s.expireTime || '-'}</TableCell>
                      <TableCell>
                        <Button variant="outline" size="sm" onClick={() => s.token && handleRevokeSession(s.token)}>
                          下线
                        </Button>
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </CardContent>
          </Card>
        </TabsContent>
      </Tabs>
    </div>
  );
}

function Row({ label, value }: { label: string; value: React.ReactNode }) {
  return (
    <div className="flex items-center gap-3">
      <span className="w-28 shrink-0 text-muted-foreground">{label}</span>
      <span>{value}</span>
    </div>
  );
}
