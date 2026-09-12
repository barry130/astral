'use client';

import { useEffect, useState } from 'react';
import { Card, Button, Space, Modal, Form, Input, Tag, message, Popconfirm } from 'antd';
import { PlusOutlined, EditOutlined, DeleteOutlined, EyeOutlined } from '@ant-design/icons';
import { mailApi, MailTemplate } from '@/api/mail';
import { usePerm, MAIL_PERMISSIONS } from '@/lib/perm';
import { ResizableTable } from '@/components/ResizableTable';

export default function MailTemplatePage() {
  const [loading, setLoading] = useState(false);
  const [data, setData] = useState<MailTemplate[]>([]);
  const [pagination, setPagination] = useState({ current: 1, pageSize: 10, total: 0 });
  const [modalVisible, setModalVisible] = useState(false);
  const [editing, setEditing] = useState<MailTemplate | null>(null);
  const [form] = Form.useForm();

  const [previewVisible, setPreviewVisible] = useState(false);
  const [previewHtml, setPreviewHtml] = useState('');
  const hasPerm = usePerm();
  /** 是否具备邮箱模板维护权限（无权限时隐藏维护按钮；预览为只读不受限） */
  const canEdit = hasPerm(MAIL_PERMISSIONS.templateEdit);

  useEffect(() => { loadData(); }, []);

  const loadData = (page = 1, size = 10) => {
    setLoading(true);
    mailApi.templatePage(page, size)
      .then((res: any) => {
        if (res.code === 200) {
          setData(res.data?.records || []);
          setPagination({ current: res.data?.current || 1, pageSize: res.data?.size || 10, total: res.data?.total || 0 });
        }
      })
      .finally(() => setLoading(false));
  };

  const handleCreate = () => {
    setEditing(null);
    form.resetFields();
    setModalVisible(true);
  };

  const handleEdit = (r: MailTemplate) => {
    setEditing(r);
    form.setFieldsValue(r);
    setModalVisible(true);
  };

  const handleDelete = async (id?: number) => {
    if (!id) return;
    try { await mailApi.templateDelete(id); message.success('删除成功'); loadData(); }
    catch (e: any) { message.error(e.message); }
  };

  const handlePreview = async (r: MailTemplate) => {
    try {
      const vars: Record<string, string> = {};
      try {
        const arr = JSON.parse(r.variables || '[]');
        if (Array.isArray(arr)) arr.forEach((k: string) => (vars[k] = '123456'));
      } catch { /* ignore */ }
      const res: any = await mailApi.templatePreview(r.id!, vars);
      if (res.code === 200) {
        setPreviewHtml(res.data || '');
        setPreviewVisible(true);
      }
    } catch (e: any) { message.error(e.message); }
  };

  const handleSubmit = async () => {
    const values = await form.validateFields();
    try {
      if (editing?.id) await mailApi.templateUpdate(editing.id, values);
      else await mailApi.templateCreate(values);
      message.success('保存成功');
      setModalVisible(false);
      loadData();
    } catch (e: any) { message.error(e.message); }
  };

  const columns = [
    { title: '模板编码', dataIndex: 'templateCode', key: 'templateCode', render: (v: string) => <code>{v}</code> },
    { title: '模板名称', dataIndex: 'templateName', key: 'templateName' },
    { title: '主题', dataIndex: 'subject', key: 'subject' },
    { title: '场景', dataIndex: 'scene', key: 'scene', render: (v: string) => v ? <Tag color="blue">{v}</Tag> : '-' },
    { title: '变量', dataIndex: 'variables', key: 'variables', render: (v: string) => v ? <code>{v}</code> : '-' },
    {
      title: '操作', key: 'action', width: 160,
      render: (_: any, r: MailTemplate) => (
        <Space>
          <Button type="link" icon={<EyeOutlined />} onClick={() => handlePreview(r)}>预览</Button>
          {canEdit && <Button type="link" icon={<EditOutlined />} onClick={() => handleEdit(r)}>编辑</Button>}
          {canEdit && (
            <Popconfirm title="确认删除?" onConfirm={() => handleDelete(r.id)}>
              <Button type="link" danger icon={<DeleteOutlined />}>删除</Button>
            </Popconfirm>
          )}
          {!canEdit && <span style={{ color: '#999' }}>无操作权限</span>}
        </Space>
      ),
    },
  ];

  return (
    <div>
      <Card>
        <div className="filter-bar" style={{ marginBottom: 16, display: 'flex', justifyContent: 'space-between' }}>
          <span style={{ fontWeight: 600, fontSize: 16 }}>邮件模板</span>
          <div className="page-toolbar">
            {canEdit && <Button type="primary" icon={<PlusOutlined />} onClick={handleCreate}>新建模板</Button>}
          </div>
        </div>
        <ResizableTable dataSource={data} columns={columns} rowKey="id" loading={loading} scroll={{ x: 'max-content' }} pagination={{ ...pagination, showQuickJumper: true, showSizeChanger: true, pageSizeOptions: ['5', '10', '20', '50', '100'] }}
          onChange={(p) => loadData(p.current, p.pageSize)} />
      </Card>

      <Modal title={editing ? '编辑模板' : '新建模板'} open={modalVisible}
        onOk={handleSubmit} onCancel={() => setModalVisible(false)} width={720} destroyOnClose>
        <Form form={form} layout="vertical" style={{ marginTop: 16 }}>
          <Form.Item name="templateCode" label="模板编码(场景标识)" rules={[{ required: true }]}>
            <Input placeholder="如：changePasswordByEmail" />
          </Form.Item>
          <Form.Item name="templateName" label="模板名称" rules={[{ required: true }]}><Input /></Form.Item>
          <Form.Item name="subject" label="邮件主题" rules={[{ required: true }]}><Input /></Form.Item>
          <Form.Item name="scene" label="适用场景"><Input placeholder="如：重置密码/获取验证码" /></Form.Item>
          <Form.Item name="content" label="邮件正文(支持 ${变量} 占位)" rules={[{ required: true }]}>
            <Input.TextArea rows={8} placeholder={'您好，您的验证码为 ${code}'} />
          </Form.Item>
          <Form.Item name="variables" label="变量列表(JSON数组)" tooltip='如 ["code"]'>
            <Input placeholder='["code"]' />
          </Form.Item>
          <Form.Item name="remark" label="备注"><Input.TextArea rows={2} /></Form.Item>
        </Form>
      </Modal>

      <Modal title="模板预览" open={previewVisible} footer={null} width={520} onCancel={() => setPreviewVisible(false)}>
        <div style={{ border: '1px solid #eee', borderRadius: 8, overflow: 'hidden' }}
          dangerouslySetInnerHTML={{ __html: previewHtml }} />
      </Modal>
    </div>
  );
}
