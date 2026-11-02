/**
 * MCP 服务器。
 *
 * 一台 MCP 服务器（Streamable HTTP 传输）通过「刷新」把远端 `tools/list` 的结果同步进
 * `ai_tool`：远端新增的工具会自动建行，远端消失的工具置为停用（不物理删，审计链不断）。
 *
 * 安全边界（服务端写死，这里只是提示）：
 * - 地址必须是 http(s) 公网地址，私网/环回/链路本地/云元数据地址一律拒绝；
 * - 服务器标记只读时，发现的工具默认 READ + NONE；只要远端没有明确 readOnlyHint=true，
 *   或服务器本身不是只读，工具一律 HIGH + ADMIN_CONFIRM。
 */

'use client';

import React, { useCallback, useEffect, useState } from 'react';

import {
  Alert,
  Button,
  Empty,
  Form,
  Input,
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
  DeleteOutlined,
  EditOutlined,
  PlusOutlined,
  ReloadOutlined,
} from '@/components/antd-compat/icons';
import { ResizableTable } from '@/components/ResizableTable';

import { aiMcpApi, type AiMcpServer, type McpServerSaveReq } from '@/api/ai';
import { usePerm } from '@/lib/perm';

import { Hint, ReadOnlyNote, StatusTag, formatTime, truncate, useAiDict } from './shared';

interface McpFormValues {
  serverCode?: string;
  name?: string;
  url?: string;
  headers?: string;
  readOnly?: boolean;
  status?: string;
}

