'use client';

import { useEffect, useMemo, useState } from 'react';
import {
  Card,
  Button,
  Space,
  Modal,
  Form,
  Input,
  Select,
  Tag,
  Tooltip,
  message,
  Popconfirm,
} from '@/components/antd-compat';
import {
  PlusOutlined,
  EditOutlined,
  DeleteOutlined,
  EyeOutlined,
} from '@/components/antd-compat/icons';
import {
  messageTemplateApi,
  MessageTemplate,
  MessageTemplateEventOption,
} from '@/api/message-template';
import { fetchDictOptions, dictLabel, dictColor, DictOption } from '@/api/dict';
import { usePerm, MAIL_PERMISSIONS } from '@/lib/perm';
import { ResizableTable } from '@/components/ResizableTable';
import { FilterBar } from '@/components/filter-bar';

/**
 * 投递渠道一律给全量（邮箱/站内信/短信），不再按事件声明收窄 ——
 * 「事件声明渠道」只代表代码当前会往哪些渠道发，模板可以提前把三个渠道都配好。
 */
const ALL_CHANNELS = ['EMAIL', 'INAPP', 'SMS'];

/**
 * 消息模板：把原来的「邮箱模板」「短信模板」两个页签合并成一页。
 *
 * 一条记录 = 模板编码 + 绑定事件 + 渠道多选（邮箱/站内信/短信）。勾了邮箱或站内信就填
 * 主题与 HTML 正文，勾了短信就填供应商模板编码、签名与报备状态；保存时后端按渠道拆行，
 * 取消勾选的渠道会被删除。发送侧仍按「事件 × 渠道」取模板，行为不变。
 */
