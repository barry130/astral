/**
 * 模型路由。
 *
 * 路由是「智能体版本 → 具体模型」的中间层。这样换模型不用改智能体，
 * 也能给同一套参数（temperature / topP / maxTokens）配多个候选。
 */

'use client';

import React, { useCallback, useEffect, useState } from 'react';

import {
  Button,
  Form,
  Input,
  InputNumber,
  Modal,
  Popconfirm,
  Select,
  Space,
  Switch,
  Tag,
  message,
} from '@/components/antd-compat';
import { DeleteOutlined, EditOutlined, PlusOutlined, ReloadOutlined } from '@/components/antd-compat/icons';
import { ResizableTable } from '@/components/ResizableTable';

import { aiModelApi, aiProviderApi, aiRouteApi, type AiModel, type AiProvider, type AiRoute } from '@/api/ai';
import { usePerm } from '@/lib/perm';

import { Hint, JsonBlock, ReadOnlyNote, formatNumber, jsonObjectRule, useAiDict } from './shared';

export default function RoutePanel() {
  const hasPerm = usePerm();
  const dict = useAiDict();
  const canEdit = hasPerm('admin:ai:route:edit');

  const [routes, setRoutes] = useState<AiRoute[]>([]);
  const [providers, setProviders] = useState<AiProvider[]>([]);
  const [models, setModels] = useState<AiModel[]>([]);
  const [loading, setLoading] = useState(false);
  const [modal, setModal] = useState<{ open: boolean; row?: AiRoute }>({ open: false });
  const [saving, setSaving] = useState(false);
  const [form] = Form.useForm();

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [routeRes, providerRes, modelRes] = await Promise.all([
        aiRouteApi.list(),
        aiProviderApi.list(),
        aiModelApi.list(),
      ]);
      setRoutes(routeRes.data ?? []);
      setProviders(providerRes.data ?? []);
      setModels(modelRes.data ?? []);
    } catch (error) {
      message.error(error instanceof Error ? error.message : '路由加载失败');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const modelOptions = (providerId?: number) =>
    models
      .filter((item) => (providerId ? item.providerId === providerId : true))
      .map((item) => ({ label: `${item.modelName}（${item.modelCode}）`, value: item.id as number }));

  const openModal = (row?: AiRoute) => {
    setModal({ open: true, row });
    form.resetFields();
    if (row) {
      form.setFieldsValue({ ...row });
    } else {
      form.setFieldsValue({ temperature: 0.3, topP: 0.9, maxTokens: 2048, enabled: true });
    }
  };

  const submit = async () => {
    try {
      const values = (await form.validateFields()) as Partial<AiRoute>;
      setSaving(true);
      if (modal.row?.id) {
        await aiRouteApi.update(modal.row.id, values);
      } else {
        await aiRouteApi.create(values);
      }
      message.success('已保存');
      setModal({ open: false });
      await load();
    } catch (error) {
      if (error instanceof Error) message.error(error.message);
    } finally {
      setSaving(false);
    }
  };

  const columns = [
    { key: 'name', title: '名称', dataIndex: 'name', width: 160 },
    {
      key: 'routeCode',
      title: '编码',
      dataIndex: 'routeCode',
      width: 160,
      render: (value: string) => <code style={{ fontSize: 12, opacity: 0.75 }}>{value}</code>,
    },
    {
      key: 'model',
      title: '模型',
      dataIndex: 'modelCode',
      width: 220,
      render: (value: string, row: AiRoute) => (
        <Space size={4} direction="vertical">
          <span style={{ fontSize: 13 }}>{row.modelCode ?? '-'}</span>
          <span style={{ fontSize: 11.5, opacity: 0.55 }}>{row.providerName ?? '-'}</span>
        </Space>
      ),
    },
    {
      key: 'fallback',
      title: '备用模型',
      dataIndex: 'fallbackModelCode',
      width: 150,
      render: (value: string) => value || <span style={{ opacity: 0.4 }}>-</span>,
    },
    {
      key: 'params',
      title: '参数',
      dataIndex: 'temperature',
      width: 200,
      render: (_: unknown, row: AiRoute) => (
        <Space size={4} wrap>
          <Tag color="default">T {row.temperature ?? '-'}</Tag>
          <Tag color="default">topP {row.topP ?? '-'}</Tag>
          <Tag color="default">max {formatNumber(row.maxTokens)}</Tag>
        </Space>
      ),
    },
    {
      key: 'extra',
      title: '额外参数',
      dataIndex: 'extraParametersJson',
      width: 160,
      render: (value: string) =>
        value ? (
          <div style={{ maxWidth: 220 }}>
            <JsonBlock value={value} maxHeight={80} />
          </div>
        ) : (
          <span style={{ opacity: 0.4 }}>-</span>
        ),
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
      render: (_: unknown, row: AiRoute) => (
        <Space size={2}>
          <Button size="small" type="text" icon={<EditOutlined />} disabled={!canEdit} onClick={() => openModal(row)} />
          <Popconfirm
            title="删除路由？"
            description="引用它的智能体版本将无法发布。"
            okButtonProps={{ danger: true }}
            disabled={!canEdit}
            onConfirm={async () => {
              if (!row.id) return;
              try {
                await aiRouteApi.remove(row.id);
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
    <div>
      <Hint>
        路由把「智能体版本」和「具体模型」解耦：换模型只改路由，不用重发智能体新版本。
        额外参数会原样并入请求体，但不能覆盖 temperature / top_p / max_tokens 这些已显式设置的同名键。
      </Hint>

      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 10 }}>
        <Space size={8}>
          <span style={{ fontSize: 13, fontWeight: 500 }}>路由</span>
          <Tag color="default">{routes.length}</Tag>
          <ReadOnlyNote show={!canEdit} />
        </Space>
        <Space size={8}>
          <Button size="small" icon={<ReloadOutlined />} onClick={() => void load()} loading={loading}>
            刷新
          </Button>
          <Button size="small" type="primary" icon={<PlusOutlined />} disabled={!canEdit} onClick={() => openModal()}>
            新建路由
          </Button>
        </Space>
      </div>

      <ResizableTable
        resizeKey="ai-route"
        rowKey="id"
        columns={columns}
        dataSource={routes}
        loading={loading}
        pagination={false}
      />

      <Modal
        title={modal.row ? '编辑路由' : '新建路由'}
        open={modal.open}
        onCancel={() => setModal({ open: false })}
        onOk={() => void submit()}
        confirmLoading={saving}
        okText="保存"
        cancelText="取消"
        width={680}
        destroyOnClose
      >
        <Form form={form} layout="vertical">
          <Space size={12} style={{ display: 'flex' }}>
            <Form.Item
              name="routeCode"
              label="编码"
              style={{ flex: 1 }}
              rules={[
                { required: true, message: '请输入编码' },
                { pattern: /^[a-zA-Z0-9_-]+$/, message: '只能是字母、数字、下划线、短横线' },
              ]}
            >
              <Input placeholder="例如 default_route" disabled={Boolean(modal.row)} />
            </Form.Item>
            <Form.Item name="name" label="名称" style={{ flex: 1 }} rules={[{ required: true, message: '请输入名称' }]}>
              <Input placeholder="例如 默认对话路由" />
            </Form.Item>
          </Space>

          <Form.Item name="providerId" label="提供方" rules={[{ required: true, message: '请选择提供方' }]}>
            <Select
              placeholder="选择提供方"
              options={providers.map((item) => ({ label: item.name as string, value: item.id as number }))}
              onChange={() => form.setFieldsValue({ modelId: undefined, fallbackModelId: undefined })}
            />
          </Form.Item>

          <Form.Item
            name="modelId"
            label="模型"
            rules={[{ required: true, message: '请选择模型' }]}
            dependencies={['providerId']}
          >
            <Select
              placeholder="选择模型"
              options={modelOptions(form.getFieldValue('providerId'))}
              showSearch
              optionFilterProp="label"
            />
          </Form.Item>

          <Form.Item name="fallbackModelId" label="备用模型" extra="主模型不可用时自动切换">
            <Select allowClear placeholder="可不填" options={modelOptions(form.getFieldValue('providerId'))} showSearch optionFilterProp="label" />
          </Form.Item>

          <Space size={12} style={{ display: 'flex' }}>
            <Form.Item name="temperature" label="temperature" style={{ flex: 1 }}>
              <InputNumber min={0} max={2} step={0.1} precision={2} style={{ width: '100%' }} />
            </Form.Item>
            <Form.Item name="topP" label="top_p" style={{ flex: 1 }}>
              <InputNumber min={0} max={1} step={0.05} precision={2} style={{ width: '100%' }} />
            </Form.Item>
            <Form.Item name="maxTokens" label="max_tokens" style={{ flex: 1 }}>
              <InputNumber min={1} max={200000} step={128} style={{ width: '100%' }} />
            </Form.Item>
          </Space>

          <Form.Item
            name="extraParametersJson"
            label="额外参数（JSON 对象）"
            rules={[jsonObjectRule]}
            extra='例如 {"frequency_penalty": 0.2}；不能覆盖上层已设置的键'
          >
            <Input.TextArea rows={3} placeholder="{}" />
          </Form.Item>

          <Form.Item name="enabled" label="启用" valuePropName="checked">
            <Switch />
          </Form.Item>

          <Form.Item name="remark" label="备注">
            <Input.TextArea rows={2} maxLength={512} />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
