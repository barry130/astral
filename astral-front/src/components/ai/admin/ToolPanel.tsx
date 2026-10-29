/**
 * 工具清单。
 *
 * 后台能改的只有「显示名 / 描述 / 风险等级 / 审批策略 / 启用状态」，
 * 真正的执行逻辑（handlerKey）必须由代码登记——否则等于开放任意命令执行。
 * 所以这里 handlerKey 只读，缺失 handler 的工具会标红并禁止启用。
 */

'use client';

import React, { useCallback, useEffect, useMemo, useState } from 'react';

import {
  Alert,
  Button,
  Empty,
  Form,
  Input,
  Modal,
  Select,
  Space,
  Spin,
  Tag,
  Tooltip,
  message,
} from '@/components/antd-compat';
import {
  CheckCircleOutlined,
  CloseCircleOutlined,
  DatabaseOutlined,
  EditOutlined,
  ReloadOutlined,
} from '@/components/antd-compat/icons';
import { ResizableTable } from '@/components/ResizableTable';

import { aiToolApi, type AiTool } from '@/api/ai';
import { usePerm } from '@/lib/perm';

import { BoolTag, Hint, ReadOnlyNote, StatusTag, formatTime, truncate, useAiDict } from './shared';

const RISK_ORDER: Record<string, number> = { HIGH: 3, DRAFT: 2, READ: 1 };

