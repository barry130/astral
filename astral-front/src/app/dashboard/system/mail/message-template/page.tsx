'use client';

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
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
  type MessageTemplateSave,
  messageTemplatePayload,
  parseTemplateVariables,
  mergeTemplateVariables,
  previewMessageTemplate,
} from '@/api/message-template';
import {
  loadAdminDictOptions as fetchDictOptions,
  dictLabel,
  dictColor,
  DictOption,
} from '@/components/admin/admin-dictionaries';
import { usePerm, MAIL_PERMISSIONS } from '@/lib/perm';
import { ResizableTable } from '@/components/ResizableTable';
import { FilterBar } from '@/components/filter-bar';
import {
  AdminLoadNotice,
  AdminLoadingNotice,
  AdminOptionNotice,
} from '@/components/admin/AdminLoadNotice';
import { useAdminLoader, useAdminMutation } from '@/components/admin/use-admin-task';
import { requireAdminSuccess } from '@/components/admin/admin-task';

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
  const { loading, error: loadError, run: runLoad } = useAdminLoader('加载模板列表失败');
  const {
    loading: optionsLoading,
    error: optionsError,
    run: runOptions,
  } = useAdminLoader('加载模板选项失败');
  const {
    loading: previewLoading,
    error: previewError,
    run: runPreview,
    invalidate: invalidatePreview,
  } = useAdminLoader('预览失败');
  const { busy: saving, run: runMutation, isBusy } = useAdminMutation('模板操作失败');
  const previewInput = useRef<Pick<MessageTemplate, 'scene' | 'content' | 'variables'> | null>(
    null,
  );
  const [data, setData] = useState<MessageTemplate[]>([]);
  const [events, setEvents] = useState<MessageTemplateEventOption[]>([]);
  const [modalVisible, setModalVisible] = useState(false);
  const [editing, setEditing] = useState<MessageTemplate | null>(null);
  const [form] = Form.useForm<MessageTemplateSave>();

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

  const loadData = useCallback(
    () =>
      runLoad(async () => {
        const rows = requireAdminSuccess(await messageTemplateApi.list(), '加载模板列表失败');
        if (!Array.isArray(rows)) throw new Error('模板列表数据缺失');
        return rows;
      }, setData),
    [runLoad],
  );
  const loadOptions = useCallback(
    () =>
      runOptions(
        async () => {
          const [response, dictionary] = await Promise.all([
            messageTemplateApi.options(),
            fetchDictOptions(['notify_delivery_channel', 'notify_sms_audit_status']),
          ]);
          const eventRows = requireAdminSuccess(response, '加载事件选项失败');
          if (!eventRows?.length) throw new Error('暂无已注册事件，暂不能保存模板');
          if (
            !dictionary.notify_delivery_channel?.length ||
            !dictionary.notify_sms_audit_status?.length
          )
            throw new Error('模板字典选项为空');
          return { eventRows, dictionary };
        },
        (result) => {
          setEvents(result.eventRows);
          setDicts(result.dictionary);
        },
      ),
    [runOptions],
  );
  useEffect(() => {
    void loadData();
    void loadOptions();
  }, [loadData, loadOptions]);

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

  const parseDeclaredVars = parseTemplateVariables;

  /** 选事件：补全变量与空渠道默认值，不覆盖已配置渠道。 */
  const handleScenePicked = (code: string) => {
    if (isBusy()) return;
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
    if (!canEdit || isBusy()) return;
    setEditing(null);
    setSelectedScene(undefined);
    form.resetFields();
    form.setFieldsValue({ channels: ['EMAIL'] });
    setModalVisible(true);
  };

  const openEdit = (row: MessageTemplate) => {
    if (!canEdit || isBusy()) return;
    form.resetFields();
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

  const handleSubmit = () => {
    if (!canEdit || optionsLoading || optionsError) return;
    const original = editing ? { templateCode: editing.templateCode, scene: editing.scene } : null;
    return runMutation(
      async () => {
        const values = await form.validateFields();
        const variables = mergeTemplateVariables(values.variables, [
          values.subject,
          values.content,
        ]);
        const payload = messageTemplatePayload({
          ...values,
          variables,
          channels: values.channels || [],
          originalCode:
            original && original.templateCode !== values.templateCode
              ? original.templateCode
              : undefined,
          originalScene: original && original.scene !== values.scene ? original.scene : undefined,
        });
        requireAdminSuccess(await messageTemplateApi.save(payload), '保存模板失败');
      },
      () => {
        message.success('保存成功');
        setModalVisible(false);
        void loadData();
      },
    );
  };

  const handleDelete = (row: MessageTemplate) => {
    if (!canEdit) return;
    const code = row.templateCode,
      scene = row.scene;
    return runMutation(
      async () => requireAdminSuccess(await messageTemplateApi.remove(code, scene), '删除模板失败'),
      () => {
        message.success('删除成功');
        void loadData();
      },
    );
  };

  const loadPreview = useCallback(
    () =>
      runPreview(
        async () => {
          const input = previewInput.current;
          if (!input?.content?.trim()) throw new Error('模板正文为空');
          const event = events.find((entry) => entry.code === input.scene);
          return previewMessageTemplate(input, event?.payloadFields || []);
        },
        (html) => setPreviewHtml(html),
      ),
    [runPreview, events],
  );

  const handlePreview = (row: MessageTemplate) => {
    previewInput.current = { scene: row.scene, content: row.content, variables: row.variables };
    setPreviewHtml('');
    setPreviewVisible(true);
    void loadPreview();
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
                aria-label={`预览模板 ${r.templateCode}`}
                onClick={() => handlePreview(r)}
              />
            </Tooltip>
          )}
          {canEdit && (
            <Tooltip title="编辑">
              <Button
                type="text"
                size="small"
                icon={<EditOutlined />}
                aria-label={`编辑模板 ${r.templateCode}`}
                disabled={saving}
                onClick={() => openEdit(r)}
              />
            </Tooltip>
          )}
          {canEdit && (
            <Popconfirm title="确认删除该模板的全部渠道内容?" onConfirm={() => handleDelete(r)}>
              <Tooltip title="删除">
                <Button
                  aria-label={`删除模板 ${r.templateCode}`}
                  disabled={saving}
                  type="text"
                  size="small"
                  danger
                  icon={<DeleteOutlined />}
                />
              </Tooltip>
            </Popconfirm>
          )}
          {!canEdit && <span className="text-muted-foreground">无权限</span>}
        </Space>
      ),
    },
  ];

  return (
    <div className="space-y-4">
      <Card>
        <FilterBar
          actions={
            canEdit ? (
              <Button
                type="primary"
                disabled={saving || optionsLoading || !!optionsError}
                icon={<PlusOutlined />}
                onClick={openCreate}
              >
                新建模板
              </Button>
            ) : null
          }
        >
          <span style={{ fontWeight: 600, fontSize: 16 }}>消息模板</span>
        </FilterBar>
        <AdminLoadNotice error={loadError} hasData={data.length > 0} onRetry={loadData} />
        <AdminOptionNotice error={optionsError} onRetry={loadOptions} />
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
        onCancel={() => {
          if (!isBusy()) setModalVisible(false);
        }}
        confirmLoading={saving}
        cancelButtonProps={{ disabled: saving }}
        okButtonProps={{ disabled: saving || optionsLoading || !!optionsError }}
        width={820}
        destroyOnClose
      >
        <Form form={form} layout="vertical" style={{ marginTop: 16 }} disabled={saving}>
          <Space size={16} wrap style={{ display: 'flex' }}>
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
              <Space size={16} wrap style={{ display: 'flex' }}>
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
              <Space size={16} wrap style={{ display: 'flex' }}>
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
        onCancel={() => {
          invalidatePreview();
          previewInput.current = null;
          setPreviewVisible(false);
        }}
      >
        {/* 正文是管理端可写内容，必须 sandbox 渲染：不执行脚本、不同源访问父页 */}
        <AdminLoadNotice error={previewError} hasData={!!previewHtml} onRetry={loadPreview} />
        {previewLoading ? (
          <AdminLoadingNotice label="正在渲染模板预览…" />
        ) : (
          !previewError && (
            <iframe
              title="模板预览"
              srcDoc={previewHtml}
              sandbox=""
              className="bg-background"
              style={{
                width: '100%',
                height: 'min(360px, 70vh)',
                border: '1px solid var(--color-border-light)',
                borderRadius: 8,
              }}
            />
          )
        )}
      </Modal>
    </div>
  );
}
