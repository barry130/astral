'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { formatDateTime } from '@/lib/format';
import { Card, Tag, Button, Space, Select, Input, Modal, Form, Row, Col, Switch, DatePicker, InputNumber, message, Popconfirm, Typography } from '@/components/antd-compat';
import { PlusOutlined, EditOutlined, DeleteOutlined, ReloadOutlined, FileTextOutlined } from '@/components/antd-compat/icons';
import dayjs from 'dayjs';
import {
  noticeAdminApi, SysNotice,
  noticeTypeLabel, noticeChannelLabel, noticeTypeOptions, noticeChannelOptions, loadNoticeDicts,
  NOTICE_CHANNEL_ALL,
} from '@/api/notice';
import { ResizableTable } from '@/components/ResizableTable';
import { usePerm } from '@/lib/perm';

const { Paragraph } = Typography;

/** 展示渠道选项（位掩码） */
const DISPLAY_OPTIONS = [
  { label: '开屏弹窗 (1)', value: 1 },
  { label: '首页通告栏 (2)', value: 2 },
  { label: '消息中心 (4)', value: 4 },
];

/** 可见人群 */
const AUDIENCE_OPTIONS = [
  { label: '全部', value: 'ALL' },
  { label: '仅登录用户', value: 'LOGGED_IN' },
  { label: '仅游客', value: 'NOT_LOGGED_IN' },
];

/** 新建通知的缺省投放平台：等价于改造前的 channel=app（Android + iOS） */
const DEFAULT_CHANNELS = ['app-android', 'app-ios'];

/**
 * 免责声明固定只投 App 六端（Android / iOS / 鸿蒙 / Windows / Linux / macOS），
 * 与后端 `NoticeTargets.allAppValue()` 保持一致；**不含 Web**（qt-web 不支持这套字段）。
 *
 * 为什么要在服务端强制改写：各端 `X-App-Ut` 跟着编译目标走（Windows→app-windows、
 * 鸿蒙原生包→app-harmony……），少勾一个平台那个平台的用户就永远收不到声明；
 * 同时老版本 APK（3.0.7 及以前）不认识 termsVersion，会把声明当成普通开屏公告，
 * 而声明模板是 `dialogClosable=0` —— 那个弹窗点「我知道了」执行的是 exitApp()。
 *
 * 服务端 `NoticeServiceImpl.clampDisclaimerChannel` 已经强制改写渠道并把
 * `dialogClosable` 顶回 1，这里同步是为了表单上显示的就是最终落库值（免得运营以为自己选的生效了）。
 */
const DISCLAIMER_CHANNELS = [
  'app-android', 'app-ios', 'app-windows', 'app-linux', 'app-macos', 'app-harmony',
];

/**
 * 首次启动免责声明的一键模板（后台「消息管理」→ 新建通知 → 选它自动填表）。
 *
 * 为什么要有模板：这段正文原本硬编码在 qt-pc 的 FirstRunDisclaimer.tsx 里，
 * 改一句话就要发版；改由后台下发后，运营得自己从空白表单写起——漏了「条款版本号」
 * 客户端就不认为这是声明（判定条件：开屏位 + termsVersion 非空），会静默不弹。
 * 模板把这些隐性约束一次填好，正文口径与 DESIGN §5.6 一致。
 *
 * 正文是富文本（客户端 lib/richText 白名单渲染，不走 innerHTML），
 * 因此可以用 h3/p/strong，但不要写 style/script/iframe（会被过滤）。
 *
 * `dialogClosable` 填 1（可关闭）：声明本身靠读秒防手滑，不需要靠「不可关闭」；
 * 而老版本 APK 不认识 termsVersion，会把这条公告当普通开屏公告，不可关闭时
 * 点「我知道了」执行 exitApp()。服务端 `clampDisclaimerChannel` 也会强制顶回 1，
 * 这里同值是为了表单显示的就是最终落库值。
 */
