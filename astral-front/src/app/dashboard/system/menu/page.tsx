'use client';

import { useEffect, useMemo, useRef, useState } from 'react';
import { Card, Button, Space, Modal, Form, Input, InputNumber, Select, Switch, Tag, Popconfirm, message } from '@/components/antd-compat';
import { PlusOutlined, EditOutlined, DeleteOutlined, MenuOutlined, HolderOutlined } from '@/components/antd-compat/icons';
import { menuApi, SysMenu } from '@/api/menu';
import { getNavExtensions, NavExtension } from '@/api/plugin';
import { usePerm } from '@/lib/perm';
import { ResizableTable } from '@/components/ResizableTable';
import { fetchDictOptions, DictOption } from '@/api/dict';

/**
 * 菜单类型字典编码（文案来自数据字典 menu_type，禁止前端硬编码）。
 *
 * ⚠️ 不要复用 `permission_type`：`sys_menu.type`（0目录/1菜单/2按钮）与
 * `sys_permission.type`（1目录/2菜单/3按钮/4接口/5数据）是**两套错开一位**的枚举，
 * 同一个「目录」一个是 0、一个是 1，复用会把标签渲染错。
 */
const MENU_TYPE_DICT = 'menu_type';

const iconOptions = [
  'DashboardOutlined', 'ApiOutlined', 'BarChartOutlined', 'ClusterOutlined',
  'SettingOutlined', 'TeamOutlined', 'SafetyOutlined', 'BookOutlined',
  'KeyOutlined', 'TableOutlined', 'MenuOutlined', 'FileTextOutlined',
  'BlockOutlined', 'UserOutlined', 'ToolOutlined', 'LogoutOutlined',
];

/** 在树中定位节点及其兄弟数组 */
const findNodeContext = (
  nodes: SysMenu[],
  id: number,
): { node: SysMenu; siblings: SysMenu[]; index: number } | null => {
  for (let i = 0; i < nodes.length; i++) {
    if (nodes[i].id === id) return { node: nodes[i], siblings: nodes, index: i };
    if (nodes[i].children) {
      const found = findNodeContext(nodes[i].children!, id);
      if (found) return found;
    }
  }
  return null;
};

/** 收集某节点及其所有后代的 id */
const collectSubtreeIds = (node: SysMenu, acc: Set<number>) => {
  acc.add(node.id);
  node.children?.forEach((c) => collectSubtreeIds(c, acc));
  return acc;
};

