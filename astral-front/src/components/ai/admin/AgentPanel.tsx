/**
 * 智能体与版本。
 *
 * 这是整个后台最需要小心的地方，所以把「版本」摆在一等公民的位置上：
 * 智能体只是身份与入口，真正决定行为的是某个已发布版本的不可变快照。
 * 左边选智能体、右边看版本，编辑只发生在 DRAFT 版本上。
 */

'use client';

import React, { useCallback, useEffect, useMemo, useState } from 'react';

import {
  Alert,
  Button,
  Card,
  Descriptions,
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
  Tabs,
  Tag,
  Tooltip,
  message,
} from '@/components/antd-compat';
import {
  CheckCircleOutlined,
  DeleteOutlined,
  EditOutlined,
  PlusOutlined,
  ReloadOutlined,
  RollbackOutlined,
  SaveOutlined,
  SendOutlined,
} from '@/components/antd-compat/icons';
import { ResizableTable } from '@/components/ResizableTable';

import {
  aiAgentApi,
  aiKnowledgeApi,
  aiPromptApi,
  aiRouteApi,
  aiToolApi,
  type AiAgent,
  type AiAgentKnowledge,
  type AiAgentVersion,
  type AiKnowledgeSource,
  type AiPromptTemplate,
  type AiRoute,
  type AiTool,
  type ValidationResult,
} from '@/api/ai';
import { usePerm } from '@/lib/perm';

import {
  Hint,
  JsonBlock,
  ReadOnlyNote,
  StatusTag,
  formatTime,
  jsonObjectRule,
  useAiDict,
} from './shared';

/* ==================== 版本表单的默认策略 ==================== */

const DEFAULT_CONTEXT_POLICY = JSON.stringify(
  { keys: ['pageContext', 'selectedRecord'], maxAgeSeconds: 600 },
  null,
  2,
);
const DEFAULT_TOOL_POLICY = JSON.stringify(
  { mode: 'whitelist', maxRounds: 3, highRiskExecute: false },
  null,
  2,
);
const DEFAULT_KNOWLEDGE_POLICY = JSON.stringify(
  { topK: 5, citationRequired: true, allowEmptyAnswer: true },
  null,
  2,
);
const DEFAULT_MODEL_PARAMS = JSON.stringify({ temperature: 0.3, topP: 0.9 }, null, 2);
const DEFAULT_SAFETY_POLICY = JSON.stringify(
  { promptInjectionGuard: true, maskSecrets: true, denyArbitrarySql: true },
  null,
  2,
);

