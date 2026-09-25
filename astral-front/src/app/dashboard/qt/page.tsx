'use client';

import { useEffect, useState } from 'react';
import { Card, Row, Col, Statistic, Tabs, Tag, Button, Input,
  Form, Modal, Space, message, Switch, Popconfirm, Typography, Select } from 'antd';
import {
  UserOutlined, CalendarOutlined, CloudDownloadOutlined,
  ReloadOutlined, PlusOutlined, ExperimentOutlined, ClearOutlined,
} from '@ant-design/icons';
import {
  qtAdminApi, githubAccelApi, QtOverview, QtUpdate, QtPage,
  QtGithubAccel, QtGithubAccelProbe, enumLabel,
} from '@/api/qt';
import { fetchDictOptions } from '@/api/dict';
import { ResizableTable } from '@/components/ResizableTable';
import SourceReleasesTab from './SourceReleasesTab';

const { Paragraph } = Typography;

export default function QtAdminPage() {
  const [overview, setOverview] = useState<QtOverview | null>(null);
  const [loading, setLoading] = useState(false);

  // 版本更新
  const [updates, setUpdates] = useState<QtUpdate[]>([]);
  const [updateTotal, setUpdateTotal] = useState(0);
  const [updatePage, setUpdatePage] = useState(1);
  const [updatesLoading, setUpdatesLoading] = useState(false);
  const [updateModal, setUpdateModal] = useState(false);
  const [updateForm] = Form.useForm();

  // GitHub 加速节点（UPDATE_DESIGN.md）
  const [accels, setAccels] = useState<QtGithubAccel[]>([]);
  const [accelTotal, setAccelTotal] = useState(0);
  const [accelPage, setAccelPage] = useState(1);
  const [accelsLoading, setAccelsLoading] = useState(false);
  const [accelModal, setAccelModal] = useState(false);
  const [accelForm] = Form.useForm();
  const [probing, setProbing] = useState(false);
  /** 探活结果按节点 id 归并到列表行内展示（仅展示，不写库） */
  const [probeMap, setProbeMap] = useState<Record<number, QtGithubAccelProbe>>({});

  // 数据字典选项（按 dict_code 动态拉取，避免写死枚举值）
  const [dict, setDict] = useState<Record<string, { value: string; label: string }[]>>({});

  // 由数据字典派生出的下拉选项（数值类将 value 转为 number 以便比较）
  const updatePlatformOpts = (dict.qt_update_platform || []).map((o) => ({ value: Number(o.value), label: o.label }));
  const updateTypeOpts = dict.qt_update_type || [];
  const updateChannelOpts = dict.qt_update_channel || [];
  const updatePublishOpts = (dict.qt_update_publish || []).map((o) => ({ value: Number(o.value), label: o.label }));
  // 发布状态：优先字典「qt_update_publish」，字典缺失时用固定映射兜底，绝不展示原始码值 0/1
  const publishStateLabel = (v: number | undefined | null): string => {
    const fromDict = enumLabel(updatePublishOpts, v);
    // enumLabel 在字典缺失时回退为原值字符串，这里把 0/1 明确映射为文案
    if (fromDict !== String(v)) return fromDict;
    const map: Record<number, string> = { 1: '已发布', 0: '未发布' };
    return map[v ?? -1] ?? '-';
  };

  const loadOverview = () => {
    qtAdminApi.overview().then((res) => {
      if (res.code === 200) setOverview(res.data);
    }).catch(() => {});
  };

  const loadUpdates = (page = updatePage) => {
    setUpdatesLoading(true);
    qtAdminApi.updates(page, 10).then((res) => {
      if (res.code === 200) {
        setUpdates(res.data.records || []);
        setUpdateTotal(res.data.total || 0);
      }
    }).finally(() => setUpdatesLoading(false));
  };

  const loadAccels = (page = accelPage) => {
    setAccelsLoading(true);
    githubAccelApi.list(page, 20).then((res) => {
      if (res.code === 200) {
        setAccels(res.data.records || []);
        setAccelTotal(res.data.total || 0);
      }
    }).finally(() => setAccelsLoading(false));
  };

  const refresh = () => {
    setLoading(true);
    Promise.all([loadOverview(), loadUpdates(), loadAccels()])
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    loadOverview();
    loadUpdates(1);
    loadAccels(1);
    fetchDictOptions([
      'qt_update_platform', 'qt_update_type', 'qt_update_channel', 'qt_update_publish',
    ]).then(setDict).catch(() => {});
  }, []);

  // ==================== 版本更新 ====================
  const openUpdateModal = (record?: QtUpdate) => {
    if (record) {
      updateForm.setFieldsValue(record);
    } else {
      updateForm.resetFields();
    }
    setUpdateModal(true);
  };

  const submitUpdate = async () => {
    const values = await updateForm.validateFields();
    const editing = values.id != null;
    try {
      const res = editing
        ? await qtAdminApi.updateUpdate(values.id, values)
        : await qtAdminApi.createUpdate(values);
      if (res.code === 200) {
        message.success(editing ? '版本信息已更新' : '版本信息已创建');
        setUpdateModal(false);
        loadUpdates();
      }
    } catch (error: any) {
      message.error(error.message || '操作失败');
    }
  };

  // ==================== GitHub 加速节点 ====================
  const openAccelModal = (record?: QtGithubAccel) => {
    if (record) {
      accelForm.setFieldsValue(record);
    } else {
      accelForm.resetFields();
      accelForm.setFieldsValue({ isShow: 1, sort: 0 });
    }
    setAccelModal(true);
  };

  const submitAccel = async () => {
    const values = await accelForm.validateFields();
    const editing = values.id != null;
    try {
      const res = editing
        ? await githubAccelApi.update(values.id, values)
        : await githubAccelApi.create(values);
      if (res.code === 200) {
        message.success(editing ? '节点已更新，缓存已刷新' : '节点已创建，缓存已刷新');
        setAccelModal(false);
        loadAccels();
      }
    } catch (error: any) {
      message.error(error.message || '操作失败');
    }
  };

  const runProbe = () => {
    setProbing(true);
    setProbeMap({});
    githubAccelApi.probe().then((res) => {
      if (res.code === 200) {
        const map: Record<number, QtGithubAccelProbe> = {};
        (res.data || []).forEach((p) => { if (p.id != null) map[p.id] = p; });
        setProbeMap(map);
        message.success('探活完成，结果已展示在列表中');
      }
    }).catch(() => message.error('探活请求失败')).finally(() => setProbing(false));
  };

  const evictAccelCache = async () => {
    try {
      const res = await githubAccelApi.evictCache();
      if (res.code === 200) message.success('加速缓存已清空并重新加载');
    } catch (error: any) {
      message.error(error.message || '操作失败');
    }
  };

  const accelColumns = [
    { title: 'ID', dataIndex: 'id', width: 104 },
    { title: '节点名称', dataIndex: 'name', width: 132 },
    { title: '加速前缀', dataIndex: 'prefixUrl', ellipsis: true },
    {
      title: '启用', dataIndex: 'isShow', width: 80,
      render: (v: number) => v === 1 ? <Tag color="success">启用</Tag> : <Tag>停用</Tag>,
    },
    { title: '排序', dataIndex: 'sort', width: 70 },
    { title: '备注', dataIndex: 'remark', ellipsis: true },
    {
      title: '状态', width: 90,
      render: (_: any, record: QtGithubAccel) => {
        const p = probeMap[record.id!];
        if (!p) return <Typography.Text type="secondary" style={{ fontSize: 12 }}>未探测</Typography.Text>;
        return p.alive ? <Tag color="success">可用</Tag> : <Tag color="error">不可用</Tag>;
      },
    },
    {
      title: '结果', width: 150,
      render: (_: any, record: QtGithubAccel) => {
        const p = probeMap[record.id!];
        if (!p) return '-';
        return <span style={{ fontSize: 12 }}>{p.message ?? ''}{p.latencyMs != null ? ` · ${p.latencyMs} ms` : ''}</span>;
      },
    },
    {
      title: '操作', key: 'action', width: 160,
      render: (_: any, record: QtGithubAccel) => (
        <Space>
          <Button type="link" onClick={() => openAccelModal(record)}>编辑</Button>
          <Popconfirm title="确认删除该节点？" onConfirm={async () => {
            await githubAccelApi.remove(record.id!);
            message.success('已删除，缓存已刷新');
            loadAccels();
          }}>
            <Button type="link" danger>删除</Button>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  // 列宽是「期望最小宽度」：合计放得下容器就等比铺满，放不下横向滚动，不再压扁内容
  const updateColumns = [
    { title: 'ID', dataIndex: 'id', width: 104 },
    { title: '版本号', dataIndex: 'versionCode', width: 104 },
    {
      title: '平台', dataIndex: 'type', width: 100,
      render: (t: number) => <Tag color={t === 1101 ? 'blue' : 'green'}>{enumLabel(updatePlatformOpts, t)}</Tag>,
    },
    { title: '版本名', dataIndex: 'versionName', width: 116 },
    { title: '更新说明', dataIndex: 'versionInfo', width: 176, ellipsis: true },
    {
      title: '提示方式', dataIndex: 'updateType', width: 96,
      render: (v: string) => <Tag>{enumLabel(updateTypeOpts, v)}</Tag>,
    },
    {
      title: '渠道', dataIndex: 'channel', width: 88,
      render: (v: string) => <Tag color="purple">{enumLabel(updateChannelOpts, v)}</Tag>,
    },
    {
      title: '下载链接', width: 148, ellipsis: true,
      render: (_: any, r: QtUpdate) => (
        <Space size={4} wrap>
          {r.downloadUrl && <Tag color="blue">直链</Tag>}
          {r.browserUrl && <Tag color="cyan">浏览器</Tag>}
          {r.isGithub === 1 && <Tag color="purple">GitHub加速</Tag>}
          {!r.downloadUrl && !r.browserUrl && <Tag>未配置</Tag>}
        </Space>
      ),
    },
    {
      title: '发布', dataIndex: 'isPublished', width: 88,
      render: (v: number) => v === 1
        ? <Tag color="success">{publishStateLabel(v)}</Tag>
        : <Tag color="default">{publishStateLabel(v)}</Tag>,
    },
    {
      title: '强制', dataIndex: 'isForce', width: 88,
      render: (v: number) => v === 1 ? <Tag color="red">强制</Tag> : <Tag>非强制</Tag>,
    },
    { title: '包大小', dataIndex: 'fileSize', width: 96, render: (v: number) => v ? `${(v / 1048576).toFixed(2)} MB` : '-' },
    { title: 'MD5', dataIndex: 'md5', width: 132, ellipsis: true },
    { title: '直链地址', dataIndex: 'downloadUrl', width: 180, ellipsis: true },
    { title: '浏览器地址', dataIndex: 'browserUrl', width: 180, ellipsis: true },
    {
      title: '操作', key: 'action', width: 160,
      render: (_: any, record: QtUpdate) => (
        <Space>
          <Button type="link" onClick={() => openUpdateModal(record)}>编辑</Button>
          <Popconfirm title="确认删除？" onConfirm={async () => {
            await qtAdminApi.deleteUpdate(record.id!);
            message.success('已删除');
            loadUpdates();
          }}>
            <Button type="link" danger>删除</Button>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <div>
      <div className="filter-bar" style={{ marginBottom: 16, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <div>
          <h2 style={{ margin: 0 }}>轻听 API 管理</h2>
          <Paragraph type="secondary" style={{ margin: '4px 0 0' }}>
            面向 qt-uniappx 的音乐 App 后端：签到/收藏/公告/版本更新（astral-plugin 内置 Qt 插件）
          </Paragraph>
        </div>
        <div className="page-toolbar">
          <Space>
            <Button icon={<ReloadOutlined spin={loading} />} onClick={refresh}>刷新</Button>
          </Space>
        </div>
      </div>

      <Row gutter={[16, 16]} style={{ marginBottom: 16 }}>
        <Col xs={{ span: 24 }} sm={{ span: 12 }} md={{ span: 8 }}>
          <Card><Statistic title="用户数" value={overview?.userCount ?? '-'} prefix={<UserOutlined />} /></Card>
        </Col>
        <Col xs={{ span: 24 }} sm={{ span: 12 }} md={{ span: 8 }}>
          <Card><Statistic title="签到记录" value={overview?.dakaCount ?? '-'} prefix={<CalendarOutlined />} /></Card>
        </Col>
        <Col xs={{ span: 24 }} sm={{ span: 12 }} md={{ span: 8 }}>
          <Card><Statistic title="版本更新" value={overview?.updateCount ?? '-'} prefix={<CloudDownloadOutlined />} /></Card>
        </Col>
      </Row>

      <Card>
        <Tabs
          items={[
            {
              key: 'updates',
              label: '版本更新',
              children: (
                <div>
                  <div className="filter-bar" style={{ marginBottom: 12 }}>
                    <Button type="primary" icon={<PlusOutlined />} onClick={() => openUpdateModal()}>新增版本</Button>
                  </div>
                  <ResizableTable
                    rowKey="id"
                    columns={updateColumns}
                    dataSource={updates}
                    loading={updatesLoading}
                    scroll={{ x: 'max-content' }}
                    pagination={{
                      current: updatePage,
                      total: updateTotal,
                      pageSize: 10,
                      onChange: (p) => { setUpdatePage(p); loadUpdates(p); },
                    }}
                  />
                </div>
              ),
            },
            {
              key: 'source-releases',
              label: '音源包',
              children: <SourceReleasesTab />,
            },
            {
              key: 'github-accels',
              label: 'GitHub 加速节点',
              children: (
                <div>
                  <div className="filter-bar" style={{ marginBottom: 12 }}>
                    <Space>
                      <Button type="primary" icon={<PlusOutlined />} onClick={() => openAccelModal()}>新增节点</Button>
                      <Button icon={<ExperimentOutlined />} loading={probing} onClick={runProbe}>手动探活</Button>
                      <Button icon={<ClearOutlined />} onClick={evictAccelCache}>刷新缓存</Button>
                    </Space>
                  </div>
                  <ResizableTable
                    rowKey="id"
                    columns={accelColumns}
                    dataSource={accels}
                    loading={accelsLoading}
                    scroll={{ x: 'max-content' }}
                    pagination={{
                      current: accelPage,
                      total: accelTotal,
                      pageSize: 20,
                      onChange: (p) => { setAccelPage(p); loadAccels(p); },
                    }}
                  />
                </div>
              ),
            },
          ]}
        />
      </Card>

      {/* 版本更新弹窗 */}
      <Modal
        title="编辑版本信息"
        open={updateModal}
        onCancel={() => setUpdateModal(false)}
        onOk={submitUpdate}
        destroyOnClose
      >
        <Form form={updateForm} layout="vertical" initialValues={{ type: 1101, channel: 'stable', isGithub: 0, isForce: 0, isPublished: 0, updateType: '1' }}>
          <Form.Item name="id" hidden><Input /></Form.Item>
          <Form.Item name="versionCode" label="版本号" rules={[{ required: true, message: '请输入版本号' }]}>
            <Input type="number" />
          </Form.Item>
          <Form.Item name="versionName" label="版本名称"><Input placeholder="如 2.3.0" /></Form.Item>
          <Form.Item name="type" label="平台" rules={[{ required: true, message: '请选择平台' }]}>
            <Select options={updatePlatformOpts as any} placeholder="请选择平台" />
          </Form.Item>
          <Form.Item name="updateType" label="提示方式" rules={[{ required: true, message: '请选择提示方式' }]}>
            <Select options={updateTypeOpts as any} placeholder="请选择提示方式" />
          </Form.Item>
          <Form.Item name="channel" label="发布渠道" rules={[{ required: true, message: '请选择渠道' }]}>
            <Select options={updateChannelOpts as any} placeholder="请选择渠道" />
          </Form.Item>
          <Form.Item name="isForce" label="是否强制更新" valuePropName="checked" getValueFromEvent={(checked: boolean) => checked ? 1 : 0} getValueProps={(v: number) => ({ checked: v === 1 })}>
            <Switch checkedChildren="强制" unCheckedChildren="非强制" />
          </Form.Item>
          <Form.Item
            name="isPublished"
            label="是否发布"
            valuePropName="checked"
            getValueFromEvent={(checked: boolean) => checked ? 1 : 0}
            getValueProps={(v: number) => ({ checked: v === 1 })}
            tooltip="未发布：仅本地版本测试，用户收不到更新通知，也不校验非官方 APP；已发布：用户才能收到更新通知"
          >
            <Switch checkedChildren="已发布" unCheckedChildren="未发布" />
          </Form.Item>
          <Form.Item
            name="isGithub"
            label="GitHub 下载"
            valuePropName="checked"
            getValueFromEvent={(checked: boolean) => checked ? 1 : 0}
            getValueProps={(v: number) => ({ checked: v === 1 })}
            tooltip="开启后：App 端「直接下载」前会并发探测所有启用的加速前缀（前缀+直链），命中可用节点即走加速地址，全部不可用回退原始链接"
          >
            <Switch checkedChildren="GitHub直链" unCheckedChildren="普通直链" />
          </Form.Item>
          <Form.Item name="versionInfo" label="更新说明"><Input.TextArea rows={3} placeholder="更新内容说明" /></Form.Item>
          <Form.Item
            name="downloadUrl"
            label="直链下载链接"
            dependencies={['browserUrl']}
            rules={[
              ({ getFieldValue }) => ({
                validator(_: unknown, value: string) {
                  if (value || getFieldValue('browserUrl')) return Promise.resolve();
                  return Promise.reject(new Error('直链下载与浏览器下载至少填一个'));
                },
              }),
            ]}
          >
            <Input placeholder="直链下载地址，GitHub 时填原始 release 链接" />
          </Form.Item>
          <Form.Item
            name="browserUrl"
            label="浏览器下载链接"
            dependencies={['downloadUrl']}
            rules={[
              ({ getFieldValue }) => ({
                validator(_: unknown, value: string) {
                  if (value || getFieldValue('downloadUrl')) return Promise.resolve();
                  return Promise.reject(new Error('直链下载与浏览器下载至少填一个'));
                },
              }),
            ]}
          >
            <Input placeholder="浏览器下载地址（两个链接都填时 App 端展示两个按钮）" />
          </Form.Item>
          <Form.Item name="fileSize" label="安装包大小(字节)"><Input type="number" placeholder="可选，如 48234457" /></Form.Item>
          <Form.Item name="md5" label="安装包 MD5"><Input placeholder="可选" /></Form.Item>
          <Paragraph type="secondary" style={{ marginBottom: 0 }}>
            「下载方式」字段已废弃：App 端按双链接并存展示按钮，不再按模式二选一。
          </Paragraph>
        </Form>
      </Modal>

      {/* GitHub 加速节点弹窗 */}
      <Modal
        title="编辑 GitHub 加速节点"
        open={accelModal}
        onCancel={() => setAccelModal(false)}
        onOk={submitAccel}
        destroyOnClose
      >
        <Form form={accelForm} layout="vertical" initialValues={{ isShow: 1, sort: 0 }}>
          <Form.Item name="id" hidden><Input /></Form.Item>
          <Form.Item name="name" label="节点名称"><Input placeholder="如 ghfast" /></Form.Item>
          <Form.Item name="prefixUrl" label="加速前缀" rules={[{ required: true, message: '请输入加速前缀' }]}>
            <Input placeholder="如 https://ghfast.top/（最终地址 = 前缀 + 原始链接直接拼接）" />
          </Form.Item>
          <Form.Item name="isShow" label="是否启用" valuePropName="checked" getValueFromEvent={(checked: boolean) => checked ? 1 : 0} getValueProps={(v: number) => ({ checked: v === 1 })}>
            <Switch checkedChildren="启用" unCheckedChildren="停用" />
          </Form.Item>
          <Form.Item name="sort" label="排序"><Input type="number" placeholder="探测顺序，小的在前" /></Form.Item>
          <Form.Item name="remark" label="备注"><Input placeholder="可选" /></Form.Item>
        </Form>
      </Modal>
    </div>
  );
}