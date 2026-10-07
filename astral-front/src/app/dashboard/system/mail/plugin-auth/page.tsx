'use client';

import { useEffect, useState } from 'react';
import { Card, Button, Space, Modal, Form, Input, InputNumber, Tag, message, Popconfirm, Switch, Select } from '@/components/antd-compat';
import { EditOutlined, DeleteOutlined, PlusOutlined } from '@/components/antd-compat/icons';
import { mailApi, MailPluginAuth, NotifyEventDef } from '@/api/mail';
import { usePerm, MAIL_PERMISSIONS } from '@/lib/perm';
import { ResizableTable } from '@/components/ResizableTable';

export default function MailPluginAuthPage() {
  const [loading, setLoading] = useState(false);
  const [data, setData] = useState<MailPluginAuth[]>([]);
  const [modalVisible, setModalVisible] = useState(false);
  const [editing, setEditing] = useState<MailPluginAuth | null>(null);
  const [form] = Form.useForm();
  /** 事件注册表（代码注册，后端 /notify/event/list 只读下发） */
  const [events, setEvents] = useState<NotifyEventDef[]>([]);
  const hasPerm = usePerm();
  /** 是否具备发信授权维护权限（无权限时隐藏维护按钮） */
  const canEdit = hasPerm(MAIL_PERMISSIONS.pluginAuthEdit);

  useEffect(() => { loadData(); loadEvents(); }, []);

  const loadData = () => {
    setLoading(true);
    mailApi.pluginAuthList()
      .then((res: any) => { if (res.code === 200) setData(res.data || []); })
      .finally(() => setLoading(false));
  };

  const loadEvents = () => {
    mailApi.notifyEventList()
      .then((res: any) => { if (res.code === 200) setEvents(res.data || []); })
      .catch(() => { /* 事件列表加载失败不阻塞页面 */ });
  };

  const sceneOptions = events.map((s) => ({ label: `${s.name}（${s.code}）`, value: s.code }));
  const sceneLabelOf = (code: string) => {
    const def = events.find((s) => s.code === code);
    return def ? `${def.name}（${code}）` : code;
  };

  const handleCreate = () => {
    setEditing(null);
    form.resetFields();
    form.setFieldsValue({ dailyLimit: 5, enabled: 1, allowedScenes: [] });
    setModalVisible(true);
  };

  const handleEdit = (r: MailPluginAuth) => {
    setEditing(r);
    // allowed_scenes 存量是逗号分隔字符串，多选控件需要数组
    form.setFieldsValue({ ...r, allowedScenes: r.allowedScenes ? r.allowedScenes.split(',').map((s) => s.trim()).filter(Boolean) : [] });
    setModalVisible(true);
  };

  const handleDelete = async (id?: number) => {
    if (!id) return;
    try { await mailApi.pluginAuthDelete(id); message.success('删除成功'); loadData(); }
    catch (e: any) { message.error(e.message); }
  };

  const handleSubmit = async () => {
    const values = await form.validateFields();
    values.enabled = values.enabled ? 1 : 0;
    // 存储格式与存量一致：逗号分隔；空数组 = 不限场景
    values.allowedScenes = (values.allowedScenes || []).join(',');
    try {
      if (editing?.id) await mailApi.pluginAuthUpdate(editing.id, values);
      else await mailApi.pluginAuthCreate(values);
      message.success('保存成功');
      setModalVisible(false);
      loadData();
    } catch (e: any) { message.error(e.message); }
  };

  const columns = [
    { title: '插件ID', dataIndex: 'pluginId', key: 'pluginId', render: (v: string) => <code>{v}</code> },
    { title: '插件名称', dataIndex: 'pluginName', key: 'pluginName' },
    { title: '每日上限', dataIndex: 'dailyLimit', key: 'dailyLimit', render: (v: number) => v > 0 ? v : '不限制' },
    {
      title: '允许场景', dataIndex: 'allowedScenes', key: 'allowedScenes',
      render: (v?: string) => v
        ? v.split(',').map((s) => s.trim()).filter(Boolean).map((s) => <Tag key={s} color="blue">{sceneLabelOf(s)}</Tag>)
        : <Tag color="red">未配置（禁止发信）</Tag>,
    },
    { title: '状态', dataIndex: 'enabled', key: 'enabled', render: (v: number) => <Tag color={v === 1 ? 'green' : 'default'}>{v === 1 ? '启用' : '停用'}</Tag> },
    {
      title: '操作', key: 'action', width: 160,
      render: (_: any, r: MailPluginAuth) => (
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
        <div className="filter-bar" style={{ marginBottom: 16, display: 'flex', justifyContent: 'space-between' }}>
          <span style={{ fontWeight: 600, fontSize: 16 }}>插件发信授权</span>
          <div className="page-toolbar">
            {canEdit && <Button type="primary" icon={<PlusOutlined />} onClick={handleCreate}>新建授权</Button>}
          </div>
        </div>
        <ResizableTable dataSource={data} columns={columns} rowKey="id" loading={loading} scroll={{ x: 'max-content' }} pagination={false} />
      </Card>

      <Modal title={editing ? '编辑授权' : '新建授权'} open={modalVisible}
        onOk={handleSubmit} onCancel={() => setModalVisible(false)} destroyOnClose>
        <Form form={form} layout="vertical" style={{ marginTop: 16 }}>
          <Form.Item name="pluginId" label="插件ID" rules={[{ required: true }]}>
            <Input disabled={!!editing} placeholder="如：qt" />
          </Form.Item>
          <Form.Item name="pluginName" label="插件名称"><Input placeholder="如：轻听音乐" /></Form.Item>
          <Form.Item name="dailyLimit" label="每日发送上限" tooltip="0 表示不限制">
            <InputNumber min={0} max={10000} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="allowedScenes" label="允许事件" tooltip="只能从系统注册的事件里选；fail-closed：留空 = 该插件一律禁止发信">
            <Select mode="multiple" options={sceneOptions} placeholder="不选 = 禁止发信（fail-closed）" allowClear maxTagCount="responsive" />
          </Form.Item>
          <Form.Item name="enabled" label="启用" valuePropName="checked">
            <Switch checkedChildren="启用" unCheckedChildren="停用" />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
