'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { formatDateTime } from '@/lib/format';
import {
  Card,
  Button,
  Space,
  Modal,
  Form,
  Input,
  Select,
  Switch,
  Tag,
  message,
  Popconfirm,
  Tabs,
  Divider,
  Typography,
} from '@/components/antd-compat';
import {
  PlusOutlined,
  EditOutlined,
  DeleteOutlined,
  KeyOutlined,
  LockOutlined,
  LoginOutlined,
} from '@/components/antd-compat/icons';

import {
  userApi,
  roleApi,
  userProfilePayload,
  saveUserProfile,
  UserPartialSaveError,
  type SysUser as User,
  type Role,
  type UserCreateWrite,
  type UserEditorWrite,
} from '@/api/system-admin';
import {
  AdminLoadNotice,
  AdminLoadingNotice,
  AdminOptionNotice,
} from '@/components/admin/AdminLoadNotice';
import { useAdminLoader, useAdminMutation, useAdminQuery } from '@/components/admin/use-admin-task';
import { requireAdminSuccess } from '@/components/admin/admin-task';
import {
  loadAdminDictOptions as fetchDictOptions,
  dictLabel,
  dictColor,
  DictOption,
} from '@/components/admin/admin-dictionaries';

import { downloadResponse } from '@/lib/http-download';
import { usePerm } from '@/lib/perm';
import { ResizableTable } from '@/components/ResizableTable';
import { PageHeader } from '@/components/page-header';
import { FilterBar } from '@/components/filter-bar';

/**
 * 用户管理页面组件
 * 提供用户的增删改查、密码重置、角色分配、状态切换功能
 * 编辑入口为完整的用户管理面板（基本信息 / 角色 / 账户操作）
 */
