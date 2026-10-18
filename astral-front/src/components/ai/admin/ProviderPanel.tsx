/**
 * 提供方与模型。
 *
 * 两者放在同一个页签，是因为它们是一对绑定关系：模型必须挂在某个提供方下面，
 * 分两个页签反而让人来回切换、配到一半找不到父级。
 */

'use client';

import React, { useCallback, useMemo, useState } from 'react';

import {
  Alert,
  Button,
  Card,
  Drawer,
  Empty,
  Form,
  Input,
  InputNumber,
  Modal,
  Popconfirm,
  Select,
  Space,
  Spin,
  Switch,
  Tag,
  Tooltip,
  message,
} from '@/components/antd-compat';
import {
  ApiOutlined,
  DeleteOutlined,
  EditOutlined,
  ExperimentOutlined,
  PlusOutlined,
  ReloadOutlined,
} from '@/components/antd-compat/icons';
import { ResizableTable } from '@/components/ResizableTable';

import {
  aiModelApi,
  aiProviderApi,
  type AiModel,
  type AiProvider,
  type ProviderTestResult,
} from '@/api/ai';
import { usePerm } from '@/lib/perm';

import { Hint, ReadOnlyNote, StatusTag, formatNumber, formatTime, useAiDict } from './shared';

export default function ProviderPanel() {
  const hasPerm = usePerm();
  const dict = useAiDict();
  const canEdit = hasPerm('admin:ai:provider:edit');
  const canEditModel = hasPerm('admin:ai:model:edit');

  const [providers, setProviders] = useState<AiProvider[]>([]);
  const [loading, setLoading] = useState(false);
  const [selectedId, setSelectedId] = useState<number | undefined>();
  const [testing, setTesting] = useState<number | undefined>();
  const [testResult, setTestResult] = useState<Record<number, ProviderTestResult>>({});

  const [providerModal, setProviderModal] = useState<{ open: boolean; row?: AiProvider }>({ open: false });
  const [modelDrawer, setModelDrawer] = useState<{ open: boolean; provider?: AiProvider }>({ open: false });
  const [saving, setSaving] = useState(false);
  const [providerForm] = Form.useForm();
  const [modelForm] = Form.useForm();

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const res = await aiProviderApi.list();
      const list = res.data ?? [];
      setProviders(list);
      setSelectedId((prev) => (prev && list.some((item) => item.id === prev) ? prev : list[0]?.id));
    } catch (error) {
      message.error(error instanceof Error ? error.message : '提供方加载失败');
    } finally {
      setLoading(false);
    }
  }, []);

  React.useEffect(() => {
    void load();
  }, [load]);

  const selected = useMemo(
    () => providers.find((item) => item.id === selectedId),
    [providers, selectedId],
  );

  /* ---------------- 提供方 ---------------- */

  const openProviderModal = (row?: AiProvider) => {
    setProviderModal({ open: true, row });
    providerForm.resetFields();
    if (row) {
      providerForm.setFieldsValue({
        providerCode: row.providerCode,
        name: row.name,
        providerType: row.providerType,
        baseUrl: row.baseUrl,
        secretRef: row.secretRef ?? '',
        enabled: row.enabled ?? true,
        connectTimeoutMs: row.connectTimeoutMs ?? 10000,
        readTimeoutMs: row.readTimeoutMs ?? 120000,
        maxRetries: row.maxRetries ?? 1,
        weight: row.weight ?? 100,
        remark: row.remark ?? '',
      });
    } else {
      providerForm.setFieldsValue({
        providerType: 'OPENAI_COMPATIBLE',
        enabled: true,
        connectTimeoutMs: 15000,
        readTimeoutMs: 120000,
        maxRetries: 1,
        weight: 100,
      });
    }
  };

  const submitProvider = async () => {
    try {
      const values = await providerForm.validateFields();
      setSaving(true);
      if (providerModal.row?.id) {
        await aiProviderApi.update(providerModal.row.id, values);
        message.success('已保存');
      } else {
        await aiProviderApi.create(values);
        message.success('已创建');
      }
      setProviderModal({ open: false });
      await load();
    } catch (error) {
      if (error instanceof Error) message.error(error.message);
    } finally {
      setSaving(false);
    }
  };

  const removeProvider = async (row: AiProvider) => {
    if (!row.id) return;
    try {
      await aiProviderApi.remove(row.id);
      message.success('已删除');
      await load();
    } catch (error) {
      message.error(error instanceof Error ? error.message : '删除失败');
    }
  };

  const testProvider = async (row: AiProvider) => {
    if (!row.id) return;
    setTesting(row.id);
    try {
      const res = await aiProviderApi.test(row.id);
      const result = res.data ?? {};
      setTestResult((prev) => ({ ...prev, [row.id as number]: result }));
      if (result.healthy) {
        message.success(`连接正常（${result.latencyMs ?? 0} ms）`);
      } else {
        message.warning(result.message || '连接失败，请检查 Base URL 与密钥');
      }
      await load();
    } catch (error) {
      message.error(error instanceof Error ? error.message : '测试失败');
    } finally {
      setTesting(undefined);
    }
  };

  const providerColumns = [
    {
      key: 'name',
      title: '名称',
      dataIndex: 'name',
      width: 180,
      render: (_: unknown, row: AiProvider) => (
        <Space size={6}>
          <ApiOutlined style={{ opacity: 0.6 }} />
          <span style={{ fontWeight: 500 }}>{row.name}</span>
        </Space>
      ),
    },
    {
      key: 'providerCode',
      title: '编码',
      dataIndex: 'providerCode',
      width: 150,
      render: (value: string) => <code style={{ fontSize: 12, opacity: 0.7 }}>{value}</code>,
    },
    {
      key: 'providerType',
      title: '类型',
      dataIndex: 'providerType',
      width: 150,
      render: (value: string) => <Tag color="default">{dict.label('ai_provider_type', value)}</Tag>,
    },
    {
      key: 'baseUrl',
      title: 'Base URL',
      dataIndex: 'baseUrl',
      ellipsis: true,
      render: (value: string) => (
        <Tooltip title={value}>
          <span style={{ fontSize: 12, opacity: 0.75 }}>{value}</span>
        </Tooltip>
      ),
    },
    {
      key: 'credentialSource',
      title: '密钥',
      dataIndex: 'credentialSource',
      width: 110,
      render: (value: string) => {
        const map: Record<string, { text: string; color: string }> = {
          DB: { text: '已配置', color: 'success' },
          ENV: { text: '环境变量', color: 'processing' },
          NONE: { text: '未配置', color: 'warning' },
        };
        const item = map[value ?? 'NONE'] ?? map.NONE;
        return <Tag color={item.color}>{item.text}</Tag>;
      },
    },
    {
      key: 'healthStatus',
      title: '健康',
      dataIndex: 'healthStatus',
      width: 130,
      render: (value: string, row: AiProvider) => {
        const result = row.id ? testResult[row.id] : undefined;
        return (
          <Space size={4} direction="vertical">
            <StatusTag dict={dict} code="" value={value ?? 'UNKNOWN'} />
            {result?.message && (
              <span style={{ fontSize: 11, opacity: 0.55 }}>{result.latencyMs ?? 0} ms</span>
            )}
          </Space>
        );
      },
    },
    {
      key: 'modelCount',
      title: '模型数',
      dataIndex: 'modelCount',
      width: 80,
      align: 'right' as const,
      render: (value: number) => formatNumber(value),
    },
    {
      key: 'enabled',
      title: '启用',
      dataIndex: 'enabled',
      width: 70,
      render: (value: boolean) => <Tag color={value ? 'success' : 'default'}>{value ? '是' : '否'}</Tag>,
    },
    {
      key: 'action',
      title: '操作',
      dataIndex: 'id',
      width: 210,
      render: (_: unknown, row: AiProvider) => (
        <Space size={4} wrap>
          <Button size="small" icon={<ExperimentOutlined />} loading={testing === row.id} onClick={() => void testProvider(row)}>
            测试
          </Button>
          <Button
            size="small"
            icon={<ApiOutlined />}
            onClick={() => {
              setSelectedId(row.id);
              setModelDrawer({ open: true, provider: row });
            }}
          >
            模型
          </Button>
          <Button size="small" type="text" icon={<EditOutlined />} disabled={!canEdit} onClick={() => openProviderModal(row)} />
          <Popconfirm
            title="删除提供方？"
            description="其下的模型与引用它的路由会一起失效。"
            okButtonProps={{ danger: true }}
            disabled={!canEdit}
            onConfirm={() => void removeProvider(row)}
          >
            <Button size="small" type="text" danger icon={<DeleteOutlined />} disabled={!canEdit} />
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <div>
      <Hint>
        这是所有模型调用的出口。密钥建议用 <code>secretRef</code> 引环境变量，落库的密钥会用 AES-256-GCM 加密存储，
        接口永远只回「是否已配置」而不回明文。Base URL 会做私网与元数据地址拦截（SSRF 防护），填内网地址会直接报错。
      </Hint>

      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 10 }}>
        <Space size={8}>
          <span style={{ fontSize: 13, fontWeight: 500 }}>提供方</span>
          <Tag color="default">{providers.length}</Tag>
          <ReadOnlyNote show={!canEdit} />
        </Space>
        <Space size={8}>
          <Button size="small" icon={<ReloadOutlined />} onClick={() => void load()} loading={loading}>
            刷新
          </Button>
          <Button size="small" type="primary" icon={<PlusOutlined />} disabled={!canEdit} onClick={() => openProviderModal()}>
            新建提供方
          </Button>
        </Space>
      </div>

      <ResizableTable
        resizeKey="ai-provider"
        rowKey="id"
        columns={providerColumns}
        dataSource={providers}
        loading={loading}
        pagination={false}
        onRow={(row: AiProvider) => ({ onClick: () => setSelectedId(row.id) })}
      />

      <Modal
        title={providerModal.row ? '编辑提供方' : '新建提供方'}
        open={providerModal.open}
        onCancel={() => setProviderModal({ open: false })}
        onOk={() => void submitProvider()}
        confirmLoading={saving}
        okText="保存"
        cancelText="取消"
        width={620}
        destroyOnClose
      >
        <Form form={providerForm} layout="vertical">
          <Space size={12} style={{ display: 'flex' }}>
            <Form.Item
              name="providerCode"
              label="编码"
              style={{ flex: 1 }}
              rules={[
                { required: true, message: '请输入编码' },
                { pattern: /^[a-zA-Z0-9_-]+$/, message: '只能是字母、数字、下划线、短横线' },
                { max: 64, message: '最多 64 个字符' },
              ]}
            >
              <Input placeholder="例如 openai_main" disabled={Boolean(providerModal.row)} />
            </Form.Item>
            <Form.Item name="name" label="名称" style={{ flex: 1 }} rules={[{ required: true, message: '请输入名称' }, { max: 128 }]}>
              <Input placeholder="例如 OpenAI 主账号" />
            </Form.Item>
          </Space>

          <Space size={12} style={{ display: 'flex' }}>
            <Form.Item name="providerType" label="类型" style={{ flex: 1 }} rules={[{ required: true }]}>
              <Select options={dict.options('ai_provider_type')} placeholder="选择类型" />
            </Form.Item>
            <Form.Item
              name="baseUrl"
              label="Base URL"
              style={{ flex: 2 }}
              rules={[{ required: true, message: '请输入 Base URL' }, { max: 512 }]}
            >
              <Input placeholder="https://api.openai.com/v1" />
            </Form.Item>
          </Space>

          <Form.Item name="apiKey" label="API Key" extra="留空表示不修改；用 secretRef 时可完全不填">
            <Input.Password placeholder="sk-..." maxLength={512} autoComplete="new-password" />
          </Form.Item>

          <Form.Item name="secretRef" label="密钥引用" extra="环境变量名，优先级高于数据库中的密钥">
            <Input placeholder="例如 OPENAI_API_KEY" maxLength={128} />
          </Form.Item>

          <Space size={12} style={{ display: 'flex' }}>
            <Form.Item name="connectTimeoutMs" label="连接超时(ms)" style={{ flex: 1 }}>
              <InputNumber min={500} max={120000} step={500} style={{ width: '100%' }} />
            </Form.Item>
            <Form.Item name="readTimeoutMs" label="读取超时(ms)" style={{ flex: 1 }}>
              <InputNumber min={1000} max={600000} step={1000} style={{ width: '100%' }} />
            </Form.Item>
            <Form.Item name="maxRetries" label="重试次数" style={{ flex: 1 }}>
              <InputNumber min={0} max={5} style={{ width: '100%' }} />
            </Form.Item>
            <Form.Item name="weight" label="权重" style={{ flex: 1 }}>
              <InputNumber min={1} max={10000} style={{ width: '100%' }} />
            </Form.Item>
          </Space>

          <Form.Item name="enabled" label="启用" valuePropName="checked">
            <Switch />
          </Form.Item>

          <Form.Item name="remark" label="备注">
            <Input.TextArea rows={2} maxLength={512} placeholder="例如：生产环境专用，月配额 100 万 token" />
          </Form.Item>
        </Form>
      </Modal>

      <ModelDrawer
        open={modelDrawer.open}
        provider={modelDrawer.provider}
        canEdit={canEditModel}
        dict={dict}
        form={modelForm}
        onClose={() => setModelDrawer({ open: false })}
      />
    </div>
  );
}