export default function AgentPanel() {
  const hasPerm = usePerm();
  const dict = useAiDict();
  const canEdit = hasPerm('admin:ai:agent:edit');
  const canPublish = hasPerm('admin:ai:agent:publish');

  const [agents, setAgents] = useState<AiAgent[]>([]);
  const [loading, setLoading] = useState(false);
  const [selectedId, setSelectedId] = useState<number | undefined>();

  const [agentModal, setAgentModal] = useState<{ open: boolean; row?: AiAgent }>({ open: false });
  const [agentForm] = Form.useForm();
  const [saving, setSaving] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const res = await aiAgentApi.list();
      const list = res.data ?? [];
      setAgents(list);
      setSelectedId((prev) => (prev && list.some((i) => i.id === prev) ? prev : list[0]?.id));
    } catch (error) {
      message.error(error instanceof Error ? error.message : '智能体加载失败');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const selected = useMemo(
    () => agents.find((item) => item.id === selectedId),
    [agents, selectedId],
  );

  const openAgentModal = (row?: AiAgent) => {
    setAgentModal({ open: true, row });
    agentForm.resetFields();
    if (row) {
      agentForm.setFieldsValue({
        agentCode: row.agentCode,
        name: row.name,
        agentType: row.agentType,
        description: row.description,
        icon: row.icon,
        clientTypes: row.clientTypes ?? [],
        entryConfig: row.entryConfig ?? '',
        permissionCode: row.permissionCode ?? '',
        clientPermissionCodes: row.clientPermissionCodes ?? '',
        quotaPolicyId: row.quotaPolicyId ?? undefined,
        allowUserContext: row.allowUserContext ?? true,
        allowFileContext: row.allowFileContext ?? false,
        conversationEnabled: row.conversationEnabled ?? true,
        feedbackEnabled: row.feedbackEnabled ?? true,
        sort: row.sort ?? 100,
      });
    } else {
      agentForm.setFieldsValue({
        agentType: 'CUSTOM',
        clientTypes: ['ASTRAL_FRONT'],
        allowUserContext: true,
        allowFileContext: false,
        conversationEnabled: true,
        feedbackEnabled: true,
        sort: 100,
      });
    }
  };

  const submitAgent = async () => {
    try {
      const values = await agentForm.validateFields();
      setSaving(true);
      if (agentModal.row?.id) {
        await aiAgentApi.update(agentModal.row.id, values);
      } else {
        await aiAgentApi.create(values);
      }
      message.success('已保存');
      setAgentModal({ open: false });
      await load();
    } catch (error) {
      if (error instanceof Error) message.error(error.message);
    } finally {
      setSaving(false);
    }
  };

  const agentColumns = [
    {
      key: 'name',
      title: '智能体',
      dataIndex: 'name',
      width: 200,
      render: (_: unknown, row: AiAgent) => (
        <Space size={6} direction="vertical">
          <span style={{ fontWeight: 500 }}>{row.name}</span>
          <code style={{ fontSize: 11.5, opacity: 0.55 }}>{row.agentCode}</code>
        </Space>
      ),
    },
    {
      key: 'agentType',
      title: '类型',
      dataIndex: 'agentType',
      width: 110,
      render: (value: string) => <Tag color="default">{dict.label('ai_agent_type', value)}</Tag>,
    },
    {
      key: 'status',
      title: '状态',
      dataIndex: 'status',
      width: 100,
      render: (value: string) => <StatusTag dict={dict} code="ai_agent_status" value={value} />,
    },
    {
      key: 'clients',
      title: '客户端',
      dataIndex: 'clientTypes',
      width: 190,
      render: (value: string[]) => (
        <Space size={3} wrap>
          {(value ?? []).map((item) => (
            <Tag key={item} color="default">
              {dict.label('ai_client_type', item)}
            </Tag>
          ))}
        </Space>
      ),
    },
    {
      key: 'version',
      title: '版本',
      dataIndex: 'publishedVersionNo',
      width: 130,
      render: (value: number, row: AiAgent) => (
        <Space size={4} direction="vertical">
          <span style={{ fontSize: 12.5 }}>{value ? `已发布 v${value}` : '未发布'}</span>
          {row.draftVersionCount ? (
            <Tag color="warning">{row.draftVersionCount} 个草稿</Tag>
          ) : (
            <span style={{ fontSize: 11.5, opacity: 0.45 }}>{row.versionCount ?? 0} 个版本</span>
          )}
        </Space>
      ),
    },
    {
      key: 'action',
      title: '操作',
      dataIndex: 'id',
      width: 160,
      render: (_: unknown, row: AiAgent) => (
        <Space size={2} wrap>
          <Button
            size="small"
            type="text"
            icon={<EditOutlined />}
            disabled={!canEdit}
            onClick={() => openAgentModal(row)}
          />
          <Button
            size="small"
            type="text"
            disabled={!canPublish}
            onClick={async () => {
              if (!row.id) return;
              try {
                if (row.status === 'DISABLED') {
                  await aiAgentApi.enable(row.id);
                  message.success('已启用');
                } else {
                  await aiAgentApi.disable(row.id);
                  message.success('已停用');
                }
                await load();
              } catch (error) {
                message.error(error instanceof Error ? error.message : '操作失败');
              }
            }}
          >
            {row.status === 'DISABLED' ? '启用' : '停用'}
          </Button>
          <Popconfirm
            title="删除智能体？"
            description="会连同全部版本一起删除，客户端入口立即消失。"
            okButtonProps={{ danger: true }}
            disabled={!canEdit}
            onConfirm={async () => {
              if (!row.id) return;
              try {
                await aiAgentApi.remove(row.id);
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
        智能体的身份、入口、可见客户端在这里配；具体行为（模型路由、提示词、工具、知识、额度）在它下面的「版本」里配。
        <strong>只有已发布版本会生效</strong>
        ，草稿随便改都不影响线上。想回到旧行为就用「回滚」，它会复制历史版本成新草稿再发布，历史不会被覆盖。
      </Hint>

      <div
        style={{
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
          marginBottom: 10,
        }}
      >
        <Space size={8}>
          <span style={{ fontSize: 13, fontWeight: 500 }}>智能体</span>
          <Tag color="default">{agents.length}</Tag>
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
            onClick={() => openAgentModal()}
          >
            新建智能体
          </Button>
        </Space>
      </div>

      <div
        style={{
          display: 'grid',
          gridTemplateColumns: 'minmax(0, 1.15fr) minmax(0, 1fr)',
          gap: 16,
          alignItems: 'start',
        }}
      >
        <ResizableTable
          resizeKey="ai-agent"
          rowKey="id"
          columns={agentColumns}
          dataSource={agents}
          loading={loading}
          pagination={false}
          onRow={(row: AiAgent) => ({ onClick: () => setSelectedId(row.id) })}
        />

        <div style={{ position: 'sticky', top: 12 }}>
          {selected ? (
            <VersionBoard
              agent={selected}
              canEdit={canEdit}
              canPublish={canPublish}
              dict={dict}
              onChanged={load}
            />
          ) : (
            <Card>
              <Empty description="左侧选一个智能体，这里会显示它的版本与发布状态" />
            </Card>
          )}
        </div>
      </div>

      <Modal
        title={agentModal.row ? '编辑智能体' : '新建智能体'}
        open={agentModal.open}
        onCancel={() => setAgentModal({ open: false })}
        onOk={() => void submitAgent()}
        confirmLoading={saving}
        okText="保存"
        cancelText="取消"
        width={720}
        destroyOnClose
      >
        <AgentForm form={agentForm} editing={Boolean(agentModal.row)} dict={dict} />
      </Modal>
    </div>
  );
}

/* ==================== 智能体表单 ==================== */

function AgentForm({
  form,
  editing,
  dict,
}: {
  form: ReturnType<typeof Form.useForm>[0];
  editing: boolean;
  dict: ReturnType<typeof useAiDict>;
}) {
  return (
    <Form form={form} layout="vertical">
      <Space size={12} style={{ display: 'flex' }}>
        <Form.Item
          name="agentCode"
          label="编码"
          style={{ flex: 1 }}
          rules={[
            { required: true, message: '请输入编码' },
            { pattern: /^[a-zA-Z0-9_-]+$/, message: '只能是字母、数字、下划线、短横线' },
          ]}
          extra="客户端按编码查找智能体，创建后不建议再改"
        >
          <Input placeholder="例如 music_assistant" disabled={editing} />
        </Form.Item>
        <Form.Item
          name="name"
          label="名称"
          style={{ flex: 1 }}
          rules={[{ required: true, message: '请输入名称' }]}
        >
          <Input placeholder="例如 音乐助手" />
        </Form.Item>
        <Form.Item
          name="agentType"
          label="类型"
          style={{ width: 160 }}
          rules={[{ required: true }]}
        >
          <Select options={dict.options('ai_agent_type')} />
        </Form.Item>
      </Space>

      <Form.Item name="description" label="描述" extra="会显示在客户端的入口卡片上">
        <Input.TextArea rows={2} maxLength={512} />
      </Form.Item>

      <Space size={12} style={{ display: 'flex' }}>
        <Form.Item
          name="clientTypes"
          label="可见客户端"
          style={{ flex: 1 }}
          rules={[{ required: true, message: '至少选择一个客户端' }]}
        >
          <Select
            mode="multiple"
            options={dict.options('ai_client_type')}
            placeholder="选择允许使用的客户端"
          />
        </Form.Item>
        <Form.Item
          name="permissionCode"
          label="使用权限码"
          style={{ flex: 1 }}
          extra="不填则按智能体类型回落默认权限"
        >
          <Input placeholder="例如 user:ai:music:use" maxLength={128} />
        </Form.Item>
      </Space>

      <Form.Item
        name="entryConfig"
        label="入口配置（JSON 对象）"
        rules={[jsonObjectRule]}
        extra='例如 {"icon":"ToolOutlined","title":"管理助手","position":"floating"}'
      >
        <Input.TextArea rows={3} placeholder="{}" />
      </Form.Item>

      <Space size={16} style={{ display: 'flex', marginBottom: 4 }}>
        <Form.Item name="conversationEnabled" label="启用会话" valuePropName="checked">
          <Switch size="small" />
        </Form.Item>
        <Form.Item name="feedbackEnabled" label="允许反馈" valuePropName="checked">
          <Switch size="small" />
        </Form.Item>
        <Form.Item name="allowUserContext" label="带用户上下文" valuePropName="checked">
          <Switch size="small" />
        </Form.Item>
        <Form.Item name="allowFileContext" label="允许文件内容" valuePropName="checked">
          <Switch size="small" />
        </Form.Item>
        <Form.Item name="sort" label="排序" style={{ width: 90 }}>
          <InputNumber min={0} max={9999} size="small" style={{ width: '100%' }} />
        </Form.Item>
      </Space>
    </Form>
  );
}

/* ==================== 版本面板 ==================== */

function VersionBoard({
  agent,
  canEdit,
  canPublish,
  dict,
  onChanged,
}: {
  agent: AiAgent;
  canEdit: boolean;
  canPublish: boolean;
  dict: ReturnType<typeof useAiDict>;
  onChanged: () => Promise<void>;
}) {
  const [versions, setVersions] = useState<AiAgentVersion[]>([]);
  const [loading, setLoading] = useState(false);
  const [activeId, setActiveId] = useState<number | undefined>();
  const [editorOpen, setEditorOpen] = useState(false);
  const [editing, setEditing] = useState<AiAgentVersion | undefined>();
  const [validation, setValidation] = useState<ValidationResult | undefined>();
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    if (!agent.id) return;
    setLoading(true);
    try {
      const res = await aiAgentApi.versions(agent.id);
      const list = (res.data ?? []).slice().sort((a, b) => (b.versionNo ?? 0) - (a.versionNo ?? 0));
      setVersions(list);
      setActiveId((prev) => {
        if (prev && list.some((item) => item.id === prev)) return prev;
        return (list.find((item) => item.id === agent.publishedVersionId) ?? list[0])?.id;
      });
    } catch (error) {
      message.error(error instanceof Error ? error.message : '版本加载失败');
    } finally {
      setLoading(false);
    }
  }, [agent.id, agent.publishedVersionId]);

  useEffect(() => {
    void load();
  }, [load]);

  const active = useMemo(() => versions.find((item) => item.id === activeId), [versions, activeId]);
  const isDraft = active?.status === 'DRAFT';

  const doValidate = async () => {
    if (!active?.id) return;
    setBusy(true);
    try {
      const res = await aiAgentApi.validateVersion(active.id);
      setValidation(res.data);
      if (res.data?.valid) {
        message.success('校验通过，可以发布');
      } else {
        message.warning('校验发现问题，请先修复阻断项');
      }
    } catch (error) {
      message.error(error instanceof Error ? error.message : '校验失败');
    } finally {
      setBusy(false);
    }
  };

  const doPublish = async () => {
    if (!active?.id) return;
    setBusy(true);
    try {
      await aiAgentApi.publishVersion(active.id);
      message.success('已发布，客户端下一次请求就会用新版本');
      await load();
      await onChanged();
    } catch (error) {
      message.error(error instanceof Error ? error.message : '发布失败');
    } finally {
      setBusy(false);
    }
  };

  const doRollback = async (version: AiAgentVersion) => {
    if (!agent.id || !version.id) return;
    setBusy(true);
    try {
      await aiAgentApi.rollback(agent.id, version.id);
      message.success(`已按 v${version.versionNo} 生成新版本并发布`);
      await load();
      await onChanged();
    } catch (error) {
      message.error(error instanceof Error ? error.message : '回滚失败');
    } finally {
      setBusy(false);
    }
  };

  const columns = [
    {
      key: 'versionNo',
      title: '版本',
      dataIndex: 'versionNo',
      width: 90,
      render: (value: number) => <span style={{ fontWeight: 500 }}>v{value}</span>,
    },
    {
      key: 'status',
      title: '状态',
      dataIndex: 'status',
      width: 96,
      render: (value: string) => (
        <StatusTag dict={dict} code="ai_agent_version_status" value={value} />
      ),
    },
    {
      key: 'route',
      title: '路由',
      dataIndex: 'routeName',
      width: 130,
      render: (value: string) => value || <span style={{ opacity: 0.4 }}>-</span>,
    },
    {
      key: 'time',
      title: '时间',
      dataIndex: 'createTime',
      width: 150,
      render: (value: string, row: AiAgentVersion) => (
        <span style={{ fontSize: 11.5, opacity: 0.6 }}>{formatTime(row.publishedAt || value)}</span>
      ),
    },
    {
      key: 'action',
      title: '操作',
      dataIndex: 'id',
      width: 120,
      render: (_: unknown, row: AiAgentVersion) => (
        <Space size={2}>
          <Button size="small" type="text" onClick={() => setActiveId(row.id)}>
            查看
          </Button>
          {row.status !== 'DRAFT' && row.status !== 'PUBLISHED' && (
            <Tooltip title="以该版本为蓝本生成新版本并发布">
              <Button
                size="small"
                type="text"
                icon={<RollbackOutlined />}
                disabled={!canPublish || busy}
                onClick={() => void doRollback(row)}
              />
            </Tooltip>
          )}
        </Space>
      ),
    },
  ];

  return (
    <Card
      size="small"
      title={
        <Space size={8}>
          <span style={{ fontWeight: 600 }}>{agent.name}</span>
          <StatusTag dict={dict} code="ai_agent_status" value={agent.status} />
        </Space>
      }
      extra={
        <Space size={6}>
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
            onClick={() => {
              setEditing(undefined);
              setEditorOpen(true);
            }}
          >
            新建版本
          </Button>
        </Space>
      }
      bodyStyle={{ padding: 12 }}
    >
      {loading && versions.length === 0 ? (
        <div style={{ padding: 24, textAlign: 'center' }}>
          <Spin size="small" />
        </div>
      ) : versions.length === 0 ? (
        <Empty description="还没有版本。新建一个草稿，配好路由与提示词后发布。" />
      ) : (
        <ResizableTable
          resizeKey="ai-agent-version"
          rowKey="id"
          columns={columns}
          dataSource={versions}
          pagination={false}
          size="small"
        />
      )}

      {active && (
        <div
          style={{
            marginTop: 12,
            borderTop: '1px solid var(--border, rgba(127,127,127,0.14))',
            paddingTop: 12,
          }}
        >
          <div
            style={{
              display: 'flex',
              justifyContent: 'space-between',
              alignItems: 'center',
              marginBottom: 8,
            }}
          >
            <Space size={8}>
              <span style={{ fontSize: 13, fontWeight: 500 }}>v{active.versionNo}</span>
              <StatusTag dict={dict} code="ai_agent_version_status" value={active.status} />
              {active.rollbackFromVersionId && <Tag color="default">由回滚生成</Tag>}
            </Space>
            <Space size={6}>
              {isDraft && (
                <Button
                  size="small"
                  icon={<EditOutlined />}
                  disabled={!canEdit}
                  onClick={() => {
                    setEditing(active);
                    setEditorOpen(true);
                  }}
                >
                  编辑草稿
                </Button>
              )}
              <Button
                size="small"
                icon={<CheckCircleOutlined />}
                loading={busy}
                onClick={() => void doValidate()}
              >
                校验
              </Button>
              {isDraft && (
                <Button
                  size="small"
                  type="primary"
                  icon={<SendOutlined />}
                  loading={busy}
                  disabled={!canPublish}
                  onClick={() => void doPublish()}
                >
                  发布
                </Button>
              )}
            </Space>
          </div>

          {validation && (
            <Alert
              style={{ marginBottom: 10 }}
              type={validation.valid ? 'success' : 'warning'}
              showIcon
              message={validation.valid ? '校验通过' : '存在问题'}
              description={
                (validation.issues ?? []).length === 0 ? undefined : (
                  <ul
                    style={{ margin: '4px 0 0', paddingLeft: 18, fontSize: 12.5, lineHeight: 1.85 }}
                  >
                    {(validation.issues ?? []).map((issue, index) => (
                      <li key={`${issue.code}-${index}`}>
                        <Tag color={issue.level === 'ERROR' ? 'error' : 'warning'}>
                          {issue.level}
                        </Tag>
                        <code style={{ fontSize: 11.5, opacity: 0.6 }}>{issue.code}</code>{' '}
                        {issue.message}
                        {issue.field ? (
                          <span style={{ opacity: 0.5 }}>（{issue.field}）</span>
                        ) : null}
                      </li>
                    ))}
                  </ul>
                )
              }
            />
          )}

          {/* 已发布/已归档版本不可变是设计约束（请求按版本快照回放审计）。
              之前这里只静默禁用 Select，看起来像「点了没反应」的 bug，所以给出明确解释。 */}
          {!isDraft && (
            <Alert
              style={{ marginBottom: 10 }}
              type="info"
              showIcon
              message="该版本已发布，配置不可修改"
              description="发布版本的模型路由、工具与知识绑定会固化为快照，保证线上行为可回放审计。要调整配置，请「新建版本」生成草稿，或对历史版本执行回滚（以它为蓝本复制出新草稿）。"
            />
          )}

          <Descriptions
            size="small"
            column={2}
            bordered
            items={[
              {
                key: 'route',
                label: '模型路由',
                children: active.routeName || active.providerRouteId || '-',
              },
              {
                key: 'prompt',
                label: '提示词版本',
                children: active.promptName || active.systemPromptVersionId || '-',
              },
              {
                key: 'published',
                label: '发布时间',
                children: active.publishedAt ? formatTime(active.publishedAt) : '未发布',
              },
              {
                key: 'tools',
                label: '工具数',
                children: `${(active.toolIds ?? []).length} 个`,
              },
              {
                key: 'knowledge',
                label: '知识源',
                children: `${(active.knowledgeSourceIds ?? []).length} 个`,
              },
              {
                key: 'snapshot',
                label: '客户端快照',
                children: active.clientTypesSnapshot || '-',
              },
            ]}
          />

          <Tabs
            size="small"
            style={{ marginTop: 10 }}
            items={[
              { key: 'policy', label: '策略', children: <PolicyView version={active} /> },
              {
                key: 'tools',
                label: '工具',
                children: <ToolBinding version={active} canEdit={canEdit && isDraft} />,
              },
              {
                key: 'knowledge',
                label: '知识源',
                children: <KnowledgeBinding version={active} canEdit={canEdit && isDraft} />,
              },
              { key: 'raw', label: '快照 JSON', children: <RawSnapshot version={active} /> },
            ]}
          />
        </div>
      )}

      <VersionEditor
        open={editorOpen}
        agent={agent}
        version={editing}
        onClose={() => setEditorOpen(false)}
        onSaved={async () => {
          setEditorOpen(false);
          await load();
          await onChanged();
        }}
      />
    </Card>
  );
}

/* ==================== 策略展示 ==================== */

function PolicyView({ version }: { version: AiAgentVersion }) {
  const rows: { label: string; value?: string | null }[] = [
    { label: '上下文策略', value: version.contextPolicyJson },
    { label: '响应 Schema', value: version.responseSchemaJson },
    { label: '工具策略', value: version.toolPolicyJson },
    { label: '知识策略', value: version.knowledgePolicyJson },
    { label: '模型参数', value: version.modelParametersJson },
    { label: '安全策略', value: version.safetyPolicyJson },
    { label: '额度覆盖', value: version.quotaOverrideJson },
  ];
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
      {rows.map((row) => (
        <div key={row.label}>
          <div style={{ fontSize: 12.5, fontWeight: 500, marginBottom: 4, opacity: 0.8 }}>
            {row.label}
          </div>
          <JsonBlock value={row.value} maxHeight={160} />
        </div>
      ))}
    </div>
  );
}

function RawSnapshot({ version }: { version: AiAgentVersion }) {
  return (
    <div>
      <div style={{ fontSize: 12.5, opacity: 0.6, marginBottom: 6 }}>
        发布后这份快照不再变化。排查线上问题时，请求详情里的 configurationSnapshot 就是它。
      </div>
      <JsonBlock
        value={JSON.stringify(
          {
            versionName: version.versionName,
            status: version.status,
            providerRouteId: version.providerRouteId,
            systemPromptVersionId: version.systemPromptVersionId,
            clientTypesSnapshot: version.clientTypesSnapshot,
            entryConfigSnapshot: version.entryConfigSnapshot,
            rollbackFromVersionId: version.rollbackFromVersionId,
            publishedAt: version.publishedAt,
          },
          null,
          2,
        )}
        maxHeight={240}
      />
    </div>
  );
}

/* ==================== 工具绑定 ==================== */

function ToolBinding({ version, canEdit }: { version: AiAgentVersion; canEdit: boolean }) {
  const [tools, setTools] = useState<AiTool[]>([]);
  const [bound, setBound] = useState<number[]>([]);
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const dict = useAiDict();

  const load = useCallback(async () => {
    if (!version.id) return;
    setLoading(true);
    try {
      const [allRes, boundRes] = await Promise.all([
        aiToolApi.list(),
        aiAgentApi.versionTools(version.id),
      ]);
      setTools(allRes.data ?? []);
      setBound(
        (boundRes.data ?? [])
          .filter((item) => item.enabled !== false)
          .map((item) => item.toolId as number),
      );
    } catch (error) {
      message.error(error instanceof Error ? error.message : '工具加载失败');
    } finally {
      setLoading(false);
    }
  }, [version.id]);

  useEffect(() => {
    void load();
  }, [load]);

  const save = async () => {
    if (!version.id) return;
    setSaving(true);
    try {
      await aiAgentApi.replaceTools(version.id, bound);
      message.success('工具绑定已保存');
    } catch (error) {
      message.error(error instanceof Error ? error.message : '保存失败');
    } finally {
      setSaving(false);
    }
  };

  const options = tools.map((item) => ({
    label: `${item.name}（${dict.label('ai_tool_risk_level', item.riskLevel)}）${item.handlerAvailable === false ? ' · 未实现' : ''}`,
    value: item.id as number,
    disabled: item.handlerAvailable === false,
  }));

  return (
    <div>
      <div style={{ fontSize: 12.5, opacity: 0.6, marginBottom: 8 }}>
        勾选这个版本可以调用的工具。标「未实现」的是后台登记了但代码里没有对应处理器，勾上也调不通。
      </div>
      {!canEdit && (
        <div style={{ fontSize: 12.5, opacity: 0.6, marginBottom: 8 }}>
          <ReadOnlyNote show /> 只读：版本不可编辑时工具绑定仅作展示。
        </div>
      )}
      {loading ? (
        <div style={{ padding: 20, textAlign: 'center' }}>
          <Spin size="small" />
        </div>
      ) : (
        <>
          <Select
            mode="multiple"
            style={{ width: '100%' }}
            placeholder="选择工具"
            options={options}
            value={bound}
            onChange={(value) => setBound(value as number[])}
            disabled={!canEdit}
            showSearch
            optionFilterProp="label"
          />
          {canEdit && (
            <Button
              size="small"
              type="primary"
              icon={<SaveOutlined />}
              style={{ marginTop: 8 }}
              loading={saving}
              onClick={() => void save()}
            >
              保存工具绑定
            </Button>
          )}
        </>
      )}
    </div>
  );
}

/* ==================== 知识绑定 ==================== */

function KnowledgeBinding({ version, canEdit }: { version: AiAgentVersion; canEdit: boolean }) {
  const dict = useAiDict();
  const [sources, setSources] = useState<AiKnowledgeSource[]>([]);
  const [bindings, setBindings] = useState<AiAgentKnowledge[]>([]);
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);

  const load = useCallback(async () => {
    if (!version.id) return;
    setLoading(true);
    try {
      const [sourceRes, boundRes] = await Promise.all([
        aiKnowledgeApi.listSources(),
        aiAgentApi.versionKnowledge(version.id),
      ]);
      setSources(sourceRes.data ?? []);
      setBindings(boundRes.data ?? []);
    } catch (error) {
      message.error(error instanceof Error ? error.message : '知识源加载失败');
    } finally {
      setLoading(false);
    }
  }, [version.id]);

  useEffect(() => {
    void load();
  }, [load]);

  const toggle = (sourceId: number) => {
    setBindings((prev) => {
      const exists = prev.find((item) => item.sourceId === sourceId);
      if (exists) return prev.filter((item) => item.sourceId !== sourceId);
      return [
        ...prev,
        {
          sourceId,
          enabled: true,
          priority: 100,
          retrievalTopK: 5,
          minScore: 0.2,
          citationPolicy: 'REQUIRED',
        },
      ];
    });
  };

  const save = async () => {
    if (!version.id) return;
    setSaving(true);
    try {
      await aiAgentApi.replaceKnowledge(
        version.id,
        bindings.map((item) => ({
          sourceId: item.sourceId,
          enabled: item.enabled ?? true,
          priority: item.priority ?? 100,
          retrievalTopK: item.retrievalTopK ?? 5,
          minScore: item.minScore ?? 0.2,
          citationPolicy: item.citationPolicy ?? 'REQUIRED',
        })),
      );
      message.success('知识源绑定已保存');
    } catch (error) {
      message.error(error instanceof Error ? error.message : '保存失败');
    } finally {
      setSaving(false);
    }
  };

  if (loading) {
    return (
      <div style={{ padding: 20, textAlign: 'center' }}>
        <Spin size="small" />
      </div>
    );
  }

  return (
    <div>
      <div style={{ fontSize: 12.5, opacity: 0.6, marginBottom: 8 }}>
        只有绑定知识源的智能体才要求引用；答疑类智能体建议把 citationPolicy 设成 REQUIRED，
        并在提示词里要求「找不到就说找不到」。
      </div>
      {!canEdit && (
        <div style={{ fontSize: 12.5, opacity: 0.6, marginBottom: 8 }}>
          <ReadOnlyNote show /> 只读：版本不可编辑时知识绑定仅作展示。
        </div>
      )}
      {sources.length === 0 ? (
        <Empty description="还没有知识源。到「知识库」页签新建一个并同步文档。" />
      ) : (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
          {sources.map((source) => {
            const binding = bindings.find((item) => item.sourceId === source.id);
            return (
              <div
                key={source.id}
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'space-between',
                  gap: 12,
                  padding: '8px 10px',
                  borderRadius: 8,
                  border: '1px solid var(--border, rgba(127,127,127,0.14))',
                  fontSize: 13,
                }}
              >
                <Space size={8}>
                  <Switch
                    size="small"
                    checked={Boolean(binding)}
                    disabled={!canEdit}
                    onChange={() => source.id && toggle(source.id)}
                  />
                  <span>{source.name}</span>
                  <Tag color="default">{source.documentCount ?? 0} 篇</Tag>
                  <StatusTag dict={dict} code="ai_knowledge_status" value={source.status} />
                </Space>
                {binding && (
                  <Space size={8}>
                    <span style={{ fontSize: 11.5, opacity: 0.55 }}>topK</span>
                    <InputNumber
                      size="small"
                      min={1}
                      max={50}
                      value={binding.retrievalTopK ?? 5}
                      disabled={!canEdit}
                      onChange={(value) =>
                        setBindings((prev) =>
                          prev.map((item) =>
                            item.sourceId === source.id
                              ? { ...item, retrievalTopK: value ?? 5 }
                              : item,
                          ),
                        )
                      }
                      style={{ width: 70 }}
                    />
                  </Space>
                )}
              </div>
            );
          })}
          {canEdit && (
            <Button
              size="small"
              type="primary"
              icon={<SaveOutlined />}
              style={{ alignSelf: 'flex-start' }}
              loading={saving}
              onClick={() => void save()}
            >
              保存知识绑定
            </Button>
          )}
        </div>
      )}
    </div>
  );
}

