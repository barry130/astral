/**
 * 工具清单。
 *
 * 工具有两种形态：
 * 1. 代码工具（executorType=CODE）：执行逻辑由代码里的 `AiToolHandler` 实现，
 *    后台只能改「显示名 / 描述 / 风险等级 / 审批策略 / 启用状态」，handlerKey 只读。
 * 2. 声明式工具（executorType=HTTP / SQL）：执行配置存在 `ai_tool.executor_config`，
 *    后台可以新建与修改，但执行面固定收敛在 HttpToolExecutor / SqlToolExecutor，
 *    写方法与非只读 SQL 一律被强制为 HIGH + ADMIN_CONFIRM（服务端写死，不可放宽）。
 *
 * MCP 工具（executorType=MCP）由「MCP 服务器」页签刷新发现，这里只读。
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
  Radio,
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
  ExperimentOutlined,
  PlusOutlined,
  ReloadOutlined,
} from '@/components/antd-compat/icons';
import { ResizableTable } from '@/components/ResizableTable';

import {
  aiToolApi,
  type AiTool,
  type DeclarativeToolSaveReq,
  type ToolTestRunResult,
} from '@/api/ai';
import { usePerm } from '@/lib/perm';

import { BoolTag, Hint, ReadOnlyNote, StatusTag, formatTime, truncate, useAiDict } from './shared';

const RISK_ORDER: Record<string, number> = { HIGH: 3, DRAFT: 2, READ: 1 };

/** 可以由后台手工创建的声明式执行方式 */
const DECLARATIVE_TYPES = [
  { label: 'HTTP 外部接口', value: 'HTTP' },
  { label: 'SQL 只读查询', value: 'SQL' },
];

const METHODS = ['GET', 'POST', 'PUT', 'PATCH', 'DELETE'].map((value) => ({
  label: value,
  value,
}));

/** 表单里可编辑的声明式工具字段（executorConfig 由这些字段拼出来） */
interface DeclarativeFormValues {
  toolCode?: string;
  name?: string;
  description?: string;
  clientTypes?: string[];
  argumentsSchema?: string;
  riskLevel?: string;
  approvalPolicy?: string;
  method?: string;
  url?: string;
  headers?: string;
  query?: string;
  body?: string;
  timeoutMs?: number;
  summaryPath?: string;
  payloadPath?: string;
  sql?: string;
  columns?: string;
  maxRows?: number;
  timeoutSeconds?: number;
}

/** 把表单值拼成后端要求的 executor_config JSON */
function buildExecutorConfig(type: string, values: DeclarativeFormValues): string {
  if (type === 'SQL') {
    const columns = (values.columns ?? '')
      .split(/[,，\s]+/)
      .map((item) => item.trim())
      .filter(Boolean);
    return JSON.stringify({
      sql: (values.sql ?? '').trim(),
      columns,
      maxRows: values.maxRows ?? 50,
      timeoutSeconds: values.timeoutSeconds ?? 5,
    });
  }
  const config: Record<string, unknown> = {
    method: values.method ?? 'GET',
    url: (values.url ?? '').trim(),
  };
  const optionalText: [string, string | undefined][] = [
    ['summaryPath', values.summaryPath],
    ['payloadPath', values.payloadPath],
  ];
  for (const [key, value] of optionalText) {
    if (value && value.trim()) config[key] = value.trim();
  }
  const jsonFields: [string, string | undefined, string][] = [
    ['headers', values.headers, '请求头'],
    ['query', values.query, '查询参数'],
  ];
  for (const [key, raw, label] of jsonFields) {
    const text = (raw ?? '').trim();
    if (!text) continue;
    try {
      config[key] = JSON.parse(text);
    } catch {
      throw new Error(`${label}必须是合法的 JSON 对象`);
    }
  }
  const bodyText = (values.body ?? '').trim();
  if (bodyText) {
    try {
      config.body = JSON.parse(bodyText);
    } catch {
      // 非 JSON 的正文按纯文本模板处理，执行器支持字符串 body
      config.body = bodyText;
    }
  }
  config.timeoutMs = values.timeoutMs ?? 8000;
  return JSON.stringify(config);
}

