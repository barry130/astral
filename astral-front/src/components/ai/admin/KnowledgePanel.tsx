/**
 * 知识库。
 *
 * 左选知识源、右管文档。同步是显式动作而不是自动轮询，
 * 因为索引本身可能很慢（要切块、算向量），后台默默跑会让人以为「改了没生效」。
 */

'use client';

import React, { useCallback, useEffect, useMemo, useState } from 'react';

import {
  Alert,
  Button,
  Card,
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
  message,
} from '@/components/antd-compat';
import {
  DatabaseOutlined,
  DeleteOutlined,
  EditOutlined,
  FileTextOutlined,
  PlusOutlined,
  ReloadOutlined,
} from '@/components/antd-compat/icons';
import { ResizableTable } from '@/components/ResizableTable';

import {
  aiKnowledgeApi,
  type AiIndexJob,
  type AiKnowledgeDocument,
  type AiKnowledgeSource,
} from '@/api/ai';
import { usePerm } from '@/lib/perm';

import {
  Hint,
  ReadOnlyNote,
  StatusTag,
  formatNumber,
  formatTime,
  truncate,
  useAiDict,
} from './shared';

export default function KnowledgePanel() {
  const hasPerm = usePerm();
  const dict = useAiDict();
  const canEdit = hasPerm('admin:ai:knowledge:edit');
  const canSync = hasPerm('admin:ai:knowledge:sync');

  const [sources, setSources] = useState<AiKnowledgeSource[]>([]);
  const [loading, setLoading] = useState(false);
  const [selectedId, setSelectedId] = useState<number | undefined>();
  const [sourceModal, setSourceModal] = useState<{ open: boolean; row?: AiKnowledgeSource }>({
    open: false,
  });
  const [saving, setSaving] = useState(false);
  const [form] = Form.useForm();

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const res = await aiKnowledgeApi.listSources();
      const list = res.data ?? [];
      setSources(list);
      setSelectedId((prev) => (prev && list.some((i) => i.id === prev) ? prev : list[0]?.id));
    } catch (error) {
      message.error(error instanceof Error ? error.message : '知识源加载失败');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const selected = useMemo(
    () => sources.find((item) => item.id === selectedId),
    [sources, selectedId],
  );

  const openModal = (row?: AiKnowledgeSource) => {
    setSourceModal({ open: true, row });
    form.resetFields();
    if (row) {
      form.setFieldsValue({ ...row });
    } else {
      form.setFieldsValue({
        sourceType: 'PROJECT_DOCUMENT',
        enabled: true,
        syncMode: 'MANUAL',
        pathPattern: 'docs/**',
      });
    }
  };

  const submit = async () => {
    try {
      const values = await form.validateFields();
      setSaving(true);
      if (sourceModal.row?.id) {
        await aiKnowledgeApi.updateSource(sourceModal.row.id, values);
      } else {
        await aiKnowledgeApi.createSource(values);
      }
      message.success('已保存');
      setSourceModal({ open: false });
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
      title: '知识源',
      dataIndex: 'name',
      width: 200,
      render: (_: unknown, row: AiKnowledgeSource) => (
        <Space size={6} direction="vertical">
          <span style={{ fontWeight: 500 }}>{row.name}</span>
          <code style={{ fontSize: 11.5, opacity: 0.55 }}>{row.sourceCode}</code>
        </Space>
      ),
    },
    {
      key: 'sourceType',
      title: '类型',
      dataIndex: 'sourceType',
      width: 150,
      render: (value: string) => (
        <Tag color="default">{dict.label('ai_knowledge_source_type', value)}</Tag>
      ),
    },
    {
      key: 'status',
      title: '状态',
      dataIndex: 'status',
      width: 100,
      render: (value: string) => <StatusTag dict={dict} code="ai_knowledge_status" value={value} />,
    },
    {
      key: 'docs',
      title: '文档 / 分块',
      dataIndex: 'documentCount',
      width: 120,
      align: 'right' as const,
      render: (_: unknown, row: AiKnowledgeSource) => (
        <span style={{ fontSize: 12.5 }}>
          {formatNumber(row.documentCount)} / {formatNumber(row.chunkCount)}
        </span>
      ),
    },
    {
      key: 'lastSync',
      title: '上次同步',
      dataIndex: 'lastSyncTime',
      width: 150,
      render: (value: string) => (
        <span style={{ fontSize: 11.5, opacity: 0.6 }}>{formatTime(value)}</span>
      ),
    },
    {
      key: 'action',
      title: '操作',
      dataIndex: 'id',
      width: 110,
      render: (_: unknown, row: AiKnowledgeSource) => (
        <Space size={2}>
          <Button
            size="small"
            type="text"
            icon={<EditOutlined />}
            disabled={!canEdit}
            onClick={() => openModal(row)}
          />
          <Button size="small" type="text" onClick={() => setSelectedId(row.id)}>
            文档
          </Button>
        </Space>
      ),
    },
  ];

  return (
    <div>
      <Hint>
        知识源是「答疑能有依据」的前提。文档入库后会切块索引，检索时按 topK 与最低相关度过滤，
        命中片段随回答一起返回，前端渲染成引用。<strong>智能体要单独绑定知识源才会用上</strong>
        ，绑在「智能体 → 版本 → 知识源」里。
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
          <span style={{ fontSize: 13, fontWeight: 500 }}>知识源</span>
          <Tag color="default">{sources.length}</Tag>
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
            新建知识源
          </Button>
        </Space>
      </div>

      <div
        style={{
          display: 'grid',
          gridTemplateColumns: 'minmax(0, 1fr) minmax(0, 1.05fr)',
          gap: 16,
          alignItems: 'start',
        }}
      >
        <ResizableTable
          resizeKey="ai-knowledge"
          rowKey="id"
          columns={columns}
          dataSource={sources}
          loading={loading}
          pagination={false}
          onRow={(row: AiKnowledgeSource) => ({ onClick: () => setSelectedId(row.id) })}
        />

        <div style={{ position: 'sticky', top: 12 }}>
          {selected ? (
            <DocumentBoard
              source={selected}
              canEdit={canEdit}
              canSync={canSync}
              dict={dict}
              onChanged={load}
            />
          ) : (
            <Card>
              <Empty description="左侧选一个知识源，这里管理它的文档与索引" />
            </Card>
          )}
        </div>
      </div>

      <Modal
        title={sourceModal.row ? '编辑知识源' : '新建知识源'}
        open={sourceModal.open}
        onCancel={() => setSourceModal({ open: false })}
        onOk={() => void submit()}
        confirmLoading={saving}
        okText="保存"
        cancelText="取消"
        width={620}
        destroyOnClose
      >
        <Form form={form} layout="vertical">
          <Space size={12} style={{ display: 'flex' }}>
            <Form.Item
              name="sourceCode"
              label="编码"
              style={{ flex: 1 }}
              rules={[
                { required: true, message: '请输入编码' },
                { pattern: /^[a-zA-Z0-9_-]+$/, message: '只能是字母、数字、下划线、短横线' },
              ]}
            >
              <Input placeholder="例如 project_docs" disabled={Boolean(sourceModal.row)} />
            </Form.Item>
            <Form.Item
              name="name"
              label="名称"
              style={{ flex: 1 }}
              rules={[{ required: true, message: '请输入名称' }]}
            >
              <Input placeholder="例如 项目文档" />
            </Form.Item>
          </Space>

          <Space size={12} style={{ display: 'flex' }}>
            <Form.Item
              name="sourceType"
              label="类型"
              style={{ flex: 1 }}
              rules={[{ required: true }]}
            >
              <Select options={dict.options('ai_knowledge_source_type')} />
            </Form.Item>
            <Form.Item name="syncMode" label="同步方式" style={{ flex: 1 }}>
              <Select
                options={[
                  { label: '手动', value: 'MANUAL' },
                  { label: '自动', value: 'AUTO' },
                ]}
              />
            </Form.Item>
          </Space>

          <Form.Item
            name="pathPattern"
            label="路径匹配"
            extra="例如 docs/**，用于说明这类文档的来源范围"
          >
            <Input placeholder="docs/**" maxLength={512} />
          </Form.Item>

          <Form.Item name="description" label="描述">
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

/* ==================== 文档面板 ==================== */

function DocumentBoard({
  source,
  canEdit,
  canSync,
  dict,
  onChanged,
}: {
  source: AiKnowledgeSource;
  canEdit: boolean;
  canSync: boolean;
  dict: ReturnType<typeof useAiDict>;
  onChanged: () => Promise<void>;
}) {
  const [documents, setDocuments] = useState<AiKnowledgeDocument[]>([]);
  const [loading, setLoading] = useState(false);
  const [syncing, setSyncing] = useState(false);
  const [job, setJob] = useState<AiIndexJob | undefined>();

  const [modal, setModal] = useState<{ open: boolean; row?: AiKnowledgeDocument }>({ open: false });
  const [saving, setSaving] = useState(false);
  const [form] = Form.useForm();

  const load = useCallback(async () => {
    if (!source.id) return;
    setLoading(true);
    try {
      const res = await aiKnowledgeApi.documents(source.id);
      setDocuments(res.data ?? []);
    } catch (error) {
      message.error(error instanceof Error ? error.message : '文档加载失败');
    } finally {
      setLoading(false);
    }
  }, [source.id]);

  useEffect(() => {
    void load();
  }, [load]);

  /** 索引任务一般是同步跑完的，但耗时可能到秒级，这里轮询几次直到终态 */
  useEffect(() => {
    if (!job?.id) return;
    if (job.status === 'SUCCEEDED' || job.status === 'FAILED' || job.status === 'CANCELLED') return;
    const timer = setTimeout(async () => {
      try {
        const res = await aiKnowledgeApi.indexJob(job.id as number);
        setJob(res.data);
        if (res.data?.status === 'SUCCEEDED') {
          await load();
          await onChanged();
        }
      } catch {
        // 单次轮询失败忽略，下一跳再试
      }
    }, 1500);
    return () => clearTimeout(timer);
  }, [job, load, onChanged]);

  const doSync = async () => {
    if (!source.id) return;
    setSyncing(true);
    try {
      const res = await aiKnowledgeApi.sync(source.id);
      setJob(res.data);
      message.success('已提交索引任务');
      await load();
      await onChanged();
    } catch (error) {
      message.error(error instanceof Error ? error.message : '同步失败');
    } finally {
      setSyncing(false);
    }
  };

  const openModal = (row?: AiKnowledgeDocument) => {
    setModal({ open: true, row });
    form.resetFields();
    if (row) form.setFieldsValue({ ...row });
  };

  const submit = async () => {
    if (!source.id) return;
    try {
      const values = await form.validateFields();
      setSaving(true);
      await aiKnowledgeApi.saveDocument(source.id, values);
      message.success('已保存并重新入库');
      setModal({ open: false });
      await load();
      await onChanged();
    } catch (error) {
      if (error instanceof Error) message.error(error.message);
    } finally {
      setSaving(false);
    }
  };

  const columns = [
    { key: 'title', title: '标题', dataIndex: 'title', width: 180 },
    {
      key: 'path',
      title: '路径',
      dataIndex: 'path',
      width: 190,
      ellipsis: true,
      render: (value: string) => <span style={{ fontSize: 12, opacity: 0.7 }}>{value || '-'}</span>,
    },
    { key: 'section', title: '章节', dataIndex: 'section', width: 90 },
    {
      key: 'chunks',
      title: '分块',
      dataIndex: 'chunkCount',
      width: 70,
      align: 'right' as const,
      render: (value: number) => formatNumber(value),
    },
    {
      key: 'action',
      title: '操作',
      dataIndex: 'id',
      width: 90,
      render: (_: unknown, row: AiKnowledgeDocument) => (
        <Space size={2}>
          <Button
            size="small"
            type="text"
            icon={<EditOutlined />}
            disabled={!canEdit}
            onClick={() => openModal(row)}
          />
          <Popconfirm
            title="删除文档？"
            description="删除后该文档的知识片段不再被检索到。"
            okButtonProps={{ danger: true }}
            disabled={!canEdit}
            onConfirm={async () => {
              if (!row.id) return;
              try {
                await aiKnowledgeApi.removeDocument(row.id);
                message.success('已删除');
                await load();
                await onChanged();
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
    <Card
      size="small"
      title={
        <Space size={8}>
          <FileTextOutlined style={{ opacity: 0.6 }} />
          <span style={{ fontWeight: 600 }}>{source.name}</span>
          <StatusTag dict={dict} code="ai_knowledge_status" value={source.status} />
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
            icon={<DatabaseOutlined />}
            disabled={!canSync}
            loading={syncing}
            onClick={() => void doSync()}
          >
            重新索引
          </Button>
          <Button
            size="small"
            type="primary"
            icon={<PlusOutlined />}
            disabled={!canEdit}
            onClick={() => openModal()}
          >
            添加文档
          </Button>
        </Space>
      }
      bodyStyle={{ padding: 12 }}
    >
      {job && (
        <Alert
          style={{ marginBottom: 10 }}
          type={job.status === 'FAILED' ? 'error' : job.status === 'SUCCEEDED' ? 'success' : 'info'}
          showIcon
          message={`索引任务 ${job.status}`}
          description={
            <span style={{ fontSize: 12.5 }}>
              共 {job.totalCount ?? 0} 篇，已处理 {job.processedCount ?? 0}，失败{' '}
              {job.failedCount ?? 0}
              {job.errorSummary ? ` · ${truncate(job.errorSummary, 120)}` : ''}
            </span>
          }
        />
      )}

      {loading && documents.length === 0 ? (
        <div style={{ padding: 24, textAlign: 'center' }}>
          <Spin size="small" />
        </div>
      ) : documents.length === 0 ? (
        <Empty description="这个知识源还没有文档。点「添加文档」粘贴 Markdown 正文即可入库。" />
      ) : (
        <ResizableTable
          resizeKey="ai-knowledge-doc"
          rowKey="id"
          columns={columns}
          dataSource={documents}
          pagination={false}
          size="small"
        />
      )}

      <Modal
        title={modal.row ? '编辑文档' : '添加文档'}
        open={modal.open}
        onCancel={() => setModal({ open: false })}
        onOk={() => void submit()}
        confirmLoading={saving}
        okText="保存并索引"
        cancelText="取消"
        width={820}
        destroyOnClose
      >
        <Form form={form} layout="vertical">
          <Space size={12} style={{ display: 'flex' }}>
            <Form.Item
              name="docKey"
              label="文档键"
              style={{ flex: 1 }}
              rules={[{ required: true, message: '请输入文档键' }]}
            >
              <Input placeholder="例如 api-contract" disabled={Boolean(modal.row)} />
            </Form.Item>
            <Form.Item
              name="title"
              label="标题"
              style={{ flex: 1 }}
              rules={[{ required: true, message: '请输入标题' }]}
            >
              <Input placeholder="例如 AI 模块接口契约" />
            </Form.Item>
          </Space>

          <Space size={12} style={{ display: 'flex' }}>
            <Form.Item name="path" label="路径" style={{ flex: 2 }}>
              <Input placeholder="docs/ai/api-contract.md" />
            </Form.Item>
            <Form.Item name="section" label="章节" style={{ flex: 1 }}>
              <Input placeholder="接口" />
            </Form.Item>
            <Form.Item name="version" label="版本" style={{ width: 100 }}>
              <Input placeholder="1.0" />
            </Form.Item>
          </Space>

          <Form.Item
            name="content"
            label="正文（Markdown）"
            rules={[{ required: true, message: '请输入正文' }]}
            extra="按标题层级切块，写清楚小标题能让检索更准"
          >
            <Input.TextArea
              rows={16}
              placeholder="# 标题&#10;&#10;正文……"
            />
          </Form.Item>
        </Form>
      </Modal>
    </Card>
  );
}
