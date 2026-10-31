'use client';

import { useEffect, useMemo, useRef, useState } from 'react';
import {
  Alert,
  Button,
  Card,
  Checkbox,
  Form,
  Input,
  Modal,
  Popconfirm,
  Select,
  Space,
  Switch,
  Tag,
  Tooltip,
  message,
} from '@/components/antd-compat';
import {
  DeleteOutlined,
  EditOutlined,
  ExperimentOutlined,
  PlusOutlined,
} from '@/components/antd-compat/icons';
import {
  notifyRuleApi,
  NotifyChannel,
  NotifyEventVo,
  NotifyLimitScope,
  NotifyPublishResult,
  NotifyRecipientType,
  NotifyRuleChannelSave,
  NotifyRuleChannelVo,
  NotifyRuleSaveDto,
  NotifyRuleVo,
  NotifyTemplateOption,
} from '@/api/notify';
import { dictColor, dictLabel, fetchDictOptions, DictOption } from '@/api/dict';
import { usePerm } from '@/lib/perm';
import { formatDateTime } from '@/lib/format';
import { ResizableTable } from '@/components/ResizableTable';
import type { DataTableColumn } from '@/components/data-table/types';
import ChannelEditor from './components/ChannelEditor';

const PERM_EDIT = 'admin:system:notify:rule:edit';

/** 表单里单个渠道的配置 */
interface ChannelFormValue {
  enabled?: boolean;
  templateId?: number;
  dailyLimit?: number;
  limitScope?: string;
  platform?: string[];
  recipients?: { type?: string; value?: string }[];
}

type ChannelsFormValue = Record<string, ChannelFormValue | undefined>;

/** 弹窗表单值（与 toFormValue 输出、resetForm 默认值同形） */
interface RuleFormValues {
  ruleName?: string;
  eventCode?: string;
  enabled?: boolean;
  remark?: string;
  channels?: ChannelsFormValue;
}

/** 新建渠道时的默认配置：至少一条收件人，否则保存必被后端 NOTIFY013 打回 */
function defaultChannelValue(): ChannelFormValue {
  return {
    enabled: true,
    templateId: undefined,
    dailyLimit: 0,
    limitScope: 'RECIPIENT',
    platform: [],
    recipients: [{ type: 'FIXED', value: '' }],
  };
}

/**
 * 事件订阅规则（事件 × 多渠道）。
 *
 * <p>一条规则绑一个事件：事件声明「可投递渠道 / 必达渠道 / 载荷字段」，规则下每个渠道
 * 各自绑定模板（可留空自动匹配）、启用态、每日限额（按收件人 / 全局）与平台定向，
 * 以及若干收件人（固定值 / 事件载荷字段 / 角色）。业务代码只发布事件，
 * 发给谁、走哪个渠道完全由启用中的规则决定。</p>
 */