/** 从已有工具回填表单值 */
function toFormValues(tool: AiTool, type: string): DeclarativeFormValues {
  const base: DeclarativeFormValues = {
    toolCode: tool.toolCode,
    name: tool.name,
    description: tool.description,
    clientTypes: tool.clientTypes,
    argumentsSchema: tool.argumentsSchema ?? '',
    riskLevel: tool.riskLevel,
    approvalPolicy: tool.approvalPolicy,
  };
  let config: Record<string, unknown> = {};
  if (tool.executorConfig) {
    try {
      config = JSON.parse(tool.executorConfig) as Record<string, unknown>;
    } catch {
      config = {};
    }
  }
  if (type === 'SQL') {
    const columns = Array.isArray(config.columns) ? (config.columns as string[]) : [];
    return {
      ...base,
      sql: typeof config.sql === 'string' ? config.sql : '',
      columns: columns.join(', '),
      maxRows: typeof config.maxRows === 'number' ? config.maxRows : 50,
      timeoutSeconds: typeof config.timeoutSeconds === 'number' ? config.timeoutSeconds : 5,
    };
  }
  const asText = (value: unknown) =>
    value === undefined || value === null ? '' : JSON.stringify(value, null, 2);
  return {
    ...base,
    method: typeof config.method === 'string' ? config.method : 'GET',
    url: typeof config.url === 'string' ? config.url : '',
    headers: asText(config.headers),
    query: asText(config.query),
    body: asText(config.body),
    timeoutMs: typeof config.timeoutMs === 'number' ? config.timeoutMs : 8000,
    summaryPath: typeof config.summaryPath === 'string' ? config.summaryPath : '',
    payloadPath: typeof config.payloadPath === 'string' ? config.payloadPath : '',
  };
}

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
  const [executorFilter, setExecutorFilter] = useState<string | undefined>();
  const [onlyMissing, setOnlyMissing] = useState(false);

  const [modal, setModal] = useState<{ open: boolean; row?: AiTool }>({ open: false });
  const [saving, setSaving] = useState(false);
  const [form] = Form.useForm();

  // 声明式工具的配置弹窗（新建 / 编辑共用）
  const [decl, setDecl] = useState<{ open: boolean; type: string; row?: AiTool }>({
    open: false,
    type: 'HTTP',
  });
  const [declSaving, setDeclSaving] = useState(false);
  const [declForm] = Form.useForm<DeclarativeFormValues>();

  // 试跑弹窗
  const [test, setTest] = useState<{ open: boolean; row?: AiTool }>({ open: false });
  const [testArgs, setTestArgs] = useState('{}');
  const [testResult, setTestResult] = useState<ToolTestRunResult | null>(null);
  const [testing, setTesting] = useState(false);

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
        if (executorFilter && (tool.executorType ?? 'CODE') !== executorFilter) return false;
        if (onlyMissing && tool.handlerAvailable !== false) return false;
        if (!key) return true;
        return [tool.toolCode, tool.name, tool.handlerKey, tool.description]
          .filter(Boolean)
          .some((text) => String(text).toLowerCase().includes(key));
      })
      .slice()
      .sort((a, b) => (RISK_ORDER[b.riskLevel ?? ''] ?? 0) - (RISK_ORDER[a.riskLevel ?? ''] ?? 0));
  }, [tools, keyword, riskFilter, approvalFilter, executorFilter, onlyMissing]);

  const stats = useMemo(() => {
    return {
      total: tools.length,
      high: tools.filter((t) => t.riskLevel === 'HIGH').length,
      approve: tools.filter((t) => t.approvalPolicy === 'ADMIN_CONFIRM').length,
      missing: tools.filter((t) => t.handlerAvailable === false).length,
      declarative: tools.filter((t) => (t.executorType ?? 'CODE') !== 'CODE').length,
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

  const openDeclarative = (type: string, row?: AiTool) => {
    setDecl({ open: true, type, row });
    declForm.resetFields();
    if (row) {
      declForm.setFieldsValue(toFormValues(row, type));
    } else {
      declForm.setFieldsValue({
        method: 'GET',
        timeoutMs: 8000,
        maxRows: 50,
        timeoutSeconds: 5,
        riskLevel: 'READ',
        approvalPolicy: 'NONE',
      });
    }
  };

  const submitDeclarative = async () => {
    const { type, row } = decl;
    try {
      const values = (await declForm.validateFields()) as DeclarativeFormValues;
      const payload: DeclarativeToolSaveReq = {
        toolCode: (values.toolCode ?? '').trim(),
        name: (values.name ?? '').trim(),
        description: values.description,
        clientTypes: values.clientTypes,
        argumentsSchema: values.argumentsSchema,
        executorConfig: buildExecutorConfig(type, values),
        riskLevel: values.riskLevel,
        approvalPolicy: values.approvalPolicy,
      };
      setDeclSaving(true);
      if (row?.id) {
        await aiToolApi.updateDeclarative(type, row.id, payload);
      } else {
        await aiToolApi.createDeclarative(type, payload);
      }
      message.success(row?.id ? '已保存' : '已创建，请到智能体版本里绑定后发布');
      setDecl({ open: false, type });
      await load();
    } catch (error) {
      if (error instanceof Error) message.error(error.message);
    } finally {
      setDeclSaving(false);
    }
  };

  const openTest = (row: AiTool) => {
    setTest({ open: true, row });
    setTestArgs('{}');
    setTestResult(null);
  };

  const runTest = async () => {
    if (!test.row?.id) return;
    setTesting(true);
    setTestResult(null);
    try {
      const res = await aiToolApi.testRun(test.row.id, testArgs.trim() || '{}');
      setTestResult(res.data ?? null);
    } catch (error) {
      message.error(error instanceof Error ? error.message : '试跑失败');
    } finally {
      setTesting(false);
    }
  };

  const columns = [
    {
      key: 'name',
      title: '工具',
      dataIndex: 'name',
      width: 230,
      render: (_: unknown, row: AiTool) => (
        <Space size={2} direction="vertical">
          <span style={{ fontWeight: 500 }}>{row.name}</span>
          <code style={{ fontSize: 11.5, opacity: 0.55 }}>{row.toolCode}</code>
        </Space>
      ),
    },
    {
      key: 'executor',
      title: '执行方式',
      dataIndex: 'executorType',
      width: 110,
      render: (value: string) => (
        <StatusTag dict={dict} code="ai_tool_executor_type" value={value || 'CODE'} />
      ),
    },
    {
      key: 'handlerKey',
      title: '处理器',
      dataIndex: 'handlerKey',
      width: 170,
      render: (value: string, row: AiTool) =>
        row.handlerAvailable === false ? (
          <Tooltip title="当前后端没有登记这个 handlerKey（或声明式配置无法解析），工具无法执行。请检查代码部署或执行配置。">
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
      width: 160,
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
        <span style={{ fontSize: 12, opacity: 0.7 }}>{truncate(value, 60)}</span>
      ),
    },
    {
      key: 'action',
      title: '操作',
      dataIndex: 'id',
      width: 110,
      render: (_: unknown, row: AiTool) => {
        const type = row.executorType ?? 'CODE';
        const declarative = type === 'HTTP' || type === 'SQL';
        return (
          <Space size={0}>
            <Tooltip title={declarative ? '编辑执行配置' : '编辑展示与策略'}>
              <Button
                size="small"
                type="text"
                icon={<EditOutlined />}
                disabled={!canEdit || type === 'MCP'}
                onClick={() => (declarative ? openDeclarative(type, row) : openModal(row))}
              />
            </Tooltip>
            <Tooltip title="用一份参数 JSON 试跑（不写审计表）">
              <Button
                size="small"
                type="text"
                icon={<ExperimentOutlined />}
                disabled={!canEdit || row.handlerAvailable === false}
                onClick={() => openTest(row)}
              />
            </Tooltip>
          </Space>
        );
      },
    },
  ];

  return (
    <div>
      <Hint>
        工具是模型能调用的动作白名单。代码工具（<code>CODE</code>）的执行逻辑必须在新代码里实现{' '}
        <code>AiToolHandler</code> 并部署；声明式工具（<code>HTTP</code> / <code>SQL</code>）可以在
        这里直接配，但执行面固定收敛在执行器里：
        <strong>HTTP 的非 GET 方法</strong>与<strong>SQL 查询</strong>
        之外的动作做不了，写方法一律被强制为 HIGH + 管理员确认。
        <code>MCP</code> 工具由「MCP 服务器」页签刷新发现，这里只读。
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
          <Tag color="default">声明式 {stats.declarative}</Tag>
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
            style={{ width: 200 }}
            value={keyword}
            onChange={(event) => setKeyword(event.target.value)}
          />
          <Select
            size="small"
            allowClear
            placeholder="执行方式"
            style={{ width: 120 }}
            value={executorFilter}
            onChange={(value) => setExecutorFilter(value)}
            options={dict.options('ai_tool_executor_type')}
          />
          <Select
            size="small"
            allowClear
            placeholder="风险"
            style={{ width: 90 }}
            value={riskFilter}
            onChange={(value) => setRiskFilter(value)}
            options={dict.options('ai_tool_risk_level')}
          />
          <Select
            size="small"
            allowClear
            placeholder="审批"
            style={{ width: 110 }}
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
          <Button
            size="small"
            type="primary"
            icon={<PlusOutlined />}
            disabled={!canEdit}
            onClick={() => openDeclarative('HTTP')}
          >
            新建声明式工具
          </Button>
        </Space>
      </div>

      {onlyMissing && (
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: 10 }}
          message="只看缺实现的工具"
          description="这些工具的 handlerKey 在当前后端里找不到对应实现（或声明式配置无法解析），绑到智能体上会在校验时报错。"
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

      {/* 代码工具：只能改元数据 */}
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

      {/* 声明式工具：新建 / 编辑 */}
      <Modal
        title={
          decl.row
            ? `编辑声明式工具 · ${decl.row.name}`
            : `新建声明式工具 · ${decl.type === 'SQL' ? 'SQL 只读查询' : 'HTTP 外部接口'}`
        }
        open={decl.open}
        onCancel={() => setDecl({ ...decl, open: false })}
        onOk={() => void submitDeclarative()}
        confirmLoading={declSaving}
        okText="保存"
        cancelText="取消"
        width={760}
        destroyOnClose
      >
        <Form form={declForm} layout="vertical">
          <Alert
            type="info"
            showIcon
            style={{ marginBottom: 12 }}
            message="执行面固定在执行器里，配置改不了安全边界"
            description={
              <span style={{ fontSize: 12.5 }}>
                私网 / 环回 / 链路本地 / 云元数据地址始终禁止访问；HTTP 非 GET
                方法、以及需要人工确认的 调用会被强制成 <code>HIGH</code>
                {' + '}
                <code>ADMIN_CONFIRM</code>
                {'\uFF1BSQL \u53EA\u5141\u8BB8\u5355\u6761'} <code>SELECT</code>
                {' / '}
                <code>WITH … SELECT</code>，连接以只读事务执行。
              </span>
            }
          />
          <Space size={12} style={{ display: 'flex' }} align="start">
            <Form.Item
              name="toolCode"
              label="工具编码"
              style={{ flex: 1 }}
              extra="小写字母开头的 snake_case，创建后不可改"
              rules={[
                { required: true, message: '请输入工具编码' },
                {
                  pattern: /^[a-z][a-z0-9_]{2,63}$/,
                  message: '只能是小写字母开头的 snake_case（3-64 位）',
                },
              ]}
            >
              <Input maxLength={64} disabled={Boolean(decl.row)} />
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
            name="description"
            label="描述"
            extra="会进模型的工具说明，写清楚「什么时候该用、参数怎么填」"
          >
            <Input.TextArea rows={2} maxLength={512} />
          </Form.Item>

          {!decl.row && (
            <Form.Item label="执行方式" extra="创建后不可切换，需要换方式请新建一个工具">
              <Radio.Group
                value={decl.type}
                optionType="button"
                options={DECLARATIVE_TYPES}
                onChange={(event) => setDecl({ ...decl, type: event.target.value })}
              />
            </Form.Item>
          )}

          {decl.type === 'SQL' ? (
            <>
              <Form.Item
                name="sql"
                label="查询语句"
                extra="只允许单条 SELECT / WITH … SELECT；参数用 #{name} 占位，按名字绑定，不要拼字符串"
                rules={[{ required: true, message: '请输入查询语句' }]}
              >
                <Input.TextArea
                  rows={5}
                  placeholder="select id, nickname as name from astral.sys_user where id = #{userId}"
                />
              </Form.Item>
              <Space size={12} style={{ display: 'flex' }} align="start">
                <Form.Item
                  name="columns"
                  label="回传列"
                  style={{ flex: 2 }}
                  extra="逗号分隔；只有这里列出的列会回给模型"
                  rules={[{ required: true, message: '请声明要回传的列' }]}
                >
                  <Input placeholder="id, name" />
                </Form.Item>
                <Form.Item name="maxRows" label="最多行数" style={{ flex: 1 }}>
                  <InputNumber min={1} max={200} style={{ width: '100%' }} />
                </Form.Item>
                <Form.Item name="timeoutSeconds" label="超时（秒）" style={{ flex: 1 }}>
                  <InputNumber min={1} max={15} style={{ width: '100%' }} />
                </Form.Item>
              </Space>
            </>
          ) : (
            <>
              <Space size={12} style={{ display: 'flex' }} align="start">
                <Form.Item name="method" label="方法" style={{ width: 120 }}>
                  <Select options={METHODS} />
                </Form.Item>
                <Form.Item
                  name="url"
                  label="地址"
                  style={{ flex: 1 }}
                  extra="必须是 http(s) 绝对地址；主机名写死，模板只能出现在路径/查询/请求头/正文里"
                  rules={[
                    { required: true, message: '请输入地址' },
                    { pattern: /^https?:\/\/[^\s]+$/, message: '必须是 http(s) 绝对地址' },
                  ]}
                >
                  <Input placeholder="https://api.example.com/v1/orders/${orderId}" />
                </Form.Item>
                <Form.Item name="timeoutMs" label="超时（毫秒）" style={{ width: 140 }}>
                  <InputNumber min={1000} max={30000} step={500} style={{ width: '100%' }} />
                </Form.Item>
              </Space>
              <Space size={12} style={{ display: 'flex' }} align="start">
                <Form.Item
                  name="summaryPath"
                  label="摘要取值路径"
                  style={{ flex: 1 }}
                  extra="可选，如 data.total"
                >
                  <Input placeholder="data.summary" />
                </Form.Item>
                <Form.Item
                  name="payloadPath"
                  label="载荷取值路径"
                  style={{ flex: 1 }}
                  extra="可选，从响应里截取给模型的部分"
                >
                  <Input placeholder="data.items" />
                </Form.Item>
              </Space>
              <Form.Item
                name="headers"
                label="请求头（JSON）"
                extra={'值可写 ${secret:KEY} 引用服务端环境变量'}
              >
                <Input.TextArea
                  rows={2}
                  placeholder={'{"Authorization": "Bearer ${secret:ERP_TOKEN}"}'}
                />
              </Form.Item>
              <Form.Item
                name="query"
                label="查询参数（JSON）"
                extra="值里的 ${name} 会按工具参数替换并编码"
              >
                <Input.TextArea
                  rows={2}
                  placeholder={'{"page": "${page}", "keyword": "${keyword}"}'}
                />
              </Form.Item>
              <Form.Item name="body" label="请求正文（JSON 或模板文本）" extra="GET 不能带正文">
                <Input.TextArea rows={3} placeholder={'{"userId": "${userId}"}'} />
              </Form.Item>
            </>
          )}

          <Form.Item
            name="clientTypes"
            label="可用客户端"
            extra="留空表示不限制；只有这里勾选的客户端能调用这个工具"
          >
            <Select
              mode="multiple"
              allowClear
              placeholder="不限制"
              options={dict.options('ai_client_type')}
            />
          </Form.Item>
          <Form.Item
            name="argumentsSchema"
            label="参数结构（JSON Schema）"
            extra="模板里用到的 ${name} / #{name} 必须在这里声明，否则保存会被拒绝"
          >
            <Input.TextArea
              rows={4}
              placeholder={
                '{"type":"object","properties":{"orderId":{"type":"string"}},"required":["orderId"]}'
              }
            />
          </Form.Item>
          <Space size={12} style={{ display: 'flex' }}>
            <Form.Item
              name="riskLevel"
              label="风险等级"
              style={{ flex: 1 }}
              extra="写方法会被强制成 HIGH"
            >
              <Select options={dict.options('ai_tool_risk_level')} />
            </Form.Item>
            <Form.Item
              name="approvalPolicy"
              label="审批策略"
              style={{ flex: 1 }}
              extra="写方法会被强制成管理员确认"
            >
              <Select options={dict.options('ai_tool_approval_policy')} />
            </Form.Item>
          </Space>
          {decl.row && (
            <div style={{ fontSize: 12, opacity: 0.6 }}>
              最后更新：{formatTime(decl.row.updateTime)}
              {decl.row.sideEffects ? ` · 副作用：${decl.row.sideEffects}` : ''}
            </div>
          )}
        </Form>
      </Modal>

      {/* 试跑 */}
      <Modal
        title={test.row ? `试跑工具 · ${test.row.name}` : '试跑工具'}
        open={test.open}
        onCancel={() => setTest({ open: false })}
        onOk={() => void runTest()}
        confirmLoading={testing}
        okText="执行一次"
        cancelText="关闭"
        width={640}
        destroyOnClose
      >
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: 12 }}
          message="这是真实调用，不是预演"
          description="HTTP 工具会真的请求外部接口，SQL 工具会真的查库（只读事务）。试跑不写工具调用审计表，但会留下后台操作日志。"
        />
        <Form layout="vertical">
          <Form.Item label="参数（JSON）" extra="按工具的 arguments_schema 填，留空按 {} 执行">
            <Input.TextArea
              rows={5}
              value={testArgs}
              onChange={(event) => setTestArgs(event.target.value)}
            />
          </Form.Item>
        </Form>
        {testResult && (
          <div style={{ marginTop: 8 }}>
            <Space size={8} wrap style={{ marginBottom: 6 }}>
              <Tag color={testResult.status === 'SUCCEEDED' ? 'success' : 'error'}>
                {testResult.status ?? 'UNKNOWN'}
              </Tag>
              <span style={{ fontSize: 12, opacity: 0.6 }}>
                耗时 {testResult.elapsedMs ?? 0} ms
              </span>
            </Space>
            <div style={{ fontSize: 12.5, marginBottom: 6 }}>{testResult.summary}</div>
            <pre
              style={{
                margin: 0,
                padding: 10,
                maxHeight: 240,
                overflow: 'auto',
                fontSize: 12,
                background: 'var(--muted, #f6f7f9)',
                borderRadius: 8,
              }}
            >
              {JSON.stringify(testResult.payload ?? {}, null, 2)}
            </pre>
          </div>
        )}
      </Modal>
    </div>
  );
}
