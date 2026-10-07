'use client';

import { useEffect, useState } from 'react';
import { Card, Button, Space, Modal, Form, Input, Select, Tag, message, Popconfirm } from '@/components/antd-compat';
import { PlusOutlined, EditOutlined, DeleteOutlined, SendOutlined, CheckCircleOutlined, CloseCircleOutlined } from '@/components/antd-compat/icons';
import { notifyRuleApi, SysNotifyRule, NotifyPublishResult } from '@/api/notify';
import { mailApi, MailTemplate, NotifyEventDef } from '@/api/mail';
import { smsApi, SmsTemplate } from '@/api/sms';
import { noticeChannelOptions } from '@/api/feedback';
import { usePerm } from '@/lib/perm';
import { ResizableTable } from '@/components/ResizableTable';

const CHANNEL_COLORS: Record<string, string> = { EMAIL: 'blue', SMS: 'green', INAPP: 'purple' };

export default function NotifyRulePage() {
  const [loading, setLoading] = useState(false);
  const [data, setData] = useState<SysNotifyRule[]>([]);
  const [pagination, setPagination] = useState({ current: 1, pageSize: 10, total: 0 });
  const [modalVisible, setModalVisible] = useState(false);
  const [editing, setEditing] = useState<SysNotifyRule | null>(null);
  const [form] = Form.useForm();

  const [events, setEvents] = useState<NotifyEventDef[]>([]);
  const [mailTemplates, setMailTemplates] = useState<MailTemplate[]>([]);
  const [smsTemplates, setSmsTemplates] = useState<SmsTemplate[]>([]);

  const [publishVisible, setPublishVisible] = useState(false);
  const [publishEventCode, setPublishEventCode] = useState<string | undefined>(undefined);
  const [publishVars, setPublishVars] = useState<Record<string, string>>({});
  const [publishRecipients, setPublishRecipients] = useState('');
  const [publishResults, setPublishResults] = useState<NotifyPublishResult[] | null>(null);
  const [publishing, setPublishing] = useState(false);

  const hasPerm = usePerm();
  const canEdit = hasPerm('admin:system:notify:rule:edit');

  useEffect(() => { loadData(); loadRefs(); }, []);

  const loadData = (page = 1, size = 10) => {
    setLoading(true);
    notifyRuleApi.page(page, size)
      .then((res: any) => {
        if (res.code === 200) {
          setData(res.data?.records || []);
          setPagination({ current: res.data?.current || 1, pageSize: res.data?.size || 10, total: res.data?.total || 0 });
        }
      })
      .finally(() => setLoading(false));
  };

  const loadRefs = () => {
    mailApi.notifyEventList().then((res: any) => { if (res.code === 200) setEvents(res.data || []); }).catch(() => {});
    mailApi.templatePage(1, 200).then((res: any) => { if (res.code === 200) setMailTemplates(res.data?.records || []); }).catch(() => {});
    smsApi.templatePage(1, 200).then((res: any) => { if (res.code === 200) setSmsTemplates(res.data?.records || []); }).catch(() => {});
  };

  const eventDefOf = (code?: string) => (code ? events.find((e) => e.code === code) : undefined);

  /** 当前表单 (事件, 渠道) 下合法的模板选项 */
  const templateOptions = (): { label: string; value: number }[] => {
    const eventCode = form.getFieldValue('eventCode');
    const channel = form.getFieldValue('channel');
    if (!eventCode || !channel) return [];
    if (channel === 'SMS') {
      return smsTemplates
        .filter((t) => t.eventCode === eventCode)
        .map((t) => ({ label: `${t.templateName}（${t.templateCode}）`, value: t.id! }));
    }
    return mailTemplates
      .filter((t) => t.scene === eventCode && t.channel === channel)
      .map((t) => ({ label: `${t.templateName}（${t.templateCode}）`, value: t.id! }));
  };

  const handleCreate = () => {
    setEditing(null);
    form.resetFields();
    form.setFieldsValue({ recipientType: 'FIXED', enabled: 1 });
    setModalVisible(true);
  };

  const handleEdit = (r: SysNotifyRule) => {
    setEditing(r);
    form.setFieldsValue({ ...r, platform: r.platform ? r.platform.split(',') : undefined });
    setModalVisible(true);
  };

  const handleDelete = async (id?: number) => {
    if (!id) return;
    try { await notifyRuleApi.remove(id); message.success('删除成功'); loadData(); }
    catch (e: any) { message.error(e.message); }
  };

  const handleSubmit = async () => {
    const values = await form.validateFields();
    if (Array.isArray(values.platform)) values.platform = values.platform.join(',');
    try {
      if (editing?.id) await notifyRuleApi.update(editing.id, values);
      else await notifyRuleApi.create(values);
      message.success('保存成功');
      setModalVisible(false);
      loadData();
    } catch (e: any) { message.error(e.message); }
  };

  const openPublish = (r?: SysNotifyRule) => {
    const eventCode = r?.eventCode || events[0]?.code;
    const def = eventDefOf(eventCode);
    const vars: Record<string, string> = {};
    (def?.payloadFields || []).forEach((f) => (vars[f.name] = ''));
    setPublishEventCode(eventCode);
    setPublishVars(vars);
    setPublishRecipients('');
    setPublishResults(null);
    setPublishVisible(true);
  };

  const handlePublish = async (eventCode: string) => {
    setPublishing(true);
    try {
      const recipients = publishRecipients.trim()
        ? publishRecipients.split(/[,;\s]+/).filter(Boolean)
        : undefined;
      const res: any = await notifyRuleApi.publish({ eventCode, variables: publishVars, recipients });
      if (res.code === 200) setPublishResults(res.data || []);
    } catch (e: any) { message.error(e.message); }
    finally { setPublishing(false); }
  };

  const columns = [
    { title: '规则名称', dataIndex: 'ruleName', key: 'ruleName' },
    {
      title: '事件', dataIndex: 'eventCode', key: 'eventCode',
      render: (v: string) => {
        const def = eventDefOf(v);
        return <Tag color="blue">{def ? `${def.name}（${v}）` : v}</Tag>;
      },
    },
    {
      title: '渠道', dataIndex: 'channel', key: 'channel', width: 90,
      render: (v: string) => <Tag color={CHANNEL_COLORS[v] || 'default'}>{v}</Tag>,
    },
    { title: '模板ID', dataIndex: 'templateId', key: 'templateId', width: 90, render: (v: number) => <code>{v}</code> },
    {
      title: '收件人', key: 'recipient', width: 200, ellipsis: true,
      render: (_: any, r: SysNotifyRule) => r.recipientType === 'PAYLOAD_FIELD'
        ? <span>payload.<code>{r.recipientValue}</code></span>
        : <span>{r.recipientValue}</span>,
    },
    {
      title: '启用', dataIndex: 'enabled', key: 'enabled', width: 80,
      render: (v: number) => v === 1 ? <Tag color="green">启用</Tag> : <Tag>停用</Tag>,
    },
    {
      title: '操作', key: 'action', width: 220,
      render: (_: any, r: SysNotifyRule) => (
        <Space>
          {canEdit && <Button type="link" icon={<SendOutlined />} onClick={() => openPublish(r)}>发布</Button>}
          {canEdit && <Button type="link" icon={<EditOutlined />} onClick={() => handleEdit(r)}>编辑</Button>}
          {canEdit && (
            <Popconfirm title="确认删除?" onConfirm={() => handleDelete(r.id)}>
              <Button type="link" danger icon={<DeleteOutlined />}>删除</Button>
            </Popconfirm>
          )}
          {!canEdit && <span style={{ color: '#999' }}>无操作权限</span>}
        </Space>
      ),
    },
  ];

  const selectedEventCode = form.getFieldValue('eventCode');
  const selectedDef = eventDefOf(selectedEventCode);
  const channelOptions = (selectedDef?.channels || ['EMAIL']).map((c) => ({ label: c, value: c }));

  return (
    <div>
      <Card>
        <div style={{ marginBottom: 16, display: 'flex', flexWrap: 'wrap', justifyContent: 'space-between', alignItems: 'center', gap: 8 }}>
          <span style={{ fontWeight: 600, fontSize: 16 }}>
            事件订阅规则
            <span style={{ marginLeft: 12, fontWeight: 400, fontSize: 12, color: '#999' }}>
              业务代码只发布事件；发给谁、走什么渠道、用什么模板由启用规则决定，逐规则隔离投递
            </span>
          </span>
          <div className="page-toolbar">
            {canEdit && <Button style={{ marginRight: 8 }} icon={<SendOutlined />} onClick={() => openPublish()}>发布测试</Button>}
            {canEdit && <Button type="primary" icon={<PlusOutlined />} onClick={handleCreate}>新建规则</Button>}
          </div>
        </div>
        <ResizableTable dataSource={data} columns={columns} rowKey="id" loading={loading} scroll={{ x: 'max-content' }} pagination={{ ...pagination, showQuickJumper: true, showSizeChanger: true, pageSizeOptions: ['5', '10', '20', '50', '100'] }}
          onChange={(p) => loadData(p.current, p.pageSize)} />
      </Card>

      <Modal title={editing ? '编辑规则' : '新建规则'} open={modalVisible}
        onOk={handleSubmit} onCancel={() => setModalVisible(false)} width={680} destroyOnClose>
        <Form form={form} layout="vertical" style={{ marginTop: 16 }}>
          <Form.Item name="ruleName" label="规则名称" rules={[{ required: true }]}><Input /></Form.Item>
          <Form.Item
            name="eventCode" label="订阅事件"
            tooltip="只能订阅代码注册的事件；发送方在业务节点发布该事件，规则决定投递"
            rules={[{ required: true }]}
          >
            <Select
              options={events.map((e) => ({ label: `${e.name}（${e.code}）`, value: e.code }))}
              placeholder="选择系统事件"
              showSearch
              optionFilterProp="label"
              onSelect={() => form.setFieldsValue({ channel: undefined, templateId: undefined })}
            />
          </Form.Item>
          <Form.Item name="channel" label="投递渠道" tooltip="限该事件声明的渠道" rules={[{ required: true }]}>
            <Select options={channelOptions} placeholder="选择渠道" onChange={() => form.setFieldsValue({ templateId: undefined })} />
          </Form.Item>
          <Form.Item name="templateId" label="绑定模板" tooltip="只列出与 (事件, 渠道) 匹配的模板；EMAIL/INAPP 为本地渲染模板，SMS 为供应商模板映射" rules={[{ required: true }]}>
            <Select options={templateOptions()} placeholder="先选事件和渠道" />
          </Form.Item>
          <Space size="large" style={{ display: 'flex' }}>
            <Form.Item name="recipientType" label="收件人类型" tooltip="FIXED=规则里写死收件值；PAYLOAD_FIELD=发布时从事件上下文取字段（如提交人 userId）；ROLE=按角色展开全部启用用户（INAPP 用用户ID，EMAIL/SMS 用邮箱/手机号）" rules={[{ required: true }]}>
              <Select style={{ width: 200 }} options={[
                { label: 'FIXED（固定收件人）', value: 'FIXED' },
                { label: 'PAYLOAD_FIELD（事件字段）', value: 'PAYLOAD_FIELD' },
                { label: 'ROLE（按角色群发）', value: 'ROLE' },
              ]} />
            </Form.Item>
            <Form.Item
              name="recipientValue" label="收件人取值"
              tooltip={form.getFieldValue('recipientType') === 'PAYLOAD_FIELD' ? '事件 payload 字段名'
                : form.getFieldValue('recipientType') === 'ROLE' ? '角色编码（如 ADMIN）' : '邮箱/手机号/用户ID'}
              rules={[{ required: true }]}
            >
              <Input style={{ width: 240 }} placeholder={form.getFieldValue('recipientType') === 'PAYLOAD_FIELD' ? '如 userId（字段名）'
                : form.getFieldValue('recipientType') === 'ROLE' ? '如 ADMIN' : '如 me@example.com'} />
            </Form.Item>
          </Space>
          {form.getFieldValue('channel') === 'INAPP' && (
            <Form.Item
              name="platform" label="平台定向（站内信）"
              tooltip="落统一通知表的 channel；选「全部平台」即不限端。管理端通知建议只选 Windows（不推用户手机）"
            >
              <Select mode="multiple" allowClear options={noticeChannelOptions()} placeholder="默认全部平台" maxTagCount="responsive" />
            </Form.Item>
          )}
          <Form.Item name="enabled" label="启用">
            <Select style={{ width: 120 }} options={[{ label: '启用', value: 1 }, { label: '停用', value: 0 }]} />
          </Form.Item>
          <Form.Item name="remark" label="备注"><Input.TextArea rows={2} /></Form.Item>
        </Form>
      </Modal>

      <Modal title="发布事件（按启用规则试投）" open={publishVisible} footer={null}
        onCancel={() => setPublishVisible(false)} width={720} destroyOnClose>
        <div style={{ marginTop: 16, display: 'flex', flexDirection: 'column', gap: 12 }}>
          <div>
            <div style={{ marginBottom: 4, fontSize: 13 }}>事件</div>
            <Select
              style={{ width: '100%' }}
              value={publishEventCode}
              placeholder="选择事件"
              showSearch
              optionFilterProp="label"
              onChange={(code) => {
                setPublishEventCode(code);
                const vars: Record<string, string> = {};
                (eventDefOf(code)?.payloadFields || []).forEach((f) => (vars[f.name] = ''));
                setPublishVars(vars);
              }}
              options={events.map((e) => ({ label: `${e.name}（${e.code}）`, value: e.code }))}
            />
          </div>
          {Object.keys(publishVars).length > 0 && (
            <div>
              <div style={{ marginBottom: 4, fontSize: 13 }}>事件上下文（payload）</div>
              {Object.entries(publishVars).map(([k, v]) => (
                <div key={k} style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 8 }}>
                  <code style={{ minWidth: 80 }}>{k}</code>
                  <Input value={v} onChange={(e: any) => setPublishVars((prev) => ({ ...prev, [k]: e.target.value }))} placeholder={`${k} 的测试值`} />
                </div>
              ))}
            </div>
          )}
          <div>
            <div style={{ marginBottom: 4, fontSize: 13 }}>收件人覆盖（可选，逗号分隔）</div>
            <Input value={publishRecipients} onChange={(e: any) => setPublishRecipients(e.target.value)}
              placeholder="留空 = 用各规则配置的收件人；填入则对每条启用规则用这些收件人试投" />
          </div>
          <div>
            <Button type="primary" loading={publishing} disabled={!publishEventCode}
              onClick={() => handlePublish(publishEventCode!)}>
              发布
            </Button>
          </div>
          {publishResults && (
            <div>
              <div style={{ marginBottom: 4, fontSize: 13 }}>投递结果</div>
              {publishResults.length === 0 && <div style={{ color: '#999', fontSize: 12 }}>该事件没有启用的订阅规则</div>}
              {publishResults.map((r, i) => (
                <div key={i} style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 6, fontSize: 13 }}>
                  {r.ok ? <CheckCircleOutlined style={{ color: '#52c41a' }} /> : <CloseCircleOutlined style={{ color: '#ff4d4f' }} />}
                  <span>{r.ruleName}</span>
                  <Tag color={CHANNEL_COLORS[r.channel || ''] || 'default'}>{r.channel}</Tag>
                  <code>{r.recipient}</code>
                  {r.error && <span style={{ color: '#ff4d4f' }}>{r.error}</span>}
                </div>
              ))}
            </div>
          )}
        </div>
      </Modal>
    </div>
  );
}
