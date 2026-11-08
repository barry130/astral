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
  message,
  Popconfirm,
  Switch,
} from '@/components/antd-compat';
import { PlusOutlined, EditOutlined, DeleteOutlined } from '@/components/antd-compat/icons';
import { smsApi, smsProviderPayload, type SmsProvider, type SmsProviderWrite } from '@/api/sms';
import { AdminLoadNotice, AdminOptionNotice } from '@/components/admin/AdminLoadNotice';
import { useAdminLoader, useAdminMutation, useAdminQuery } from '@/components/admin/use-admin-task';
import { requireAdminSuccess } from '@/components/admin/admin-task';
import {
  loadAdminDictOptions as fetchDictOptions,
  DictOption,
} from '@/components/admin/admin-dictionaries';
import { usePerm } from '@/lib/perm';
import { ResizableTable } from '@/components/ResizableTable';
import { FilterBar } from '@/components/filter-bar';

export default function SmsProviderPage() {
  const { loading, error: loadError, run: runLoad } = useAdminLoader('加载短信供应商失败');
  const {
    loading: optionsLoading,
    error: optionsError,
    run: runOptions,
  } = useAdminLoader('加载供应商选项失败');
  const { busy: saving, run: runMutation } = useAdminMutation('短信供应商操作失败');
  const query = useAdminQuery({ current: 1, pageSize: 10 });
  const [data, setData] = useState<SmsProvider[]>([]);
  const [pagination, setPagination] = useState({ current: 1, pageSize: 10, total: 0 });
  const [modalVisible, setModalVisible] = useState(false);
  const [editing, setEditing] = useState<SmsProvider | null>(null);
  const [form] = Form.useForm<SmsProviderWrite>();

  /** 后端 SPI 已注册的供应商类型（MOCK/ALIYUN…） */
  const [types, setTypes] = useState<string[]>([]);

  /** 启用状态枚举一律取数据字典 */
  const [dicts, setDicts] = useState<Record<string, DictOption[]>>({});
  const enableDict = dicts.enable_status;

  const hasPerm = usePerm();
  const canEdit = hasPerm('admin:system:notify:sms:edit');

  const loadData = useCallback(
    (page?: number, size?: number) => {
      const q = query.read({
        ...(page === undefined ? {} : { current: page }),
        ...(size === undefined ? {} : { pageSize: size }),
      });
      return runLoad(
        async () => {
          const result = requireAdminSuccess(
            await smsApi.providerPage(q.current, q.pageSize),
            '加载短信供应商失败',
          );
          if (!result) throw new Error('供应商分页数据缺失');
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
      runOptions(
        async () => {
          const [response, map] = await Promise.all([
            smsApi.providerTypes(),
            fetchDictOptions(['enable_status']),
          ]);
          const values = requireAdminSuccess(response, '加载供应商类型失败') || [];
          if (!values.length || !map.enable_status?.length)
            throw new Error('供应商类型或状态暂无可用选项');
          return { values, map };
        },
        ({ values, map }) => {
          setTypes(values);
          setDicts(map);
        },
      ),
    [runOptions],
  );
  useEffect(() => {
    void loadData();
    void loadOptions();
  }, [loadData, loadOptions]);

  const handleCreate = () => {
    setEditing(null);
    form.resetFields();
    form.setFieldsValue({ enabled: 1, weight: 1 });
    setModalVisible(true);
  };

  const handleEdit = (r: SmsProvider) => {
    setEditing(r);
    // accessSecret 后端永远不回传；编辑留空 = 不修改原密钥
    form.resetFields();
    form.setFieldsValue(smsProviderPayload(r));
    setModalVisible(true);
  };

  const handleDelete = (id?: number) => {
    if (!canEdit || !id) return;
    return runMutation(
      async () => requireAdminSuccess(await smsApi.providerDelete(id), '删除失败'),
      () => {
        message.success('删除成功');
        void loadData();
      },
    );
  };
  const handleToggle = (record: SmsProvider, enabled: number) => {
    if (!canEdit || !record.id) return;
    return runMutation(
      async () =>
        requireAdminSuccess(await smsApi.providerUpdate(record.id!, { enabled }), '状态更新失败'),
      () => void loadData(),
    );
  };
  const handleSubmit = () => {
    if (!canEdit || optionsLoading || optionsError) return;
    const id = editing?.id;
    return runMutation(
      async () => {
        const values = await form.validateFields();
        return requireAdminSuccess(
          id ? await smsApi.providerUpdate(id, values) : await smsApi.providerCreate(values),
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
    { title: '供应商名称', dataIndex: 'providerName', key: 'providerName' },
    {
      title: '类型',
      dataIndex: 'providerType',
      key: 'providerType',
      render: (v: string) => <Tag color={v === 'MOCK' ? 'default' : 'blue'}>{v}</Tag>,
    },
    { title: '签名', dataIndex: 'signName', key: 'signName', render: (v: string) => v || '-' },
    {
      title: 'AccessKey',
      dataIndex: 'accessKey',
      key: 'accessKey',
      render: (v: string) => (v ? <code>{v}</code> : '-'),
    },
    {
      title: '启用',
      dataIndex: 'enabled',
      key: 'enabled',
      width: 80,
      render: (v: number, r: SmsProvider) => (
        <Switch
          size="small"
          aria-label={`启用供应商：${r.providerName}`}
          checked={v === 1}
          disabled={!canEdit || saving}
          onChange={(c) => handleToggle(r, c ? 1 : 0)}
        />
      ),
    },
    { title: '权重', dataIndex: 'weight', key: 'weight', width: 70 },
    { title: '备注', dataIndex: 'remark', key: 'remark', ellipsis: true },
    {
      title: '操作',
      key: 'action',
      width: 150,
      render: (_: unknown, r: SmsProvider) => (
        <Space>
          {canEdit && (
            <Button
              type="link"
              icon={<EditOutlined />}
              onClick={() => handleEdit(r)}
              disabled={saving}
            >
              编辑
            </Button>
          )}
          {canEdit && (
            <Popconfirm title="确认删除?" disabled={saving} onConfirm={() => handleDelete(r.id)}>
              <Button type="link" danger icon={<DeleteOutlined />} disabled={saving}>
                删除
              </Button>
            </Popconfirm>
          )}
          {!canEdit && <span className="text-muted-foreground">无操作权限</span>}
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
                新增供应商
              </Button>
            ) : null
          }
        >
          <span style={{ fontWeight: 600, fontSize: 16 }}>
            短信供应商
            <span className="text-muted-foreground ml-3 text-xs font-normal">
              按权重轮选发送，失败自动换下一个；密钥保存后不再回传
            </span>
          </span>
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
        title={editing ? '编辑供应商' : '新增供应商'}
        open={modalVisible}
        onOk={handleSubmit}
        confirmLoading={saving}
        okButtonProps={{ disabled: optionsLoading || !!optionsError }}
        cancelButtonProps={{ disabled: saving }}
        onCancel={() => {
          if (!saving) setModalVisible(false);
        }}
        width={760}
        destroyOnClose
      >
        <Form form={form} disabled={saving} layout="vertical" style={{ marginTop: 16 }}>
          <Form.Item name="providerName" label="供应商名称" rules={[{ required: true }]}>
            <Input />
          </Form.Item>
          <Form.Item
            name="providerType"
            label="类型"
            tooltip="由后端 SPI 注册（MOCK 本地测试 / ALIYUN 阿里云短信）"
            rules={[{ required: true }]}
          >
            <Select
              options={types.map((t) => ({ label: t, value: t }))}
              placeholder="选择供应商类型"
            />
          </Form.Item>
          <Form.Item name="accessKey" label="AccessKey ID">
            <Input placeholder="阿里云 AccessKey ID" />
          </Form.Item>
          <Form.Item
            name="accessSecret"
            label="AccessKey Secret"
            tooltip={
              editing ? '留空 = 不修改已保存的密钥' : '相当于该短信账户的调用凭据，保存后不再回传'
            }
            extra={editing ? '留空保持原密钥不变' : undefined}
          >
            <Input.Password
              placeholder={editing ? '不修改请留空' : '阿里云 AccessKey Secret'}
              autoComplete="new-password"
            />
          </Form.Item>
          <Form.Item
            name="signName"
            label="短信签名"
            tooltip="模板未单独配签名时的兜底签名（阿里云需已备案）"
          >
            <Input placeholder="如：轻听音乐" />
          </Form.Item>
          <Form.Item name="region" label="地域">
            <Input placeholder="cn-hangzhou" />
          </Form.Item>
          <Form.Item
            name="endpoint"
            label="API 地址"
            tooltip="留空用默认 dysmsapi.aliyuncs.com，可覆盖便于代理/调试"
          >
            <Input placeholder="dysmsapi.aliyuncs.com" />
          </Form.Item>
          <Space size="large" wrap>
            <Form.Item name="enabled" label="启用" valuePropName="value">
              <Select
                style={{ width: 100 }}
                options={(enableDict || []).map((o) => ({
                  label: o.label,
                  value: Number(o.value),
                }))}
              />
            </Form.Item>
            <Form.Item
              name="weight"
              label="权重"
              tooltip="越大被选中概率越高；失败自动降级到其他启用供应商"
            >
              <InputNumber min={1} max={100} style={{ width: 100 }} />
            </Form.Item>
          </Space>
          <Form.Item name="remark" label="备注">
            <Input.TextArea rows={2} />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