export default function MessageTemplatePage() {
  const [loading, setLoading] = useState(false);
  const [data, setData] = useState<MessageTemplate[]>([]);
  const [events, setEvents] = useState<MessageTemplateEventOption[]>([]);
  const [modalVisible, setModalVisible] = useState(false);
  const [editing, setEditing] = useState<MessageTemplate | null>(null);
  const [form] = Form.useForm();

  /**
   * 表单当前选中的渠道（决定显隐邮件/短信字段块）。
   * 直接订阅表单值而不是另存一份 state —— 渠道由 Form.Item 统一管理，
   * 双份状态在「多选」时会与表单值不同步（历史上就出过只提交第一个渠道的问题）。
   */
  const watchedChannels = Form.useWatch('channels', form) as string[] | undefined;
  const channels = watchedChannels || [];
  const [selectedScene, setSelectedScene] = useState<string | undefined>(undefined);
  const [previewVisible, setPreviewVisible] = useState(false);
  const [previewHtml, setPreviewHtml] = useState('');
  /** 渠道/报备状态等枚举一律取数据字典 */
  const [dicts, setDicts] = useState<Record<string, DictOption[]>>({});
  const channelDict = dicts.notify_delivery_channel;
  const auditDict = dicts.notify_sms_audit_status;

  const hasPerm = usePerm();
  const canEdit = hasPerm(MAIL_PERMISSIONS.templateEdit);

  useEffect(() => {
    loadData();
    loadEvents();
    loadDicts();
  }, []);

  const loadDicts = () => {
    fetchDictOptions(['notify_delivery_channel', 'notify_sms_audit_status'])
      .then(setDicts)
      .catch(() => {
        /* 字典失败时回退显示原始值 */
      });
  };

  const loadData = () => {
    setLoading(true);
    messageTemplateApi
      .list()
      .then((res) => {
        if (res.code === 200) setData(res.data || []);
      })
      .catch((e: unknown) => message.error((e as Error).message))
      .finally(() => setLoading(false));
  };

  const loadEvents = () => {
    messageTemplateApi
      .options()
      .then((res) => {
        if (res.code === 200) setEvents(res.data || []);
      })
      .catch(() => {
        /* 事件列表失败不阻塞，退化为自由输入 */
      });
  };

  const eventDefOf = (code?: string) => (code ? events.find((e) => e.code === code) : undefined);
  const eventOptions = events.map((e) => ({ label: `${e.name}（${e.code}）`, value: e.code }));

  /** 渠道字典为空时回退到模块级全量 ALL_CHANNELS（见文件顶部说明） */
  const allowedChannels = useMemo(() => {
    const fromDict = (channelDict || []).map((o) => o.value);
    return fromDict.length ? fromDict : ALL_CHANNELS;
  }, [channelDict]);

  /** 事件当前真正会发的渠道，仅用于提示 */
  const declaredChannels = eventDefOf(selectedScene)?.channels || [];

  const channelOptions = allowedChannels.map((c) => ({
    label: dictLabel(channelDict, c, c),
    value: c,
  }));

  const mailSelected = channels.some((c) => c === 'EMAIL' || c === 'INAPP');
  const smsSelected = channels.includes('SMS');

  const parseDeclaredVars = (raw?: string): string[] => {
    try {
      const arr = JSON.parse(raw || '[]');
      return Array.isArray(arr) ? arr.filter((k: unknown) => typeof k === 'string') : [];
    } catch {
      return [];
    }
  };

  /** 提取文本中的 ${变量}（与后端 NotifyEventRegistry.extractVariables 同规则） */
  const extractVars = (text?: string): string[] =>
    text ? Array.from(text.matchAll(/\$\{([A-Za-z0-9_.-]+)\}/g), (m) => m[1]) : [];

  /** 变量声明 = 显式声明 ∪ 正文/主题中实际用到的，保序去重后回写为 JSON 数组 */
  const mergeUsedVars = (declaredRaw: string | undefined, texts: string[]): string =>
    JSON.stringify(
      Array.from(new Set([...parseDeclaredVars(declaredRaw), ...texts.flatMap(extractVars)])),
    );

  /** 选事件：变量自动补全为 payload 字段，渠道收窄到事件声明的渠道 */
  const handleScenePicked = (code: string) => {
    setSelectedScene(code);
    const def = eventDefOf(code);
    if (!def) return;
    const merged = Array.from(
      new Set([
        ...parseDeclaredVars(form.getFieldValue('variables')),
        ...(def.payloadFields || []),
      ]),
    );
    form.setFieldsValue({ variables: JSON.stringify(merged) });
    // 只做「补默认值」不做「收窄」：保留用户已勾的渠道，空选时默认事件声明的第一个渠道
    const next = channels.length ? channels : def.channels.slice(0, 1);
    form.setFieldsValue({ channels: next });
  };

  const openCreate = () => {
    setEditing(null);
    setSelectedScene(undefined);
    form.resetFields();
    form.setFieldsValue({ channels: ['EMAIL'] });
    setModalVisible(true);
  };

  const openEdit = (row: MessageTemplate) => {
    setEditing(row);
    setSelectedScene(row.scene);
    form.setFieldsValue({
      templateCode: row.templateCode,
      templateName: row.templateName,
      scene: row.scene,
      channels: row.channels || [],
      subject: row.subject,
      content: row.content,
      variables: row.variables,
      remark: row.remark,
      smsProviderTemplateCode: row.smsProviderTemplateCode,
      smsSignName: row.smsSignName,
      smsContentSample: row.smsContentSample,
      smsAuditStatus: row.smsAuditStatus ?? 0,
      smsAuditRemark: row.smsAuditRemark,
    });
    setModalVisible(true);
  };

  const handleSubmit = async () => {
    const values = await form.validateFields();
    // 变量声明与正文保持一致：后端按「用到的变量」校验并归一化，这里同步回显，避免表单里
    // 留着过期的声明（编辑老模板时最容易踩：声明缺字段被误判为"必须覆盖全部字段"）。
    const merged = mergeUsedVars(
      values.variables as string | undefined,
      [values.subject, values.content].filter(Boolean) as string[],
    );
    form.setFieldsValue({ variables: merged });
    const payload = {
      ...values,
      variables: merged,
      channels: values.channels || channels,
      originalCode:
        editing && editing.templateCode !== values.templateCode ? editing.templateCode : undefined,
      originalScene: editing && editing.scene !== values.scene ? editing.scene : undefined,
    };
    try {
      await messageTemplateApi.save(payload);
      message.success('保存成功');
      setModalVisible(false);
      loadData();
    } catch (e: unknown) {
      message.error((e as Error).message);
    }
  };

  const handleDelete = async (row: MessageTemplate) => {
    try {
      await messageTemplateApi.remove(row.templateCode, row.scene);
      message.success('删除成功');
      loadData();
    } catch (e: unknown) {
      message.error((e as Error).message);
    }
  };

  const handlePreview = async () => {
    const def = eventDefOf(selectedScene);
    const vars: Record<string, string> = {};
    const declared = parseDeclaredVars(form.getFieldValue('variables'));
    (def?.payloadFields?.length ? def.payloadFields : declared).forEach(
      (k) => (vars[k] = '示例值'),
    );
    try {
      const res = await messageTemplateApi.preview(form.getFieldValue('content') || '', vars);
      if (res.code === 200) {
        setPreviewHtml(res.data || '');
        setPreviewVisible(true);
      }
    } catch (e: unknown) {
      message.error((e as Error).message);
    }
  };

  const sceneHint = (() => {
    const def = eventDefOf(selectedScene);
    if (!def) return undefined;
    const fields = (def.payloadFields || []).join('、');
    return `${def.description || ''}${fields ? `（可用变量：${fields}）` : ''}`;
  })();

  const columns = [
    {
      title: '模板编码',
      dataIndex: 'templateCode',
      key: 'templateCode',
      width: 190,
      render: (v: string) => (
        <code title={v} style={{ fontSize: 12 }}>
          {v}
        </code>
      ),
    },
    { title: '模板名称', dataIndex: 'templateName', key: 'templateName', width: 160 },
    {
      title: '绑定事件',
      dataIndex: 'scene',
      key: 'scene',
      width: 170,
      render: (v: string) => {
        const def = eventDefOf(v);
        return (
          <Tooltip title={def ? `${def.name}（${v}）` : v}>
            <Tag color="blue">{def ? def.name : v}</Tag>
          </Tooltip>
        );
      },
    },
    {
      title: '投递渠道',
      dataIndex: 'channels',
      key: 'channels',
      width: 170,
      render: (list: string[]) => (
        <Space size={4}>
          {(list || []).map((c) => (
            <Tag key={c} color={dictColor(channelDict, c) || 'default'}>
              {dictLabel(channelDict, c, c)}
            </Tag>
          ))}
        </Space>
      ),
    },
    {
      title: '短信报备',
      key: 'sms',
      width: 100,
      render: (_: unknown, r: MessageTemplate) => {
        if (!(r.channels || []).includes('SMS')) return '-';
        const status = r.smsAuditStatus ?? 0;
        return (
          <Tag color={dictColor(auditDict, status) || 'default'}>
            {dictLabel(auditDict, status, String(status))}
          </Tag>
        );
      },
    },
    {
      // 操作列收成图标按钮：三枚文字链接（预览/编辑/删除）在 1440 宽下会把表格顶出容器，
      // 文案改由 Tooltip 承担，列宽从 200 降到 120 后不再横向溢出。
      title: '操作',
      key: 'action',
      width: 120,
      render: (_: unknown, r: MessageTemplate) => (
        <Space size={2}>
          {(r.channels || []).some((c) => c === 'EMAIL' || c === 'INAPP') && (
            <Tooltip title="预览">
              <Button
                type="text"
                size="small"
                icon={<EyeOutlined />}
                onClick={() => {
                  openEdit(r);
                  setTimeout(() => handlePreview(), 0);
                }}
              />
            </Tooltip>
          )}
          {canEdit && (
            <Tooltip title="编辑">
              <Button
                type="text"
                size="small"
                icon={<EditOutlined />}
                onClick={() => openEdit(r)}
              />
            </Tooltip>
          )}
          {canEdit && (
            <Popconfirm title="确认删除该模板的全部渠道内容?" onConfirm={() => handleDelete(r)}>
              <Tooltip title="删除">
                <Button type="text" size="small" danger icon={<DeleteOutlined />} />
              </Tooltip>
            </Popconfirm>
          )}
          {!canEdit && <span style={{ color: '#999' }}>无权限</span>}
        </Space>
      ),
    },
  ];

  return (
    <div className="space-y-4">
      <Card>
        <FilterBar split>
          <span style={{ fontWeight: 600, fontSize: 16 }}>消息模板</span>
          <div className="page-toolbar">
            {canEdit && (
              <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
                新建模板
              </Button>
            )}
          </div>
        </FilterBar>
        <ResizableTable
          dataSource={data}
          columns={columns}
          rowKey={(r: MessageTemplate) => `${r.scene}::${r.templateCode}`}
          loading={loading}
          scroll={{ x: 'max-content' }}
          pagination={{
            pageSize: 10,
            showSizeChanger: true,
            pageSizeOptions: ['10', '20', '50'],
            showQuickJumper: true,
          }}
        />
      </Card>

      <Modal
        title={editing ? '编辑消息模板' : '新建消息模板'}
        open={modalVisible}
        onOk={handleSubmit}
        onCancel={() => setModalVisible(false)}
        width={820}
        destroyOnClose
      >
        <Form form={form} layout="vertical" style={{ marginTop: 16 }}>
          <Space size={16} style={{ display: 'flex' }}>
            <Form.Item
              name="templateCode"
              label="模板编码"
              style={{ flex: 1 }}
              rules={[{ required: true }]}
              tooltip="同一渠道下唯一；发送侧按「绑定事件 × 渠道」取模板，编码只作标识"
            >
              <Input placeholder="如：feedbackStatusChanged" />
            </Form.Item>
            <Form.Item
              name="templateName"
              label="模板名称"
              style={{ flex: 1 }}
              rules={[{ required: true }]}
            >
              <Input placeholder="如：反馈状态变更通知" />
            </Form.Item>
          </Space>
          <Form.Item
            name="scene"
            label="绑定事件"
            extra={sceneHint}
            rules={[{ required: true }]}
            tooltip="事件由代码注册；同一个事件的一个渠道只能绑定一条模板"
          >
            <Select
              options={eventOptions}
              showSearch
              optionFilterProp="label"
              placeholder="选择系统事件"
              onChange={(v: string) => handleScenePicked(v)}
            />
          </Form.Item>
          <Form.Item
            name="channels"
            label="投递渠道（可多选）"
            rules={[{ required: true, message: '至少选择一个渠道' }]}
            extra={
              declaredChannels.length
                ? `该事件当前声明发往：${declaredChannels.map((c) => dictLabel(channelDict, c, c)).join('、')}；未声明的渠道也会保存，待事件真正发往该渠道时生效`
                : undefined
            }
            tooltip="勾选哪些渠道就维护哪些内容：邮箱/站内信共用主题与 HTML 正文，短信填供应商模板码与签名"
          >
            <Select mode="multiple" options={channelOptions} placeholder="选择投递渠道" />
          </Form.Item>

          {mailSelected && (
            <>
              <Form.Item
                name="subject"
                label="邮件主题"
                rules={[{ required: true, message: '请填写邮件主题' }]}
              >
                <Input placeholder="支持 ${变量} 占位" />
              </Form.Item>
              <Form.Item
                name="content"
                label="邮件/站内信正文（HTML）"
                rules={[{ required: true, message: '请填写正文' }]}
              >
                <Input.TextArea rows={8} placeholder={'<p>您好，${content}</p>'} />
              </Form.Item>
              <Form.Item
                name="variables"
                label="变量声明（JSON 数组）"
                tooltip="保存时自动合并正文/主题中用到的 ${变量}；用到的变量必须在事件声明的可用变量内"
              >
                <Input placeholder='["featureTitle","statusName"]' />
              </Form.Item>
            </>
          )}

          {smsSelected && (
            <>
              <Space size={16} style={{ display: 'flex' }}>
                <Form.Item
                  name="smsProviderTemplateCode"
                  label="供应商模板编码"
                  style={{ flex: 1 }}
                  tooltip="短信正文在供应商侧备案，本地只存映射"
                >
                  <Input placeholder="如：SMS_123456789" />
                </Form.Item>
                <Form.Item name="smsSignName" label="短信签名" style={{ flex: 1 }}>
                  <Input placeholder="如：Astral" />
                </Form.Item>
              </Space>
              <Form.Item name="smsContentSample" label="短信内容示例">
                <Input.TextArea rows={3} placeholder="【Astral】您的反馈已处理完成" />
              </Form.Item>
              <Space size={16} style={{ display: 'flex' }}>
                <Form.Item name="smsAuditStatus" label="报备状态" style={{ flex: 1 }}>
                  <Select
                    options={(auditDict || []).map((o) => ({
                      label: o.label,
                      value: Number(o.value),
                    }))}
                  />
                </Form.Item>
                <Form.Item name="smsAuditRemark" label="审核备注" style={{ flex: 2 }}>
                  <Input />
                </Form.Item>
              </Space>
            </>
          )}

          <Form.Item name="remark" label="备注">
            <Input.TextArea rows={2} />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title="模板预览"
        open={previewVisible}
        footer={null}
        width={760}
        onCancel={() => setPreviewVisible(false)}
      >
        {/* 正文是管理端可写内容，必须 sandbox 渲染：不执行脚本、不同源访问父页 */}
        <iframe
          title="模板预览"
          srcDoc={previewHtml}
          sandbox=""
          style={{
            width: '100%',
            height: 360,
            border: '1px solid var(--color-border-light)',
            borderRadius: 8,
            background: '#fff',
          }}
        />
      </Modal>
    </div>
  );
}
