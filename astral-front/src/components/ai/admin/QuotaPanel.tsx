/**
 * 额度策略。
 *
 * 多个策略同时命中时按「更严的那条」生效（逐维度取最小值），
 * 所以这里不需要排序或优先级字段——加一条 USER 策略只会让它更紧，不会放宽全局。
 * 页面上必须把这条规则写明白，否则运营会以为新建策略能覆盖上限。
 */

'use client';

import React, { useCallback, useEffect, useMemo, useState } from 'react';

import {
  Alert,
  Button,
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
  message,
} from '@/components/antd-compat';
import {
  DeleteOutlined,
  EditOutlined,
  PlusOutlined,
  ReloadOutlined,
} from '@/components/antd-compat/icons';
import { ResizableTable } from '@/components/ResizableTable';

import { aiQuotaApi, type AiQuotaPolicy } from '@/api/ai';
import { usePerm } from '@/lib/perm';

import { BoolTag, Hint, ReadOnlyNote, formatNumber, useAiDict } from './shared';

/** 数值型额度字段：表单里统一按「留空 = 不限制」处理 */
const NUMBER_FIELDS: { name: keyof AiQuotaPolicy; label: string; hint?: string }[] = [
  { name: 'dailyRequests', label: '每日请求数' },
  { name: 'monthlyRequests', label: '每月请求数' },
  { name: 'dailyInputTokens', label: '每日输入 token' },
  { name: 'monthlyInputTokens', label: '每月输入 token' },
  { name: 'dailyOutputTokens', label: '每日输出 token' },
  { name: 'monthlyOutputTokens', label: '每月输出 token' },
  { name: 'maxInputChars', label: '单次输入字符上限' },
  { name: 'maxContextTokens', label: '上下文 token 上限' },
  { name: 'maxOutputTokens', label: '单次输出 token 上限' },
  { name: 'maxConcurrent', label: '并发请求数' },
];