/** 收集某节点的所有后代 id（含自身），用于编辑时排除自身及子孙 */
const getDescendantIds = (tree: SysMenu[], id: number): Set<number> => {
  const ctx = findNodeContext(tree, id);
  const set = new Set<number>();
  if (ctx) collectSubtreeIds(ctx.node, set);
  return set;
};

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
  const [loading, setLoading] = useState(false);
  const [data, setData] = useState<SysMenu[]>([]);
  const [modalOpen, setModalOpen] = useState(false);
  const [editRecord, setEditRecord] = useState<SysMenu | null>(null);
  const [form] = Form.useForm();
  const [typeOptions, setTypeOptions] = useState<DictOption[]>([]);
  const [pluginNavItems, setPluginNavItems] = useState<NavExtension[]>([]);
  const dragIdRef = useRef<number | null>(null);

  const hasPerm = usePerm();
  /** 是否具备菜单维护权限：权限码需与后端 @RequiresPermission("admin:system:menu:edit") 一致 */
  const canEdit = hasPerm('admin:system:menu:edit');

  const loadData = () => {
    setLoading(true);
    menuApi.getTree().then(res => {
      if (res.code === 200) setData(res.data);
    }).finally(() => setLoading(false));
  };

  useEffect(() => {
    loadData();
    fetchDictOptions([MENU_TYPE_DICT])
      .then((map) => setTypeOptions(map[MENU_TYPE_DICT] || []))
      .catch(() => {});
    // 插件声明的导航项：只读合并展示（失败不影响菜单管理主流程）
    getNavExtensions()
      .then((items) => setPluginNavItems(items || []))
      .catch(() => {});
  }, []);

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
    const exclude = excludeId ? getDescendantIds(data, excludeId) : new Set<number>();
    const walk = (nodes: SysMenu[], depth: number) => {
      nodes.forEach(n => {
        if (exclude.has(n.id)) return;
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
    form.setFieldsValue(record);
    setModalOpen(true);
  };

  const handleDelete = async (id: number) => {
    const res = await menuApi.delete(id);
    if (res.code === 200) { message.success('已删除'); loadData(); }
  };

  const handleSave = async () => {
    const values = await form.validateFields();
    const res = editRecord
      ? await menuApi.update(editRecord.id, values)
      : await menuApi.create(values);
    if (res.code === 200) { message.success(editRecord ? '已更新' : '已创建'); setModalOpen(false); loadData(); }
  };

  /** 拖拽落下：同父级重排，跨父级则移动并改 parentId */
  const handleDrop = async (targetId: number) => {
    if (!canEdit) return; // 无菜单维护权限时不响应拖拽排序
    if (targetId < 0) return; // 插件声明虚拟行不能作为落点
    const dragId = dragIdRef.current;
    dragIdRef.current = null;
    if (!dragId || dragId === targetId) return;
    if (dragId < 0) return; // 插件声明虚拟行不能拖动
    const src = findNodeContext(data, dragId);
    const tgt = findNodeContext(data, targetId);
    if (!src || !tgt) return;
    if (src.node.id === tgt.node.id) return;
    // 不能拖进自己的子孙节点
    if (collectSubtreeIds(src.node, new Set()).has(tgt.node.id)) return;

    const moved = src.siblings.splice(src.index, 1)[0];
    moved.parentId = tgt.node.parentId;
    tgt.siblings.splice(tgt.index, 0, moved);

    const updates: { id: number; sort: number; parentId?: number }[] = [];
    const reassign = (sibs: SysMenu[]) =>
      sibs.forEach((n, i) =>
        updates.push({ id: n.id, sort: i, ...(n.id === moved.id ? { parentId: moved.parentId } : {}) }),
      );
    reassign(src.siblings);
    reassign(tgt.siblings);

    setData([...data]);
    try {
      await Promise.all(updates.map(u => menuApi.update(u.id, { sort: u.sort, parentId: u.parentId })));
      message.success('排序已保存');
    } catch {
      message.error('保存失败');
      loadData();
    }
  };

  const columns = [
    {
      title: '拖拽',
      key: 'drag',
      width: 50,
      render: (_: unknown, record: DisplayMenu) =>
        record.pluginDeclared
          ? <span className="text-muted-foreground">—</span>
          : <HolderOutlined style={{ color: '#999', cursor: 'move' }} />,
    },
    {
      title: '菜单名称',
      dataIndex: 'name',
      key: 'name',
      render: (v: string, record: DisplayMenu) => (
        <span className="inline-flex items-center gap-1.5">
          <span>{v}</span>
          {record.pluginDeclared && <Tag color="purple" style={{ marginInlineEnd: 0 }}>插件声明</Tag>}
        </span>
      ),
    },
    { title: '图标', dataIndex: 'icon', key: 'icon', render: (v: string) => v || '-' },
    { title: '路由', dataIndex: 'path', key: 'path', render: (v: string) => v ? <Tag>{v}</Tag> : '-' },
    { title: '权限', dataIndex: 'permission', key: 'permission', render: (v: string) => v ? <Tag color="blue">{v}</Tag> : '-' },
    { title: '排序', dataIndex: 'sort', key: 'sort', width: 60 },
    {
      title: '可见', dataIndex: 'visible', key: 'visible', width: 60,
      render: (v: number) => v === 1 ? <Tag color="green">是</Tag> : <Tag color="red">否</Tag>,
    },
    {
      title: '操作', key: 'action', width: 160,
      render: (_: unknown, record: DisplayMenu) => record.pluginDeclared ? (
        <span className="text-xs text-muted-foreground">插件代码声明，只读</span>
      ) : (
        <Space>
          <Button type="link" size="small" icon={<EditOutlined />} onClick={() => handleEdit(record)} disabled={!canEdit}>编辑</Button>
          <Popconfirm title="确认删除？" disabled={!canEdit} onConfirm={() => handleDelete(record.id)}>
            <Button type="link" size="small" danger icon={<DeleteOutlined />} disabled={!canEdit}>删除</Button>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <div>
      <Card
        title={<><MenuOutlined /> 菜单管理</>}
        extra={canEdit && <Button type="primary" icon={<PlusOutlined />} onClick={handleCreate}>新增菜单</Button>}
      >
        <ResizableTable
          columns={columns}
          dataSource={displayData}
          rowKey="id"
          loading={loading}
          scroll={{ x: 'max-content' }}
          pagination={false}
          defaultExpandAllRows
          onRow={(record: DisplayMenu) => ({
            draggable: canEdit && !record.pluginDeclared,
            style: { cursor: canEdit && !record.pluginDeclared ? 'move' : 'default' },
            onDragStart: () => { dragIdRef.current = record.id; },
            onDragOver: (e: React.DragEvent) => e.preventDefault(),
            onDrop: () => handleDrop(record.id),
          })}
        />
      </Card>

      <Modal
        title={editRecord ? '编辑菜单' : '新增菜单'}
        open={modalOpen}
        onCancel={() => setModalOpen(false)}
        onOk={handleSave}
        width={600}
      >
        <Form form={form} layout="vertical">
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
            <Select allowClear placeholder="选择图标" options={iconOptions.map(i => ({ value: i, label: i }))} />
          </Form.Item>
          <Form.Item name="path" label="路由路径">
            <Input placeholder="/dashboard/xxx" />
          </Form.Item>
          <Form.Item name="permission" label="权限标识">
            <Input placeholder="module:action" />
          </Form.Item>
          <Space style={{ width: '100%' }} size="large">
            <Form.Item name="sort" label="排序">
              <InputNumber />
            </Form.Item>
            <Form.Item name="visible" label="可见" valuePropName="checked">
              <Switch disabled={!canEdit} />
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
