'use client';

import { useCallback, useEffect, useState } from 'react';
import { toast } from 'sonner';
import { Bell, Webhook, History, Plus, Pencil, Trash2, Send } from 'lucide-react';

import { alertApi, AlertChannel, AlertRule, AlertRecord, ALERT_METRICS } from '@/api/alert';
import { hasPermissionIn } from '@/lib/perm';
import { useAuth } from '@/context/AuthContext';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { Badge } from '@/components/ui/badge';
import { Textarea } from '@/components/ui/textarea';
import {
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
} from '@/components/ui/dialog';
import {
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
} from '@/components/ui/select';
import {
  Table, TableBody, TableCell, TableHead, TableHeader, TableRow,
} from '@/components/ui/table';

/**
 * 告警管理：通知渠道（EMAIL/WEBHOOK）/ 告警规则 / 触发记录
 * 权限码 admin:alert:channel:view|edit、admin:alert:rule:edit。
 */
export default function AlertPage() {
  const { user } = useAuth();
  const canEditChannel = hasPermissionIn(user?.permissions, 'admin:alert:channel:edit');
  const canEditRule = hasPermissionIn(user?.permissions, 'admin:alert:rule:edit');

  const [channels, setChannels] = useState<AlertChannel[]>([]);
  const [rules, setRules] = useState<AlertRule[]>([]);
  const [records, setRecords] = useState<AlertRecord[]>([]);
  const [recordsTotal, setRecordsTotal] = useState(0);
  const [recordsPage, setRecordsPage] = useState(1);
  const [loading, setLoading] = useState(true);

  const [channelDialogOpen, setChannelDialogOpen] = useState(false);
  const [editingChannel, setEditingChannel] = useState<AlertChannel | null>(null);
  const [channelForm, setChannelForm] = useState({ name: '', type: 'WEBHOOK', config: '{\n  "url": ""\n}', remark: '' });

  const [ruleDialogOpen, setRuleDialogOpen] = useState(false);
  const [editingRule, setEditingRule] = useState<AlertRule | null>(null);
  const [ruleForm, setRuleForm] = useState({
    name: '', metric: 'SERVER_ERROR_COUNT', threshold: '5',
    windowMinutes: '5', channelId: '', cooldownMinutes: '30',
  });

  const loadAll = useCallback(async () => {
    setLoading(true);
    try {
      const [c, r] = await Promise.all([alertApi.listChannels(), alertApi.listRules()]);
      setChannels(c.data || []);
      setRules(r.data || []);
    } catch (e: any) {
      toast.error(e.message || '加载失败');
    } finally {
      setLoading(false);
    }
  }, []);

  const loadRecords = useCallback(async (page = 1) => {
    try {
      const res = await alertApi.pageRecords(page, 10);
      setRecords(res.data?.records || []);
      setRecordsTotal(res.data?.total || 0);
      setRecordsPage(page);
    } catch (e: any) {
      toast.error(e.message || '加载触发记录失败');
    }
  }, []);

  useEffect(() => {
    void loadAll();
    void loadRecords();
  }, [loadAll, loadRecords]);

  // ---------------- 渠道 ----------------

  const openChannelDialog = (channel?: AlertChannel) => {
    setEditingChannel(channel ?? null);
    setChannelForm(channel
      ? { name: channel.name, type: channel.type, config: prettyJson(channel.config), remark: channel.remark || '' }
      : { name: '', type: 'WEBHOOK', config: '{\n  "url": ""\n}', remark: '' });
    setChannelDialogOpen(true);
  };

  const submitChannel = async () => {
    if (!channelForm.name.trim()) {
      toast.error('请填写渠道名称');
      return;
    }
    try {
      JSON.parse(channelForm.config);
    } catch {
      toast.error('渠道配置不是合法 JSON');
      return;
    }
    const payload = {
      name: channelForm.name.trim(),
      type: channelForm.type,
      config: channelForm.config,
      remark: channelForm.remark,
      enabled: editingChannel?.enabled ?? 1,
    };
    try {
      if (editingChannel) {
        await alertApi.updateChannel(editingChannel.id, payload);
      } else {
        await alertApi.createChannel(payload);
      }
      toast.success(editingChannel ? '渠道已更新' : '渠道已创建');
      setChannelDialogOpen(false);
      await loadAll();
    } catch (e: any) {
      toast.error(e.message || '保存失败');
    }
  };

  const deleteChannel = async (channel: AlertChannel) => {
    if (!window.confirm(`确认删除渠道「${channel.name}」？`)) return;
    try {
      await alertApi.deleteChannel(channel.id);
      toast.success('已删除');
      await loadAll();
    } catch (e: any) {
      toast.error(e.message || '删除失败');
    }
  };

  const testChannel = async (channel: AlertChannel) => {
    try {
      await alertApi.testChannel(channel.id);
      toast.success('测试通知已发送，请查收');
    } catch (e: any) {
      toast.error(e.message || '发送失败');
    }
  };

  // ---------------- 规则 ----------------

  const openRuleDialog = (rule?: AlertRule) => {
    setEditingRule(rule ?? null);
    setRuleForm(rule
      ? {
          name: rule.name, metric: rule.metric, threshold: String(rule.threshold),
          windowMinutes: String(rule.windowMinutes), channelId: String(rule.channelId),
          cooldownMinutes: String(rule.cooldownMinutes),
        }
      : { name: '', metric: 'SERVER_ERROR_COUNT', threshold: '5', windowMinutes: '5', channelId: '', cooldownMinutes: '30' });
    setRuleDialogOpen(true);
  };

  const submitRule = async () => {
    if (!ruleForm.name.trim()) {
      toast.error('请填写规则名称');
      return;
    }
    if (!ruleForm.channelId) {
      toast.error('请选择通知渠道');
      return;
    }
    const payload = {
      name: ruleForm.name.trim(),
      metric: ruleForm.metric,
      threshold: Number(ruleForm.threshold),
      windowMinutes: Number(ruleForm.windowMinutes),
      channelId: Number(ruleForm.channelId),
      cooldownMinutes: Number(ruleForm.cooldownMinutes),
      enabled: editingRule?.enabled ?? 1,
    };
    try {
      if (editingRule) {
        await alertApi.updateRule(editingRule.id, payload);
      } else {
        await alertApi.createRule(payload);
      }
      toast.success(editingRule ? '规则已更新' : '规则已创建');
      setRuleDialogOpen(false);
      await loadAll();
    } catch (e: any) {
      toast.error(e.message || '保存失败');
    }
  };

  const deleteRule = async (rule: AlertRule) => {
    if (!window.confirm(`确认删除规则「${rule.name}」？`)) return;
    try {
      await alertApi.deleteRule(rule.id);
      toast.success('已删除');
      await loadAll();
    } catch (e: any) {
      toast.error(e.message || '删除失败');
    }
  };

  const metricLabel = (metric: string) =>
    ALERT_METRICS.find((m) => m.value === metric)?.value || metric;

  return (
    <div className="space-y-4">
      <Tabs defaultValue="channels">
        <TabsList>
          <TabsTrigger value="channels" className="gap-1.5"><Webhook className="size-4" />通知渠道</TabsTrigger>
          <TabsTrigger value="rules" className="gap-1.5"><Bell className="size-4" />告警规则</TabsTrigger>
          <TabsTrigger value="records" className="gap-1.5"><History className="size-4" />触发记录</TabsTrigger>
        </TabsList>

        {/* ---------------- 渠道 ---------------- */}
        <TabsContent value="channels">
          <Card>
            <CardHeader className="flex flex-row items-center justify-between space-y-0">
              <div className="space-y-1.5">
                <CardTitle>通知渠道</CardTitle>
                <CardDescription>告警触达方式；EMAIL 复用系统邮箱发信账户，WEBHOOK 通用 JSON POST</CardDescription>
              </div>
              {canEditChannel && <Button onClick={() => openChannelDialog()} className="gap-1.5"><Plus className="size-4" />新建渠道</Button>}
            </CardHeader>
            <CardContent>
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>名称</TableHead>
                    <TableHead>类型</TableHead>
                    <TableHead>配置摘要</TableHead>
                    <TableHead>状态</TableHead>
                    <TableHead>备注</TableHead>
                    <TableHead className="w-52">操作</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {loading ? (
                    <TableRow><TableCell colSpan={6} className="text-center text-muted-foreground">加载中…</TableCell></TableRow>
                  ) : channels.length === 0 ? (
                    <TableRow><TableCell colSpan={6} className="text-center text-muted-foreground">尚未创建渠道</TableCell></TableRow>
                  ) : channels.map((c) => (
                    <TableRow key={c.id}>
                      <TableCell className="font-medium">{c.name}</TableCell>
                      <TableCell><Badge variant="outline">{c.type}</Badge></TableCell>
                      <TableCell className="max-w-[280px] truncate font-mono text-xs">{configSummary(c)}</TableCell>
                      <TableCell>
                        <Badge variant={c.enabled === 1 ? 'default' : 'secondary'}>{c.enabled === 1 ? '启用' : '停用'}</Badge>
                      </TableCell>
                      <TableCell className="text-muted-foreground">{c.remark || '-'}</TableCell>
                      <TableCell>
                        <div className="flex gap-1.5">
                          <Button variant="outline" size="sm" className="gap-1" onClick={() => testChannel(c)}>
                            <Send className="size-3.5" />测试
                          </Button>
                          {canEditChannel && (
                            <>
                              <Button variant="outline" size="sm" className="gap-1" onClick={() => openChannelDialog(c)}>
                                <Pencil className="size-3.5" />编辑
                              </Button>
                              <Button variant="outline" size="sm" className="gap-1 text-destructive" onClick={() => deleteChannel(c)}>
                                <Trash2 className="size-3.5" />删除
                              </Button>
                            </>
                          )}
                        </div>
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </CardContent>
          </Card>
        </TabsContent>

        {/* ---------------- 规则 ---------------- */}
        <TabsContent value="rules">
          <Card>
            <CardHeader className="flex flex-row items-center justify-between space-y-0">
              <div className="space-y-1.5">
                <CardTitle>告警规则</CardTitle>
                <CardDescription>每分钟评估一次：窗口内指标 ≥ 阈值即触发，冷却时间内不重复发送</CardDescription>
              </div>
              {canEditRule && <Button onClick={() => openRuleDialog()} className="gap-1.5"><Plus className="size-4" />新建规则</Button>}
            </CardHeader>
            <CardContent>
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>名称</TableHead>
                    <TableHead>指标</TableHead>
                    <TableHead>阈值 / 窗口</TableHead>
                    <TableHead>渠道</TableHead>
                    <TableHead>冷却</TableHead>
                    <TableHead>状态</TableHead>
                    <TableHead>上次触发</TableHead>
                    <TableHead className="w-32">操作</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {loading ? (
                    <TableRow><TableCell colSpan={8} className="text-center text-muted-foreground">加载中…</TableCell></TableRow>
                  ) : rules.length === 0 ? (
                    <TableRow><TableCell colSpan={8} className="text-center text-muted-foreground">尚未创建规则</TableCell></TableRow>
                  ) : rules.map((r) => (
                    <TableRow key={r.id}>
                      <TableCell className="font-medium">{r.name}</TableCell>
                      <TableCell><Badge variant="outline">{metricLabel(r.metric)}</Badge></TableCell>
                      <TableCell>≥{r.threshold} / {r.windowMinutes} 分钟</TableCell>
                      <TableCell>{r.channelName || r.channelId}</TableCell>
                      <TableCell>{r.cooldownMinutes} 分钟</TableCell>
                      <TableCell>
                        <Badge variant={r.enabled === 1 ? 'default' : 'secondary'}>{r.enabled === 1 ? '启用' : '停用'}</Badge>
                      </TableCell>
                      <TableCell className="text-muted-foreground">{r.lastFiredAt || '从未'}</TableCell>
                      <TableCell>
                        {canEditRule && (
                          <div className="flex gap-1.5">
                            <Button variant="outline" size="sm" className="gap-1" onClick={() => openRuleDialog(r)}>
                              <Pencil className="size-3.5" />编辑
                            </Button>
                            <Button variant="outline" size="sm" className="gap-1 text-destructive" onClick={() => deleteRule(r)}>
                              <Trash2 className="size-3.5" />删除
                            </Button>
                          </div>
                        )}
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </CardContent>
          </Card>
        </TabsContent>

        {/* ---------------- 触发记录 ---------------- */}
        <TabsContent value="records">
          <Card>
            <CardHeader>
              <CardTitle>触发记录</CardTitle>
              <CardDescription>发送失败也会留档（含原因），共 {recordsTotal} 条</CardDescription>
            </CardHeader>
            <CardContent>
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>时间</TableHead>
                    <TableHead>规则</TableHead>
                    <TableHead>渠道</TableHead>
                    <TableHead>指标值</TableHead>
                    <TableHead>状态</TableHead>
                    <TableHead>失败原因</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {records.length === 0 ? (
                    <TableRow><TableCell colSpan={6} className="text-center text-muted-foreground">暂无触发记录</TableCell></TableRow>
                  ) : records.map((rec) => (
                    <TableRow key={rec.id}>
                      <TableCell>{rec.firedAt}</TableCell>
                      <TableCell>{rec.ruleName}</TableCell>
                      <TableCell>{rec.channelName || '-'}</TableCell>
                      <TableCell>{rec.metricValue}</TableCell>
                      <TableCell>
                        <Badge variant={rec.status === 'SUCCESS' ? 'default' : 'destructive'}>{rec.status}</Badge>
                      </TableCell>
                      <TableCell className="max-w-[260px] truncate text-muted-foreground">{rec.errorMsg || '-'}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
              {recordsTotal > 10 && (
                <div className="mt-3 flex items-center justify-end gap-2 text-sm">
                  <Button variant="outline" size="sm" disabled={recordsPage <= 1}
                          onClick={() => loadRecords(recordsPage - 1)}>上一页</Button>
                  <span className="text-muted-foreground">第 {recordsPage} 页</span>
                  <Button variant="outline" size="sm" disabled={recordsPage * 10 >= recordsTotal}
                          onClick={() => loadRecords(recordsPage + 1)}>下一页</Button>
                </div>
              )}
            </CardContent>
          </Card>
        </TabsContent>
      </Tabs>

      {/* ---------------- 渠道弹窗 ---------------- */}
      <Dialog open={channelDialogOpen} onOpenChange={setChannelDialogOpen}>
        <DialogContent className="sm:max-w-lg">
          <DialogHeader>
            <DialogTitle>{editingChannel ? '编辑渠道' : '新建渠道'}</DialogTitle>
            <DialogDescription>
              EMAIL 配置 {"{ \"to\": \"邮箱\" }"}；WEBHOOK 配置 {"{ \"url\": \"…\", \"secret\": \"可选\" }"}
            </DialogDescription>
          </DialogHeader>
          <div className="space-y-4">
            <div className="space-y-1.5">
              <Label>渠道名称</Label>
              <Input value={channelForm.name} onChange={(e) => setChannelForm({ ...channelForm, name: e.target.value })} />
            </div>
            <div className="space-y-1.5">
              <Label>类型</Label>
              <Select value={channelForm.type} onValueChange={(v) => setChannelForm({
                ...channelForm, type: v,
                config: v === 'EMAIL' ? '{\n  "to": ""\n}' : '{\n  "url": ""\n}',
              })}>
                <SelectTrigger><SelectValue /></SelectTrigger>
                <SelectContent>
                  <SelectItem value="WEBHOOK">WEBHOOK（通用 JSON POST）</SelectItem>
                  <SelectItem value="EMAIL">EMAIL（系统邮箱）</SelectItem>
                </SelectContent>
              </Select>
            </div>
            <div className="space-y-1.5">
              <Label>配置（JSON）</Label>
              <Textarea rows={5} className="font-mono text-xs" value={channelForm.config}
                        onChange={(e) => setChannelForm({ ...channelForm, config: e.target.value })} />
            </div>
            <div className="space-y-1.5">
              <Label>备注</Label>
              <Input value={channelForm.remark} onChange={(e) => setChannelForm({ ...channelForm, remark: e.target.value })} />
            </div>
          </div>
          <DialogFooter>
            <Button variant="ghost" onClick={() => setChannelDialogOpen(false)}>取消</Button>
            <Button onClick={submitChannel}>保存</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* ---------------- 规则弹窗 ---------------- */}
      <Dialog open={ruleDialogOpen} onOpenChange={setRuleDialogOpen}>
        <DialogContent className="sm:max-w-lg">
          <DialogHeader>
            <DialogTitle>{editingRule ? '编辑规则' : '新建规则'}</DialogTitle>
            <DialogDescription>窗口内指标达到阈值即向所选渠道发送通知</DialogDescription>
          </DialogHeader>
          <div className="space-y-4">
            <div className="space-y-1.5">
              <Label>规则名称</Label>
              <Input value={ruleForm.name} onChange={(e) => setRuleForm({ ...ruleForm, name: e.target.value })} />
            </div>
            <div className="space-y-1.5">
              <Label>指标</Label>
              <Select value={ruleForm.metric} onValueChange={(v) => setRuleForm({ ...ruleForm, metric: v })}>
                <SelectTrigger><SelectValue /></SelectTrigger>
                <SelectContent>
                  {ALERT_METRICS.map((m) => (
                    <SelectItem key={m.value} value={m.value}>{m.label}</SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
            <div className="grid grid-cols-2 gap-3">
              <div className="space-y-1.5">
                <Label>阈值（≥ 触发）</Label>
                <Input type="number" min={1} value={ruleForm.threshold}
                       onChange={(e) => setRuleForm({ ...ruleForm, threshold: e.target.value })} />
              </div>
              <div className="space-y-1.5">
                <Label>窗口（分钟）</Label>
                <Input type="number" min={1} max={1440} value={ruleForm.windowMinutes}
                       onChange={(e) => setRuleForm({ ...ruleForm, windowMinutes: e.target.value })} />
              </div>
            </div>
            <div className="grid grid-cols-2 gap-3">
              <div className="space-y-1.5">
                <Label>通知渠道</Label>
                <Select value={ruleForm.channelId} onValueChange={(v) => setRuleForm({ ...ruleForm, channelId: v })}>
                  <SelectTrigger><SelectValue placeholder="选择渠道" /></SelectTrigger>
                  <SelectContent>
                    {channels.filter((c) => c.enabled === 1).map((c) => (
                      <SelectItem key={c.id} value={String(c.id)}>{c.name}（{c.type}）</SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>
              <div className="space-y-1.5">
                <Label>冷却（分钟）</Label>
                <Input type="number" min={0} max={10080} value={ruleForm.cooldownMinutes}
                       onChange={(e) => setRuleForm({ ...ruleForm, cooldownMinutes: e.target.value })} />
              </div>
            </div>
          </div>
          <DialogFooter>
            <Button variant="ghost" onClick={() => setRuleDialogOpen(false)}>取消</Button>
            <Button onClick={submitRule}>保存</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}

function prettyJson(config?: string): string {
  if (!config) return '';
  try {
    return JSON.stringify(JSON.parse(config), null, 2);
  } catch {
    return config;
  }
}

function configSummary(channel: AlertChannel): string {
  try {
    const obj = JSON.parse(channel.config);
    if (channel.type === 'EMAIL') return String(obj.to || '');
    if (channel.type === 'WEBHOOK') return String(obj.url || '');
    return channel.config;
  } catch {
    return '(配置解析失败)';
  }
}