export default function ToolPanel() {
  const hasPerm = usePerm();
  const dict = useAiDict();
  const canEdit = hasPerm('admin:ai:tool:edit');
  const canApprove = hasPerm('admin:ai:tool:approve');

  const [tools, setTools] = useState<AiTool[]>([]);
  const [loading, setLoading] = useState(false);
  const [syncing, setSyncing] = useState(false);
  const [keyword, setKeyword] = useState('');
  const [riskFilter, setRiskFilter] = useState<string | undefined>();
  const [approvalFilter, setApprovalFilter] = useState<string | undefined>();
  const [onlyMissing, setOnlyMissing] = useState(false);

  const [modal, setModal] = useState<{ open: boolean; row?: AiTool }>({ open: false });
  const [saving, setSaving] = useState(false);
  const [form] = Form.useForm();

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const res = await aiToolApi.list();
      setTools(res.data ?? []);
    } catch (error) {
      message.error(error instanceof Error ? error.message : '工具加载失败');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const doSync = async () => {
    setSyncing(true);
    try {
      const res = await aiToolApi.sync();
      const changed = res.data?.changed;
      message.success(typeof changed === 'number' ? `已同步，变更 ${changed} 项` : '已同步');
      await load();
    } catch (error) {
      message.error(error instanceof Error ? error.message : '同步失败');
    } finally {
      setSyncing(false);
    }
  };

  const filtered = useMemo(() => {
    const key = keyword.trim().toLowerCase();
    return tools
      .filter((tool) => {
        if (riskFilter && tool.riskLevel !== riskFilter) return false;
        if (approvalFilter && tool.approvalPolicy !== approvalFilter) return false;
        if (onlyMissing && tool.handlerAvailable !== false) return false;
        if (!key) return true;
        return [tool.toolCode, tool.name, tool.handlerKey, tool.description]
          .filter(Boolean)
          .some((text) => String(text).toLowerCase().includes(key));
      })
      .slice()
      .sort((a, b) => (RISK_ORDER[b.riskLevel ?? ''] ?? 0) - (RISK_ORDER[a.riskLevel ?? ''] ?? 0));
  }, [tools, keyword, riskFilter, approvalFilter, onlyMissing]);

  const stats = useMemo(() => {
    return {
      total: tools.length,
      high: tools.filter((t) => t.riskLevel === 'HIGH').length,
      approve: tools.filter((t) => t.approvalPolicy === 'ADMIN_CONFIRM').length,
      missing: tools.filter((t) => t.handlerAvailable === false).length,
    };
  }, [tools]);

  const openModal = (row: AiTool) => {
    setModal({ open: true, row });
    form.resetFields();
    form.setFieldsValue({ ...row });
  };

  const submit = async () => {
    if (!modal.row?.id) return;
    try {
      const values = await form.validateFields();
      setSaving(true);
      await aiToolApi.update(modal.row.id, values);
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
    {
      key: 'name',
      title: '工具',
      dataIndex: 'name',
      width: 210,
      render: (_: unknown, row: AiTool) => (
        <Space size={2} direction="vertical">
          <span style={{ fontWeight: 500 }}>{row.name}</span>
          <code style={{ fontSize: 11.5, opacity: 0.55 }}>{row.toolCode}</code>
        </Space>
      ),
    },
    {
      key: 'handlerKey',
      title: '处理器',
      dataIndex: 'handlerKey',
      width: 190,
      render: (value: string, row: AiTool) =>
        row.handlerAvailable === false ? (
          <Tooltip title="代码里没有登记这个 handlerKey，工具无法执行。请检查后端是否已部署对应实现。">
            <span style={{ fontSize: 12, color: 'var(--destructive, #d4380d)' }}>
              <CloseCircleOutlined style={{ marginRight: 4 }} />
              {value}
            </span>
          </Tooltip>
        ) : (
          <span style={{ fontSize: 12, opacity: 0.7 }}>
            <CheckCircleOutlined style={{ marginRight: 4, opacity: 0.5 }} />
            {value}
          </span>
        ),
    },
    {
      key: 'risk',
      title: '风险',
      dataIndex: 'riskLevel',
      width: 90,
      render: (value: string) => <StatusTag dict={dict} code="ai_tool_risk_level" value={value} />,
    },
    {
      key: 'approval',
      title: '审批',
      dataIndex: 'approvalPolicy',
      width: 110,
      render: (value: string) => (
        <StatusTag dict={dict} code="ai_tool_approval_policy" value={value} />
      ),
    },
    {
      key: 'clients',
      title: '可用客户端',
      dataIndex: 'clientTypes',
      width: 170,
      render: (value: string[]) =>
        value && value.length > 0 ? (
          <Space size={4} wrap>
            {value.map((item) => (
              <Tag key={item} color="default">
                {dict.label('ai_client_type', item)}
              </Tag>
            ))}
          </Space>
        ) : (
          <span style={{ opacity: 0.5 }}>-</span>
        ),
    },
    {
      key: 'status',
      title: '启用',
      dataIndex: 'status',
      width: 80,
      render: (value: string) => <BoolTag value={value === 'ENABLED'} />,
    },
    {
      key: 'desc',
      title: '说明',
      dataIndex: 'description',
      ellipsis: true,
      render: (value: string) => (
        <span style={{ fontSize: 12, opacity: 0.7 }}>{truncate(value, 70)}</span>
      ),
    },
    {
      key: 'action',
      title: '操作',
      dataIndex: 'id',
      width: 70,
      render: (_: unknown, row: AiTool) => (
        <Button
          size="small"
          type="text"
          icon={<EditOutlined />}
          disabled={!canEdit}
          onClick={() => openModal(row)}
        />
      ),
    },
  ];

  return (
    <div>
      <Hint>
        工具是模型能调用的动作白名单。<strong>只能改元数据，不能新增工具</strong>——
        新增工具必须在新代码里实现 `AiToolProvider` 并部署，否则等于给模型开一个任意执行口子。
        标记为 HIGH 且审批为 ADMIN_CONFIRM
        的工具，即使智能体配置里写了允许，也要人工点通过才会执行。
      </Hint>

      <div
        style={{
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
          marginBottom: 10,
          gap: 8,
          flexWrap: 'wrap',
        }}
      >
        <Space size={8} wrap>
          <Tag color="default">共 {stats.total}</Tag>
          <Tag color={stats.high > 0 ? 'error' : 'default'}>高危 {stats.high}</Tag>
          <Tag color={stats.approve > 0 ? 'warning' : 'default'}>需审批 {stats.approve}</Tag>
          {stats.missing > 0 && (
            <Tag color="error" style={{ cursor: 'pointer' }}>
              <span onClick={() => setOnlyMissing(true)}>缺实现 {stats.missing}</span>
            </Tag>
          )}
          <ReadOnlyNote show={!canEdit} />
        </Space>
        <Space size={8} wrap>
          <Input
            size="small"
            allowClear
            placeholder="搜索工具名 / 编码 / 处理器"
            style={{ width: 220 }}
            value={keyword}
            onChange={(event) => setKeyword(event.target.value)}
          />
          <Select
            size="small"
            allowClear
            placeholder="风险"
            style={{ width: 100 }}
            value={riskFilter}
            onChange={(value) => setRiskFilter(value)}
            options={dict.options('ai_tool_risk_level')}
          />
          <Select
            size="small"
            allowClear
            placeholder="审批"
            style={{ width: 130 }}
            value={approvalFilter}
            onChange={(value) => setApprovalFilter(value)}
            options={dict.options('ai_tool_approval_policy')}
          />
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
            icon={<DatabaseOutlined />}
            loading={syncing}
            disabled={!canEdit}
            onClick={() => void doSync()}
          >
            从代码同步
          </Button>
        </Space>
      </div>

      {onlyMissing && (
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: 10 }}
          message="只看缺实现的工具"
          description="这些工具的 handlerKey 在当前后端里找不到对应实现，绑到智能体上会在校验时报错。"
          action={
            <Button size="small" type="text" onClick={() => setOnlyMissing(false)}>
              取消筛选
            </Button>
          }
        />
      )}

      {loading && tools.length === 0 ? (
        <div style={{ padding: 40, textAlign: 'center' }}>
          <Spin />
        </div>
      ) : filtered.length === 0 ? (
        <Empty
          description={
            tools.length === 0 ? '还没有登记任何工具，点「从代码同步」拉取' : '没有匹配的工具'
          }
        />
      ) : (
        <ResizableTable
          resizeKey="ai-tool"
          rowKey="id"
          columns={columns}
          dataSource={filtered}
          loading={loading}
          pagination={false}
        />
      )}

      <Modal
        title={modal.row ? `编辑工具 · ${modal.row.name}` : '编辑工具'}
        open={modal.open}
        onCancel={() => setModal({ open: false })}
        onOk={() => void submit()}
        confirmLoading={saving}
        okText="保存"
        cancelText="取消"
        width={680}
        destroyOnClose
      >
        {modal.row && (
          <Form form={form} layout="vertical">
            <Alert
              type="info"
              showIcon
              style={{ marginBottom: 12 }}
              message="只能改展示与策略"
              description={
                <span style={{ fontSize: 12.5 }}>
                  工具编码 <code>{modal.row.toolCode}</code> 与处理器{' '}
                  <code>{modal.row.handlerKey}</code> 由代码决定，
                  这里改不了。要新增工具，请在后端实现 <code>AiToolProvider</code>{' '}
                  后点「从代码同步」。
                </span>
              }
            />
            <Form.Item
              name="name"
              label="显示名"
              rules={[{ required: true, message: '请输入显示名' }]}
            >
              <Input maxLength={128} />
            </Form.Item>
            <Form.Item
              name="description"
              label="描述"
              extra="这段描述会进模型的工具说明，写清楚「什么时候该用」"
            >
              <Input.TextArea rows={3} maxLength={512} />
            </Form.Item>
            <Space size={12} style={{ display: 'flex' }}>
              <Form.Item
                name="riskLevel"
                label="风险等级"
                style={{ flex: 1 }}
                rules={[{ required: true }]}
              >
                <Select options={dict.options('ai_tool_risk_level')} />
              </Form.Item>
              <Form.Item
                name="approvalPolicy"
                label="审批策略"
                style={{ flex: 1 }}
                rules={[{ required: true }]}
              >
                <Select options={dict.options('ai_tool_approval_policy')} />
              </Form.Item>
              <Form.Item
                name="status"
                label="状态"
                style={{ flex: 1 }}
                rules={[{ required: true }]}
              >
                <Select
                  options={[
                    { label: '启用', value: 'ENABLED' },
                    { label: '停用', value: 'DISABLED' },
                  ]}
                />
              </Form.Item>
            </Space>
            <div style={{ fontSize: 12, opacity: 0.6 }}>
              最后更新：{formatTime(modal.row.updateTime)}
              {modal.row.sideEffects ? ` · 副作用：${modal.row.sideEffects}` : ''}
              {canApprove ? '' : ' · 当前账号无审批权限，不能处理待审批调用'}
            </div>
          </Form>
        )}
      </Modal>
    </div>
  );
}
