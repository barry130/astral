/**
 * 提示词模板与版本。
 *
 * Prompt 从智能体版本里拆出来单独管，是因为同一段系统提示词经常被多个智能体复用；
 * 同页又能看见版本，避免「改了模板以为线上立刻变」——实际上要发布版本才会生效。
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
  Space,
  Spin,
  Tag,
  Tooltip,
  message,
} from '@/components/antd-compat';
import {
  EditOutlined,
  PlusOutlined,
  ReloadOutlined,
  SendOutlined,
} from '@/components/antd-compat/icons';
import { ResizableTable } from '@/components/ResizableTable';

import { aiPromptApi, type AiPromptTemplate, type AiPromptVersion } from '@/api/ai';
import { usePerm } from '@/lib/perm';

import { Hint, ReadOnlyNote, StatusTag, formatTime, useAiDict } from './shared';

export default function PromptPanel() {
  const hasPerm = usePerm();
  const dict = useAiDict();
  const canEdit = hasPerm('admin:ai:prompt:edit');

  const [templates, setTemplates] = useState<AiPromptTemplate[]>([]);
  const [loading, setLoading] = useState(false);
  const [selectedId, setSelectedId] = useState<number | undefined>();

  const [templateModal, setTemplateModal] = useState<{ open: boolean; row?: AiPromptTemplate }>({
    open: false,
  });
  const [templateForm] = Form.useForm();
  const [versionModal, setVersionModal] = useState<{ open: boolean; row?: AiPromptVersion }>({
    open: false,
  });
  const [saving, setSaving] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const res = await aiPromptApi.list();
      const list = res.data ?? [];
      setTemplates(list);
      setSelectedId((prev) => (prev && list.some((i) => i.id === prev) ? prev : list[0]?.id));
    } catch (error) {
      message.error(error instanceof Error ? error.message : '提示词加载失败');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const selected = useMemo(
    () => templates.find((item) => item.id === selectedId),
    [templates, selectedId],
  );

  const openTemplateModal = (row?: AiPromptTemplate) => {
    setTemplateModal({ open: true, row });
    templateForm.resetFields();
    if (row) templateForm.setFieldsValue({ ...row });
  };

  const submitTemplate = async () => {
    try {
      const values = await templateForm.validateFields();
      setSaving(true);
      if (templateModal.row?.id) {
        await aiPromptApi.update(templateModal.row.id, values);
      } else {
        await aiPromptApi.create(values);
      }
      message.success('已保存');
      setTemplateModal({ open: false });
      await load();
    } catch (error) {
      if (error instanceof Error) message.error(error.message);
    } finally {
      setSaving(false);
    }
  };

  const columns = [
    { key: 'name', title: '模板', dataIndex: 'name', width: 190 },
    {
      key: 'promptCode',
      title: '编码',
      dataIndex: 'promptCode',
      width: 190,
      render: (value: string) => <code style={{ fontSize: 12, opacity: 0.72 }}>{value}</code>,
    },
    { key: 'scene', title: '场景', dataIndex: 'scene', width: 130 },
    {
      key: 'published',
      title: '已发布版本',
      dataIndex: 'publishedVersionNo',
      width: 120,
      render: (value: number) =>
        value ? <Tag color="success">v{value}</Tag> : <Tag color="warning">未发布</Tag>,
    },
    {
      key: 'count',
      title: '版本数',
      dataIndex: 'versionCount',
      width: 80,
      align: 'right' as const,
    },
    {
      key: 'action',
      title: '操作',
      dataIndex: 'id',
      width: 110,
      render: (_: unknown, row: AiPromptTemplate) => (
        <Space size={2}>
          <Button
            size="small"
            type="text"
            icon={<EditOutlined />}
            disabled={!canEdit}
            onClick={() => openTemplateModal(row)}
          />
          <Button
            size="small"
            type="text"
            onClick={() => {
              setSelectedId(row.id);
              setVersionModal({ open: true });
            }}
          >
            版本
          </Button>
        </Space>
      ),
    },
  ];

  return (
    <div>
      <Hint>
        这里的正文是模型的系统提示词。发布过的版本不可修改，改文案要新建版本——
        这样线上回答风格突变时能立刻定位到是哪一版、什么时候发的。
        <strong>提示词发布后，还要在智能体版本里重新指向它并发布</strong>，两者都到位才生效。
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
          <span style={{ fontSize: 13, fontWeight: 500 }}>模板</span>
          <Tag color="default">{templates.length}</Tag>
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
            onClick={() => openTemplateModal()}
          >
            新建模板
          </Button>
        </Space>
      </div>

      <ResizableTable
        resizeKey="ai-prompt"
        rowKey="id"
        columns={columns}
        dataSource={templates}
        loading={loading}
        pagination={false}
        onRow={(row: AiPromptTemplate) => ({ onClick: () => setSelectedId(row.id) })}
      />

      <Modal
        title={templateModal.row ? '编辑模板' : '新建模板'}
        open={templateModal.open}
        onCancel={() => setTemplateModal({ open: false })}
        onOk={() => void submitTemplate()}
        confirmLoading={saving}
        okText="保存"
        cancelText="取消"
        width={560}
        destroyOnClose
      >
        <Form form={templateForm} layout="vertical">
          <Form.Item
            name="promptCode"
            label="编码"
            rules={[
              { required: true, message: '请输入编码' },
              { pattern: /^[a-zA-Z0-9_-]+$/, message: '只能是字母、数字、下划线、短横线' },
            ]}
          >
            <Input placeholder="例如 admin_qa_prompt" disabled={Boolean(templateModal.row)} />
          </Form.Item>
          <Form.Item name="name" label="名称" rules={[{ required: true, message: '请输入名称' }]}>
            <Input placeholder="例如 答疑助手提示词" />
          </Form.Item>
          <Form.Item name="scene" label="场景" extra="便于筛选用，例如 music / admin / qa">
            <Input placeholder="qa" maxLength={64} />
          </Form.Item>
          <Form.Item name="description" label="描述">
            <Input.TextArea rows={2} maxLength={512} />
          </Form.Item>
        </Form>
      </Modal>

      <VersionDrawer
        open={versionModal.open}
        template={selected}
        canEdit={canEdit}
        dict={dict}
        onClose={() => setVersionModal({ open: false })}
      />
    </div>
  );
}

/* ==================== 版本抽屉 ==================== */

