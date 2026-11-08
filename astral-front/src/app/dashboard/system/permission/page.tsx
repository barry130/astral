'use client';

import { useState, useEffect, useMemo, useCallback } from 'react';
import {
  Button,
  Modal,
  Form,
  Input,
  InputNumber,
  Select,
  message,
  Popconfirm,
  Tag,
} from '@/components/antd-compat';
import {
  PlusOutlined,
  EditOutlined,
  DeleteOutlined,
  ReloadOutlined,
} from '@/components/antd-compat/icons';
import {
  permissionApi,
  permissionPayload,
  permissionDomain,
  type PermissionRow,
  type PermissionWrite,
} from '@/api/system-admin';
import { usePerm } from '@/lib/perm';
import { AdminLoadNotice } from '@/components/admin/AdminLoadNotice';
import { useAdminLoader, useAdminMutation } from '@/components/admin/use-admin-task';
import { requireAdminSuccess } from '@/components/admin/admin-task';
import { ResizableTable } from '@/components/ResizableTable';
import {
  loadAdminDictOptions as fetchDictOptions,
  dictLabel,
  dictColor,
  DictOption,
} from '@/components/admin/admin-dictionaries';
import { PermissionSideTag, SIDE_META, sideOfCode } from '@/lib/permission-side';
import { PageHeader } from '@/components/page-header';
import { FilterBar } from '@/components/filter-bar';

const { Option } = Select;

/** 权限类型字典编码（文案来自数据字典 permission_type，禁止前端硬编码） */
const PERMISSION_TYPE_DICT = 'permission_type';

/** 类型标签配色：目录/菜单/按钮 属菜单树；接口/数据 属后端权限（不参与导航渲染） */
const TYPE_COLORS: Record<string, string> = {
  '1': 'blue',
  '2': 'green',
  '3': 'orange',
  '4': 'purple',
  '5': 'magenta',
};