/* ==================== 版本编辑器 ==================== */

function VersionEditor({
  open,
  agent,
  version,
  onClose,
  onSaved,
}: {
  open: boolean;
  agent: AiAgent;
  version?: AiAgentVersion;
  onClose: () => void;
  onSaved: () => Promise<void>;
}) {
  const [form] = Form.useForm();
  const [routes, setRoutes] = useState<AiRoute[]>([]);
  const [prompts, setPrompts] = useState<AiPromptTemplate[]>([]);
  const [promptVersions, setPromptVersions] = useState<{ label: string; value: number }[]>([]);
  const [saving, setSaving] = useState(false);
  const [, setPromptTemplateId] = useState<number | undefined>();

  useEffect(() => {
    if (!open) return;
    void (async () => {
      try {
        const [routeRes, promptRes] = await Promise.all([aiRouteApi.list(), aiPromptApi.list()]);
        setRoutes(routeRes.data ?? []);
        setPrompts(promptRes.data ?? []);
      } catch {
        // 下拉数据加载失败不阻断编辑，用户可以先填其余字段
      }
    })();
  }, [open]);

  useEffect(() => {
    if (!open) return;
    form.resetFields();
    if (version) {
      form.setFieldsValue({
        versionName: version.versionName,
        providerRouteId: version.providerRouteId,
        systemPromptVersionId: version.systemPromptVersionId,
        contextPolicyJson: version.contextPolicyJson ?? DEFAULT_CONTEXT_POLICY,
        responseSchemaJson: version.responseSchemaJson ?? '',
        toolPolicyJson: version.toolPolicyJson ?? DEFAULT_TOOL_POLICY,
        knowledgePolicyJson: version.knowledgePolicyJson ?? DEFAULT_KNOWLEDGE_POLICY,
        modelParametersJson: version.modelParametersJson ?? DEFAULT_MODEL_PARAMS,
        safetyPolicyJson: version.safetyPolicyJson ?? DEFAULT_SAFETY_POLICY,
        quotaOverrideJson: version.quotaOverrideJson ?? '',
      });
    } else {
      form.setFieldsValue({
        contextPolicyJson: DEFAULT_CONTEXT_POLICY,
        toolPolicyJson: DEFAULT_TOOL_POLICY,
        knowledgePolicyJson: DEFAULT_KNOWLEDGE_POLICY,
        modelParametersJson: DEFAULT_MODEL_PARAMS,
        safetyPolicyJson: DEFAULT_SAFETY_POLICY,
      });
    }
  }, [open, version, form]);

  const loadPromptVersions = async (templateId?: number) => {
    if (!templateId) {
      setPromptVersions([]);
      return;
    }
    try {
      const res = await aiPromptApi.versions(templateId);
      setPromptVersions(
        (res.data ?? [])
          .filter((item) => item.status === 'PUBLISHED')
          .map((item) => ({ label: `v${item.versionNo}（已发布）`, value: item.id as number })),
      );
    } catch {
      setPromptVersions([]);
    }
  };

  const submit = async () => {
    if (!agent.id) return;
    try {
      const values = (await form.validateFields()) as Partial<AiAgentVersion>;
      setSaving(true);
      if (version?.id) {
        await aiAgentApi.updateVersion(version.id, values);
        message.success('草稿已保存。要生效请点「校验 → 发布」');
      } else {
        await aiAgentApi.createVersion(agent.id, values);
        message.success('已创建草稿版本');
      }
      await onSaved();
    } catch (error) {
      if (error instanceof Error) message.error(error.message);
    } finally {
      setSaving(false);
    }
  };

  return (
    <Modal
      title={version ? `编辑 v${version.versionNo} 草稿` : '新建版本草稿'}
      open={open}
      onCancel={onClose}
      onOk={() => void submit()}
      confirmLoading={saving}
      okText="保存草稿"
      cancelText="取消"
      width={860}
      destroyOnClose
    >
      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 12 }}
        message="保存后仍是草稿"
        description="发布会把这一版的全部配置冻结成快照；发布后再改必须新建版本。"
      />
      <Form form={form} layout="vertical">
        <Space size={12} style={{ display: 'flex' }}>
          <Form.Item
            name="versionName"
            label="版本名"
            style={{ flex: 1 }}
            rules={[{ required: true, message: '请输入版本名' }]}
          >
            <Input placeholder="例如 v1 / 增加退款问答" maxLength={64} />
          </Form.Item>
          <Form.Item name="providerRouteId" label="模型路由" style={{ flex: 1 }}>
            <Select
              allowClear
              placeholder="不选则用默认可用模型"
              options={routes.map((item) => ({
                label: `${item.name}（${item.modelCode}）`,
                value: item.id as number,
              }))}
            />
          </Form.Item>
        </Space>

        <Space size={12} style={{ display: 'flex' }}>
          <Form.Item name="promptTemplate" label="提示词模板" style={{ flex: 1 }}>
            <Select
              allowClear
              placeholder="选择模板"
              options={prompts.map((item) => ({
                label: item.name as string,
                value: item.id as number,
              }))}
              onChange={(value) => {
                setPromptTemplateId(value as number);
                form.setFieldsValue({ systemPromptVersionId: undefined });
                void loadPromptVersions(value as number);
              }}
            />
          </Form.Item>
          <Form.Item
            name="systemPromptVersionId"
            label="提示词版本"
            style={{ flex: 1 }}
            extra="只能选已发布版本"
          >
            <Select allowClear placeholder="选择已发布版本" options={promptVersions} />
          </Form.Item>
        </Space>

        <Form.Item
          name="contextPolicyJson"
          label="上下文策略"
          rules={[jsonObjectRule]}
          extra='{"keys":["pageContext","selectedRecord"],"maxAgeSeconds":600}'
        >
          <Input.TextArea rows={3} />
        </Form.Item>

        <Form.Item
          name="responseSchemaJson"
          label="响应 Schema"
          rules={[jsonObjectRule]}
          extra="模型被要求严格按它输出；改这里等于改前端能收到的结构"
        >
          <Input.TextArea rows={4} placeholder='{"type":"object","properties":{...}}' />
        </Form.Item>

        <Space size={12} style={{ display: 'flex' }}>
          <Form.Item
            name="toolPolicyJson"
            label="工具策略"
            style={{ flex: 1 }}
            rules={[jsonObjectRule]}
          >
            <Input.TextArea rows={4} />
          </Form.Item>
          <Form.Item
            name="knowledgePolicyJson"
            label="知识策略"
            style={{ flex: 1 }}
            rules={[jsonObjectRule]}
          >
            <Input.TextArea rows={4} />
          </Form.Item>
        </Space>

        <Space size={12} style={{ display: 'flex' }}>
          <Form.Item
            name="modelParametersJson"
            label="模型参数"
            style={{ flex: 1 }}
            rules={[jsonObjectRule]}
          >
            <Input.TextArea rows={3} />
          </Form.Item>
          <Form.Item
            name="safetyPolicyJson"
            label="安全策略"
            style={{ flex: 1 }}
            rules={[jsonObjectRule]}
          >
            <Input.TextArea rows={3} />
          </Form.Item>
        </Space>

        <Form.Item
          name="quotaOverrideJson"
          label="额度覆盖（可选）"
          rules={[jsonObjectRule]}
          extra="只会收紧，不会放宽全局额度"
        >
          <Input.TextArea rows={2} placeholder="{}" />
        </Form.Item>
      </Form>
    </Modal>
  );
}