function VersionDrawer({
  open,
  template,
  canEdit,
  dict,
  onClose,
}: {
  open: boolean;
  template?: AiPromptTemplate;
  canEdit: boolean;
  dict: ReturnType<typeof useAiDict>;
  onClose: () => void;
}) {
  const [versions, setVersions] = useState<AiPromptVersion[]>([]);
  const [loading, setLoading] = useState(false);
  const [modal, setModal] = useState<{ open: boolean; row?: AiPromptVersion }>({ open: false });
  const [saving, setSaving] = useState(false);
  const [form] = Form.useForm();

  const load = useCallback(async () => {
    if (!template?.id) return;
    setLoading(true);
    try {
      const res = await aiPromptApi.versions(template.id);
      setVersions((res.data ?? []).slice().sort((a, b) => (b.versionNo ?? 0) - (a.versionNo ?? 0)));
    } catch (error) {
      message.error(error instanceof Error ? error.message : '版本加载失败');
    } finally {
      setLoading(false);
    }
  }, [template?.id]);

  useEffect(() => {
    if (open) void load();
  }, [open, load]);

  const openModal = (row?: AiPromptVersion) => {
    setModal({ open: true, row });
    form.resetFields();
    if (row) form.setFieldsValue({ ...row });
  };

  const submit = async () => {
    if (!template?.id) return;
    try {
      const values = await form.validateFields();
      setSaving(true);
      if (modal.row?.id) {
        await aiPromptApi.updateVersion(modal.row.id, values);
        message.success('已保存');
      } else {
        await aiPromptApi.createVersion(template.id, values);
        message.success('已新建草稿版本');
      }
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
      key: 'versionNo',
      title: '版本',
      dataIndex: 'versionNo',
      width: 80,
      render: (value: number) => <span style={{ fontWeight: 500 }}>v{value}</span>,
    },
    {
      key: 'status',
      title: '状态',
      dataIndex: 'status',
      width: 100,
      render: (value: string) => (
        <StatusTag dict={dict} code="ai_agent_version_status" value={value} />
      ),
    },
    {
      key: 'prompt',
      title: '提示词',
      dataIndex: 'systemPrompt',
      ellipsis: true,
      render: (value: string) => (
        <span style={{ fontSize: 12, opacity: 0.75 }}>{(value ?? '').slice(0, 80) || '-'}</span>
      ),
    },
    {
      key: 'time',
      title: '发布时间',
      dataIndex: 'publishedAt',
      width: 150,
      render: (value: string) => (
        <span style={{ fontSize: 11.5, opacity: 0.6 }}>{formatTime(value)}</span>
      ),
    },
    {
      key: 'action',
      title: '操作',
      dataIndex: 'id',
      width: 150,
      render: (_: unknown, row: AiPromptVersion) => (
        <Space size={2}>
          {row.status === 'DRAFT' && (
            <Button
              size="small"
              type="text"
              icon={<EditOutlined />}
              disabled={!canEdit}
              onClick={() => openModal(row)}
            />
          )}
          {row.status === 'DRAFT' && (
            <Tooltip title="发布后该版本不可修改">
              <Button
                size="small"
                type="text"
                icon={<SendOutlined />}
                disabled={!canEdit}
                onClick={async () => {
                  if (!row.id) return;
                  try {
                    await aiPromptApi.publishVersion(row.id);
                    message.success('已发布');
                    await load();
                  } catch (error) {
                    message.error(error instanceof Error ? error.message : '发布失败');
                  }
                }}
              />
            </Tooltip>
          )}
        </Space>
      ),
    },
  ];

  return (
    <Modal
      title={template ? `${template.name} · 版本` : '版本'}
      open={open}
      onCancel={onClose}
      footer={
        <Space>
          <Button onClick={onClose}>关闭</Button>
          <Button
            type="primary"
            icon={<PlusOutlined />}
            disabled={!canEdit || !template}
            onClick={() => openModal()}
          >
            新建版本
          </Button>
        </Space>
      }
      width={960}
      destroyOnClose
    >
      {!template ? (
        <Empty description="先在上面的列表里选一个模板" />
      ) : loading ? (
        <div style={{ padding: 32, textAlign: 'center' }}>
          <Spin />
        </div>
      ) : versions.length === 0 ? (
        <Empty description="这个模板还没有任何版本" />
      ) : (
        <>
          <Alert
            type="info"
            showIcon
            style={{ marginBottom: 10 }}
            message="发布即冻结"
            description="发布后的版本只读；要改文案请新建版本再发布。智能体版本需重新指向新版本才会用上。"
          />
          <ResizableTable
            resizeKey="ai-prompt-version"
            rowKey="id"
            columns={columns}
            dataSource={versions}
            pagination={false}
            size="small"
          />
        </>
      )}

      <Modal
        title={modal.row ? `编辑 v${modal.row.versionNo} 草稿` : '新建提示词版本'}
        open={modal.open}
        onCancel={() => setModal({ open: false })}
        onOk={() => void submit()}
        confirmLoading={saving}
        okText="保存"
        cancelText="取消"
        width={800}
        destroyOnClose
        styles={{ body: { maxHeight: '62vh', overflowY: 'auto' } }}
      >
        <Form form={form} layout="vertical">
          <Form.Item
            name="systemPrompt"
            label="系统提示词"
            rules={[{ required: true, message: '请输入提示词' }]}
            extra="模型每次请求都会带上它。写清楚身份、边界、输出格式要求"
          >
            <Input.TextArea rows={14} placeholder="你是一个……" />
          </Form.Item>
          <Form.Item
            name="userPromptTemplate"
            label="用户提示词模板"
            extra="可选，支持 {{变量}} 占位"
          >
            <Input.TextArea rows={4} />
          </Form.Item>
          <Form.Item name="variablesJson" label="变量定义（JSON）">
            <Input.TextArea rows={3} placeholder='{"userName":"当前用户昵称"}' />
          </Form.Item>
          <Form.Item name="changelog" label="变更说明">
            <Input placeholder="例如：补充退款流程说明" maxLength={512} />
          </Form.Item>
        </Form>
      </Modal>
    </Modal>
  );
}
