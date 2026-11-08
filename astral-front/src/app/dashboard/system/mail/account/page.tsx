'use client';

import { useCallback, useEffect, useState } from 'react';
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
import { mailApi, mailAccountPayload, type MailAccount, type MailAccountWrite } from '@/api/mail';
import { AdminLoadNotice, AdminOptionNotice } from '@/components/admin/AdminLoadNotice';
import { useAdminLoader, useAdminMutation, useAdminQuery } from '@/components/admin/use-admin-task';
import { requireAdminSuccess } from '@/components/admin/admin-task';
import {
  loadAdminDictOptions as fetchDictOptions,
  dictLabel,
  dictColor,
  DictOption,
} from '@/components/admin/admin-dictionaries';
import { usePerm, MAIL_PERMISSIONS } from '@/lib/perm';
import { ResizableTable } from '@/components/ResizableTable';
import { FilterBar } from '@/components/filter-bar';

/** 邮箱账户页：账户 CRUD + 连接测试 */
export default function MailAccountPage() {
  const { loading, error: loadError, run: runLoad } = useAdminLoader('加载邮箱账户失败');
  const {
    loading: optionsLoading,
    error: optionsError,
    run: runOptions,
  } = useAdminLoader('加载账户选项失败');
  const { busy: saving, run: runMutation } = useAdminMutation('邮箱账户操作失败');
  const query = useAdminQuery({ current: 1, pageSize: 10 });
  const [data, setData] = useState<MailAccount[]>([]);
  const [pagination, setPagination] = useState({ current: 1, pageSize: 10, total: 0 });
  const [modalVisible, setModalVisible] = useState(false);
  const [editing, setEditing] = useState<MailAccount | null>(null);
  const [form] = Form.useForm<MailAccountWrite>();
  const [testId, setTestId] = useState<number | null>(null);
  const [testVisible, setTestVisible] = useState(false);
  const [testForm] = Form.useForm<{ toEmail: string }>();
  /** 启用状态枚举一律取数据字典 */
  const [dicts, setDicts] = useState<Record<string, DictOption[]>>({});
  const enableDict = dicts.enable_status;
  const hasPerm = usePerm();
  /** 是否具备邮箱账户维护权限（无权限时隐藏维护按钮） */
  const canEdit = hasPerm(MAIL_PERMISSIONS.accountEdit);

  const loadData = useCallback(
    (page?: number, size?: number) => {
      const q = query.read({
        ...(page === undefined ? {} : { current: page }),
        ...(size === undefined ? {} : { pageSize: size }),
      });
      return runLoad(
        async () => {
          const result = requireAdminSuccess(
            await mailApi.accountPage(q.current, q.pageSize),
            '加载邮箱账户失败',
          );
          if (!result) throw new Error('账户分页数据缺失');
          return result;
        },
        (result) => {
          setData(result.records || []);
          setPagination({
            current: result.current || q.current,
            pageSize: result.size || q.pageSize,
            total: result.total || 0,
          });
        },
      );
    },
    [query, runLoad],
  );
  const loadOptions = useCallback(
    () =>
      runOptions(async () => {
        const map = await fetchDictOptions(['enable_status']);
        if (!map.enable_status?.length) throw new Error('账户状态字典暂无可用选项');
        return map;
      }, setDicts),
    [runOptions],
  );
  useEffect(() => {
    void loadData();
    void loadOptions();
  }, [loadData, loadOptions]);

  /** 启用/停用标签：文案与颜色取字典 enable_status */
  const yesNo = (v: number) => (
    <Tag color={dictColor(enableDict, v) || 'default'}>{dictLabel(enableDict, v, String(v))}</Tag>
  );

  const handleCreate = () => {
    setEditing(null);
    form.resetFields();
    form.setFieldsValue({ smtpPort: 465, sslEnable: 1, starttlsEnable: 0, enabled: 1, weight: 1 });
    setModalVisible(true);
  };

  const handleEdit = (r: MailAccount) => {
    setEditing(r);
    // 列表/详情接口不回传授权码（password 恒为 undefined），编辑时留空即表示不修改
    form.resetFields();
    form.setFieldsValue(mailAccountPayload(r));
    setModalVisible(true);
  };

  const handleDelete = (id?: number) => {
    if (!canEdit || !id) return;
    return runMutation(
      async () => requireAdminSuccess(await mailApi.accountDelete(id), '删除失败'),
      () => {
        message.success('删除成功');
        void loadData();
      },
    );
  };
  const handleTest = (record: MailAccount) => {
    if (!canEdit || saving || !record.id) return;
    setTestId(record.id);
    testForm.resetFields();
    setTestVisible(true);
  };
  const submitTest = () => {
    if (!canEdit || !testId) return;
    const id = testId;
    return runMutation(
      async () => {
        const values = await testForm.validateFields();
        return requireAdminSuccess(
          await mailApi.accountTest(id, values.toEmail),
          '测试邮件发送失败',
        );
      },
      () => {
        message.success('测试邮件已发送，请查收');
        setTestVisible(false);
      },
    );
  };
  const handleSubmit = () => {
    if (!canEdit || optionsLoading || optionsError) return;
    const id = editing?.id;
    return runMutation(
      async () => {
        const values = await form.validateFields();
        return requireAdminSuccess(
          id ? await mailApi.accountUpdate(id, values) : await mailApi.accountCreate(values),
          '保存失败',
        );
      },
      () => {
        message.success('保存成功');
        setModalVisible(false);
        void loadData();
      },
    );
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
                disabled={saving}
                aria-label={`发送测试邮件：${r.accountName}`}
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
                disabled={saving}
                aria-label={`编辑账户：${r.accountName}`}
              />
            </Tooltip>
          )}
          {canEdit && (
            <Popconfirm title="确认删除?" disabled={saving} onConfirm={() => handleDelete(r.id)}>
              <Tooltip title="删除">
                <Button
                  type="text"
                  size="small"
                  danger
                  icon={<DeleteOutlined />}
                  disabled={saving}
                  aria-label={`删除账户：${r.accountName}`}
                />
              </Tooltip>
            </Popconfirm>
          )}
          {!canEdit && <span className="text-muted-foreground">无权限</span>}
        </Space>
      ),
    },
  ];

  return (
    <div className="space-y-4">
      <Card>
        <FilterBar
          actions={
            canEdit ? (
              <Button
                type="primary"
                icon={<PlusOutlined />}
                onClick={handleCreate}
                disabled={saving || optionsLoading || !!optionsError}
              >
                新建账户
              </Button>
            ) : null
          }
        >
          <span style={{ fontWeight: 600, fontSize: 16 }}>邮箱账户</span>
        </FilterBar>
        <AdminLoadNotice
          error={loadError}
          hasData={data.length > 0}
          onRetry={() => void loadData()}
        />
        <AdminOptionNotice error={optionsError} onRetry={() => void loadOptions()} />
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
        confirmLoading={saving}
        okButtonProps={{ disabled: optionsLoading || !!optionsError }}
        cancelButtonProps={{ disabled: saving }}
        onCancel={() => {
          if (!saving) setModalVisible(false);
        }}
        destroyOnClose
      >
        <Form form={form} disabled={saving} layout="vertical" style={{ marginTop: 16 }}>
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
            rules={
              editing ? [] : [{ required: true }, { pattern: /\S/, message: '授权码不能仅为空白' }]
            }
            extra={editing ? '留空表示不修改' : undefined}
          >
            <Input.Password placeholder={editing ? '留空表示不修改' : undefined} />
          </Form.Item>
          <Space wrap style={{ display: 'flex' }}>
            <Form.Item
              name="fromAddr"
              label="发件地址"
              rules={[{ required: true, type: 'email' }]}
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
        confirmLoading={saving}
        cancelButtonProps={{ disabled: saving }}
        onCancel={() => {
          if (!saving) setTestVisible(false);
        }}
        destroyOnClose
      >
        <Form form={testForm} disabled={saving} layout="vertical" style={{ marginTop: 16 }}>
          <Form.Item name="toEmail" label="接收邮箱" rules={[{ required: true, type: 'email' }]}>
            <Input placeholder="test@example.com" />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
