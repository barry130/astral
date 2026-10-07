'use client';

import { useEffect, useState } from 'react';
import { Card, Button, Space, Modal, Form, Input, InputNumber, Select, Tag, message, Popconfirm, Switch } from '@/components/antd-compat';
import { PlusOutlined, EditOutlined, DeleteOutlined } from '@/components/antd-compat/icons';
import { smsApi, SmsProvider } from '@/api/sms';
import { usePerm } from '@/lib/perm';
import { ResizableTable } from '@/components/ResizableTable';

export default function SmsProviderPage() {
  const [loading, setLoading] = useState(false);
  const [data, setData] = useState<SmsProvider[]>([]);
  const [pagination, setPagination] = useState({ current: 1, pageSize: 10, total: 0 });
  const [modalVisible, setModalVisible] = useState(false);
  const [editing, setEditing] = useState<SmsProvider | null>(null);
  const [form] = Form.useForm();

  /** 后端 SPI 已注册的供应商类型（MOCK/ALIYUN…） */
  const [types, setTypes] = useState<string[]>([]);

  const hasPerm = usePerm();
  const canEdit = hasPerm('admin:system:notify:sms:edit');

  useEffect(() => { loadData(); loadTypes(); }, []);

  const loadData = (page = 1, size = 10) => {
    setLoading(true);
    smsApi.providerPage(page, size)
      .then((res: any) => {
        if (res.code === 200) {
          setData(res.data?.records || []);
          setPagination({ current: res.data?.current || 1, pageSize: res.data?.size || 10, total: res.data?.total || 0 });
        }
      })
      .finally(() => setLoading(false));
  };

  const loadTypes = () => {
    smsApi.providerTypes()
      .then((res: any) => { if (res.code === 200) setTypes(res.data || []); })
      .catch(() => { /* 类型加载失败不阻塞页面 */ });
  };

  const handleCreate = () => {
    setEditing(null);
    form.resetFields();
    form.setFieldsValue({ enabled: 1, weight: 1 });
    setModalVisible(true);
  };

  const handleEdit = (r: SmsProvider) => {
    setEditing(r);
    // accessSecret 后端永远不回传；编辑留空 = 不修改原密钥
    form.setFieldsValue({ ...r, accessSecret: undefined });
    setModalVisible(true);
  };

  const handleDelete = async (id?: number) => {
    if (!id) return;
    try { await smsApi.providerDelete(id); message.success('删除成功'); loadData(); }
    catch (e: any) { message.error(e.message); }
  };

  const handleToggle = async (r: SmsProvider, enabled: number) => {
    try { await smsApi.providerUpdate(r.id!, { enabled }); loadData(); }
    catch (e: any) { message.error(e.message); }
  };

  const handleSubmit = async () => {
    const values = await form.validateFields();
    try {
      if (editing?.id) await smsApi.providerUpdate(editing.id, values);
      else await smsApi.providerCreate(values);
      message.success('保存成功');
      setModalVisible(false);
      loadData();
    } catch (e: any) { message.error(e.message); }
  };

  const columns = [
    { title: '供应商名称', dataIndex: 'providerName', key: 'providerName' },
    { title: '类型', dataIndex: 'providerType', key: 'providerType', render: (v: string) => <Tag color={v === 'MOCK' ? 'default' : 'blue'}>{v}</Tag> },
    { title: '签名', dataIndex: 'signName', key: 'signName', render: (v: string) => v || '-' },
    { title: 'AccessKey', dataIndex: 'accessKey', key: 'accessKey', render: (v: string) => v ? <code>{v}</code> : '-' },
    {
      title: '启用', dataIndex: 'enabled', key: 'enabled', width: 80,
      render: (v: number, r: SmsProvider) => (
        <Switch size="small" checked={v === 1} disabled={!canEdit} onChange={(c) => handleToggle(r, c ? 1 : 0)} />
      ),
    },
    { title: '权重', dataIndex: 'weight', key: 'weight', width: 70 },
    { title: '备注', dataIndex: 'remark', key: 'remark', ellipsis: true },
    {
      title: '操作', key: 'action', width: 150,
      render: (_: any, r: SmsProvider) => (
        <Space>
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
        <div style={{ marginBottom: 16, display: 'flex', flexWrap: 'wrap', justifyContent: 'space-between', alignItems: 'center', gap: 8 }}>
          <span style={{ fontWeight: 600, fontSize: 16 }}>
            短信供应商
            <span style={{ marginLeft: 12, fontWeight: 400, fontSize: 12, color: '#999' }}>
              按权重轮选发送，失败自动换下一个；密钥保存后不再回传
            </span>
          </span>
          <div className="page-toolbar">
            {canEdit && <Button type="primary" icon={<PlusOutlined />} onClick={handleCreate}>新增供应商</Button>}
          </div>
        </div>
        <ResizableTable dataSource={data} columns={columns} rowKey="id" loading={loading} scroll={{ x: 'max-content' }} pagination={{ ...pagination, showQuickJumper: true, showSizeChanger: true, pageSizeOptions: ['5', '10', '20', '50', '100'] }}
          onChange={(p) => loadData(p.current, p.pageSize)} />
      </Card>

      <Modal title={editing ? '编辑供应商' : '新增供应商'} open={modalVisible}
        onOk={handleSubmit} onCancel={() => setModalVisible(false)} width={640} destroyOnClose>
        <Form form={form} layout="vertical" style={{ marginTop: 16 }}>
          <Form.Item name="providerName" label="供应商名称" rules={[{ required: true }]}><Input /></Form.Item>
          <Form.Item name="providerType" label="类型" tooltip="由后端 SPI 注册（MOCK 本地测试 / ALIYUN 阿里云短信）" rules={[{ required: true }]}>
            <Select options={types.map((t) => ({ label: t, value: t }))} placeholder="选择供应商类型" />
          </Form.Item>
          <Form.Item name="accessKey" label="AccessKey ID"><Input placeholder="阿里云 AccessKey ID" /></Form.Item>
          <Form.Item
            name="accessSecret" label="AccessKey Secret"
            tooltip={editing ? '留空 = 不修改已保存的密钥' : '相当于该短信账户的调用凭据，保存后不再回传'}
            extra={editing ? '留空保持原密钥不变' : undefined}
          >
            <Input.Password placeholder={editing ? '不修改请留空' : '阿里云 AccessKey Secret'} autoComplete="new-password" />
          </Form.Item>
          <Form.Item name="signName" label="短信签名" tooltip="模板未单独配签名时的兜底签名（阿里云需已备案）">
            <Input placeholder="如：轻听音乐" />
          </Form.Item>
          <Form.Item name="region" label="地域"><Input placeholder="cn-hangzhou" /></Form.Item>
          <Form.Item name="endpoint" label="API 地址" tooltip="留空用默认 dysmsapi.aliyuncs.com，可覆盖便于代理/调试">
            <Input placeholder="dysmsapi.aliyuncs.com" />
          </Form.Item>
          <Space size="large">
            <Form.Item name="enabled" label="启用" valuePropName="value">
              <Select style={{ width: 100 }} options={[{ label: '启用', value: 1 }, { label: '停用', value: 0 }]} />
            </Form.Item>
            <Form.Item name="weight" label="权重" tooltip="越大被选中概率越高；失败自动降级到其他启用供应商">
              <InputNumber min={1} max={100} style={{ width: 100 }} />
            </Form.Item>
          </Space>
          <Form.Item name="remark" label="备注"><Input.TextArea rows={2} /></Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