export default function UserPage() {
  /** 加载状态 */
  const { loading, error: loadError, run: runLoad } = useAdminLoader('加载用户列表失败');
  const {
    loading: optionsLoading,
    error: optionsError,
    run: runOptions,
  } = useAdminLoader('加载用户选项失败');
  const {
    loading: rolesLoading,
    error: rolesError,
    run: runUserRoles,
    invalidate: invalidateUserRoles,
  } = useAdminLoader('加载用户角色失败');
  const { busy: saving, run: runMutation, isBusy } = useAdminMutation('用户操作失败');
  const { busy: exporting, run: runExport } = useAdminMutation('导出用户失败');
  const query = useAdminQuery({ current: 1, pageSize: 10, keyword: '', userType: '' });
  const editorId = useRef<number | null>(null);
  const [hydratedUserId, setHydratedUserId] = useState<number | null>(null);
  /** 用户列表数据 */
  const [data, setData] = useState<User[]>([]);
  /** 分页状态 */
  const [pagination, setPagination] = useState({ current: 1, pageSize: 10, total: 0 });
  /** 搜索关键词 */
  const [search, setSearch] = useState('');
  /** 用户类型筛选：''全部 / ADMIN / APP */
  const [typeFilter, setTypeFilter] = useState('');
  /** 新建用户弹窗显示状态 */
  const [createVisible, setCreateVisible] = useState(false);
  /** 编辑用户面板显示状态 */
  const [editVisible, setEditVisible] = useState(false);
  /** 当前编辑的用户 */
  const [editingUser, setEditingUser] = useState<User | null>(null);
  /** 编辑面板当前 Tab */
  const [activeTab, setActiveTab] = useState('info');
  /** 所有角色列表 */
  const [roles, setRoles] = useState<Role[]>([]);
  /** 用户已有角色ID列表 */
  // Role assignment remains disabled until this specific user has hydrated successfully.
  /** 用户表单实例 */
  const [form] = Form.useForm<UserEditorWrite>();
  /** 封禁理由弹窗 */
  const [banModalOpen, setBanModalOpen] = useState(false);
  const [banTargetId, setBanTargetId] = useState<number | null>(null);
  const [banReason, setBanReason] = useState('');
  /** 新建用户表单实例 */
  const [createForm] = Form.useForm<UserCreateWrite>();
  /** 密码表单实例 */
  const [passwordForm] = Form.useForm<{ password: string }>();
  /** 角色表单实例 */
  const [roleForm] = Form.useForm<{ roleIds: number[] }>();
  /** 用户类型枚举一律取数据字典 */
  const [dicts, setDicts] = useState<Record<string, DictOption[]>>({});
  const userTypeDict = dicts.user_type;
  /** 用户类型下拉选项（值仍为 ADMIN / APP，文案与颜色来自字典） */
  const userTypeOptions = (userTypeDict || []).map((o) => ({ label: o.label, value: o.value }));

  const hasPerm = usePerm();
  /** 是否具备用户维护权限：权限码需与后端 @RequiresPermission("admin:system:user:edit") 一致 */
  const canEdit = hasPerm('admin:system:user:edit');
  const isSuper = hasPerm('*:*:*');

  const loadData = useCallback(
    (page?: number, size?: number, keyword?: string, userType?: string) => {
      const q = query.read({
        ...(page === undefined ? {} : { current: page }),
        ...(size === undefined ? {} : { pageSize: size }),
        ...(keyword === undefined ? {} : { keyword }),
        ...(userType === undefined ? {} : { userType }),
      });
      return runLoad(
        async () => {
          const value = requireAdminSuccess(
            await userApi.getPage(q.current, q.pageSize, {
              username: q.keyword || undefined,
              userType: q.userType || undefined,
            }),
            '加载用户列表失败',
          );
          if (!value) throw new Error('用户分页数据缺失');
          return value;
        },
        (value) => {
          setData(value.records || []);
          setPagination({
            current: value.current || q.current,
            pageSize: value.size || q.pageSize,
            total: value.total || 0,
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
          const [map, response] = await Promise.all([
            fetchDictOptions(['user_type', 'enable_status']),
            isSuper ? roleApi.getAll() : Promise.resolve({ code: 200, data: [] as Role[] }),
          ]);
          const roleRows = requireAdminSuccess(response, '加载角色选项失败') || [];
          if (!map.user_type?.length) throw new Error('用户类型字典暂无选项');
          return { map, roleRows };
        },
        ({ map, roleRows }) => {
          setDicts(map);
          setRoles(roleRows);
        },
      ),
    [runOptions, isSuper],
  );
  const loadUserRoles = useCallback(
    (id: number) =>
      runUserRoles(
        async () => requireAdminSuccess(await userApi.getUserRoles(id), '加载用户角色失败') || [],
        (rows) => {
          if (editorId.current !== id) return;
          roleForm.setFieldsValue({ roleIds: rows.map((row) => row.id) });
          setHydratedUserId(id);
        },
      ),
    [runUserRoles, roleForm],
  );
  useEffect(() => {
    void loadData();
    void loadOptions();
  }, [loadData, loadOptions]);

  /** 打开新建用户弹窗 */
  const handleCreate = () => {
    if (!isSuper || isBusy() || optionsLoading || optionsError) return;
    createForm.resetFields();
    createForm.setFieldsValue({ userType: 'ADMIN' });
    setCreateVisible(true);
  };

  const openEdit = (record: User) => {
    if (!canEdit || isBusy()) return;
    invalidateUserRoles();
    editorId.current = record.id;
    setHydratedUserId(null);
    setEditingUser(record);
    form.resetFields();
    form.setFieldsValue({ ...userProfilePayload(record), userType: record.userType });
    roleForm.resetFields();
    passwordForm.resetFields();
    setActiveTab('info');
    setEditVisible(true);
    if (isSuper) void loadUserRoles(record.id);
  };
  const closeEditor = () => {
    if (isBusy()) return;
    editorId.current = null;
    invalidateUserRoles();
    setEditVisible(false);
  };
  const handleSubmitInfo = () => {
    if (!canEdit || !editingUser) return;
    const id = editingUser.id,
      originalType = editingUser.userType;
    return runMutation(
      async () => {
        const values = await form.validateFields();
        if (editorId.current !== id) throw new Error('编辑目标已变化，请重新打开');
        try {
          return await saveUserProfile(id, values, originalType, isSuper);
        } catch (failure: unknown) {
          if (failure instanceof UserPartialSaveError && editorId.current === id) {
            editorId.current = null;
            invalidateUserRoles();
            setEditVisible(false);
            setEditingUser(null);
          }
          throw failure;
        } finally {
          void loadData();
        }
      },
      ({ profile, userType, typeChanged }) => {
        message.success(typeChanged ? '资料与用户类型已保存，该用户需重新登录' : '保存成功');
        setEditingUser((current) =>
          current?.id === id ? { ...current, ...profile, userType } : current,
        );
      },
    );
  };

  const handleCreateSubmit = () => {
    if (!isSuper || optionsLoading || optionsError) return;
    return runMutation(
      async () =>
        requireAdminSuccess(await userApi.create(await createForm.validateFields()), '创建失败'),
      () => {
        message.success('创建成功');
        setCreateVisible(false);
        void loadData();
      },
    );
  };
  const handleDelete = (id: number) => {
    if (!isSuper) return;
    return runMutation(
      async () => requireAdminSuccess(await userApi.delete(id), '删除失败'),
      () => {
        message.success('删除成功');
        if (editorId.current === id) {
          editorId.current = null;
          invalidateUserRoles();
          setEditVisible(false);
          setEditingUser(null);
        }
        void loadData();
      },
    );
  };
  const handleResetPasswordSubmit = () => {
    if (!isSuper || !editingUser) return;
    const id = editingUser.id;
    return runMutation(
      async () => {
        const values = await passwordForm.validateFields();
        if (editorId.current !== id) throw new Error('编辑目标已变化');
        return requireAdminSuccess(
          await userApi.resetPassword(id, values.password),
          '密码重置失败',
        );
      },
      () => {
        message.success('密码重置成功');
        if (editorId.current === id) passwordForm.resetFields();
      },
    );
  };
  const handleUnban = (id: number) => {
    if (!canEdit) return;
    return runMutation(
      async () => requireAdminSuccess(await userApi.updateStatus(id, 1), '启用失败'),
      () => {
        message.success('已启用');
        setEditingUser((current) =>
          current?.id === id ? { ...current, status: 1, statusReason: undefined } : current,
        );
        void loadData();
      },
    );
  };
  const requestBan = (id: number) => {
    if (!canEdit || isBusy()) return;
    setBanTargetId(id);
    setBanReason('');
    setBanModalOpen(true);
  };
  const handleBanSubmit = () => {
    if (!canEdit || banTargetId === null) return;
    const id = banTargetId,
      reason = banReason.trim();
    return runMutation(
      async () => {
        if (!reason) throw new Error('封禁必须填写理由');
        return requireAdminSuccess(await userApi.updateStatus(id, 0, reason), '封禁失败');
      },
      () => {
        message.success('已封禁并强制下线');
        setBanModalOpen(false);
        setEditingUser((current) =>
          current?.id === id ? { ...current, status: 0, statusReason: reason } : current,
        );
        void loadData();
      },
    );
  };

  /** Export the last applied query via the explicit shared-client binary contract. */
  const handleExport = () => {
    const snapshot = query.read();
    return runExport(
      async () => {
        const blob = await userApi.exportCsv({
          username: snapshot.keyword || undefined,
          userType: snapshot.userType || undefined,
        });
        await downloadResponse(
          new Response(blob, { headers: { 'Content-Type': blob.type } }),
          `users-${new Date().toISOString().slice(0, 10)}.csv`,
        );
      },
      () => message.success('已触发下载'),
    );
  };

  const handleAssignRolesSubmit = () => {
    if (
      !isSuper ||
      !editingUser ||
      hydratedUserId !== editingUser.id ||
      rolesLoading ||
      optionsLoading ||
      optionsError
    )
      return;
    const id = editingUser.id;
    return runMutation(
      async () => {
        const values = await roleForm.validateFields();
        if (editorId.current !== id) throw new Error('编辑目标已变化');
        return requireAdminSuccess(
          await userApi.assignRoles(id, values.roleIds || []),
          '角色分配失败',
        );
      },
      () => message.success('角色分配成功'),
    );
  };
  const handleKickOut = () => {
    if (!canEdit || !editingUser) return;
    const id = editingUser.id;
    return runMutation(
      async () => requireAdminSuccess(await userApi.kickOut(id), '踢下线失败'),
      () => message.success('已踢下线'),
    );
  };

  /** 表格列定义 */
  const columns = [
    { title: '用户ID', dataIndex: 'id', key: 'id', width: 80 },
    { title: '用户名', dataIndex: 'username', key: 'username' },
    { title: '昵称', dataIndex: 'nickname', key: 'nickname' },
    { title: '邮箱', dataIndex: 'email', key: 'email' },
    { title: '手机号', dataIndex: 'phone', key: 'phone' },
    {
      title: '用户类型',
      dataIndex: 'userType',
      key: 'userType',
      render: (v: string) => (
        <Tag color={dictColor(userTypeDict, v) || 'default'}>
          {dictLabel(userTypeDict, v, v || '-')}
        </Tag>
      ),
    },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      render: (v: number, r: User) => (
        <Switch
          checked={v === 1}
          onChange={(checked) => (checked ? handleUnban(r.id) : requestBan(r.id))}
          disabled={!canEdit || saving}
          aria-label={`启用用户：${r.username}`}
          checkedChildren={dictLabel(dicts.enable_status, 1, '1')}
          unCheckedChildren={dictLabel(dicts.enable_status, 0, '0')}
        />
      ),
    },
    {
      title: '创建时间',
      dataIndex: 'createTime',
      key: 'createTime',
      render: (v: string) => formatDateTime(v),
    },
    {
      title: '操作',
      key: 'action',
      width: 120,
      render: (_: unknown, r: User) => (
        <Space>
          <Button
            type="link"
            icon={<EditOutlined />}
            onClick={() => openEdit(r)}
            disabled={!canEdit || saving}
          >
            编辑
          </Button>
        </Space>
      ),
    },
  ];

  /** 编辑面板 Tab 配置 */
  const editTabItems = editingUser
    ? [
        {
          key: 'info',
          label: '基本信息',
          children: (
            <Form form={form} disabled={saving} layout="vertical" style={{ marginTop: 8 }}>
              <Form.Item
                name="userType"
                label="用户类型"
                extra={
                  isSuper ? '修改后保存可变更用户归属（管理端/App）' : '类型变更仅限超级管理员'
                }
              >
                <Select
                  disabled={
                    !isSuper ||
                    saving ||
                    optionsLoading ||
                    !!optionsError ||
                    (editingUser.userType === 'ADMIN' && editingUser.username === 'admin')
                  }
                  options={userTypeOptions}
                />
              </Form.Item>
              <Form.Item label="用户名">
                <Input value={editingUser.username} disabled />
              </Form.Item>
              <Form.Item name="nickname" label="昵称">
                <Input />
              </Form.Item>
              <Form.Item name="avatar" label="头像地址">
                <Input placeholder="头像图片URL" />
              </Form.Item>
              <Form.Item name="email" label="邮箱">
                <Input />
              </Form.Item>
              <Form.Item name="phone" label="手机号">
                <Input />
              </Form.Item>
              <Space>
                <Button type="primary" onClick={handleSubmitInfo} disabled={!canEdit || saving}>
                  保存
                </Button>
              </Space>
            </Form>
          ),
        },
        {
          key: 'roles',
          label: '角色',
          children: (
            <Form
              form={roleForm}
              disabled={
                saving ||
                rolesLoading ||
                hydratedUserId !== editingUser.id ||
                optionsLoading ||
                !!optionsError
              }
              layout="vertical"
              style={{ marginTop: 8 }}
            >
              {rolesLoading && <AdminLoadingNotice label="正在加载该用户角色…" />}
              <AdminLoadNotice
                error={rolesError}
                hasData={false}
                onRetry={() => void loadUserRoles(editingUser.id)}
              />
              <Form.Item name="roleIds" label="分配角色">
                <Select
                  mode="multiple"
                  placeholder="选择角色"
                  options={roles.map((r) => ({ label: r.roleName, value: r.id }))}
                />
              </Form.Item>
              <Button
                type="primary"
                onClick={handleAssignRolesSubmit}
                disabled={
                  !isSuper ||
                  saving ||
                  rolesLoading ||
                  hydratedUserId !== editingUser.id ||
                  optionsLoading ||
                  !!optionsError
                }
              >
                保存角色
              </Button>
            </Form>
          ),
        },
        {
          key: 'account',
          label: '账户操作',
          children: (
            <div>
              <Typography.Title level={5}>账号状态</Typography.Title>
              <Space direction="vertical" style={{ width: '100%' }}>
                <div
                  style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}
                >
                  <span>
                    当前状态：
                    {dictLabel(dicts.enable_status, editingUser.status, String(editingUser.status))}
                  </span>
                  {editingUser.status === 1 ? (
                    <Button
                      danger
                      icon={<LockOutlined />}
                      disabled={!canEdit || saving}
                      onClick={() => requestBan(editingUser.id)}
                    >
                      封禁
                    </Button>
                  ) : (
                    <Button
                      icon={<DeleteOutlined />}
                      onClick={() => handleUnban(editingUser.id)}
                      disabled={!canEdit || saving}
                    >
                      解封
                    </Button>
                  )}
                </div>
                {editingUser.status === 0 && (
                  <div className="text-destructive">
                    封禁/注销理由：{editingUser.statusReason || '-'}
                  </div>
                )}
                <Button
                  icon={<LoginOutlined />}
                  onClick={handleKickOut}
                  disabled={!canEdit || saving}
                >
                  踢下线
                </Button>
              </Space>

              {isSuper && (
                <>
                  <Divider />
                  <Typography.Title level={5}>重置密码</Typography.Title>
                  <Form form={passwordForm} disabled={saving} layout="vertical">
                    <Form.Item
                      name="password"
                      label="新密码"
                      rules={[
                        { required: true, min: 8, message: '密码至少8位' },
                        {
                          pattern: /^(?=.*[a-zA-Z])(?=.*\d)\S+$/,
                          message: '必须同时包含字母和数字',
                        },
                      ]}
                    >
                      <Input.Password />
                    </Form.Item>
                    <Button
                      icon={<KeyOutlined />}
                      onClick={handleResetPasswordSubmit}
                      disabled={!isSuper || saving}
                    >
                      重置密码
                    </Button>
                  </Form>

                  <Divider />
                  <Typography.Title level={5} type="danger">
                    危险操作
                  </Typography.Title>
                  <Popconfirm
                    title="确认删除该用户?"
                    disabled={!canEdit || saving}
                    onConfirm={() => handleDelete(editingUser.id)}
                  >
                    <Button danger icon={<DeleteOutlined />} disabled={!canEdit || saving}>
                      删除该用户
                    </Button>
                  </Popconfirm>
                </>
              )}
            </div>
          ),
        },
      ].filter((item) => isSuper || item.key !== 'roles')
    : [];

  return (
    <div className="space-y-4">
      <PageHeader title="用户管理" description="后台账号、启用状态与角色归属维护" />
      <Card>
        <FilterBar split>
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: 12 }}>
            {/* 必须写回 search 状态，否则翻页时 loadData(p, ps, search) 传回空串、丢失搜索条件 */}
            <Input.Search
              placeholder="搜索用户名"
              aria-label="搜索用户名"
              allowClear
              onChange={(e) => setSearch(e.target.value)}
              onSearch={(v) => {
                setSearch(v);
                loadData(1, pagination.pageSize, v);
              }}
              className="w-full sm:w-[300px]"
            />
            <Select
              value={typeFilter}
              style={{ width: 140 }}
              allowClear
              placeholder="用户类型"
              aria-label="筛选用户类型"
              options={userTypeOptions}
              onChange={(v) => {
                setTypeFilter(v || '');
                loadData(1, pagination.pageSize, search, v || '');
              }}
            />
          </div>
          <Space>
            <Button
              icon={<LoginOutlined />}
              loading={exporting}
              disabled={exporting || loading || !!loadError}
              onClick={handleExport}
            >
              导出 CSV
            </Button>
            {isSuper && (
              <Button
                type="primary"
                icon={<PlusOutlined />}
                onClick={handleCreate}
                disabled={saving || optionsLoading || !!optionsError}
              >
                新建用户
              </Button>
            )}
          </Space>
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
            onChange: (p, ps) => loadData(p, ps),
            showQuickJumper: true,
            showSizeChanger: true,
            pageSizeOptions: ['5', '10', '20', '50', '100'],
          }}
        />
      </Card>

      {/* 新建用户弹窗 */}
      <Modal
        title="新建用户"
        open={createVisible}
        onOk={handleCreateSubmit}
        confirmLoading={saving}
        okButtonProps={{ disabled: !isSuper || optionsLoading || !!optionsError }}
        cancelButtonProps={{ disabled: saving }}
        onCancel={() => {
          if (!isBusy()) setCreateVisible(false);
        }}
      >
        <Form form={createForm} disabled={saving} layout="vertical" style={{ marginTop: 16 }}>
          <Form.Item name="userType" label="用户类型" rules={[{ required: true }]}>
            <Select options={userTypeOptions} />
          </Form.Item>
          <Form.Item name="username" label="用户名" rules={[{ required: true }]}>
            <Input />
          </Form.Item>
          <Form.Item
            name="password"
            label="密码"
            rules={[
              { required: true },
              { min: 8, message: '密码至少 8 位' },
              { pattern: /^(?=.*[a-zA-Z])(?=.*\d)\S+$/, message: '必须同时包含字母和数字' },
            ]}
            extra="至少 8 位，须同时包含字母和数字"
          >
            <Input.Password />
          </Form.Item>
          <Form.Item name="nickname" label="昵称">
            <Input />
          </Form.Item>
          <Form.Item name="email" label="邮箱">
            <Input />
          </Form.Item>
          <Form.Item name="phone" label="手机号">
            <Input />
          </Form.Item>
        </Form>
      </Modal>

      {/* 编辑用户完整面板 */}
      <Modal
        title={editingUser ? `编辑用户：${editingUser.username}` : '编辑用户'}
        open={editVisible}
        width={760}
        footer={null}
        onCancel={closeEditor}
      >
        <Tabs
          activeKey={!isSuper && activeTab === 'roles' ? 'info' : activeTab}
          onChange={setActiveTab}
          items={editTabItems}
        />
      </Modal>

      {/* 封禁理由弹窗（封禁必填理由，后端强校验） */}
      <Modal
        title="封禁用户"
        open={banModalOpen}
        onOk={handleBanSubmit}
        okText="确认封禁"
        confirmLoading={saving}
        okButtonProps={{ danger: true, disabled: !canEdit }}
        cancelButtonProps={{ disabled: saving }}
        onCancel={() => {
          if (!isBusy()) setBanModalOpen(false);
        }}
      >
        <div style={{ marginTop: 16 }}>
          <div style={{ marginBottom: 8 }}>
            封禁后该用户将被强制下线且无法登录。请填写封禁理由（用户管理列表可见）：
          </div>
          <Input.TextArea
            rows={3}
            aria-label="封禁理由"
            disabled={saving}
            value={banReason}
            onChange={(e) => setBanReason(e.target.value)}
            placeholder="例如：违规使用 / 刷量 / 安全事件"
            maxLength={200}
            showCount
          />
        </div>
      </Modal>
    </div>
  );
}