const DISCLAIMER_TEMPLATE: Partial<SysNotice> & { display?: number } = {
  title: '使用前请先阅读',
  noticeType: 'announce',
  display: 1,
  audience: 'ALL',
  dialogClosable: 1,
  firstLoginOnly: 0,
  isShow: 1,
  isTop: 0,
  marquee: 0,
  readSeconds: 3,
  termsVersion: '2026.10',
  content: [
    '<h3>一、这是什么</h3>',
    '<p>轻听（QuietMusic）是一个个人开发、免费开源的音乐播放器，用来管理并播放你自己的本地音乐，也可以通过你自己安装的音源包在线试听。它按「现状」提供，不承诺随时可用。</p>',
    '<h3>二、请这样使用</h3>',
    '<p>仅供个人学习与自用，不得用于商业用途；不得用于批量抓取、下载或再分发受版权保护的音乐。通过音源包访问到的第三方内容，其合法性与风险由你自己承担。</p>',
    '<h3>三、开源与免费</h3>',
    '<p>本软件的全部功能免费，源代码完整开源（Apache-2.0 + 补充条款）。</p>',
    '<p>如果你见到任何以本软件名义向你收取费用的版本或服务，那不是本项目，请立即卸载，并到反馈渠道告诉我们。</p>',
  ].join(''),
};

/** 落库值（逗号分隔）→ 多选控件值 */
function parseChannels(channel?: string): string[] {
  if (!channel) return DEFAULT_CHANNELS;
  const parts = channel.split(',').map((s) => s.trim()).filter(Boolean);
  if (!parts.length) return DEFAULT_CHANNELS;
  // 兼容存量遗留值：app → Android+iOS，pc → Windows
  if (parts.includes('app')) return [...DEFAULT_CHANNELS];
  return parts.map((p) => (p === 'pc' ? 'app-windows' : p));
}

/** 多选控件值 → 落库值；「全部平台」独占 */
function joinChannels(selected: string[]): string {
  if (selected.includes(NOTICE_CHANNEL_ALL)) return NOTICE_CHANNEL_ALL;
  return selected.join(',');
}

/**
 * 「全部平台」与具体平台互斥的取值切换：
 * 选中 all 时只留 all；已在 all 状态下再勾具体平台时丢弃 all。
 */
function toggleChannel(next: string[], prev: string[]): string[] {
  if (next.includes(NOTICE_CHANNEL_ALL) && !prev.includes(NOTICE_CHANNEL_ALL)) {
    return [NOTICE_CHANNEL_ALL];
  }
  if (prev.includes(NOTICE_CHANNEL_ALL) && next.length > 1) {
    return next.filter((v) => v !== NOTICE_CHANNEL_ALL);
  }
  return next;
}

/**
 * 通知管理面板（统一通知表 sys_notice，宿主通知模块）
 * <p>入口收敛到「消息中心 → 通知管理」页签（/dashboard/system/mail?tab=notice）与顶栏铃铛的
 * 「全部通知」链接；原 /dashboard/message 独立路由已删除。</p>
 */
