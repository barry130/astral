'use client';

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  Card,
  Button,
  Space,
  Modal,
  Form,
  Input,
  InputNumber,
  Select,
  Switch,
  Tag,
  Popconfirm,
  message,
} from '@/components/antd-compat';
import {
  PlusOutlined,
  EditOutlined,
  DeleteOutlined,
  MenuOutlined,
  HolderOutlined,
} from '@/components/antd-compat/icons';
import {
  menuApi,
  menuWritePayload,
  persistMenuOrder,
  type SysMenu,
  type MenuWrite,
} from '@/api/menu';
import { AdminLoadNotice, AdminOptionNotice } from '@/components/admin/AdminLoadNotice';
import { useAdminLoader, useAdminMutation } from '@/components/admin/use-admin-task';
import { numericSwitchBinding, requireAdminSuccess } from '@/components/admin/admin-task';
import {
  indexMenuSiblingTargets,
  menuDescendantIds,
  planMenuMove,
} from '@/components/admin/menu-move';
import { getNavExtensions, NavExtension } from '@/api/plugin';
import { usePerm } from '@/lib/perm';
import { ResizableTable } from '@/components/ResizableTable';
import {
  loadAdminDictOptions as fetchDictOptions,
  dictLabel,
  type DictOption,
} from '@/components/admin/admin-dictionaries';
import { PageHeader } from '@/components/page-header';

/**
 * 菜单类型字典编码（文案来自数据字典 menu_type，禁止前端硬编码）。
 *
 * ⚠️ 不要复用 `permission_type`：`sys_menu.type`（0目录/1菜单/2按钮）与
 * `sys_permission.type`（1目录/2菜单/3按钮/4接口/5数据）是**两套错开一位**的枚举，
 * 同一个「目录」一个是 0、一个是 1，复用会把标签渲染错。
 */
const MENU_TYPE_DICT = 'menu_type';

const iconOptions = [
  'DashboardOutlined',
  'ApiOutlined',
  'BarChartOutlined',
  'ClusterOutlined',
  'SettingOutlined',
  'TeamOutlined',
  'SafetyOutlined',
  'BookOutlined',
  'KeyOutlined',
  'TableOutlined',
  'MenuOutlined',
  'FileTextOutlined',
  'BlockOutlined',
  'UserOutlined',
  'ToolOutlined',
  'LogoutOutlined',
];

/**
 * 展示用菜单行：sys_menu 真实行 + 插件声明产生的「虚拟行」。
 *
 * 插件导航项（`PluginFrontendExtension.NavItem`）是代码里声明的，不落 sys_menu 表。
 * 如果菜单管理只展示表数据，管理员会以为「轻听/反馈/文件存储这些入口漏登记了」，
 * 于是手工再建一条 —— 结果侧边栏出现重复入口。这里把它们合并进来只读展示：
 * id 取负数（不可能与真实主键冲突，真实 id 由全局序列保证 ≥ 1），标「插件声明」，
 * 禁止编辑/删除/拖拽（改了也不会生效，下次启动仍以插件声明为准）。
 */
type DisplayMenu = SysMenu & { pluginDeclared?: boolean };

