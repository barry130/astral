'use client';

import { useCallback, useEffect, useState } from 'react';
import { formatDateTime } from '@/lib/format';
import {
  Card,
  Button,
  Space,
  Modal,
  Form,
  Input,
  Select,
  Tag,
  message,
  Popconfirm,
} from '@/components/antd-compat';
import { PlusOutlined, EditOutlined, DeleteOutlined } from '@/components/antd-compat/icons';
import { configApi, configPayload, type ConfigWrite, type SysConfig } from '@/api/system-admin';
import { AdminLoadNotice } from '@/components/admin/AdminLoadNotice';
import { useAdminLoader, useAdminMutation, useAdminQuery } from '@/components/admin/use-admin-task';
import { requireAdminSuccess } from '@/components/admin/admin-task';
import {
  loadAdminDictOptions as fetchDictOptions,
  dictLabel,
  dictColor,
  DictOption,
} from '@/components/admin/admin-dictionaries';
import { usePerm } from '@/lib/perm';
import { ResizableTable } from '@/components/ResizableTable';
import { PageHeader } from '@/components/page-header';
import { FilterBar } from '@/components/filter-bar';

/**
 * 系统配置管理页面组件
 * 提供系统配置的增删改查功能，内置配置不可编辑/删除
 */
export default function ConfigPage() {
  /** 加载状态 */
  const { loading, error: loadError, run: runLoad } = useAdminLoader('加载配置失败');
  const { busy: saving, run: runMutation } = useAdminMutation('操作配置失败');
  const query = useAdminQuery({ current: 1, pageSize: 10, keyword: '' });
  /** 配置列表数据 */
  const [data, setData] = useState<SysConfig[]>([]);
  /** 分页状态 */
  const [pagination, setPagination] = useState({ current: 1, pageSize: 10, total: 0 });
  /** 新增/编辑弹窗显示状态 */
  const [modalVisible, setModalVisible] = useState(false);
  /** 当前编辑的配置，null表示新增模式 */
  const [editingConfig, setEditingConfig] = useState<SysConfig | null>(null);
  /** 表单实例 */
  const [form] = Form.useForm<ConfigWrite>();
  /** 配置类型枚举一律取数据字典 */
  const [dicts, setDicts] = useState<Record<string, DictOption[]>>({});
  const configTypeDict = dicts.config_type;
  /** 当前搜索关键字：删除/保存后的重查必须沿用，否则会把筛选态悄悄丢掉 */
  const [keywordState, setKeywordState] = useState('');

  const hasPerm = usePerm();
  /** 是否具备系统配置维护权限：权限码需与后端 @RequiresPermission("admin:system:config:edit") 一致 */
  const canEdit = hasPerm('admin:system:config:edit');

  const loadData = useCallback(
    (patch: Partial<{ current: number; pageSize: number; keyword: string }> = {}) => {
      const q = query.read(patch);
      return runLoad(
        async () => {
          const res = await configApi.getPage(q.current, q.pageSize, q.keyword);
          const page = requireAdminSuccess(res, '加载配置失败');
          if (!page) throw new Error('配置分页数据缺失');
          return page;
        },
        (page) => {
          setData(page.records || []);
          setPagination({
            current: page.current || q.current,
            pageSize: page.size || q.pageSize,
            total: page.total || 0,
          });
        },
      );
    },
    [query, runLoad],
  );

  useEffect(() => {
    void loadData();
    let active = true;
    fetchDictOptions(['config_type'])
      .then((map) => {
        if (active) setDicts(map);
      })
      .catch(() => {
        /* Unknown labels retain their raw values. */
      });
    return () => {
      active = false;
    };
  }, [loadData]);

  /** 分页/搜索统一入口（表格 onChange 也要回到这里，否则删除后页码与筛选会漂） */
  const handleSearch = (page: number, size: number, keyword: string) => {
    setKeywordState(keyword);
    void loadData({ current: page, pageSize: size, keyword });
  };

  /** 打开新增配置弹窗 */
  const handleCreate = () => {
    setEditingConfig(null);
    form.resetFields();
    setModalVisible(true);
  };

  /** 打开编辑配置弹窗 */
  const handleEdit = (record: SysConfig) => {
    setEditingConfig(record);
    form.resetFields();
    form.setFieldsValue(configPayload(record));
    setModalVisible(true);
  };

  const handleDelete = (id: number) => {
    if (!canEdit || data.find((row) => row.id === id)?.configType === 1) return;
    return runMutation(
      async () => requireAdminSuccess(await configApi.delete(id), '删除失败'),
      () => {
        message.success('删除成功');
        void loadData();
      },
    );
  };

  const handleSubmit = () => {
    if (!canEdit) return;
    const id = editingConfig?.id;
    return runMutation(
      async () => {
        const values = await form.validateFields();
        return requireAdminSuccess(
          id ? await configApi.update(id, values) : await configApi.create(values),
          '保存失败',
        );
      },
      () => {
        message.success(id ? '更新成功' : '创建成功');
        setModalVisible(false);
        void loadData();
      },
    );
  };

  /** 表格列定义 */
  const columns = [
    // 全列显式 width：DataTable 在所有列都有宽度时启用 table-layout:fixed，列宽成为硬约束
    // （auto 布局下内容长的「描述」会把「配置值」挤到只剩几十px，实测 71px）
    { title: '配置名称', dataIndex: 'configName', key: 'configName', width: 160 },
    {
      title: '配置键',
      dataIndex: 'configKey',
      key: 'configKey',
      width: 240,
      ellipsis: true,
      render: (v: string) => <code className="bg-muted rounded px-1.5 py-0.5 text-xs">{v}</code>,
    },
    { title: '配置值', dataIndex: 'configValue', key: 'configValue', width: 200, ellipsis: true },
    {
      title: '类型',
      dataIndex: 'configType',
      key: 'configType',
      width: 90,
      render: (v: number) => (
        <Tag color={dictColor(configTypeDict, v) || 'default'}>
          {dictLabel(configTypeDict, v, String(v))}
        </Tag>
      ),
    },
    { title: '描述', dataIndex: 'description', key: 'description', width: 300 },
    {
      title: '创建时间',
      dataIndex: 'createTime',
      key: 'createTime',
      width: 170,
      render: (v: string) => formatDateTime(v),
    },
    {
      title: '操作',
      key: 'action',
      width: 140,
      render: (_: unknown, r: SysConfig) => (
        <Space>
          {/* 内置配置不允许编辑和删除 */}
          <Button
            type="link"
            icon={<EditOutlined />}
            onClick={() => handleEdit(r)}
            disabled={!canEdit || r.configType === 1}
          >
            编辑
          </Button>
          <Popconfirm title="确认删除?" disabled={!canEdit} onConfirm={() => handleDelete(r.id)}>
            <Button
              type="link"
              danger
              icon={<DeleteOutlined />}
              disabled={!canEdit || r.configType === 1}
            >
              删除
            </Button>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <div className="space-y-4">
      <PageHeader title="系统配置" description="系统级参数项与取值的维护" />
      <Card>
        <FilterBar split>
          <Input.Search
            placeholder="搜索配置名称"
            allowClear
            onSearch={(v) => handleSearch(1, pagination.pageSize, v)}
            aria-label="搜索配置名称"
            className="w-full sm:w-[300px]"
          />
          {canEdit && (
            <Button type="primary" icon={<PlusOutlined />} onClick={handleCreate} disabled={saving}>
              新建配置
            </Button>
          )}
        </FilterBar>
        <AdminLoadNotice
          error={loadError}
          hasData={data.length > 0}
          onRetry={() => void loadData()}
        />
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
            onChange: (page, size) => handleSearch(page, size, keywordState),
          }}
        />
      </Card>

      <Modal
        title={editingConfig ? '编辑配置' : '新建配置'}
        open={modalVisible}
        onOk={handleSubmit}
        confirmLoading={saving}
        cancelButtonProps={{ disabled: saving }}
        onCancel={() => {
          if (!saving) setModalVisible(false);
        }}
      >
        <Form form={form} disabled={saving} layout="vertical" style={{ marginTop: 16 }}>
          <Form.Item name="configName" label="配置名称" rules={[{ required: true }]}>
            <Input />
          </Form.Item>
          <Form.Item name="configKey" label="配置键" rules={[{ required: true }]}>
            <Input disabled={!!editingConfig} />
          </Form.Item>
          <Form.Item name="configValue" label="配置值" rules={[{ required: true }]}>
            <Input.TextArea rows={3} />
          </Form.Item>
          <Form.Item name="configType" label="类型" initialValue={2}>
            <Select
              options={(configTypeDict || []).map((o) => ({
                label: o.label,
                value: Number(o.value),
              }))}
            />
          </Form.Item>
          <Form.Item name="description" label="描述">
            <Input.TextArea rows={2} />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
