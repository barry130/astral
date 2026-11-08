'use client';

import { useEffect, useState, useMemo, useCallback } from 'react';
import {
  Card,
  Button,
  Space,
  Modal,
  Form,
  Input,
  InputNumber,
  Switch,
  Tag,
  message,
  Popconfirm,
  TreeSelect,
  TreeSelectNode,
  Row,
  Col,
} from '@/components/antd-compat';
import {
  PlusOutlined,
  EditOutlined,
  DeleteOutlined,
  SafetyOutlined,
} from '@/components/antd-compat/icons';
import {
  roleApi,
  rolePayload,
  permissionApi,
  groupPermissionRows,
  paginateRoles,
  type Role,
  type RoleWrite,
  type PermissionNode as Permission,
} from '@/api/system-admin';
import { AdminLoadNotice } from '@/components/admin/AdminLoadNotice';
import {
  useAdminLoader,
  useAdminMutation,
  useAdminQuery,
  useAdminTask,
} from '@/components/admin/use-admin-task';
import {
  requireAdminSuccess,
  numericSwitchBinding,
  adminErrorMessage,
} from '@/components/admin/admin-task';
import { usePerm } from '@/lib/perm';
import { PermissionSideTag } from '@/lib/permission-side';
import { ResizableTable } from '@/components/ResizableTable';
import {
  loadAdminDictOptions as fetchDictOptions,
  dictLabel,
  dictColor,
  DictOption,
} from '@/components/admin/admin-dictionaries';
import { PageHeader } from '@/components/page-header';
import { FilterBar } from '@/components/filter-bar';

/** 权限类型标签配色：4=接口 / 5=数据（结果级权限）与菜单树类型区分 */
const TYPE_COLORS: Record<string, string> = {
  '1': 'blue',
  '2': 'green',
  '3': 'orange',
  '4': 'purple',
  '5': 'magenta',
};

/**
 * 角色权限管理页面组件
 * 提供角色的增删改查和权限分配功能
 */