export default function PermissionPage() {
  const [permissions, setPermissions] = useState<PermissionRow[]>([]);
  const { loading, error: loadError, run: runLoad } = useAdminLoader('获取权限列表失败');
  const { busy: saving, run: runMutation } = useAdminMutation('权限操作失败');
  const hasPerm = usePerm();
  const isSuper = hasPerm('*:*:*');
  const [statusOptions, setStatusOptions] = useState<DictOption[]>([]);
  const [modalVisible, setModalVisible] = useState(false);
  const [editingPermission, setEditingPermission] = useState<PermissionRow | null>(null);
  const [typeOptions, setTypeOptions] = useState<DictOption[]>([]);
  const [domainFilter, setDomainFilter] = useState<string | undefined>(undefined);
  const [typeFilter, setTypeFilter] = useState<string | undefined>(undefined);
  const [sideFilter, setSideFilter] = useState<string | undefined>(undefined);
  const [keyword, setKeyword] = useState('');
  const [form] = Form.useForm<PermissionWrite>();

  const fetchPermissions = useCallback(
    () => runLoad(() => permissionApi.getAll(), setPermissions),
    [runLoad],
  );
  useEffect(() => {
    void fetchPermissions();
    let active = true;
    fetchDictOptions([PERMISSION_TYPE_DICT, 'enable_status'])
      .then((map) => {
        if (active) {
          setTypeOptions(map[PERMISSION_TYPE_DICT] || []);
          setStatusOptions(map.enable_status || []);
        }
      })
      .catch(() => {
        /* Unknown labels retain their raw values. */
      });
    return () => {
      active = false;
    };
  }, [fetchPermissions]);

  const typeLabel = (type: number | undefined): string => {
    const key = String(type ?? '');
    const hit = typeOptions.find((o) => String(o.value) === key);
    return hit ? hit.label : key || '-';
  };

  /** 域下拉选项：从现有权限里汇总（权限域是数据驱动的，不登记字典） */
  const domainOptions = useMemo(() => {
    const set = new Set<string>();
    permissions.forEach((p) => set.add(permissionDomain(p)));
    return Array.from(set).sort();
  }, [permissions]);

  /** 过滤 + 按「域 → 端 → 类型 → 权限码」排序，同域权限天然聚在一起 */
  const dataSource = useMemo(() => {
    const kw = keyword.trim().toLowerCase();
    return permissions
      .filter((p) => (domainFilter ? permissionDomain(p) === domainFilter : true))
      .filter((p) => (typeFilter ? String(p.type) === typeFilter : true))
      .filter((p) => (sideFilter ? sideOfCode(p.permissionCode) === sideFilter : true))
      .filter((p) => {
        if (!kw) return true;
        return (
          String(p.permissionCode || '')
            .toLowerCase()
            .includes(kw) ||
          String(p.permissionName || '')
            .toLowerCase()
            .includes(kw)
        );
      })
      .sort((a, b) => {
        const d = permissionDomain(a).localeCompare(permissionDomain(b));
        if (d !== 0) return d;
        const sa = sideOfCode(a.permissionCode) ?? 'zz';
        const sb = sideOfCode(b.permissionCode) ?? 'zz';
        if (sa !== sb) return sa.localeCompare(sb);
        const t = (a.type ?? 0) - (b.type ?? 0);
        if (t !== 0) return t;
        return String(a.permissionCode || '').localeCompare(String(b.permissionCode || ''));
      });
  }, [permissions, domainFilter, typeFilter, sideFilter, keyword]);

  const handleAdd = () => {
    setEditingPermission(null);
    form.resetFields();
    setModalVisible(true);
  };

  const handleEdit = (record: PermissionRow) => {
    setEditingPermission(record);
    form.resetFields();
    form.setFieldsValue(permissionPayload(record));
    setModalVisible(true);
  };

  const handleDelete = (id: number) => {
    if (!isSuper) return;
    return runMutation(
      async () => requireAdminSuccess(await permissionApi.delete(id), '删除失败'),
      () => {
        message.success('删除成功');
        void fetchPermissions();
      },
    );
  };
  const handleSubmit = () => {
    if (!isSuper) return;
    const id = editingPermission?.id;
    return runMutation(
      async () => {
        const values = await form.validateFields();
        return requireAdminSuccess(
          id ? await permissionApi.update(id, values) : await permissionApi.create(values),
          '保存失败',
        );
      },
      () => {
        message.success(id ? '更新成功' : '创建成功');
        setModalVisible(false);
        void fetchPermissions();
      },
    );
  };

  const columns = [
    {
      title: '权限域',
      dataIndex: 'domain',
      width: 110,
      render: (_: unknown, record: PermissionRow) => (
        <Tag color="cyan">{permissionDomain(record)}</Tag>
      ),
    },
    {
      // 显式 key：与下一列同 dataIndex（permissionCode），不写 key 会让两列派生出
      // 相同的 React key（resolveColumnKey 回退到 dataIndex），表头/表体报重复 key 警告
      title: '端',
      dataIndex: 'permissionCode',
      key: 'side',
      width: 90,
      render: (_: unknown, record: PermissionRow) => (
        <PermissionSideTag code={record.permissionCode} />
      ),
    },
    { title: '权限编码', dataIndex: 'permissionCode', width: 260 },
    { title: '权限名称', dataIndex: 'permissionName' },
    {
      title: '类型',
      dataIndex: 'type',
      width: 90,
      render: (type: number) => (
        <Tag color={TYPE_COLORS[String(type)] || 'default'}>{typeLabel(type)}</Tag>
      ),
    },
    { title: '路径', dataIndex: 'url', ellipsis: true },
    { title: '方法', dataIndex: 'method', width: 80 },
    { title: '排序', dataIndex: 'sort', width: 70 },
    {
      title: '状态',
      dataIndex: 'status',
      width: 80,
      render: (status: number) => (
        <Tag color={dictColor(statusOptions, status) || 'default'}>
          {dictLabel(statusOptions, status, String(status))}
        </Tag>
      ),
    },
    {
      title: '操作',
      width: 150,
      render: (_: unknown, record: PermissionRow) => (
        <>
          <Button
            type="link"
            icon={<EditOutlined />}
            onClick={() => handleEdit(record)}
            disabled={!isSuper || saving}
          >
            编辑
          </Button>
          <Popconfirm
            title="确定删除?"
            disabled={!isSuper || saving}
            onConfirm={() => handleDelete(record.id)}
          >
            <Button type="link" danger icon={<DeleteOutlined />} disabled={!isSuper || saving}>
              删除
            </Button>
          </Popconfirm>
        </>
      ),
    },
  ];

  return (
    <div className="space-y-4">
      <PageHeader
        title="权限管理"
        description="接口权限点与权限分组的维护"
        actions={
          <>
            <Button icon={<ReloadOutlined />} onClick={fetchPermissions}>
              刷新
            </Button>
            <Button
              type="primary"
              icon={<PlusOutlined />}
              onClick={handleAdd}
              disabled={!isSuper || saving}
            >
              新建权限
            </Button>
          </>
        }
      />
      <FilterBar>
        <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
          <Input
            placeholder="搜索权限编码/名称"
            allowClear
            style={{ width: 220 }}
            value={keyword}
            onChange={(e) => setKeyword(e.target.value)}
          />
          <Select
            placeholder="权限域"
            allowClear
            style={{ width: 140 }}
            value={domainFilter}
            onChange={(v: string | undefined) => setDomainFilter(v)}
          >
            {domainOptions.map((d) => (
              <Option key={d} value={d}>
                {d}
              </Option>
            ))}
          </Select>
          <Select
            placeholder="端"
            allowClear
            style={{ width: 110 }}
            value={sideFilter}
            onChange={(v: string | undefined) => setSideFilter(v)}
          >
            {Object.entries(SIDE_META).map(([value, meta]) => (
              <Option key={value} value={value}>
                {meta.label}
              </Option>
            ))}
          </Select>
          <Select
            placeholder="类型"
            allowClear
            style={{ width: 120 }}
            value={typeFilter}
            onChange={(v: string | undefined) => setTypeFilter(v)}
          >
            {typeOptions.map((o) => (
              <Option key={o.value} value={String(o.value)}>
                {o.label}
              </Option>
            ))}
          </Select>
        </div>
      </FilterBar>
      <AdminLoadNotice
        error={loadError}
        hasData={permissions.length > 0}
        onRetry={() => void fetchPermissions()}
      />
      <ResizableTable
        columns={columns}
        dataSource={dataSource}
        rowKey="id"
        loading={loading}
        pagination={false}
        scroll={{ x: 'max-content' }}
      />
      <Modal
        title={editingPermission ? '编辑权限' : '新建权限'}
        open={modalVisible}
        onOk={handleSubmit}
        confirmLoading={saving}
        cancelButtonProps={{ disabled: saving }}
        onCancel={() => {
          if (!saving) setModalVisible(false);
        }}
      >
        <Form form={form} disabled={saving || !isSuper} layout="vertical">
          <Form.Item
            name="permissionCode"
            label="权限编码"
            rules={[{ required: true }]}
            extra="规范：端:域:资源:操作[:范围]，端=admin(管理端)/user(用户端)/all(通用)，如 admin:system:user:view、user:qt:update:channel:beta。全小写；范围段用于结果级权限"
          >
            <Input placeholder="如: admin:system:user:view" />
          </Form.Item>
          <Form.Item name="permissionName" label="权限名称" rules={[{ required: true }]}>
            <Input />
          </Form.Item>
          <Form.Item
            name="domain"
            label="权限域"
            extra="留空按权限码第2段归域，如 admin:system:user:view 的域为 system"
          >
            <Input placeholder="如: system / qt / storage" />
          </Form.Item>
          <Form.Item
            name="type"
            label="类型"
            rules={[{ required: true }]}
            extra="接口=控制 API 可达性；数据=结果级权限（同一接口按人群返回不同结果）"
          >
            <Select>
              {typeOptions.map((o) => (
                <Option key={o.value} value={Number(o.value)}>
                  {o.label}
                </Option>
              ))}
            </Select>
          </Form.Item>
          <Form.Item name="url" label="路径">
            <Input placeholder="/api/v1/..." />
          </Form.Item>
          <Form.Item name="method" label="方法">
            <Select allowClear>
              <Option value="GET">GET</Option>
              <Option value="POST">POST</Option>
              <Option value="PUT">PUT</Option>
              <Option value="DELETE">DELETE</Option>
            </Select>
          </Form.Item>
          <Form.Item
            name="parentId"
            label="父级ID"
            extra="留空或 0 表示顶级；仅菜单树（目录/菜单/按钮）需要挂父级"
          >
            <InputNumber style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="icon" label="图标">
            <Input placeholder="如: UserOutlined" />
          </Form.Item>
          <Form.Item name="sort" label="排序" initialValue={0}>
            <InputNumber style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="status" label="状态" initialValue={1}>
            <Select>
              {statusOptions.map((option) => (
                <Option key={option.value} value={Number(option.value)}>
                  {option.label}
                </Option>
              ))}
            </Select>
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