export default function QuotaPanel() {
  const hasPerm = usePerm();
  const dict = useAiDict();
  const canEdit = hasPerm('admin:ai:quota:edit');

  const [policies, setPolicies] = useState<AiQuotaPolicy[]>([]);
  const [loading, setLoading] = useState(false);
  const [modal, setModal] = useState<{ open: boolean; row?: AiQuotaPolicy }>({ open: false });
  const [saving, setSaving] = useState(false);
  const [form] = Form.useForm();

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const res = await aiQuotaApi.list();
      setPolicies(res.data ?? []);
    } catch (error) {
      message.error(error instanceof Error ? error.message : '额度策略加载失败');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const openModal = (row?: AiQuotaPolicy) => {
    setModal({ open: true, row });
    form.resetFields();
    if (row) {
      form.setFieldsValue({ ...row });
    } else {
      form.setFieldsValue({
        scopeType: 'GLOBAL',
        enabled: true,
        maxInputChars: 20000,
        maxOutputTokens: 2048,
      });
    }
  };

  const submit = async () => {
    try {
      const values = await form.validateFields();
      setSaving(true);
      if (modal.row?.id) {
        await aiQuotaApi.update(modal.row.id, values);
      } else {
        await aiQuotaApi.create(values);
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

  const globalCount = useMemo(
    () => policies.filter((p) => p.scopeType === 'GLOBAL').length,
    [policies],
  );

  const columns = [
    {
      key: 'name',
      title: '策略',
      dataIndex: 'name',
      width: 190,
      render: (_: unknown, row: AiQuotaPolicy) => (
        <Space size={2} direction="vertical">
          <span style={{ fontWeight: 500 }}>{row.name}</span>
          <code style={{ fontSize: 11.5, opacity: 0.55 }}>{row.policyCode}</code>
        </Space>
      ),
    },
    {
      key: 'scope',
      title: '作用域',
      dataIndex: 'scopeType',
      width: 110,
      render: (value: string, row: AiQuotaPolicy) => (
        <Space size={2} direction="vertical">
          <StatusScope dict={dict} value={value} />
          <span style={{ fontSize: 11, opacity: 0.55 }}>
            {value === 'AGENT' && row.agentId ? `#${row.agentId}` : ''}
            {value === 'USER' && row.userId ? `#${row.userId}` : ''}
            {value === 'ROLE' && row.roleId ? `#${row.roleId}` : ''}
          </span>
        </Space>
      ),
    },
    {
      key: 'daily',
      title: '每日请求',
      dataIndex: 'dailyRequests',
      width: 100,
      align: 'right' as const,
      render: (value: number) => <Mono value={value} />,
    },
    {
      key: 'monthly',
      title: '每月请求',
      dataIndex: 'monthlyRequests',
      width: 100,
      align: 'right' as const,
      render: (value: number) => <Mono value={value} />,
    },
    {
      key: 'in',
      title: '每日输入 token',
      dataIndex: 'dailyInputTokens',
      width: 130,
      align: 'right' as const,
      render: (value: number) => <Mono value={value} />,
    },
    {
      key: 'out',
      title: '每日输出 token',
      dataIndex: 'dailyOutputTokens',
      width: 130,
      align: 'right' as const,
      render: (value: number) => <Mono value={value} />,
    },
    {
      key: 'concurrent',
      title: '并发',
      dataIndex: 'maxConcurrent',
      width: 80,
      align: 'right' as const,
      render: (value: number) => <Mono value={value} />,
    },
    {
      key: 'cost',
      title: '月度成本上限',
      dataIndex: 'monthlyCostLimit',
      width: 120,
      align: 'right' as const,
      render: (value: number) =>
        value ? <span style={{ fontSize: 12.5 }}>{formatNumber(value)}</span> : <Unlimited />,
    },
    {
      key: 'enabled',
      title: '启用',
      dataIndex: 'enabled',
      width: 70,
      render: (value: boolean) => <BoolTag value={value} />,
    },
    {
      key: 'action',
      title: '操作',
      dataIndex: 'id',
      width: 90,
      render: (_: unknown, row: AiQuotaPolicy) => (
        <Space size={2}>
          <Button
            size="small"
            type="text"
            icon={<EditOutlined />}
            disabled={!canEdit}
            onClick={() => openModal(row)}
          />
          <Popconfirm
            title="删除策略？"
            description="删除后不再受这条限制约束，可能放宽用量。"
            okButtonProps={{ danger: true }}
            disabled={!canEdit}
            onConfirm={async () => {
              if (!row.id) return;
              try {
                await aiQuotaApi.remove(row.id);
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
        多条策略同时命中时<strong>逐项取最严的值</strong>，不会被放宽——
        给某个用户单独加策略只能是更紧。留空或不填表示该维度不限制，但仍受「禁止超出代码侧硬上限」约束。
      </Hint>

      {globalCount === 0 && (
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: 10 }}
          message="还没有全局策略"
          description="建议至少建一条 GLOBAL 策略作为兜底，否则所有用户共用后端硬上限。"
        />
      )}

      <div
        style={{
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
          marginBottom: 10,
        }}
      >
        <Space size={8}>
          <Tag color="default">{policies.length} 条策略</Tag>
          <ReadOnlyNote show={!canEdit} />
        </Space>
        <Space size={8}>
          <Button
            size="small"
            icon={<ReloadOutlined />}
            onClick={() => void load()}
            loading={loading}
          >
            刷新
          </Button>
          <Button
            size="small"
            type="primary"
            icon={<PlusOutlined />}
            disabled={!canEdit}
            onClick={() => openModal()}
          >
            新建策略
          </Button>
        </Space>
      </div>

      {loading && policies.length === 0 ? (
        <div style={{ padding: 40, textAlign: 'center' }}>
          <Spin />
        </div>
      ) : policies.length === 0 ? (
        <Empty description="还没有额度策略。所有用户将共用代码侧硬上限。" />
      ) : (
        <ResizableTable
          resizeKey="ai-quota"
          rowKey="id"
          columns={columns}
          dataSource={policies}
          loading={loading}
          pagination={false}
        />
      )}

      <Modal
        title={modal.row ? '编辑额度策略' : '新建额度策略'}
        open={modal.open}
        onCancel={() => setModal({ open: false })}
        onOk={() => void submit()}
        confirmLoading={saving}
        okText="保存"
        cancelText="取消"
        width={760}
        destroyOnClose
        styles={{ body: { maxHeight: '64vh', overflowY: 'auto' } }}
      >
        <Form form={form} layout="vertical">
          <Space size={12} style={{ display: 'flex' }}>
            <Form.Item
              name="policyCode"
              label="编码"
              style={{ flex: 1 }}
              rules={[
                { required: true, message: '请输入编码' },
                { pattern: /^[a-zA-Z0-9_-]+$/, message: '只能是字母、数字、下划线、短横线' },
              ]}
            >
              <Input placeholder="例如 default_quota" disabled={Boolean(modal.row)} />
            </Form.Item>
            <Form.Item
              name="name"
              label="名称"
              style={{ flex: 1 }}
              rules={[{ required: true, message: '请输入名称' }]}
            >
              <Input placeholder="例如 默认额度" />
            </Form.Item>
          </Space>

          <Space size={12} style={{ display: 'flex' }}>
            <Form.Item
              name="scopeType"
              label="作用域"
              style={{ flex: 1 }}
              rules={[{ required: true }]}
            >
              <Select options={dict.options('ai_quota_scope_type')} />
            </Form.Item>
            <Form.Item
              name="agentId"
              label="智能体 ID"
              style={{ flex: 1 }}
              extra="作用域为 AGENT 时必填"
            >
              <InputNumber min={0} style={{ width: '100%' }} placeholder="智能体 ID" />
            </Form.Item>
            <Form.Item
              name="roleId"
              label="角色 ID"
              style={{ flex: 1 }}
              extra="作用域为 ROLE 时必填"
            >
              <InputNumber min={0} style={{ width: '100%' }} placeholder="角色 ID" />
            </Form.Item>
            <Form.Item
              name="userId"
              label="用户 ID"
              style={{ flex: 1 }}
              extra="作用域为 USER 时必填"
            >
              <InputNumber min={0} style={{ width: '100%' }} placeholder="用户 ID" />
            </Form.Item>
          </Space>

          <div
            style={{
              display: 'grid',
              gridTemplateColumns: 'repeat(3, minmax(0, 1fr))',
              gap: '0 12px',
            }}
          >
            {NUMBER_FIELDS.map((field) => (
              <Form.Item
                key={field.name}
                name={field.name as string}
                label={field.label}
                extra={field.hint}
              >
                <InputNumber min={0} style={{ width: '100%' }} placeholder="不限" />
              </Form.Item>
            ))}
            <Form.Item name="monthlyCostLimit" label="月度成本上限">
              <InputNumber min={0} step={1} style={{ width: '100%' }} placeholder="不限" />
            </Form.Item>
          </div>

          <Form.Item name="remark" label="备注">
            <Input.TextArea rows={2} maxLength={512} />
          </Form.Item>
          <Form.Item name="enabled" label="启用" valuePropName="checked">
            <Switch />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}

/** 作用域标签（独立写一个是为了不依赖字典 key 命名一致性） */
function StatusScope({ dict, value }: { dict: ReturnType<typeof useAiDict>; value?: string }) {
  const color =
    value === 'GLOBAL' ? 'blue' : value === 'AGENT' ? 'purple' : value === 'ROLE' ? 'cyan' : 'gold';
  return <Tag color={color}>{dict.label('ai_quota_scope_type', value)}</Tag>;
}

function Mono({ value }: { value?: number }) {
  if (value === undefined || value === null) return <Unlimited />;
  return <span style={{ fontSize: 12.5 }}>{formatNumber(value)}</span>;
}

function Unlimited() {
  return <span style={{ fontSize: 12, opacity: 0.4 }}>不限</span>;
}
