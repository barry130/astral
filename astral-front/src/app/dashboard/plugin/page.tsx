'use client';

import { useEffect, useState } from 'react';
import { Card, Tag, Switch, Button, message, Space, Empty, Tooltip } from '@/components/antd-compat';
import { ApiOutlined, ReloadOutlined, CheckCircleOutlined, CloseCircleOutlined, LockOutlined } from '@/components/antd-compat/icons';
import { pluginApi, PluginInfo } from '@/api/plugin';
import { usePerm } from '@/lib/perm';
import { ResizableTable } from '@/components/ResizableTable';

export default function PluginPage() {
  const [loading, setLoading] = useState(false);
  const [plugins, setPlugins] = useState<PluginInfo[]>([]);
  const hasPerm = usePerm();
  /** 是否具备插件启停权限（须与后端 PluginController 上的 @RequiresPermission("admin:plugin:edit") 一致） */
  const canEdit = hasPerm('admin:plugin:edit');

  const loadData = () => {
    setLoading(true);
    pluginApi.getPluginList()
      .then((res) => {
        if (res.code === 200) setPlugins(res.data);
      })
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    loadData();
  }, []);

  const togglePlugin = async (plugin: PluginInfo, enabled: boolean) => {
    if (plugin.required && !enabled) {
      message.warning(`${plugin.pluginName} 是系统内置插件，不允许禁用`);
      return;
    }
    try {
      if (enabled) {
        await pluginApi.enablePlugin(plugin.pluginId);
      } else {
        await pluginApi.disablePlugin(plugin.pluginId);
      }
      message.success(`${plugin.pluginName} 已${enabled ? '启用' : '禁用'}`);
      loadData();
    } catch (error: any) {
      message.error(error.message || '操作失败');
    }
  };

  const columns = [
    { title: '插件名称', dataIndex: 'pluginName', width: 180 },
    { title: '插件ID', dataIndex: 'pluginId', width: 150 },
    {
      title: '类型',
      dataIndex: 'required',
      width: 100,
      render: (required: boolean) => required
        ? <Tag color="red" icon={<LockOutlined />}>系统内置</Tag>
        : <Tag color="blue">普通插件</Tag>,
    },
    { title: '版本', dataIndex: 'version', width: 80 },
    { title: '描述', dataIndex: 'description', ellipsis: true },
    {
      title: '状态',
      dataIndex: 'enabled',
      width: 100,
      render: (enabled: boolean) => (
        <Tag color={enabled ? 'success' : 'default'} icon={enabled ? <CheckCircleOutlined /> : <CloseCircleOutlined />}>
          {enabled ? '已启用' : '已禁用'}
        </Tag>
      ),
    },
    {
      title: '操作',
      key: 'action',
      width: 120,
      render: (_: any, record: PluginInfo) =>
        record.required ? (
          <Tooltip title="系统内置插件，不允许禁用">
            <Switch checked disabled checkedChildren="启用" unCheckedChildren="禁用" />
          </Tooltip>
        ) : (
          <Space>
            <Switch
              checked={record.enabled}
              onChange={(checked) => togglePlugin(record, checked)}
              disabled={!canEdit}
              checkedChildren="启用"
              unCheckedChildren="禁用"
            />
            {!canEdit && <Tag>只读</Tag>}
          </Space>
        ),
    },
  ];

  return (
    <div>
      <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <div>
          <h2 style={{ margin: 0 }}>插件管理</h2>
          <p style={{ color: '#909399', margin: '4px 0 0' }}>管理系统插件，支持启用/禁用及后续扩展</p>
        </div>
        <Button icon={<ReloadOutlined />} onClick={loadData} loading={loading}>刷新</Button>
      </div>
      <Card>
        {plugins.length === 0 ? (
          <Empty description="暂无插件，可在 astral-plugin 中新增业务插件" />
        ) : (
          <ResizableTable
            rowKey="pluginId"
            columns={columns}
            dataSource={plugins}
            loading={loading}
            pagination={false}
          />
        )}
      </Card>
    </div>
  );
}
