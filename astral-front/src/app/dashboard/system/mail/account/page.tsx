'use client';

import { useEffect, useState } from 'react';
import {
  Card,
  Button,
  Space,
  Modal,
  Form,
  Input,
  InputNumber,
  Select,
  Tag,
  Tooltip,
  message,
  Popconfirm,
} from '@/components/antd-compat';
import {
  PlusOutlined,
  EditOutlined,
  DeleteOutlined,
  SendOutlined,
} from '@/components/antd-compat/icons';
import { mailApi, MailAccount } from '@/api/mail';
import { fetchDictOptions, dictLabel, dictColor, DictOption } from '@/api/dict';
import { usePerm, MAIL_PERMISSIONS } from '@/lib/perm';
import { ResizableTable } from '@/components/ResizableTable';
import { FilterBar } from '@/components/filter-bar';

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
  /** 启用状态枚举一律取数据字典 */
  const [dicts, setDicts] = useState<Record<string, DictOption[]>>({});
  const enableDict = dicts.enable_status;
  const hasPerm = usePerm();
  /** 是否具备邮箱账户维护权限（无权限时隐藏维护按钮） */
  const canEdit = hasPerm(MAIL_PERMISSIONS.accountEdit);

  useEffect(() => {
    loadData();
    loadDicts();
  }, []);

  /** 加载数据字典（失败时回退显示原始值） */
  const loadDicts = () => {
    fetchDictOptions(['enable_status'])
      .then(setDicts)
      .catch(() => {
        /* 字典失败时回退显示原始值 */
      });
  };

  /** 启用/停用标签：文案与颜色取字典 enable_status */
  const yesNo = (v: number) => (
    <Tag color={dictColor(enableDict, v) || 'default'}>{dictLabel(enableDict, v, String(v))}</Tag>
  );

  const loadData = (page = 1, size = 10) => {
    setLoading(true);
    mailApi
      .accountPage(page, size)
      .then((res) => {
        if (res.code === 200) {
          setData(res.data?.records || []);
          setPagination({
            current: res.data?.current || 1,
            pageSize: res.data?.size || 10,
            total: res.data?.total || 0,
          });
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
    // 列表/详情接口不回传授权码（password 恒为 undefined），编辑时留空即表示不修改
    form.setFieldsValue({ ...r, password: undefined });
    setModalVisible(true);
  };

  const handleDelete = async (id?: number) => {
    if (!id) return;
    try {
      await mailApi.accountDelete(id);
      message.success('删除成功');
      loadData();
    } catch (e: unknown) {
      message.error((e as Error).message);
    }
  };

  const handleTest = (r: MailAccount) => {
    setTestId(r.id!);
    testForm.resetFields();
    setTestVisible(true);
  };

  const submitTest = async () => {
    try {
      const v = await testForm.validateFields();
      await mailApi.accountTest(testId!, v.toEmail);
      message.success('测试邮件已发送，请查收');
      setTestVisible(false);
    } catch (e: unknown) {
      // 校验失败抛的是 { errorFields } 不是 Error，取不到 message，兜底一句中文提示（与 storage 页同款）
      message.error((e as Error)?.message || '请检查表单填写是否完整');
    }
  };

  const handleSubmit = async () => {
    try {
      const values = await form.validateFields();
      if (editing?.id) {
        // 留空 = 不修改授权码：必须把该字段整个摘掉，否则会以空串覆盖掉已保存的授权码
        // （后端 updateById 只忽略 null，不忽略空串）
        const payload = { ...values };
        if (!payload.password) delete payload.password;
        await mailApi.accountUpdate(editing.id, payload);
      } else {
        await mailApi.accountCreate(values);
      }
      message.success('保存成功');
      setModalVisible(false);
      loadData();
    } catch (e: unknown) {
      message.error((e as Error).message);
    }
  };

  const columns = [
    { title: '账户名称', dataIndex: 'accountName', key: 'accountName', width: 130, ellipsis: true },
    {
      title: 'SMTP',
      key: 'smtp',
      width: 200,
      render: (_: unknown, r: MailAccount) => (
        <span title={`${r.smtpHost}:${r.smtpPort}`}>
          {r.smtpHost}:{r.smtpPort}
          {r.sslEnable === 1 ? ' (SSL)' : ''}
        </span>
      ),
    },
    { title: '登录账号', dataIndex: 'username', key: 'username', width: 160, ellipsis: true },
    {
      title: '发件人',
      key: 'from',
      width: 170,
      ellipsis: true,
      render: (_: unknown, r: MailAccount) => (
        <span title={`${r.fromName} <${r.fromAddr}>`}>
          {r.fromName} &lt;{r.fromAddr}&gt;
        </span>
      ),
    },
    { title: '密码', key: 'pwd', width: 80, render: () => <span>••••••••</span> },
    { title: '权重', dataIndex: 'weight', key: 'weight', width: 60 },
    {
      title: '状态',
      dataIndex: 'enabled',
      key: 'enabled',
      width: 70,
      render: (v: number) => yesNo(v),
    },
    {
      // 操作收成图标按钮：三枚文字链接会把表格顶出卡片右边（编辑/删除被裁掉）
      title: '操作',
      key: 'action',
      width: 132,
      render: (_: unknown, r: MailAccount) => (
        <Space size={2}>
          {canEdit && (
            <Tooltip title="发送测试邮件">
              <Button
                type="text"
                size="small"
                icon={<SendOutlined />}
                onClick={() => handleTest(r)}
              />
            </Tooltip>
          )}
          {canEdit && (
            <Tooltip title="编辑">
              <Button
                type="text"
                size="small"
                icon={<EditOutlined />}
                onClick={() => handleEdit(r)}
              />
            </Tooltip>
          )}
          {canEdit && (
            <Popconfirm title="确认删除?" onConfirm={() => handleDelete(r.id)}>
              <Tooltip title="删除">
                <Button type="text" size="small" danger icon={<DeleteOutlined />} />
              </Tooltip>
            </Popconfirm>
          )}
          {!canEdit && <span style={{ color: '#999' }}>无权限</span>}
        </Space>
      ),
    },
  ];

  return (
    <div className="space-y-4">
      <Card>
        <FilterBar split>
          <span style={{ fontWeight: 600, fontSize: 16 }}>邮箱账户</span>
          <div className="page-toolbar">
            {canEdit && (
              <Button type="primary" icon={<PlusOutlined />} onClick={handleCreate}>
                新建账户
              </Button>
            )}
          </div>
        </FilterBar>
        <ResizableTable
          dataSource={data}
          columns={columns}
          rowKey="id"
          loading={loading}
          scroll={{ x: 'max-content' }}
          pagination={{
            ...pagination,
            showQuickJumper: true,
            showSizeChanger: true,
            pageSizeOptions: ['5', '10', '20', '50', '100'],
          }}
          onChange={(p) => loadData(p.current, p.pageSize)}
        />
      </Card>

      <Modal
        title={editing ? '编辑账户' : '新建账户'}
        open={modalVisible}
        onOk={handleSubmit}
        onCancel={() => setModalVisible(false)}
        destroyOnClose
      >
        <Form form={form} layout="vertical" style={{ marginTop: 16 }}>
          <Form.Item name="accountName" label="账户名称" rules={[{ required: true }]}>
            <Input placeholder="如：轻听官方邮箱" />
          </Form.Item>
          <Space wrap style={{ display: 'flex' }}>
            <Form.Item
              name="smtpHost"
              label="SMTP服务器"
              rules={[{ required: true }]}
              style={{ flex: 1 }}
            >
              <Input placeholder="smtp.exmail.qq.com" />
            </Form.Item>
            <Form.Item name="smtpPort" label="端口" rules={[{ required: true }]}>
              <InputNumber min={1} max={65535} style={{ width: 100 }} />
            </Form.Item>
          </Space>
          <Form.Item name="username" label="登录账号" rules={[{ required: true }]}>
            <Input />
          </Form.Item>
          <Form.Item
            name="password"
            label="密码/授权码"
            rules={editing ? [] : [{ required: true }]}
            extra={editing ? '留空表示不修改' : undefined}
          >
            <Input.Password placeholder={editing ? '留空表示不修改' : undefined} />
          </Form.Item>
          <Space wrap style={{ display: 'flex' }}>
            <Form.Item
              name="fromAddr"
              label="发件地址"
              rules={[{ required: true }]}
              style={{ flex: 1 }}
            >
              <Input placeholder="noreply@example.com" />
            </Form.Item>
            <Form.Item name="fromName" label="发件显示名">
              <Input placeholder="轻听APP" />
            </Form.Item>
          </Space>
          <Space wrap style={{ display: 'flex' }}>
            <Form.Item name="sslEnable" label="SSL" rules={[{ required: true }]}>
              <Select
                options={(enableDict || []).map((o) => ({
                  label: o.label,
                  value: Number(o.value),
                }))}
                style={{ width: 120 }}
              />
            </Form.Item>
            <Form.Item name="starttlsEnable" label="STARTTLS">
              <Select
                options={(enableDict || []).map((o) => ({
                  label: o.label,
                  value: Number(o.value),
                }))}
                style={{ width: 120 }}
              />
            </Form.Item>
            <Form.Item name="enabled" label="状态">
              <Select
                options={(enableDict || []).map((o) => ({
                  label: o.label,
                  value: Number(o.value),
                }))}
                style={{ width: 120 }}
              />
            </Form.Item>
            <Form.Item name="weight" label="权重">
              <InputNumber min={1} max={100} />
            </Form.Item>
          </Space>
          <Form.Item name="remark" label="备注">
            <Input.TextArea rows={2} />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title="测试发送"
        open={testVisible}
        onOk={submitTest}
        onCancel={() => setTestVisible(false)}
        destroyOnClose
      >
        <Form form={testForm} layout="vertical" style={{ marginTop: 16 }}>
          <Form.Item name="toEmail" label="接收邮箱" rules={[{ required: true, type: 'email' }]}>
            <Input placeholder="test@example.com" />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
