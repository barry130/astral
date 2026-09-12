'use client';

import { useEffect, useState } from 'react';
import { Card, Button, Space, Modal, Form, Input, InputNumber, Select, Tag, message, Popconfirm } from 'antd';
import { PlusOutlined, EditOutlined, DeleteOutlined, SendOutlined } from '@ant-design/icons';
import { mailApi, MailAccount } from '@/api/mail';
import { usePerm, MAIL_PERMISSIONS } from '@/lib/perm';
import { ResizableTable } from '@/components/ResizableTable';

const yesNo = (v: number) => (
  <Tag color={v === 1 ? 'green' : 'default'}>{v === 1 ? '启用' : '停用'}</Tag>
);

export default function MailAccountPage() {
  const [loading, setLoading] = useState(false);
  const [data, setData] = useState<MailAccount[]>([]);
  const [pagination, setPagination] = useState({ current: 1, pageSize: 10, total: 0 });
  const [modalVisible, setModalVisible] = useState(false);
  const [editing, setEditing] = useState<MailAccount | null>(null);
  const [form] = Form.useForm();
  const [testId, setTestId] = useState<number | null>(null);
  const [testVisible, setTestVisible] = useState(false);
  const [testForm] = Form.useForm();
  const hasPerm = usePerm();
  /** 是否具备邮箱账户维护权限（无权限时隐藏维护按钮） */
  const canEdit = hasPerm(MAIL_PERMISSIONS.accountEdit);

  useEffect(() => { loadData(); }, []);

  const loadData = (page = 1, size = 10) => {
    setLoading(true);
    mailApi.accountPage(page, size)
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
    form.setFieldsValue({ smtpPort: 465, sslEnable: 1, starttlsEnable: 0, enabled: 1, weight: 1 });
    setModalVisible(true);
  };

  const handleEdit = (r: MailAccount) => {
    setEditing(r);
    form.setFieldsValue(r);
    setModalVisible(true);
  };

  const handleDelete = async (id?: number) => {
    if (!id) return;
    try { await mailApi.accountDelete(id); message.success('删除成功'); loadData(); }
    catch (e: any) { message.error(e.message); }
  };

  const handleToggle = async (r: MailAccount) => {
    try { await mailApi.accountToggle(r.id!, r.enabled === 1 ? 0 : 1); message.success('已更新'); loadData(); }
    catch (e: any) { message.error(e.message); }
  };

  const handleTest = (r: MailAccount) => {
    setTestId(r.id!);
    testForm.resetFields();
    setTestVisible(true);
  };

  const submitTest = async () => {
    const v = await testForm.validateFields();
    try { await mailApi.accountTest(testId!, v.toEmail); message.success('测试邮件已发送，请查收'); setTestVisible(false); }
    catch (e: any) { message.error(e.message); }
  };

  const handleSubmit = async () => {
    const values = await form.validateFields();
    try {
      if (editing?.id) await mailApi.accountUpdate(editing.id, values);
      else await mailApi.accountCreate(values);
      message.success('保存成功');
      setModalVisible(false);
      loadData();
    } catch (e: any) { message.error(e.message); }
  };

  const columns = [
    { title: '账户名称', dataIndex: 'accountName', key: 'accountName' },
    {
      title: 'SMTP', key: 'smtp',
      render: (_: any, r: MailAccount) => <span>{r.smtpHost}:{r.smtpPort}{r.sslEnable === 1 ? ' (SSL)' : ''}</span>,
    },
    { title: '登录账号', dataIndex: 'username', key: 'username' },
    { title: '发件人', key: 'from', render: (_: any, r: MailAccount) => <span>{r.fromName} &lt;{r.fromAddr}&gt;</span> },
    { title: '密码', key: 'pwd', render: () => <span>••••••••</span> },
    { title: '权重', dataIndex: 'weight', key: 'weight' },
    { title: '状态', dataIndex: 'enabled', key: 'enabled', render: (v: number) => yesNo(v) },
    {
      title: '操作', key: 'action', width: 160,
      render: (_: any, r: MailAccount) => (
        <Space>
          {canEdit && <Button type="link" icon={<SendOutlined />} onClick={() => handleTest(r)}>测试</Button>}
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
          <span style={{ fontWeight: 600, fontSize: 16 }}>邮箱账户</span>
          <div className="page-toolbar">
            {canEdit && <Button type="primary" icon={<PlusOutlined />} onClick={handleCreate}>新建账户</Button>}
          </div>
        </div>
        <ResizableTable dataSource={data} columns={columns} rowKey="id" loading={loading} scroll={{ x: 'max-content' }} pagination={{ ...pagination, showQuickJumper: true, showSizeChanger: true, pageSizeOptions: ['5', '10', '20', '50', '100'] }}
          onChange={(p) => loadData(p.current, p.pageSize)} />
      </Card>

      <Modal title={editing ? '编辑账户' : '新建账户'} open={modalVisible}
        onOk={handleSubmit} onCancel={() => setModalVisible(false)} destroyOnClose>
        <Form form={form} layout="vertical" style={{ marginTop: 16 }}>
          <Form.Item name="accountName" label="账户名称" rules={[{ required: true }]}><Input placeholder="如：轻听官方邮箱" /></Form.Item>
          <Space style={{ display: 'flex' }}>
            <Form.Item name="smtpHost" label="SMTP服务器" rules={[{ required: true }]} style={{ flex: 1 }}>
              <Input placeholder="smtp.exmail.qq.com" />
            </Form.Item>
            <Form.Item name="smtpPort" label="端口" rules={[{ required: true }]}>
              <InputNumber min={1} max={65535} style={{ width: 100 }} />
            </Form.Item>
          </Space>
          <Form.Item name="username" label="登录账号" rules={[{ required: true }]}><Input /></Form.Item>
          <Form.Item name="password" label="密码/授权码" rules={[{ required: true }]}><Input.Password /></Form.Item>
          <Space style={{ display: 'flex' }}>
            <Form.Item name="fromAddr" label="发件地址" rules={[{ required: true }]} style={{ flex: 1 }}><Input placeholder="noreply@example.com" /></Form.Item>
            <Form.Item name="fromName" label="发件显示名"><Input placeholder="轻听APP" /></Form.Item>
          </Space>
          <Space style={{ display: 'flex' }}>
            <Form.Item name="sslEnable" label="SSL" rules={[{ required: true }]}>
              <Select options={[{ label: '启用', value: 1 }, { label: '停用', value: 0 }]} style={{ width: 120 }} />
            </Form.Item>
            <Form.Item name="starttlsEnable" label="STARTTLS">
              <Select options={[{ label: '启用', value: 1 }, { label: '停用', value: 0 }]} style={{ width: 120 }} />
            </Form.Item>
            <Form.Item name="enabled" label="状态">
              <Select options={[{ label: '启用', value: 1 }, { label: '停用', value: 0 }]} style={{ width: 120 }} />
            </Form.Item>
            <Form.Item name="weight" label="权重"><InputNumber min={1} max={100} /></Form.Item>
          </Space>
          <Form.Item name="remark" label="备注"><Input.TextArea rows={2} /></Form.Item>
        </Form>
      </Modal>

      <Modal title="测试发送" open={testVisible} onOk={submitTest} onCancel={() => setTestVisible(false)} destroyOnClose>
        <Form form={testForm} layout="vertical" style={{ marginTop: 16 }}>
          <Form.Item name="toEmail" label="接收邮箱" rules={[{ required: true, type: 'email' }]}>
            <Input placeholder="test@example.com" />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