export default function NotifyRulePage() {
  const [loading, setLoading] = useState(false);
  const [data, setData] = useState<NotifyRuleVo[]>([]);
  const [pagination, setPagination] = useState({ current: 1, pageSize: 10, total: 0 });
  const [events, setEvents] = useState<NotifyEventVo[]>([]);
  /** 渠道码 → 该「事件 × 渠道」下可选模板（按需拉取，未选事件时为空） */
  const [templates, setTemplates] = useState<Record<string, NotifyTemplateOption[]>>({});
  const [dicts, setDicts] = useState<Record<string, DictOption[]>>({});
  const [filterEvent, setFilterEvent] = useState<string | undefined>(undefined);
  const [filterEnabled, setFilterEnabled] = useState<number | undefined>(undefined);
  const [keyword, setKeyword] = useState('');

  const [modalVisible, setModalVisible] = useState(false);
  const [editing, setEditing] = useState<NotifyRuleVo | null>(null);
  const [saving, setSaving] = useState(false);
  const [togglingId, setTogglingId] = useState<number | undefined>(undefined);

  const [publishVisible, setPublishVisible] = useState(false);
  const [publishEvent, setPublishEvent] = useState<string | undefined>(undefined);
  const [publishVars, setPublishVars] = useState<Record<string, string>>({});
  const [publishRecipients, setPublishRecipients] = useState('');
  const [publishing, setPublishing] = useState(false);
  const [publishResults, setPublishResults] = useState<NotifyPublishResult[]>([]);

  const [form] = Form.useForm();
  const watchedEventCode = Form.useWatch('eventCode', form) as string | undefined;
  const watchedChannels = Form.useWatch('channels', form) as ChannelsFormValue | undefined;
  /** 上一次选中的事件码：只在「真的换了事件」时清空各渠道已选模板 */
  const lastEventRef = useRef<string | undefined>(undefined);

  const hasPerm = usePerm();
  const canEdit = hasPerm(PERM_EDIT);

  const channelDict = dicts.notify_delivery_channel;
  const enableDict = dicts.enable_status;

  const selectedChannels = useMemo(() => Object.keys(watchedChannels || {}), [watchedChannels]);
  const selectedEvent = useMemo(
    () => events.find((e) => e.code === watchedEventCode),
    [events, watchedEventCode],
  );
  const requiredChannels = selectedEvent?.requiredChannels || [];
  const payloadFields = selectedEvent?.payloadFields || [];
  const payloadKeys = selectedEvent?.payloadKeys || payloadFields.map((f) => f.name);
  /** 渠道可选项 = 事件声明的渠道（未选事件时为空，先选事件） */
  const availableChannels = (selectedEvent?.channels || []) as NotifyChannel[];

  useEffect(() => {
    loadData(1, pagination.pageSize);
    loadEvents();
    fetchDictOptions([
      'notify_delivery_channel',
      'notify_recipient_type',
      'notify_limit_scope',
      'notice_channel',
      'enable_status',
    ])
      .then(setDicts)
      .catch(() => {
        /* 字典失败时回退英文码/内置文案 */
      });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  /** 模板下拉：选了事件后，按该事件支持的每个渠道分别拉「事件 × 渠道」可选模板 */
  useEffect(() => {
    const code = watchedEventCode;
    if (!code) {
      setTemplates({});
      return undefined;
    }
    const channels = events.find((e) => e.code === code)?.channels || [];
    let alive = true;
    Promise.all(
      channels.map((ch) =>
        notifyRuleApi
          .templates(code, ch)
          .then((res) => [ch, res.data || []] as const)
          .catch(() => [ch, [] as NotifyTemplateOption[]] as const),
      ),
    ).then((pairs) => {
      if (!alive) return;
      const next: Record<string, NotifyTemplateOption[]> = {};
      pairs.forEach(([ch, list]) => {
        next[ch] = list;
      });
      setTemplates(next);
    });
    return () => {
      alive = false;
    };
  }, [watchedEventCode, events]);

  const loadData = (page = 1, size = pagination.pageSize) => {
    setLoading(true);
    notifyRuleApi
      .page(page, size, {
        eventCode: filterEvent,
        enabled: filterEnabled,
        keyword: keyword.trim() || undefined,
      })
      .then((res) => {
        setData(res.data?.records || []);
        setPagination({
          current: res.data?.current || page,
          pageSize: res.data?.size || size,
          total: res.data?.total || 0,
        });
      })
      .catch((e: unknown) => message.error((e as Error).message))
      .finally(() => setLoading(false));
  };

  const loadEvents = () => {
    notifyRuleApi
      .events()
      .then((res) => setEvents(res.data || []))
      .catch((e: unknown) => message.error((e as Error).message));
  };

  const eventDefOf = (code?: string) => (code ? events.find((e) => e.code === code) : undefined);

  /** 当前 (事件, 渠道) 下可用的模板（后端 /notify/template 已按事件 × 渠道过滤） */
  const templateOptionsOf = (channel: string): { label: string; value: number }[] =>
    (templates[channel] || []).map((t) => ({
      label: t.templateCode
        ? `${t.templateName || t.templateCode}（${t.templateCode}）`
        : t.templateName || `模板 ${t.id}`,
      value: t.id,
    }));

  /* ------------------------------ 弹窗与表单 ------------------------------ */

  const resetForm = (values?: RuleFormValues) => {
    form.resetFields();
    form.setFieldsValue(
      values ?? {
        ruleName: '',
        eventCode: undefined,
        enabled: true,
        remark: '',
        channels: {},
      },
    );
  };

  const openCreate = () => {
    setEditing(null);
    lastEventRef.current = undefined;
    resetForm();
    setModalVisible(true);
  };

  const openEdit = (row: NotifyRuleVo) => {
    setEditing(row);
    lastEventRef.current = row.eventCode;
    resetForm(toFormValue(row));
    setModalVisible(true);
  };

  /** 规则出参 → 表单值（接口字段名与表单字段名保持一致，减少映射出错） */
  const toFormValue = (vo: NotifyRuleVo) => {
    const channels: ChannelsFormValue = {};
    (vo.channels || []).forEach((c) => {
      if (!c.channel) return;
      channels[c.channel] = {
        enabled: c.enabled === undefined ? true : Number(c.enabled) === 1,
        templateId: c.templateId ?? undefined,
        dailyLimit: c.dailyLimit ?? 0,
        limitScope: c.limitScope || 'RECIPIENT',
        platform: c.platform && c.platform !== 'all' ? c.platform.split(',').filter(Boolean) : [],
        recipients: (c.recipients || []).map((r) => ({
          type: r.type || 'FIXED',
          value: r.value || '',
        })),
      };
    });
    return {
      ruleName: vo.ruleName || '',
      eventCode: vo.eventCode,
      enabled: vo.enabled === undefined ? true : Number(vo.enabled) === 1,
      remark: vo.remark || '',
      channels,
    };
  };

  /** 事件切换：剔除新事件不支持的渠道，保留其余配置，自动补齐「必达渠道」 */
  const handleEventChange = (code?: string) => {
    const changed = code !== lastEventRef.current;
    lastEventRef.current = code;
    const def = eventDefOf(code);
    const allowed = new Set(def?.channels || []);
    const required = def?.requiredChannels || [];
    const current = (form.getFieldValue('channels') || {}) as ChannelsFormValue;
    const next: ChannelsFormValue = {};
    Object.keys(current).forEach((ch) => {
      if (!allowed.has(ch)) return;
      const cfg = current[ch] || defaultChannelValue();
      // 模板按事件过滤：换事件时清空模板选择，避免残留不匹配的模板 ID（后端会拒）
      next[ch] = changed ? { ...cfg, templateId: undefined } : cfg;
    });
    // 必达渠道始终补齐并强制启用：事件声明的安全渠道不允许缺失
    required.forEach((ch) => {
      next[ch] = { ...(next[ch] || defaultChannelValue()), enabled: true };
    });
    form.setFieldValue('channels', next);
  };

  /** 勾选/取消勾选渠道 */
  const toggleChannel = (channel: string, checked: boolean) => {
    if (requiredChannels.includes(channel)) return;
    const current = (form.getFieldValue('channels') || {}) as ChannelsFormValue;
    const next: ChannelsFormValue = { ...current };
    if (checked) {
      next[channel] = next[channel] || defaultChannelValue();
    } else {
      delete next[channel];
    }
    form.setFieldValue('channels', next);
  };

  /** 构造保存体；必达渠道强制启用，前端先挡住能挡的问题，后端错误码兜底 */
  const buildSaveDto = (values: RuleFormValues): { dto?: NotifyRuleSaveDto; problems: string[] } => {
    const problems: string[] = [];
    const def = eventDefOf(values.eventCode);
    const allowed = new Set(def?.channels || []);
    const required = def?.requiredChannels || [];
    /** 该事件的载荷字段名（PAYLOAD_FIELD 收件人必须落在这里面，否则后端 NOTIFY007） */
    const keys = def?.payloadKeys?.length
      ? def.payloadKeys
      : (def?.payloadFields || []).map((f) => f.name);
    const channelsForm = (values.channels || {}) as ChannelsFormValue;
    const codes = Object.keys(channelsForm).filter((ch) => allowed.has(ch));
    if (!codes.length) problems.push('至少勾选一个投递渠道');

    const missing = required.filter((ch) => !codes.includes(ch));
    if (missing.length) {
      problems.push(
        `必达渠道不可取消：${missing.map((ch) => dictLabel(channelDict, ch, ch)).join('、')}`,
      );
    }

    const channels: NotifyRuleChannelSave[] = codes.map((channel) => {
      const cfg = channelsForm[channel] || {};
      const name = dictLabel(channelDict, channel, channel);
      const isRequired = required.includes(channel);
      if (isRequired && cfg.enabled === false) problems.push(`${name} 是必达渠道，必须启用`);
      if (!cfg.limitScope) problems.push(`${name} 渠道未选择限额口径`);

      const recipients = (cfg.recipients || [])
        .map((r) => ({
          type: (r.type || 'FIXED') as NotifyRecipientType,
          value: (r.value || '').trim(),
        }))
        .filter((r) => r.value);
      if (!recipients.length) problems.push(`${name} 渠道至少配置一条收件人`);
      recipients.forEach((r) => {
        if (r.type === 'PAYLOAD_FIELD' && !keys.includes(r.value)) {
          problems.push(`${name} 渠道的事件字段「${r.value}」不在该事件的载荷字段里`);
        }
      });

      const platform =
        channel === 'INAPP'
          ? Array.isArray(cfg.platform)
            ? cfg.platform.filter(Boolean).join(',')
            : ''
          : '';

      return {
        channel: channel as NotifyChannel,
        templateId: cfg.templateId ?? null,
        enabled: isRequired || cfg.enabled !== false ? 1 : 0,
        dailyLimit: Math.max(0, Number(cfg.dailyLimit) || 0),
        limitScope: (cfg.limitScope || 'RECIPIENT') as NotifyLimitScope,
        platform: platform || undefined,
        recipients,
      };
    });

    if (problems.length) return { problems };
    return {
      dto: {
        ruleName: (values.ruleName || '').trim(),
        eventCode: values.eventCode ?? '',
        remark: (values.remark || '').trim() || undefined,
        enabled: values.enabled === false ? 0 : 1,
        channels,
      },
      problems: [],
    };
  };

  const handleSubmit = async () => {
    let values: RuleFormValues;
    try {
      values = await form.validateFields();
    } catch {
      return;
    }
    const { dto, problems } = buildSaveDto(values);
    if (!dto) {
      message.error(problems[0] || '请检查表单');
      return;
    }
    setSaving(true);
    try {
      if (editing?.id) {
        await notifyRuleApi.update(editing.id, dto);
      } else {
        await notifyRuleApi.create(dto);
      }
      message.success('保存成功');
      setModalVisible(false);
      loadData(editing ? pagination.current : 1, pagination.pageSize);
    } catch (e: unknown) {
      // 后端业务错误（NOTIFY001/007/012…）由 client 拦截器转成 Error(msg)
      message.error((e as Error).message || '保存失败');
    } finally {
      setSaving(false);
    }
  };

  const handleDelete = async (row: NotifyRuleVo) => {
    if (!row.id) return;
    try {
      await notifyRuleApi.remove(row.id);
      message.success('删除成功');
      loadData(pagination.current, pagination.pageSize);
    } catch (e: unknown) {
      message.error((e as Error).message || '删除失败');
    }
  };

  /** 列表内启用开关：整体覆盖保存（先按行数据还原完整表单，避免只带开关把渠道配置冲掉） */
  const handleToggle = async (row: NotifyRuleVo, next: boolean) => {
    if (!row.id) return;
    const { dto, problems } = buildSaveDto(toFormValue(row));
    if (!dto) {
      message.error(problems[0] || '规则配置不完整，请先编辑保存');
      return;
    }
    setTogglingId(row.id);
    try {
      await notifyRuleApi.update(row.id, { ...dto, enabled: next ? 1 : 0 });
      message.success(next ? '已启用' : '已停用');
      loadData(pagination.current, pagination.pageSize);
    } catch (e: unknown) {
      message.error((e as Error).message || '操作失败');
    } finally {
      setTogglingId(undefined);
    }
  };

  /* ------------------------------ 发布测试 ------------------------------ */

  const openPublish = () => {
    setPublishEvent(undefined);
    setPublishVars({});
    setPublishRecipients('');
    setPublishResults([]);
    setPublishVisible(true);
  };

  const handlePublishEventChange = (code?: string) => {
    setPublishEvent(code);
    const def = eventDefOf(code);
    const vars: Record<string, string> = {};
    (def?.payloadFields || []).forEach((f) => {
      vars[f.name] = '';
    });
    (def?.payloadKeys || []).forEach((k) => {
      if (!(k in vars)) vars[k] = '';
    });
    setPublishVars(vars);
  };

  const handlePublish = async () => {
    if (!publishEvent) {
      message.error('请选择事件');
      return;
    }
    const recipients = publishRecipients
      .split(/[,，;；\s]+/)
      .map((s) => s.trim())
      .filter(Boolean);
    setPublishing(true);
    try {
      const res = await notifyRuleApi.publish({
        eventCode: publishEvent,
        variables: publishVars,
        recipients: recipients.length ? recipients : undefined,
      });
      const list: NotifyPublishResult[] = res.data || [];
      setPublishResults(list);
      if (!list.length) {
        message.warning('没有命中任何启用中的规则');
      } else if (list.every((r) => r.ok)) {
        message.success(`发布成功：${list.length} 条投递`);
      } else {
        message.warning('部分投递失败，详见下方结果');
      }
    } catch (e: unknown) {
      message.error((e as Error).message || '发布失败');
    } finally {
      setPublishing(false);
    }
  };

  /* ------------------------------ 列表渲染 ------------------------------ */

  const channelLabel = (ch?: string) => (ch ? dictLabel(channelDict, ch, ch) : '-');
  const platformLabel = (p: string) => dictLabel(dicts.notice_channel, p, p);

  /** 渠道 chips：渠道 · 模板 / 限额 / 平台 */
  const renderChannels = (channels?: NotifyRuleChannelVo[]) => {
    if (!channels?.length) return <span style={{ color: '#999' }}>未配置</span>;
    return (
      <Space size={4} wrap>
        {channels.map((c, i) => {
          if (!c.channel) return null;
          const disabled = Number(c.enabled) === 0;
          const scope = dictLabel(dicts.notify_limit_scope, c.limitScope, c.limitScope || '');
          const limitText = c.dailyLimit ? `${c.dailyLimit}/天·${scope || '按收件人'}` : '不限';
          const platformText =
            c.channel === 'INAPP' && c.platform && c.platform !== 'all'
              ? `·${c.platform.split(',').map(platformLabel).join('/')}`
              : '';
          // 明细放 title（悬停可看全），单元格只留「渠道 · 模板」，避免规则列表被长文案撑爆
          const fullText = `${channelLabel(c.channel)} · ${c.templateName || '自动匹配'} · ${limitText}${platformText}${disabled ? '（停用）' : ''}`;
          const shortText = `${channelLabel(c.channel)} · ${c.templateName || '自动匹配'} · ${limitText}`;
          return (
            <span
              key={`${c.channel}-${i}`}
              title={fullText}
              style={{ display: 'inline-block', maxWidth: '100%' }}
            >
              <Tag
                color={disabled ? 'default' : dictColor(channelDict, c.channel) || 'blue'}
                style={{
                  ...(disabled ? { opacity: 0.6 } : undefined),
                  maxWidth: 260,
                  overflow: 'hidden',
                  textOverflow: 'ellipsis',
                  verticalAlign: 'middle',
                }}
              >
                {shortText}
              </Tag>
            </span>
          );
        })}
      </Space>
    );
  };

  const columns: DataTableColumn<NotifyRuleVo>[] = [
    {
      title: '事件',
      dataIndex: 'eventCode',
      key: 'eventCode',
      width: 200,
      render: (v: string, row: NotifyRuleVo) => (
        <div>
          <Tag color="blue">{row.eventName || v}</Tag>
          <div style={{ fontSize: 12, color: '#999' }}>{v}</div>
        </div>
      ),
    },
    {
      title: '规则名称',
      dataIndex: 'ruleName',
      key: 'ruleName',
      width: 200,
      ellipsis: true,
      render: (v: string, row: NotifyRuleVo) => (
        <div>
          <div>{v || '-'}</div>
          {row.remark ? <div style={{ fontSize: 12, color: '#999' }}>{row.remark}</div> : null}
        </div>
      ),
    },
    {
      title: '渠道配置',
      dataIndex: 'channels',
      key: 'channels',
      width: 300,
      render: (_: unknown, row: NotifyRuleVo) => renderChannels(row.channels),
    },
    {
      title: '更新时间',
      dataIndex: 'updateTime',
      key: 'updateTime',
      width: 170,
      render: (v: string) => formatDateTime(v),
    },
    {
      title: '启用',
      dataIndex: 'enabled',
      key: 'enabled',
      width: 108,
      render: (v: number, row: NotifyRuleVo) => (
        <Switch
          checked={Number(v) === 1}
          loading={togglingId === row.id}
          disabled={!canEdit}
          checkedChildren="启用"
          unCheckedChildren="停用"
          onChange={(checked) => handleToggle(row, checked)}
        />
      ),
    },
    {
      title: '操作',
      key: 'action',
      width: 110,
      render: (_: unknown, row: NotifyRuleVo) => (
        <Space size={2}>
          {canEdit ? (
            <>
              <Tooltip title="编辑">
                <Button
                  type="text"
                  size="small"
                  icon={<EditOutlined />}
                  onClick={() => openEdit(row)}
                />
              </Tooltip>
              <Popconfirm title="确认删除该规则?" onConfirm={() => handleDelete(row)}>
                <Tooltip title="删除">
                  <Button type="text" size="small" danger icon={<DeleteOutlined />} />
                </Tooltip>
              </Popconfirm>
            </>
          ) : (
            <span style={{ color: '#999' }}>无权限</span>
          )}
        </Space>
      ),
    },
  ];

  const eventOptions = events.map((e) => ({ label: `${e.name}（${e.code}）`, value: e.code }));
  const enabledOptions = enableDict?.length
    ? enableDict.map((o) => ({ label: o.label, value: Number(o.value) }))
    : [
        { label: '启用', value: 1 },
        { label: '停用', value: 0 },
      ];
  const publishDef = eventDefOf(publishEvent);
  const publishFieldNames =
    publishDef?.payloadFields?.map((f) => f.name) || publishDef?.payloadKeys || [];

  return (
    <div className="space-y-4">
      <Card>
        <div
          style={{
            marginBottom: 16,
            display: 'flex',
            flexWrap: 'wrap',
            justifyContent: 'space-between',
            alignItems: 'center',
            gap: 8,
          }}
        >
          <span style={{ fontWeight: 600, fontSize: 16 }}>
            通知规则
            <span style={{ marginLeft: 12, fontWeight: 400, fontSize: 12, color: '#999' }}>
              一条规则绑一个事件；每个渠道单独配模板、限额、平台与收件人，必达渠道不可移除
            </span>
          </span>
          <Space>
            {canEdit && (
              <Button icon={<ExperimentOutlined />} onClick={openPublish}>
                发布测试
              </Button>
            )}
            {canEdit && (
              <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
                新建规则
              </Button>
            )}
          </Space>
        </div>

        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8, marginBottom: 16 }}>
          <Select
            style={{ width: 280 }}
            allowClear
            showSearch
            optionFilterProp="label"
            placeholder="按事件筛选"
            value={filterEvent}
            options={eventOptions}
            onChange={(v) => setFilterEvent(v)}
          />
          <Select
            style={{ width: 140 }}
            allowClear
            placeholder="启用状态"
            value={filterEnabled}
            options={enabledOptions}
            onChange={(v) => setFilterEnabled(v)}
          />
          <Input.Search
            style={{ width: 220 }}
            allowClear
            placeholder="搜索规则名称"
            value={keyword}
            onChange={(e) => setKeyword(e.target.value)}
            onSearch={() => loadData(1, pagination.pageSize)}
          />
          <Button onClick={() => loadData(1, pagination.pageSize)}>查询</Button>
        </div>

        <ResizableTable
          dataSource={data}
          columns={columns}
          rowKey="id"
          loading={loading}
          scroll={{ x: 'max-content' }}
          pagination={{
            ...pagination,
            showQuickJumper: true,
            showSizeChanger: true,
            pageSizeOptions: ['5', '10', '20', '50', '100'],
          }}
          onChange={(p: { current?: number; pageSize?: number }) => loadData(p.current, p.pageSize)}
        />
      </Card>

      <Modal
        title={editing ? '编辑通知规则' : '新建通知规则'}
        open={modalVisible}
        onOk={handleSubmit}
        confirmLoading={saving}
        onCancel={() => setModalVisible(false)}
        width={900}
        destroyOnClose
      >
        <Form form={form} layout="vertical" style={{ marginTop: 16 }}>
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: 16 }}>
            <Form.Item
              name="ruleName"
              label="规则名称"
              rules={[{ required: true, message: '请输入规则名称' }]}
              style={{ marginBottom: 12, minWidth: 320, flex: 1 }}
            >
              <Input placeholder="如：反馈状态变更-邮件+站内信" />
            </Form.Item>
            <Form.Item
              name="eventCode"
              label="绑定事件"
              rules={[{ required: true, message: '请选择事件' }]}
              style={{ marginBottom: 12, minWidth: 320, flex: 1 }}
              tooltip="事件由代码注册；规则决定该事件发给谁、走哪些渠道"
            >
              <Select
                options={eventOptions}
                placeholder="选择系统事件"
                showSearch
                optionFilterProp="label"
                onChange={(code) => handleEventChange(code)}
              />
            </Form.Item>
          </div>

          {selectedEvent && (
            <Alert
              type="info"
              showIcon
              style={{ marginBottom: 12 }}
              message={`${selectedEvent.name}（${selectedEvent.code}）`}
              description={
                <div style={{ fontSize: 12 }}>
                  {selectedEvent.description ? <div>{selectedEvent.description}</div> : null}
                  <div>
                    可用渠道：
                    {availableChannels.map((c) => channelLabel(c)).join('、') || '-'}
                    {requiredChannels.length > 0 && (
                      <>
                        {'；'}必达渠道：
                        {requiredChannels.map((c) => (
                          <Tag key={c} color="red" style={{ marginLeft: 4 }}>
                            {channelLabel(c)}
                          </Tag>
                        ))}
                      </>
                    )}
                  </div>
                  {payloadKeys.length > 0 ? (
                    <div>
                      载荷字段（变量补全 / PAYLOAD_FIELD 收件人可用）：
                      {payloadKeys.map((k) => {
                        const field = payloadFields.find((f) => f.name === k);
                        return (
                          <code key={k} title={field?.description} style={{ marginRight: 8 }}>
                            {k}
                          </code>
                        );
                      })}
                    </div>
                  ) : null}
                </div>
              }
            />
          )}

          <div style={{ marginBottom: 8, fontSize: 13, fontWeight: 500 }}>
            投递渠道
            <span style={{ marginLeft: 8, fontWeight: 400, fontSize: 12, color: '#999' }}>
              {selectedEvent
                ? '勾选后出现对应渠道配置；模板留空则按事件×渠道自动匹配'
                : '请先选择绑定事件'}
            </span>
          </div>
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: 16, marginBottom: 16 }}>
            {availableChannels.map((ch) => {
              const isRequired = requiredChannels.includes(ch);
              return (
                <Checkbox
                  key={ch}
                  checked={selectedChannels.includes(ch)}
                  disabled={isRequired}
                  onChange={(e) => toggleChannel(ch, e.target.checked)}
                >
                  {channelLabel(ch)}
                  {isRequired && (
                    <Tag color="red" style={{ marginLeft: 6 }}>
                      必达
                    </Tag>
                  )}
                </Checkbox>
              );
            })}
            {!selectedEvent && (
              <span style={{ fontSize: 12, color: '#999' }}>
                事件声明了可投递渠道后，这里才会出现选项
              </span>
            )}
          </div>

          {selectedChannels.map((ch) => (
            <ChannelEditor
              key={ch}
              channel={ch}
              form={form}
              required={requiredChannels.includes(ch)}
              dicts={dicts}
              templateOptions={templateOptionsOf(ch)}
              payloadFields={payloadFields}
              recipients={watchedChannels?.[ch]?.recipients}
              onRemove={() => toggleChannel(ch, false)}
            />
          ))}

          {selectedChannels.length === 0 && (
            <Alert
              type="warning"
              showIcon
              style={{ marginBottom: 12 }}
              message="请至少勾选一个投递渠道"
            />
          )}

          <div style={{ display: 'flex', flexWrap: 'wrap', gap: 16 }}>
            <Form.Item
              name="enabled"
              label="规则启用"
              valuePropName="checked"
              style={{ marginBottom: 12 }}
            >
              <Switch checkedChildren="启用" unCheckedChildren="停用" />
            </Form.Item>
          </div>
          <Form.Item name="remark" label="备注" style={{ marginBottom: 0 }}>
            <Input.TextArea rows={2} placeholder="规则用途说明" />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title="发布测试"
        open={publishVisible}
        onOk={handlePublish}
        confirmLoading={publishing}
        onCancel={() => setPublishVisible(false)}
        okText="发布"
        width={720}
        destroyOnClose
      >
        <div style={{ marginBottom: 12, fontSize: 12, color: '#999' }}>
          按启用中的规则逐条投递，逐规则回报结果；收件人留空时使用规则里配置的收件人。
        </div>
        <div style={{ marginBottom: 12 }}>
          <div style={{ marginBottom: 4, fontSize: 13 }}>事件</div>
          <Select
            style={{ width: '100%' }}
            placeholder="选择要发布的事件"
            options={eventOptions}
            value={publishEvent}
            showSearch
            optionFilterProp="label"
            onChange={(code) => handlePublishEventChange(code)}
          />
        </div>
        {publishFieldNames.length > 0 && (
          <div style={{ marginBottom: 12 }}>
            <div style={{ marginBottom: 4, fontSize: 13 }}>事件变量</div>
            {publishFieldNames.map((name) => {
              const field = publishDef?.payloadFields?.find((f) => f.name === name);
              return (
                <div
                  key={name}
                  style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 6 }}
                >
                  <code style={{ width: 180, fontSize: 12 }}>{name}</code>
                  <Input
                    style={{ flex: 1 }}
                    placeholder={field?.description || '变量值'}
                    value={publishVars[name] || ''}
                    onChange={(e) =>
                      setPublishVars((prev) => ({ ...prev, [name]: e.target.value }))
                    }
                  />
                </div>
              );
            })}
          </div>
        )}
        <div style={{ marginBottom: 12 }}>
          <div style={{ marginBottom: 4, fontSize: 13 }}>测试收件人（可选，逗号或换行分隔）</div>
          <Input.TextArea
            rows={2}
            placeholder="a@b.com, 13800000000；留空则投给规则里配置的收件人"
            value={publishRecipients}
            onChange={(e) => setPublishRecipients(e.target.value)}
          />
        </div>
        {publishResults.length > 0 && (
          <div
            style={{
              maxHeight: 220,
              overflow: 'auto',
              borderTop: '1px solid #f0f0f0',
              paddingTop: 8,
            }}
          >
            {publishResults.map((r, i) => (
              <div
                key={`${r.ruleId}-${r.channel}-${i}`}
                style={{
                  display: 'flex',
                  gap: 8,
                  alignItems: 'center',
                  marginBottom: 6,
                  fontSize: 12,
                }}
              >
                <Tag color={r.ok ? 'green' : 'red'}>{r.ok ? '成功' : '失败'}</Tag>
                <span style={{ minWidth: 140 }}>{r.ruleName || `规则 ${r.ruleId}`}</span>
                <span style={{ minWidth: 60 }}>{channelLabel(r.channel)}</span>
                <span style={{ flex: 1, color: '#666' }}>{r.recipient || '-'}</span>
                {!r.ok && r.error ? <span style={{ color: '#cf1322' }}>{r.error}</span> : null}
              </div>
            ))}
          </div>
        )}
      </Modal>
    </div>
  );
}