/* ==================== 模型抽屉 ==================== */

function ModelDrawer({
  open,
  provider,
  canEdit,
  dict,
  form,
  onClose,
}: {
  open: boolean;
  provider?: AiProvider;
  canEdit: boolean;
  dict: ReturnType<typeof useAiDict>;
  form: ReturnType<typeof Form.useForm>[0];
  onClose: () => void;
}) {
  const [models, setModels] = useState<AiModel[]>([]);
  const [loading, setLoading] = useState(false);
  const [editing, setEditing] = useState<AiModel | undefined>();
  const [modalOpen, setModalOpen] = useState(false);
  const [saving, setSaving] = useState(false);

  const load = useCallback(async () => {
    if (!provider?.id) return;
    setLoading(true);
    try {
      const res = await aiModelApi.list(provider.id);
      setModels(res.data ?? []);
    } catch (error) {
      message.error(error instanceof Error ? error.message : '模型加载失败');
    } finally {
      setLoading(false);
    }
  }, [provider?.id]);

  React.useEffect(() => {
    if (open) void load();
  }, [open, load]);

  const openModal = (row?: AiModel) => {
    setEditing(row);
    setModalOpen(true);
    form.resetFields();
    if (row) {
      form.setFieldsValue({ ...row });
    } else {
      form.setFieldsValue({
        modelKind: 'CHAT',
        supportsStream: true,
        supportsTools: true,
        supportsJson: true,
        supportsVision: false,
        maxContextTokens: 128000,
        maxOutputTokens: 4096,
        currency: 'CNY',
        enabled: true,
      });
    }
  };

  const submit = async () => {
    try {
      const values = (await form.validateFields()) as Partial<AiModel>;
      setSaving(true);
      const payload: Partial<AiModel> = { ...values, providerId: provider?.id };
      if (editing?.id) {
        await aiModelApi.update(editing.id, payload);
      } else {
        await aiModelApi.create(payload);
      }
      message.success('已保存');
      setModalOpen(false);
      await load();
    } catch (error) {
      if (error instanceof Error) message.error(error.message);
    } finally {
      setSaving(false);
    }
  };

  const columns = [
    { key: 'modelName', title: '名称', dataIndex: 'modelName', width: 170 },
    {
      key: 'modelCode',
      title: '编码',
      dataIndex: 'modelCode',
      width: 170,
      render: (value: string) => <code style={{ fontSize: 12, opacity: 0.75 }}>{value}</code>,
    },
    {
      key: 'modelKind',
      title: '类型',
      dataIndex: 'modelKind',
      width: 110,
      render: (value: string) => <Tag color="default">{dict.label('ai_model_kind', value)}</Tag>,
    },
    {
      key: 'capabilities',
      title: '能力',
      dataIndex: 'id',
      width: 190,
      render: (_: unknown, row: AiModel) => (
        <Space size={3} wrap>
          {row.supportsStream && <Tag color="default">流式</Tag>}
          {row.supportsTools && <Tag color="default">工具</Tag>}
          {row.supportsJson && <Tag color="default">JSON</Tag>}
          {row.supportsVision && <Tag color="default">视觉</Tag>}
        </Space>
      ),
    },
    {
      key: 'context',
      title: '上下文',
      dataIndex: 'maxContextTokens',
      width: 110,
      align: 'right' as const,
      render: (value: number) => formatNumber(value),
    },
    {
      key: 'enabled',
      title: '启用',
      dataIndex: 'enabled',
      width: 70,
      render: (value: boolean) => <Tag color={value ? 'success' : 'default'}>{value ? '是' : '否'}</Tag>,
    },
    {
      key: 'action',
      title: '操作',
      dataIndex: 'id',
      width: 100,
      render: (_: unknown, row: AiModel) => (
        <Space size={2}>
          <Button size="small" type="text" icon={<EditOutlined />} disabled={!canEdit} onClick={() => openModal(row)} />
          <Popconfirm
            title="删除模型？"
            okButtonProps={{ danger: true }}
            disabled={!canEdit}
            onConfirm={async () => {
              if (!row.id) return;
              try {
                await aiModelApi.remove(row.id);
                message.success('已删除');
                await load();
              } catch (error) {
                message.error(error instanceof Error ? error.message : '删除失败');
              }
            }}
          >
            <Button size="small" type="text" danger icon={<DeleteOutlined />} disabled={!canEdit} />
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <Drawer
      title={provider ? `${provider.name} · 模型` : '模型'}
      open={open}
      onClose={onClose}
      width={1080}
      extra={
        <Space size={8}>
          <Button size="small" icon={<ReloadOutlined />} onClick={() => void load()} loading={loading}>
            刷新
          </Button>
          <Button size="small" type="primary" icon={<PlusOutlined />} disabled={!canEdit} onClick={() => openModal()}>
            新建模型
          </Button>
        </Space>
      }
    >
      {models.length === 0 && !loading ? (
        <Empty description="该提供方下还没有模型。一个提供方至少要有一个 CHAT 模型，智能体才能选到路由。" />
      ) : (
        <ResizableTable
          resizeKey="ai-model"
          rowKey="id"
          columns={columns}
          dataSource={models}
          loading={loading}
          pagination={false}
        />
      )}

      <Modal
        title={editing ? '编辑模型' : '新建模型'}
        open={modalOpen}
        onCancel={() => setModalOpen(false)}
        onOk={() => void submit()}
        confirmLoading={saving}
        okText="保存"
        cancelText="取消"
        width={640}
        destroyOnClose
      >
        <Form form={form} layout="vertical">
          <Space size={12} style={{ display: 'flex' }}>
            <Form.Item name="modelCode" label="模型编码" style={{ flex: 1 }} rules={[{ required: true, message: '请输入模型编码' }]}>
              <Input placeholder="gpt-4o-mini" />
            </Form.Item>
            <Form.Item name="modelName" label="展示名" style={{ flex: 1 }} rules={[{ required: true, message: '请输入展示名' }]}>
              <Input placeholder="GPT-4o mini" />
            </Form.Item>
            <Form.Item name="modelKind" label="类型" style={{ width: 140 }}>
              <Select options={dict.options('ai_model_kind')} />
            </Form.Item>
          </Space>

          <Space size={16} style={{ display: 'flex', marginBottom: 4 }}>
            <Form.Item name="supportsStream" label="流式" valuePropName="checked">
              <Switch size="small" />
            </Form.Item>
            <Form.Item name="supportsTools" label="工具调用" valuePropName="checked">
              <Switch size="small" />
            </Form.Item>
            <Form.Item name="supportsJson" label="JSON 输出" valuePropName="checked">
              <Switch size="small" />
            </Form.Item>
            <Form.Item name="supportsVision" label="视觉" valuePropName="checked">
              <Switch size="small" />
            </Form.Item>
            <Form.Item name="enabled" label="启用" valuePropName="checked">
              <Switch size="small" />
            </Form.Item>
          </Space>

          <Space size={12} style={{ display: 'flex' }}>
            <Form.Item name="maxContextTokens" label="最大上下文" style={{ flex: 1 }}>
              <InputNumber min={1024} max={4000000} step={1024} style={{ width: '100%' }} />
            </Form.Item>
            <Form.Item name="maxOutputTokens" label="最大输出" style={{ flex: 1 }}>
              <InputNumber min={128} max={200000} step={128} style={{ width: '100%' }} />
            </Form.Item>
            <Form.Item name="currency" label="币种" style={{ width: 100 }}>
              <Input maxLength={8} />
            </Form.Item>
          </Space>

          <Space size={12} style={{ display: 'flex' }}>
            <Form.Item name="inputPrice" label="输入单价（每 1K token）" style={{ flex: 1 }}>
              <InputNumber min={0} step={0.000001} precision={6} style={{ width: '100%' }} />
            </Form.Item>
            <Form.Item name="outputPrice" label="输出单价（每 1K token）" style={{ flex: 1 }}>
              <InputNumber min={0} step={0.000001} precision={6} style={{ width: '100%' }} />
            </Form.Item>
          </Space>

          <Form.Item name="remark" label="备注">
            <Input.TextArea rows={2} maxLength={512} />
          </Form.Item>
        </Form>
      </Modal>
    </Drawer>
  );
}