export default function McpServerPanel() {
  const hasPerm = usePerm();
  const dict = useAiDict();
  const canEdit = hasPerm('admin:ai:mcp:edit');

  const [servers, setServers] = useState<AiMcpServer[]>([]);
  const [loading, setLoading] = useState(false);
  const [refreshingId, setRefreshingId] = useState<number | null>(null);

  const [modal, setModal] = useState<{ open: boolean; row?: AiMcpServer }>({ open: false });
  const [saving, setSaving] = useState(false);
  const [form] = Form.useForm();

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const res = await aiMcpApi.list();
      setServers(res.data ?? []);
    } catch (error) {
      message.error(error instanceof Error ? error.message : 'MCP 服务器加载失败');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const openModal = (row?: AiMcpServer) => {
    setModal({ open: true, row });
    form.resetFields();
    if (row) {
      form.setFieldsValue({
        serverCode: row.serverCode,
        name: row.name,
        url: row.url,
        headers: row.headers ?? '',
        readOnly: row.readOnly !== false,
        status: row.status ?? 'ENABLED',
      });
    } else {
      form.setFieldsValue({ readOnly: true, status: 'ENABLED' });
    }
  };

  const submit = async () => {
    try {
      const values = (await form.validateFields()) as McpFormValues;
      const payload: McpServerSaveReq = {
        serverCode: values.serverCode?.trim(),
        name: (values.name ?? '').trim(),
        url: (values.url ?? '').trim(),
        headers: values.headers?.trim() || undefined,
        readOnly: values.readOnly !== false,
        status: values.status ?? 'ENABLED',
      };
      setSaving(true);
      if (modal.row?.id) {
        await aiMcpApi.update(modal.row.id, payload);
      } else {
        await aiMcpApi.create(payload);
      }
      message.success(modal.row?.id ? '已保存' : '已创建，点「刷新工具」拉取远端工具清单');
      setModal({ open: false });
      await load();
    } catch (error) {
      if (error instanceof Error) message.error(error.message);
    } finally {
      setSaving(false);
    }
  };

  const doRefresh = async (row: AiMcpServer) => {
    if (!row.id) return;
    setRefreshingId(row.id);
    try {
      await aiMcpApi.refresh(row.id);
      message.success('已刷新，工具清单已同步到「工具」页签');
      await load();
    } catch (error) {
      message.error(error instanceof Error ? error.message : '刷新失败');
      await load();
    } finally {
      setRefreshingId(null);
    }
  };

  const doRemove = async (row: AiMcpServer) => {
    if (!row.id) return;
    try {
      await aiMcpApi.remove(row.id);
      message.success('已删除，其下工具已停用');
      await load();
    } catch (error) {
      message.error(error instanceof Error ? error.message : '删除失败');
    }
  };

  const columns = [
    {
      key: 'name',
      title: '服务器',
      dataIndex: 'name',
      width: 200,
      render: (_: unknown, row: AiMcpServer) => (
        <Space size={2} direction="vertical">
          <span style={{ fontWeight: 500 }}>{row.name}</span>
          <code style={{ fontSize: 11.5, opacity: 0.55 }}>{row.serverCode}</code>
        </Space>
      ),
    },
    {
      key: 'url',
      title: '端点',
      dataIndex: 'url',
      ellipsis: true,
      render: (value: string) => <code style={{ fontSize: 12 }}>{truncate(value, 56)}</code>,
    },
    {
      key: 'readOnly',
      title: '只读',
      dataIndex: 'readOnly',
      width: 90,
      render: (value: boolean) =>
        value === false ? (
          <Tooltip title="非只读服务器发现的工具一律 HIGH + 管理员确认">
            <Tag color="warning">否</Tag>
          </Tooltip>
        ) : (
          <Tag color="success">是</Tag>
        ),
    },
    {
      key: 'status',
      title: '状态',
      dataIndex: 'status',
      width: 90,
      render: (value: string) => <StatusTag dict={dict} code="ai_mcp_server_status" value={value} />,
    },
    {
      key: 'tools',
      title: '工具',
      dataIndex: 'toolCount',
      width: 110,
      render: (_: unknown, row: AiMcpServer) => (
        <span style={{ fontSize: 12 }}>
          {row.toolCountEnabled ?? 0} / {row.toolCount ?? 0}
          <span style={{ opacity: 0.5 }}> 启用/共</span>
        </span>
      ),
    },
    {
      key: 'refresh',
      title: '最近刷新',
      dataIndex: 'lastRefreshTime',
      width: 160,
      render: (value: string, row: AiMcpServer) =>
        value ? (
          <span style={{ fontSize: 12, opacity: 0.75 }}>{formatTime(value)}</span>
        ) : row.lastError ? (
          <Tooltip title={row.lastError}>
            <span style={{ fontSize: 12, color: 'var(--destructive, #d4380d)' }}>
              失败：{truncate(row.lastError, 24)}
            </span>
          </Tooltip>
        ) : (
          <span style={{ opacity: 0.5 }}>未刷新</span>
        ),
    },
    {
      key: 'action',
      title: '操作',
      dataIndex: 'id',
      width: 130,
      render: (_: unknown, row: AiMcpServer) => (
        <Space size={0}>
          <Tooltip title="刷新远端工具清单">
            <Button
              size="small"
              type="text"
              icon={<ReloadOutlined />}
              disabled={!canEdit || row.status === 'DISABLED'}
              loading={refreshingId === row.id}
              onClick={() => void doRefresh(row)}
            />
          </Tooltip>
          <Tooltip title="编辑">
            <Button
              size="small"
              type="text"
              icon={<EditOutlined />}
              disabled={!canEdit}
              onClick={() => openModal(row)}
            />
          </Tooltip>
          <Popconfirm
            title="删除后其下工具会被停用，确定？"
            okText="删除"
            cancelText="取消"
            onConfirm={() => void doRemove(row)}
            disabled={!canEdit}
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
        MCP 服务器是远端提供的工具集合（Streamable HTTP 传输）。填好地址后点
        「刷新工具」，远端 <code>tools/list</code> 的结果会写进「工具」页签，随后就能在智能体版本里绑定。
        <strong>工具不会凭空可用</strong>：远端删掉的工具会被置为停用，需要人工确认风险与确认策略。
        私网地址（含 <code>127.0.0.1</code>、<code>169.254.169.254</code>）一律拒绝。
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
          <Tag color="default">共 {servers.length} 台</Tag>
          <Tag color="default">
            启用 {servers.filter((item) => item.status === 'ENABLED').length}
          </Tag>
          <ReadOnlyNote show={!canEdit} />
        </Space>
        <Space size={8} wrap>
          <Button size="small" icon={<ReloadOutlined />} loading={loading} onClick={() => void load()}>
            刷新
          </Button>
          <Button
            size="small"
            type="primary"
            icon={<PlusOutlined />}
            disabled={!canEdit}
            onClick={() => openModal()}
          >
            新建 MCP 服务器
          </Button>
        </Space>
      </div>

      {loading && servers.length === 0 ? (
        <div style={{ padding: 40, textAlign: 'center' }}>
          <Spin />
        </div>
      ) : servers.length === 0 ? (
        <Empty description="还没有配置 MCP 服务器" />
      ) : (
        <ResizableTable
          resizeKey="ai-mcp-server"
          rowKey="id"
          columns={columns}
          dataSource={servers}
          loading={loading}
          pagination={false}
        />
      )}

      <Modal
        title={modal.row ? `编辑 MCP 服务器 · ${modal.row.name}` : '新建 MCP 服务器'}
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
          <Alert
            type="info"
            showIcon
            style={{ marginBottom: 12 }}
            message="只有公网 http(s) 地址可用"
            description={
              <span style={{ fontSize: 12.5 }}>
                保存时会先握手一次（<code>initialize</code> + <code>notifications/initialized</code>），
                握手失败不会落库。工具清单在「刷新工具」时拉取，不在保存时拉取。
              </span>
            }
          />
          <Space size={12} style={{ display: 'flex' }}>
            <Form.Item
              name="serverCode"
              label="服务器编码"
              style={{ flex: 1 }}
              extra="小写 snake_case，创建后不可改；工具编码会以它为前缀"
              rules={[
                { required: true, message: '请输入服务器编码' },
                {
                  pattern: /^[a-z][a-z0-9_]{2,63}$/,
                  message: '只能是小写字母开头的 snake_case（3-64 位）',
                },
              ]}
            >
              <Input maxLength={64} disabled={Boolean(modal.row)} />
            </Form.Item>
            <Form.Item
              name="name"
              label="显示名"
              style={{ flex: 1 }}
              rules={[{ required: true, message: '请输入显示名' }]}
            >
              <Input maxLength={128} />
            </Form.Item>
          </Space>
          <Form.Item
            name="url"
            label="MCP 端点"
            extra="Streamable HTTP 地址，例如 https://mcp.example.com/mcp"
            rules={[
              { required: true, message: '请输入端点地址' },
              { pattern: /^https?:\/\/[^\s]+$/, message: '必须是 http(s) 绝对地址' },
            ]}
          >
            <Input placeholder="https://mcp.example.com/mcp" />
          </Form.Item>
          <Form.Item
            name="headers"
            label="附加请求头（JSON）"
            extra={'凭据不要写明文，用 ${secret:KEY} 引用服务端环境变量'}
          >
            <Input.TextArea rows={2} placeholder={'{"Authorization": "Bearer ${secret:MCP_TOKEN}"}'} />
          </Form.Item>
          <Space size={24} style={{ display: 'flex' }} align="start">
            <Form.Item
              name="readOnly"
              label="服务器只读"
              valuePropName="checked"
              extra="只读服务器发现的工具默认 READ，否则一律 HIGH + 管理员确认"
            >
              <Switch />
            </Form.Item>
            <Form.Item name="status" label="状态" style={{ width: 160 }}>
              <Select
                options={[
                  { label: '启用', value: 'ENABLED' },
                  { label: '停用', value: 'DISABLED' },
                ]}
              />
            </Form.Item>
          </Space>
          {modal.row && (
            <div style={{ fontSize: 12, opacity: 0.6 }}>
              最近刷新：{formatTime(modal.row.lastRefreshTime)}
              {modal.row.lastError ? ` · 最近错误：${modal.row.lastError}` : ''}
            </div>
          )}
        </Form>
      </Modal>
    </div>
  );
}