export default function RolePage() {
  /** 加载状态 */
  const { loading, error: loadError, run: runLoad } = useAdminLoader('加载角色列表失败');
  const {
    loading: permissionLoading,
    error: permissionError,
    run: runPermissions,
  } = useAdminLoader('加载权限定义失败');
  const { busy: saving, run: runMutation } = useAdminMutation('角色操作失败');
  const selectionTask = useAdminTask();
  const query = useAdminQuery({ current: 1, pageSize: 10, keyword: '' });
  const [search, setSearch] = useState('');
  const [dicts, setDicts] = useState<Record<string, DictOption[]>>({});
  /** 角色列表数据 */
  const [data, setData] = useState<Role[]>([]);
  /** 分页状态 */
  const [pagination, setPagination] = useState({ current: 1, pageSize: 10, total: 0 });
  /** 角色新增/编辑弹窗显示状态 */
  const [modalVisible, setModalVisible] = useState(false);
  /** 权限分配弹窗显示状态 */
  const [permModalVisible, setPermModalVisible] = useState(false);
  /** 当前编辑的角色 */
  const [editingRole, setEditingRole] = useState<Role | null>(null);
  /** 当前分配权限的目标角色 */
  const [targetRole, setTargetRole] = useState<Role | null>(null);
  /** 权限树数据 */
  const [permissionTree, setPermissionTree] = useState<Permission[]>([]);
  /** 已选中的权限ID列表 */
  const [selectedPerms, setSelectedPerms] = useState<number[]>([]);
  /** 权限类型字典（文案来源：数据字典 permission_type） */
  const [typeOptions, setTypeOptions] = useState<DictOption[]>([]);
  /** 角色表单实例 */
  const [form] = Form.useForm<RoleWrite>();

  const hasPerm = usePerm();
  /** 是否具备角色维护权限：权限码需与后端 @RequiresPermission("admin:system:role:edit") 一致 */
  const canEdit = hasPerm('admin:system:role:edit');
  const isSuper = hasPerm('*:*:*');

  /** 权限类型文案（字典兜底为原始值） */
  const typeLabel = useCallback(
    (type: number): string => {
      const key = String(type ?? '');
      const hit = typeOptions.find((o) => String(o.value) === key);
      return hit ? hit.label : key;
    },
    [typeOptions],
  );

  /**
   * 权限树 → TreeSelect 节点。
   * 域分组节点（id 为空）不产出 value：勾选它等于勾选该域下全部权限，
   * 提交给后端的永远是真实权限 ID，不会写入非法值。
   * 递归经内部 walk 实现，避免函数自引用破坏 useCallback 的稳定标识。
   */
  const buildTreeNodes = useCallback(
    (nodes: Permission[]): TreeSelectNode[] => {
      const walk = (list: Permission[]): TreeSelectNode[] =>
        list.map((p) => ({
          title:
            p.id == null ? (
              <span className="font-medium">{p.permissionName}</span>
            ) : (
              <span className="inline-flex items-center gap-1">
                <PermissionSideTag code={p.permissionCode} />
                {p.permissionName}
                <Tag color={TYPE_COLORS[String(p.type)] || 'default'}>{typeLabel(p.type)}</Tag>
              </span>
            ),
          value: p.id ?? undefined,
          key: p.id != null ? p.id : `domain-${p.permissionName}`,
          disabled: false,
          children: p.children?.length ? walk(p.children) : undefined,
        }));
      return walk(nodes);
    },
    [typeLabel],
  );

  /** 当前分配弹窗的树数据（域分组的 value 需为 undefined，见 buildTreeNodes） */
  const treeData = useMemo(() => buildTreeNodes(permissionTree), [permissionTree, buildTreeNodes]);

  const loadData = useCallback(
    (patch: Partial<{ current: number; pageSize: number; keyword: string }> = {}) => {
      const q = query.read(patch);
      return runLoad(
        async () => requireAdminSuccess(await roleApi.getAll(), '加载角色列表失败') || [],
        (rows) => {
          const page = paginateRoles(rows, q);
          query.read({ current: page.current, pageSize: page.size });
          setData(page.records);
          setPagination({ current: page.current, pageSize: page.size, total: page.total });
        },
      );
    },
    [query, runLoad],
  );
  const loadPermissions = useCallback(
    () =>
      runPermissions(
        async () => groupPermissionRows(await permissionApi.getAll()),
        setPermissionTree,
      ),
    [runPermissions],
  );

  useEffect(() => {
    void loadData();
    if (isSuper) void loadPermissions();
    let active = true;
    fetchDictOptions(['permission_type', 'enable_status'])
      .then((map) => {
        if (active) {
          setTypeOptions(map.permission_type || []);
          setDicts(map);
        }
      })
      .catch(() => {
        /* Unknown labels retain their raw values. */
      });
    return () => {
      active = false;
    };
  }, [isSuper, loadData, loadPermissions]);

  const handleCreate = () => {
    if (!isSuper || saving) return;
    setEditingRole(null);
    form.resetFields();
    form.setFieldsValue({ status: 1, sort: 0 });
    setModalVisible(true);
  };
  const handleEdit = (record: Role) => {
    if (!canEdit || saving) return;
    setEditingRole(record);
    form.resetFields();
    form.setFieldsValue(rolePayload(record, true));
    setModalVisible(true);
  };
  const handleDelete = (id: number) => {
    if (!canEdit) return;
    return runMutation(
      async () => requireAdminSuccess(await roleApi.delete(id), '删除失败'),
      () => {
        message.success('删除成功');
        void loadData();
      },
    );
  };
  const handleSubmit = () => {
    if (!(editingRole ? canEdit : isSuper)) return;
    const id = editingRole?.id;
    return runMutation(
      async () => {
        const values = await form.validateFields();
        return requireAdminSuccess(
          id ? await roleApi.update(id, values) : await roleApi.create(values),
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
  const handleAssignPermissions = (record: Role) => {
    if (!isSuper || permissionLoading || permissionError || record.isSuper === 1 || saving) return;
    return selectionTask.run(
      async () =>
        requireAdminSuccess(await roleApi.getPermissions(record.id), '加载角色权限失败') || [],
      {
        onSuccess: (rows) => {
          setTargetRole(record);
          setSelectedPerms(
            rows.map((row) => row.id).filter((id): id is number => id !== null && id > 0),
          );
          setPermModalVisible(true);
        },
        onError: (error) => message.error(adminErrorMessage(error, '加载角色权限失败')),
      },
    );
  };
  const handleAssignPermissionsSubmit = () => {
    if (!isSuper || !targetRole || permissionLoading || permissionError) return;
    const id = targetRole.id;
    const ids = [...selectedPerms];
    return runMutation(
      async () => requireAdminSuccess(await roleApi.assignPermissions(id, ids), '权限分配失败'),
      () => {
        message.success('权限分配成功');
        setPermModalVisible(false);
        void loadData();
      },
    );
  };

  /** 表格列定义 */
  const columns = [
    { title: '角色编码', dataIndex: 'roleCode', key: 'roleCode' },
    { title: '角色名称', dataIndex: 'roleName', key: 'roleName' },
    { title: '描述', dataIndex: 'description', key: 'description' },
    { title: '排序', dataIndex: 'sort', key: 'sort' },
    {
      title: '超管',
      dataIndex: 'isSuper',
      key: 'isSuper',
      width: 80,
      render: (v: number) => (
        <Tag color={v === 1 ? 'gold' : 'default'}>
          {v === 1 ? '超级管理员角色' : `isSuper=${v ?? 0}`}
        </Tag>
      ),
    },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      render: (v: number) => (
        <Tag color={dictColor(dicts.enable_status, v) || 'default'}>
          {dictLabel(dicts.enable_status, v, String(v))}
        </Tag>
      ),
    },
    {
      title: '操作',
      key: 'action',
      width: 160,
      render: (_: unknown, r: Role) => (
        <Space>
          <Button
            type="link"
            icon={<EditOutlined />}
            onClick={() => handleEdit(r)}
            disabled={!canEdit || saving}
          >
            编辑
          </Button>
          {/* 提权写入口与后端 RequiresSuper 一致。 */}
          <Button
            type="link"
            icon={<SafetyOutlined />}
            disabled={
              !isSuper || permissionLoading || !!permissionError || r.isSuper === 1 || saving
            }
            title={r.isSuper === 1 ? '超管角色默认拥有全部权限，无需分配' : undefined}
            onClick={() => handleAssignPermissions(r)}
          >
            权限
          </Button>
          <Popconfirm
            title="确认删除?"
            disabled={!canEdit || saving}
            onConfirm={() => handleDelete(r.id)}
          >
            <Button type="link" danger icon={<DeleteOutlined />} disabled={!canEdit || saving}>
              删除
            </Button>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <div className="space-y-4">
      <PageHeader title="角色权限" description="角色及其权限点、数据范围的分配" />
      <Card>
        <FilterBar split>
          <Input.Search
            placeholder="搜索角色编码/名称/描述"
            aria-label="搜索角色"
            allowClear
            value={search}
            onChange={(event) => setSearch(event.target.value)}
            onSearch={(keyword) => void loadData({ current: 1, keyword })}
            className="w-full sm:w-[300px]"
          />
          {isSuper && (
            <Button type="primary" icon={<PlusOutlined />} onClick={handleCreate} disabled={saving}>
              新建角色
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
            onChange: (current, pageSize) => void loadData({ current, pageSize }),
          }}
        />
      </Card>

      <Modal
        title={editingRole ? '编辑角色' : '新建角色'}
        open={modalVisible}
        onOk={handleSubmit}
        confirmLoading={saving}
        cancelButtonProps={{ disabled: saving }}
        onCancel={() => {
          if (!saving) setModalVisible(false);
        }}
      >
        <Form form={form} disabled={saving} layout="vertical" style={{ marginTop: 16 }}>
          <Form.Item name="roleCode" label="角色编码" rules={[{ required: true }]}>
            <Input disabled={!!editingRole} />
          </Form.Item>
          <Form.Item name="roleName" label="角色名称" rules={[{ required: true }]}>
            <Input />
          </Form.Item>
          <Form.Item name="description" label="描述">
            <Input.TextArea rows={2} />
          </Form.Item>
          <Row gutter={[16, 16]}>
            <Col xs={{ span: 24 }} md={{ span: 12 }}>
              <Form.Item name="sort" label="排序" initialValue={0}>
                <InputNumber min={0} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col xs={{ span: 24 }} md={{ span: 12 }}>
              <Form.Item name="status" label="状态" {...numericSwitchBinding} initialValue={1}>
                <Switch
                  checkedChildren={dictLabel(dicts.enable_status, 1, '1')}
                  unCheckedChildren={dictLabel(dicts.enable_status, 0, '0')}
                />
              </Form.Item>
            </Col>
          </Row>
        </Form>
      </Modal>

      <Modal
        title={`分配权限${targetRole ? ` - ${targetRole.roleName}` : ''}`}
        open={permModalVisible}
        onOk={handleAssignPermissionsSubmit}
        confirmLoading={saving}
        cancelButtonProps={{ disabled: saving }}
        onCancel={() => {
          if (!saving) {
            selectionTask.invalidate();
            setPermModalVisible(false);
          }
        }}
        width={760}
      >
        <AdminLoadNotice
          error={permissionError}
          hasData={permissionTree.length > 0}
          onRetry={() => void loadPermissions()}
        />
        <div style={{ marginTop: 16 }}>
          <div
            style={{
              marginBottom: 8,
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'space-between',
              gap: 12,
              color: 'var(--muted-foreground, #888)',
              fontSize: 12,
            }}
          >
            <span>
              按「权限域」分组；接口/数据类权限不参与左侧菜单渲染。勾选域名可全选该域权限。
            </span>
            <span style={{ flexShrink: 0 }}>已选 {selectedPerms.length} 项</span>
          </div>
          {/* overflowX: hidden —— 弹窗内绝不出现横向滚动条；宽度问题由 TreeSelect 自身换行消化 */}
          <div
            style={{
              maxHeight: 420,
              overflowY: 'auto',
              overflowX: 'hidden',
              border: '1px solid var(--border, #e5e7eb)',
              borderRadius: 6,
              padding: 8,
            }}
          >
            <TreeSelect
              treeData={treeData}
              treeCheckable
              showCheckedStrategy="SHOW_ALL"
              allowClear
              placeholder="选择权限"
              style={{ width: '100%' }}
              disabled={saving || permissionLoading || !!permissionError}
              value={selectedPerms}
              onChange={(val) => setSelectedPerms(val || [])}
            />
          </div>
        </div>
      </Modal>
    </div>
  );
}