export default function NoticeManagement() {
  const [loading, setLoading] = useState(false);
  const [data, setData] = useState<SysNotice[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(10);
  const [channelFilter, setChannelFilter] = useState<string[]>([]);
  const [typeFilter, setTypeFilter] = useState<string | undefined>(undefined);
  const [keyword, setKeyword] = useState('');

  const [modalOpen, setModalOpen] = useState(false);
  const [editing, setEditing] = useState<SysNotice | null>(null);
  const [form] = Form.useForm();
  const isFeedbackReply = editing?.scene === 'feedbackAdminReplied' || editing?.scene === 'feedbackUserReplied';
  /**
   * 表单里「条款版本号」这一格一旦填了，这条公告就是首次启动免责声明：
   * 客户端按它认领，服务端按它把渠道强制改写为 App 六端。这里订阅它，是为了在渠道那一格
   * 当场说明白「你选的平台会被改写」，而不是等提交后才发现落库值和选的不一样。
   */
  const termsVersionInput = Form.useWatch('termsVersion', form) as string | undefined;
  const isDisclaimerDraft = (termsVersionInput ?? '').trim().length > 0;

  const hasPerm = usePerm();
  // 通知维护权限：必须与后端 AdminNoticeController 写接口的 @RequiresPermission("admin:message:edit") 保持一致
  // （页面可见性另由 admin:message:view 控制，此处只加写权限闸门）
  const canEdit = hasPerm('admin:message:edit');

  // kw/ch/ty 可由调用方显式传入：setState 异步，紧随其后的 load 读闭包会拿到旧筛选值
  const load = useCallback((p = page, s = pageSize, kw = keyword, ch = channelFilter, ty = typeFilter) => {
    setLoading(true);
    const params: Record<string, any> = { pageNum: p, pageSize: s };
    // 渠道多选：后端按逗号分隔解析，逐项做包含匹配（筛选用原始多值，不做 all 独占折叠）
    if (ch.length) params.channel = ch.join(',');
    if (ty) params.noticeType = ty;
    if (kw) params.keyword = kw;
    noticeAdminApi.page(params)
      .then((res) => {
        setData(res.data?.records || []);
        setTotal(res.data?.total || 0);
      })
      .finally(() => setLoading(false));
  }, [page, pageSize, keyword, channelFilter, typeFilter]);

  useEffect(() => {
    load(1, pageSize);
    // 只预热通知自身的字典（notice_channel / notice_type）；反馈业务字典与本页无关
    loadNoticeDicts().catch(() => {});
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [channelFilter, typeFilter]);

  const openCreate = () => {
    setEditing(null);
    form.resetFields();
    form.setFieldsValue({
      channel: DEFAULT_CHANNELS,
      noticeType: 'announce',
      display: [4],
      isShow: 1,
      isTop: 0,
      dialogClosable: 1,
      firstLoginOnly: 0,
      marquee: 0,
      audience: 'ALL',
    });
    setModalOpen(true);
  };

  /**
   * 用免责声明模板新建：只填表不落库，运营还能改文案、改读秒、改版本号后再点确定。
   * 单独一个入口而不是塞进 openCreate 的默认值 —— 普通公告不该默认带读秒和条款版本号，
   * 那会让每条公告都被 qt-pc 当成免责声明（两个弹窗叠着弹）。
   */
  const openCreateDisclaimer = () => {
    setEditing(null);
    form.resetFields();
    form.setFieldsValue({
      ...DISCLAIMER_TEMPLATE,
      channel: [...DISCLAIMER_CHANNELS],
      display: [DISCLAIMER_TEMPLATE.display ?? 1],
      effectiveRange: undefined,
    });
    setModalOpen(true);
  };

  const openEdit = useCallback((record: SysNotice) => {
    setEditing(record);
    form.resetFields();
    const display = record.display ? [1, 2, 4].filter((b) => (record.display! & b) === b) : [4];
    form.setFieldsValue({
      ...record,
      display,
      isShow: record.isShow ?? 1,
      isTop: record.isTop ?? 0,
      dialogClosable: record.dialogClosable ?? 1,
      firstLoginOnly: record.firstLoginOnly ?? 0,
      marquee: record.marquee ?? 0,
      audience: record.audience || 'ALL',
      channel: record.scene === 'feedbackAdminReplied' || record.scene === 'feedbackUserReplied'
        ? [NOTICE_CHANNEL_ALL] : parseChannels(record.channel),
      // 类型口径只有两档：历史 feedback/request 行在编辑保存时自然归一为 notice（§4.6，不做批量 UPDATE）
      noticeType: record.noticeType === 'announce' ? 'announce' : 'notice',
      effectiveRange: record.effectiveStart && record.effectiveEnd
        ? [dayjs(record.effectiveStart), dayjs(record.effectiveEnd)] : undefined,
    });
    setModalOpen(true);
  }, [form]);

  const submit = async () => {
    const values = await form.validateFields();
    // 位掩码列表 → 数值
    const display = Array.isArray(values.display)
      ? (values.display as number[]).reduce((acc, b) => acc | b, 0) : values.display;
    const payload: SysNotice = {
      id: editing?.id,
      channel: isFeedbackReply ? NOTICE_CHANNEL_ALL
        : Array.isArray(values.channel) ? joinChannels(values.channel as string[]) : values.channel,
      noticeType: values.noticeType,
      title: values.title,
      content: values.content,
      url: values.url,
      userId: values.userId,
      display,
      audience: values.audience || 'ALL',
      versionMin: values.versionMin,
      versionMax: values.versionMax,
      isShow: values.isShow ?? 1,
      isTop: values.isTop ?? 0,
      dialogClosable: values.dialogClosable ?? 1,
      firstLoginOnly: values.firstLoginOnly ?? 0,
      marquee: values.marquee ?? 0,
      // 首次启动免责声明两列。清空输入框 = 取消声明身份，必须真的写回 NULL：
      // 后端的 update 对这两列是「无条件 set」，所以这里用 undefined 让 JSON 省略该键即可，
      // 服务端收到 null 就会落 NULL（不要写成 0 或空串 —— 空串会让 qt-pc 侧误判成「有版本号」）
      readSeconds: typeof values.readSeconds === 'number' ? values.readSeconds : undefined,
      termsVersion: (values.termsVersion ?? '').trim() || undefined,
      effectiveStart: values.effectiveRange?.[0]?.format('YYYY-MM-DD HH:mm:ss') || undefined,
      effectiveEnd: values.effectiveRange?.[1]?.format('YYYY-MM-DD HH:mm:ss') || undefined,
    };
    try {
      if (editing?.id) {
        await noticeAdminApi.update(editing.id, payload);
        message.success('已更新');
      } else {
        await noticeAdminApi.create(payload);
        message.success('已发布');
      }
      setModalOpen(false);
      load();
    } catch (e: any) {
      message.error(e.message);
    }
  };

  const handleDelete = useCallback(async (id: number) => {
    try {
      await noticeAdminApi.delete(id);
      message.success('已删除');
      load();
    } catch (e: any) {
      message.error(e.message);
    }
  }, [load]);

  // 用 useMemo 固定列引用，否则每次渲染新建数组会让 ResizableTable 内的列 useMemo 全部失效
  const columns = useMemo(() => [
    { title: 'ID', dataIndex: 'id', width: 96 },
    {
      title: '渠道', dataIndex: 'channel', width: 230,
      render: (v: string) => {
        const parts = (v || '').split(',').map((s) => s.trim()).filter(Boolean);
        if (!parts.length) return <Tag>未指定</Tag>;
        return (
          <>
            {parts.map((p) => (
              <Tag key={p} color={p === NOTICE_CHANNEL_ALL ? 'purple' : 'blue'}>{noticeChannelLabel(p)}</Tag>
            ))}
          </>
        );
      },
    },
    {
      // 类型口径只有两档：announce=公告（青），其余（notice/历史 feedback/request/未知）一律按「通知」渲染（蓝）
      title: '类型', dataIndex: 'noticeType', width: 90,
      render: (v: string) => <Tag color={v === 'announce' ? 'cyan' : 'geekblue'}>{noticeTypeLabel(v)}</Tag>,
    },
    { title: '标题', dataIndex: 'title', ellipsis: true },
    {
      // 标出哪条是「首次启动免责声明」：判定在客户端，后台光看展示位看不出区别
      // （一条 display 含开屏位、但没填条款版本号的公告，客户端当普通开屏弹窗处理）
      title: '免责声明', dataIndex: 'termsVersion', width: 110,
      render: (v: string, record: SysNotice) => {
        const ver = (v ?? '').trim();
        if (!ver) return '-';
        const splash = ((record.display ?? 0) & 1) === 1;
        return (
          <Space size={4}>
            <Tag color={splash ? 'green' : 'orange'}>{splash ? '开屏' : '缺开屏位'}</Tag>
            <Tag>{ver}</Tag>
          </Space>
        );
      },
    },
    {
      title: '展示位', dataIndex: 'display', width: 140,
      render: (v: number) => DISPLAY_OPTIONS.filter((o) => (v & o.value) === o.value).map((o) => o.label.replace(/ \(\d\)$/, '')).join(' / ') || '-',
    },
    {
      title: '人群', dataIndex: 'audience', width: 100,
      render: (v: string) => v === 'ALL' ? '全部' : v === 'LOGGED_IN' ? '登录' : '游客',
    },
    { title: '目标用户', dataIndex: 'userId', width: 90, render: (v: number) => v ?? <Tag>广播</Tag> },
    {
      title: '启用', dataIndex: 'isShow', width: 70,
      render: (v: number) => v === 1 ? <Tag color="green">是</Tag> : <Tag color="red">否</Tag>,
    },
    { title: '时间', dataIndex: 'createTime', width: 170, render: (v: string) => (v ? formatDateTime(v) : '-') },
    {
      title: '操作', key: 'action', width: 150,
      render: (_: any, record: SysNotice) => (
        <Space>
          <Button type="link" size="small" icon={<EditOutlined />} disabled={!canEdit} onClick={() => openEdit(record)}>编辑</Button>
          <Popconfirm title="确认删除该通知?" disabled={!canEdit} onConfirm={() => handleDelete(record.id!)}>
            <Button type="link" danger size="small" icon={<DeleteOutlined />} disabled={!canEdit}>删除</Button>
          </Popconfirm>
        </Space>
      ),
    },
  ], [openEdit, handleDelete, canEdit]);

  return (
    <div>
      <Card>
        <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
          <div>
            <h2 style={{ margin: 0 }}>通知管理</h2>
            <Paragraph type="secondary" style={{ margin: '4px 0 0' }}>
              统一通知 sys_notice：公告（广播）/ 通知（点对点站内信）（投放平台可多选，与接口统计口径一致）
            </Paragraph>
          </div>
          <Space>
            <Button icon={<ReloadOutlined />} onClick={() => load()}>刷新</Button>
            {canEdit && <Button icon={<FileTextOutlined />} onClick={openCreateDisclaimer}>免责声明</Button>}
            {canEdit && <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>发布通知</Button>}
            {!canEdit && <Tag>只读</Tag>}
          </Space>
        </div>

        <div style={{ marginBottom: 16 }}>
          <Space wrap>
            <Input.Search placeholder="搜索标题/内容" allowClear style={{ width: 220 }}
              onSearch={(v) => { setKeyword(v); load(1, pageSize, v); }} />
            <Select mode="multiple" placeholder="渠道（可多选）" allowClear style={{ width: 260 }}
              value={channelFilter}
              onChange={(v) => setChannelFilter((v as string[]) || [])}
              options={noticeChannelOptions()}
              maxTagCount="responsive" />
            <Select placeholder="类型" allowClear style={{ width: 120 }} value={typeFilter}
              onChange={(v) => setTypeFilter(v)}
              options={noticeTypeOptions()} />
          </Space>
        </div>

        <ResizableTable
          rowKey="id"
          columns={columns}
          dataSource={data}
          loading={loading}
          pagination={{
            current: page,
            total,
            pageSize,
            showSizeChanger: true,
            showQuickJumper: true,
            pageSizeOptions: ['5', '10', '20', '50', '100'],
            showTotal: (t) => `共 ${t} 条`,
            onChange: (p, ps) => { setPage(p); setPageSize(ps); load(p, ps); },
          }}
        />
      </Card>

      {/* 发布/编辑通知弹窗 */}
      <Modal
        title={editing?.id ? '编辑通知' : '发布通知'}
        open={modalOpen}
        onOk={submit}
        okButtonProps={{ disabled: !canEdit }}
        onCancel={() => setModalOpen(false)}
        width={760}
        destroyOnClose
      >
        <Form form={form} layout="vertical" style={{ marginTop: 12 }}>
          <Row gutter={16}>
            <Col xs={24} md={12}>
              <Form.Item
                name="channel"
                label="投放平台（可多选）"
                extra={isFeedbackReply
                  ? '系统事件自动生成的通知固定投「全部平台」'
                  : isDisclaimerDraft
                    // 这一格填了版本号就是免责声明，渠道会被服务端强制改写
                    ? '免责声明固定只投 App 六端，不含 Web（Android / iOS / 鸿蒙 / Windows / Linux / macOS），保存时会自动改写为这六项，并强制「可关闭」'
                    : '与接口统计的平台口径一致；选「全部平台」即不限端'}
                rules={[{ required: true, message: '请选择投放平台' }]}
                // 兼容层不支持 normalize，「全部平台」与具体平台的互斥在这里做
                getValueFromEvent={(next: string[]) =>
                  toggleChannel(next || [], (form.getFieldValue('channel') as string[]) || [])}
              >
                <Select mode="multiple" disabled={isFeedbackReply} options={noticeChannelOptions()} placeholder="选择投放平台" maxTagCount="responsive" />
              </Form.Item>
            </Col>
            <Col xs={24} md={12}>
              <Form.Item name="noticeType" label="类型" rules={[{ required: true, message: '请选择类型' }]}>
                <Select options={noticeTypeOptions()} />
              </Form.Item>
            </Col>
          </Row>

          <Form.Item name="title" label="标题" rules={[{ required: true, message: '请输入标题' }]}>
            <Input placeholder="通知/公告标题" />
          </Form.Item>

          <Form.Item name="display" label="展示类型（可多选）" rules={[{ required: true, message: '请至少选择一个展示渠道' }]}>
            <Select mode="multiple" options={DISPLAY_OPTIONS} placeholder="选择展示位置" />
          </Form.Item>

          <Form.Item name="content" label="内容">
            <Input.TextArea rows={8} placeholder="通知内容（支持富文本）" />
          </Form.Item>

          <Row gutter={16}>
            <Col xs={24} md={12}>
              <Form.Item name="url" label="跳转链接">
                <Input placeholder="点击后跳转链接（可选）" />
              </Form.Item>
            </Col>
            <Col xs={24} md={12}>
              <Form.Item name="userId" label="目标用户ID（留空=广播）">
                <InputNumber style={{ width: '100%' }} placeholder="广播则留空" />
              </Form.Item>
            </Col>
          </Row>

          <Row gutter={16}>
            <Col xs={24} md={12}>
              <Form.Item name="effectiveRange" label="生效时间区间（留空=长期）">
                <DatePicker.RangePicker showTime format="YYYY-MM-DD HH:mm:ss" style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col xs={24} md={12}>
              <Form.Item name="audience" label="可见用户人群">
                <Select options={AUDIENCE_OPTIONS} />
              </Form.Item>
            </Col>
          </Row>

          <Row gutter={16}>
            <Col xs={24} md={12}>
              <Form.Item name="versionMin" label="生效版本码下限"><InputNumber style={{ width: '100%' }} placeholder="如 300（3.0.0），可选" /></Form.Item>
            </Col>
            <Col xs={24} md={12}>
              <Form.Item name="versionMax" label="生效版本码上限"><InputNumber style={{ width: '100%' }} placeholder="如 399，可选" /></Form.Item>
            </Col>
          </Row>

          <Row gutter={16}>
            <Col xs={24} sm={8}>
              <Form.Item name="isShow" label="是否启用" valuePropName="checked" getValueFromEvent={(checked: boolean) => (checked ? 1 : 0)} getValueProps={(v: number) => ({ checked: v === 1 })}>
                <Switch checkedChildren="启用" unCheckedChildren="停用" />
              </Form.Item>
            </Col>
            <Col xs={24} sm={8}>
              <Form.Item name="isTop" label="是否置顶" valuePropName="checked" getValueFromEvent={(checked: boolean) => (checked ? 1 : 0)} getValueProps={(v: number) => ({ checked: v === 1 })}>
                <Switch checkedChildren="置顶" unCheckedChildren="普通" />
              </Form.Item>
            </Col>
            <Col xs={24} sm={8}>
              <Form.Item name="marquee" label="通告栏跑马灯" valuePropName="checked" getValueFromEvent={(checked: boolean) => (checked ? 1 : 0)} getValueProps={(v: number) => ({ checked: v === 1 })}>
                <Switch checkedChildren="滚动" unCheckedChildren="静止" />
              </Form.Item>
            </Col>
          </Row>

          <Row gutter={16}>
            <Col xs={24} md={12}>
              <Form.Item name="dialogClosable" label="开屏弹窗可关闭" valuePropName="checked" getValueFromEvent={(checked: boolean) => (checked ? 1 : 0)} getValueProps={(v: number) => ({ checked: v === 1 })}>
                <Switch checkedChildren="可关闭" unCheckedChildren="不可关闭" />
              </Form.Item>
            </Col>
            <Col xs={24} md={12}>
              <Form.Item name="firstLoginOnly" label="仅首次登录弹出" valuePropName="checked" getValueFromEvent={(checked: boolean) => (checked ? 1 : 0)} getValueProps={(v: number) => ({ checked: v === 1 })}>
                <Switch checkedChildren="仅首次" unCheckedChildren="每次" />
              </Form.Item>
            </Col>
          </Row>

          {/*
            首次启动免责声明专用的两列。判定条件写在客户端（qt-pc stores/disclaimer.ts）：
            「开屏位 + 条款版本号非空」= 这条是免责声明，会从通用开屏弹窗里摘出去单独处理。
            所以这两项只在真的要做免责声明时填，普通公告留空。
          */}
          <Row gutter={16}>
            <Col xs={24} md={12}>
              <Form.Item
                name="readSeconds"
                label="确认前读秒（秒）"
                extra="留空或 0 = 不读秒，确认键立即可点。仅对首次启动免责声明生效"
              >
                <InputNumber min={0} max={60} style={{ width: '100%' }} placeholder="如 3" />
              </Form.Item>
            </Col>
            <Col xs={24} md={12}>
              <Form.Item
                name="termsVersion"
                label="条款版本号"
                extra="填了才算「首次启动免责声明」；改这个值 = 所有客户端重新弹一次。如 2026.10"
                rules={[{ max: 32, message: '版本号最长 32 个字符' }]}
              >
                <Input placeholder="留空 = 不是免责声明" />
              </Form.Item>
            </Col>
          </Row>
        </Form>
      </Modal>
    </div>
  );
}