export default function MenuPage() {
  const { loading, error: loadError, run: runLoad } = useAdminLoader('加载菜单失败');
  const {
    loading: optionsLoading,
    error: optionsError,
    run: runOptions,
  } = useAdminLoader('加载菜单选项失败');
  const { busy: saving, run: runMutation } = useAdminMutation('菜单操作失败');
  const [data, setData] = useState<SysMenu[]>([]);
  const [modalOpen, setModalOpen] = useState(false);
  const [editRecord, setEditRecord] = useState<SysMenu | null>(null);
  const [form] = Form.useForm<MenuWrite>();
  const [typeOptions, setTypeOptions] = useState<DictOption[]>([]);
  const [visibleOptions, setVisibleOptions] = useState<DictOption[]>([]);
  const [pluginNavItems, setPluginNavItems] = useState<NavExtension[]>([]);
  const dragIdRef = useRef<number | null>(null);

  const hasPerm = usePerm();
  /** 是否具备菜单维护权限：权限码需与后端 @RequiresPermission("admin:system:menu:edit") 一致 */
  const canEdit = hasPerm('admin:system:menu:edit');

  const loadData = useCallback(
    () =>
      runLoad(
        async () => requireAdminSuccess(await menuApi.getTree(), '加载菜单失败') || [],
        setData,
      ),
    [runLoad],
  );
  const loadOptions = useCallback(
    () =>
      runOptions(
        async () => {
          const [map, items] = await Promise.all([
            fetchDictOptions([MENU_TYPE_DICT, 'qt_yes_no']),
            getNavExtensions(),
          ]);
          if (!map[MENU_TYPE_DICT]?.length) throw new Error('菜单类型字典暂无选项');
          return { map, items };
        },
        ({ map, items }) => {
          setTypeOptions(map[MENU_TYPE_DICT]);
          setVisibleOptions(map.qt_yes_no || []);
          setPluginNavItems(items || []);
        },
      ),
    [runOptions],
  );
  useEffect(() => {
    void loadData();
    void loadOptions();
  }, [loadData, loadOptions]);
  const moveTargets = useMemo(() => indexMenuSiblingTargets(data), [data]);

  /**
   * 展示用树 = sys_menu 树 + 插件声明虚拟行（只读）。
   * 深拷贝后再挂虚拟行：`data` 仍是纯粹的数据库视图，拖拽/编辑只作用于它，
   * 避免虚拟行混进 `menuApi.update` 的入参（负数 id 打过去必然 404/500）。
   */
  const displayData = useMemo<DisplayMenu[]>(() => {
    if (pluginNavItems.length === 0) return data as DisplayMenu[];
    const clone = structuredClone(data) as DisplayMenu[];
    const byPath = new Map<string, DisplayMenu>();
    const walk = (nodes: DisplayMenu[]) => {
      for (const n of nodes) {
        if (n.path) byPath.set(n.path, n);
        if (n.children && n.children.length > 0) walk(n.children as DisplayMenu[]);
      }
    };
    walk(clone);

    const tops: DisplayMenu[] = [];
    pluginNavItems.forEach((n, i) => {
      const parent = n.parentPath ? byPath.get(n.parentPath) : undefined;
      const virtual: DisplayMenu = {
        id: -(i + 1),
        name: n.label,
        path: n.path,
        icon: n.icon || '',
        permission: n.permission || '',
        type: 1,
        visible: 1,
        sort: n.sort ?? 0,
        parentId: parent ? parent.id : 0,
        children: [],
        pluginDeclared: true,
      };
      if (parent) {
        if (!parent.children) parent.children = [];
        (parent.children as DisplayMenu[]).push(virtual);
      } else {
        tops.push(virtual);
      }
    });
    tops.sort((a, b) => (a.sort ?? 0) - (b.sort ?? 0));
    clone.push(...tops);
    return clone;
  }, [data, pluginNavItems]);

  /** 父菜单下拉选项：展示中文名，带层级缩进 */
  const buildParentOptions = (excludeId?: number) => {
    const opts: { value: number; label: string }[] = [{ value: 0, label: '顶级菜单' }];
    const exclude = excludeId ? menuDescendantIds(data, excludeId) : new Set<number>();
    const walk = (nodes: SysMenu[], depth: number) => {
      nodes.forEach((n) => {
        if (exclude.has(n.id)) return;
        if (n.id > 0 && n.type !== 2)
          opts.push({ value: n.id, label: '　'.repeat(depth) + n.name });
        if (n.children) walk(n.children, depth + 1);
      });
    };
    walk(data, 0);
    return opts;
  };

  const handleCreate = () => {
    setEditRecord(null);
    form.resetFields();
    form.setFieldsValue({ visible: 1, type: 0, sort: 0, parentId: 0 });
    setModalOpen(true);
  };

  const handleEdit = (record: SysMenu) => {
    setEditRecord(record);
    form.resetFields();
    form.setFieldsValue(menuWritePayload(record));
    setModalOpen(true);
  };

  const handleDelete = (id: number) => {
    if (!canEdit || id <= 0) return;
    return runMutation(
      async () => requireAdminSuccess(await menuApi.delete(id), '删除失败'),
      () => {
        message.success('已删除');
        void loadData();
      },
    );
  };
  const handleSave = () => {
    if (!canEdit || optionsLoading || optionsError) return;
    const id = editRecord?.id;
    return runMutation(
      async () => {
        const values = await form.validateFields();
        return requireAdminSuccess(
          id ? await menuApi.update(id, values) : await menuApi.create(values),
          '保存失败',
        );
      },
      () => {
        message.success(id ? '已更新' : '已创建');
        setModalOpen(false);
        void loadData();
      },
    );
  };
  const handleMove = (
    sourceId: number,
    targetId: number,
    position: 'before' | 'after' = 'before',
  ) => {
    if (!canEdit || saving || sourceId <= 0 || targetId <= 0 || sourceId === targetId) return;
    return runMutation(
      async () => {
        const plan = planMenuMove(data, sourceId, targetId, position);
        try {
          return await persistMenuOrder(plan.updates);
        } finally {
          void loadData();
        } // Reconcile confirmed/partial writes with server truth, never optimistic mutation.
      },
      (count) => {
        if (count) message.success('排序已保存');
      },
    );
  };
  const handleDrop = (targetId: number) => {
    const sourceId = dragIdRef.current;
    dragIdRef.current = null;
    if (sourceId) return handleMove(sourceId, targetId);
  };

  const columns = [
    {
      title: '拖拽',
      key: 'drag',
      width: 50,
      render: (_: unknown, record: DisplayMenu) =>
        record.pluginDeclared ? (
          <span className="text-muted-foreground">—</span>
        ) : (
          <HolderOutlined className="text-muted-foreground" />
        ),
    },
    {
      title: '菜单名称',
      dataIndex: 'name',
      key: 'name',
      render: (v: string, record: DisplayMenu) => (
        <span className="inline-flex items-center gap-1.5">
          <span>{v}</span>
          {record.pluginDeclared && (
            <Tag color="purple" style={{ marginInlineEnd: 0 }}>
              插件声明
            </Tag>
          )}
        </span>
      ),
    },
    { title: '图标', dataIndex: 'icon', key: 'icon', render: (v: string) => v || '-' },
    {
      title: '路由',
      dataIndex: 'path',
      key: 'path',
      render: (v: string) => (v ? <Tag>{v}</Tag> : '-'),
    },
    {
      title: '权限',
      dataIndex: 'permission',
      key: 'permission',
      render: (v: string) => (v ? <Tag color="blue">{v}</Tag> : '-'),
    },
    { title: '排序', dataIndex: 'sort', key: 'sort', width: 60 },
    {
      title: '可见',
      dataIndex: 'visible',
      key: 'visible',
      width: 60,
      render: (v: number) => (
        <Tag color={v === 1 ? 'green' : 'default'}>{dictLabel(visibleOptions, v, String(v))}</Tag>
      ),
    },
    {
      title: '操作',
      key: 'action',
      width: 240,
      render: (_: unknown, record: DisplayMenu) =>
        record.pluginDeclared ? (
          <span className="text-muted-foreground text-xs">插件代码声明，只读</span>
        ) : (
          <Space>
            <Button
              type="link"
              size="small"
              icon={<EditOutlined />}
              onClick={() => handleEdit(record)}
              disabled={!canEdit || saving}
            >
              编辑
            </Button>
            <Popconfirm
              title="确认删除？"
              disabled={!canEdit || saving}
              onConfirm={() => handleDelete(record.id)}
            >
              <Button
                type="link"
                size="small"
                danger
                icon={<DeleteOutlined />}
                disabled={!canEdit || saving}
              >
                删除
              </Button>
            </Popconfirm>
            {(['up', 'down'] as const).map((direction) => {
              const targets = moveTargets.get(record.id);
              const target = direction === 'up' ? targets?.previousId : targets?.nextId;
              return (
                <Button
                  key={direction}
                  size="small"
                  disabled={!canEdit || saving || !target}
                  aria-label={`${direction === 'up' ? '上移' : '下移'}菜单：${record.name}`}
                  onClick={() => {
                    if (target)
                      void handleMove(record.id, target, direction === 'up' ? 'before' : 'after');
                  }}
                >
                  {direction === 'up' ? '上移' : '下移'}
                </Button>
              );
            })}
          </Space>
        ),
    },
  ];

  return (
    <div className="space-y-4">
      <PageHeader title="菜单管理" description="后台菜单结构、图标与权限标识维护" />
      <Card
        title={
          <>
            <MenuOutlined /> 菜单管理
          </>
        }
        extra={
          canEdit && (
            <Button
              type="primary"
              icon={<PlusOutlined />}
              onClick={handleCreate}
              disabled={saving || optionsLoading || !!optionsError}
            >
              新增菜单
            </Button>
          )
        }
      >
        <AdminLoadNotice
          error={loadError}
          hasData={data.length > 0}
          onRetry={() => void loadData()}
        />
        <AdminOptionNotice error={optionsError} onRetry={() => void loadOptions()} />
        <ResizableTable
          columns={columns}
          dataSource={displayData}
          rowKey="id"
          loading={loading}
          scroll={{ x: 'max-content' }}
          pagination={false}
          defaultExpandAllRows
          onRow={(record: DisplayMenu) => ({
            draggable: canEdit && !saving && !record.pluginDeclared,
            style: { cursor: canEdit && !saving && !record.pluginDeclared ? 'move' : 'default' },
            onDragStart: () => {
              if (canEdit && !saving && !record.pluginDeclared) dragIdRef.current = record.id;
            },
            onDragEnd: () => {
              dragIdRef.current = null;
            },
            onDragOver: (e: React.DragEvent) => e.preventDefault(),
            onDrop: () => handleDrop(record.id),
          })}
        />
      </Card>

      <Modal
        title={editRecord ? '编辑菜单' : '新增菜单'}
        open={modalOpen}
        onCancel={() => {
          if (!saving) setModalOpen(false);
        }}
        onOk={handleSave}
        confirmLoading={saving}
        okButtonProps={{ disabled: !canEdit || optionsLoading || !!optionsError }}
        cancelButtonProps={{ disabled: saving }}
        width={760}
      >
        <Form form={form} disabled={saving} layout="vertical">
          <Form.Item name="parentId" label="父菜单" rules={[{ required: true }]}>
            <Select
              showSearch
              optionFilterProp="label"
              placeholder="选择父菜单"
              options={buildParentOptions(editRecord?.id)}
            />
          </Form.Item>
          <Form.Item name="name" label="菜单名称" rules={[{ required: true }]}>
            <Input />
          </Form.Item>
          <Form.Item name="icon" label="图标名称">
            <Select
              allowClear
              placeholder="选择图标"
              options={iconOptions.map((i) => ({ value: i, label: i }))}
            />
          </Form.Item>
          <Form.Item name="path" label="路由路径">
            <Input placeholder="/dashboard/xxx" />
          </Form.Item>
          <Form.Item name="permission" label="权限标识">
            <Input placeholder="module:action" />
          </Form.Item>
          <Space style={{ width: '100%' }} size="large" wrap>
            <Form.Item name="sort" label="排序">
              <InputNumber />
            </Form.Item>
            <Form.Item name="visible" label="可见" {...numericSwitchBinding}>
              <Switch disabled={!canEdit || saving} />
            </Form.Item>
            <Form.Item name="type" label="类型">
              {/* 文案统一走数据字典 menu_type，前端不再硬编码「目录/菜单/按钮」 */}
              <Select
                style={{ width: 120 }}
                options={typeOptions.map((o) => ({ value: Number(o.value), label: o.label }))}
              />
            </Form.Item>
          </Space>
        </Form>
      </Modal>
    </div>
  );
}
