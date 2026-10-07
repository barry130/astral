'use client';

import { useEffect, useState } from 'react';
import { Card, Button, Space, Modal, Form, Input, Select, Tag, message, Popconfirm } from '@/components/antd-compat';
import { PlusOutlined, EditOutlined, DeleteOutlined, SendOutlined } from '@/components/antd-compat/icons';
import { smsApi, SmsTemplate } from '@/api/sms';
import { mailApi, NotifyEventDef } from '@/api/mail';
import { fetchDictOptions, DictOption } from '@/api/dict';
import { usePerm } from '@/lib/perm';
import { ResizableTable } from '@/components/ResizableTable';

/** 审核状态兜底文案（正常走字典 notify_sms_audit_status，字典缺失时退化用色块+码值） */
const AUDIT_FALLBACK: Record<number, { label: string; color: string }> = {
  0: { label: '草稿', color: 'default' },
  1: { label: '审核中', color: 'orange' },
  2: { label: '已通过', color: 'green' },
  3: { label: '已拒绝', color: 'red' },
};

export default function SmsTemplatePage() {
  const [loading, setLoading] = useState(false);
  const [data, setData] = useState<SmsTemplate[]>([]);
  const [pagination, setPagination] = useState({ current: 1, pageSize: 10, total: 0 });
  const [modalVisible, setModalVisible] = useState(false);
  const [editing, setEditing] = useState<SmsTemplate | null>(null);
  const [form] = Form.useForm();

  const [events, setEvents] = useState<NotifyEventDef[]>([]);
  /** 声明了 SMS 渠道的事件才允许绑定短信模板（后端同规则校验） */
  const smsEvents = events.filter((e) => (e.channels || []).includes('SMS'));

  /** 审核状态选项（字典 notify_sms_audit_status） */
  const [auditOptions, setAuditOptions] = useState<DictOption[]>([]);
  const auditLabelOf = (v?: number) =>
    auditOptions.find((o) => o.value === String(v))?.label || AUDIT_FALLBACK[v || 0]?.label || String(v ?? '-');
  const auditColorOf = (v?: number) => AUDIT_FALLBACK[v || 0]?.color || 'default';

  const [sendVisible, setSendVisible] = useState(false);
  const [sendTarget, setSendTarget] = useState<SmsTemplate | null>(null);
  const [sendTo, setSendTo] = useState('');
  const [sendVars, setSendVars] = useState<Record<string, string>>({});
  const [sending, setSending] = useState(false);

  const hasPerm = usePerm();
  const canEdit = hasPerm('admin:system:notify:sms:edit');

  useEffect(() => { loadData(); loadEvents(); loadDicts(); }, []);

  const loadData = (page = 1, size = 10) => {
    setLoading(true);
    smsApi.templatePage(page, size)
      .then((res: any) => {
        if (res.code === 200) {
          setData(res.data?.records || []);
          setPagination({ current: res.data?.current || 1, pageSize: res.data?.size || 10, total: res.data?.total || 0 });
        }
      })
      .finally(() => setLoading(false));
  };

  const loadEvents = () => {
    mailApi.notifyEventList()
      .then((res: any) => { if (res.code === 200) setEvents(res.data || []); })
      .catch(() => { /* 事件列表加载失败不阻塞页面 */ });
  };

  const loadDicts = () => {
    fetchDictOptions(['notify_sms_audit_status'])
      .then((m) => setAuditOptions(m.notify_sms_audit_status || []))
      .catch(() => { /* 字典缺失时用兜底文案 */ });
  };

  const eventDefOf = (code?: string) => (code ? events.find((e) => e.code === code) : undefined);

  const handleCreate = () => {
    setEditing(null);
    form.resetFields();
    form.setFieldsValue({ auditStatus: 0 });
    setModalVisible(true);
  };

  const handleEdit = (r: SmsTemplate) => {
    setEditing(r);
    form.setFieldsValue(r);
    setModalVisible(true);
  };

  const handleDelete = async (id?: number) => {
    if (!id) return;
    try { await smsApi.templateDelete(id); message.success('删除成功'); loadData(); }
    catch (e: any) { message.error(e.message); }
  };

  const openSend = (r: SmsTemplate) => {
    setSendTarget(r);
    setSendTo('');
    const vars: Record<string, string> = {};
    (eventDefOf(r.eventCode)?.payloadFields || []).forEach((f) => (vars[f.name] = ''));
    setSendVars(vars);
    setSendVisible(true);
  };

  const handleSend = async () => {
    if (!sendTarget?.id) return;
    if (!sendTo.trim()) { message.error('请填写手机号'); return; }
    setSending(true);
    try {
      await smsApi.templateSend(sendTarget.id, { phone: sendTo.trim(), variables: sendVars });
      message.success('试发成功（真实调用供应商，记录见短信日志）');
      setSendVisible(false);
    } catch (e: any) { message.error(e.message); }
    finally { setSending(false); }
  };

  const handleSubmit = async () => {
    const values = await form.validateFields();
    try {
      if (editing?.id) await smsApi.templateUpdate(editing.id, values);
      else await smsApi.templateCreate(values);
      message.success('保存成功');
      setModalVisible(false);
      loadData();
    } catch (e: any) { message.error(e.message); }
  };

  const selectedEventHint = () => {
    const code = form.getFieldValue('eventCode');
    const def = eventDefOf(code);
    if (!def) return undefined;
    return `${def.description || ''}（变量：${(def.payloadFields || []).map((f) => `${f.name}=${f.description || f.name}`).join('、')}）`;
  };

  const columns = [
    { title: '模板编码', dataIndex: 'templateCode', key: 'templateCode', render: (v: string) => <code>{v}</code> },
    { title: '模板名称', dataIndex: 'templateName', key: 'templateName' },
    {
      title: '绑定事件', dataIndex: 'eventCode', key: 'eventCode',
      render: (v: string) => {
        if (!v) return '-';
        const def = eventDefOf(v);
        return <Tag color="blue">{def ? `${def.name}（${v}）` : v}</Tag>;
      },
    },
    { title: '供应商模板', dataIndex: 'providerTemplateCode', key: 'providerTemplateCode', render: (v: string) => <code>{v}</code> },
    { title: '签名', dataIndex: 'signName', key: 'signName', render: (v: string) => v || <span style={{ color: '#999' }}>随供应商</span> },
    {
      title: '审核状态', dataIndex: 'auditStatus', key: 'auditStatus', width: 100,
      render: (v: number) => <Tag color={auditColorOf(v)}>{auditLabelOf(v)}</Tag>,
    },
    {
      title: '操作', key: 'action', width: 220,
      render: (_: any, r: SmsTemplate) => (
        <Space>
          {canEdit && <Button type="link" icon={<SendOutlined />} onClick={() => openSend(r)}>试发</Button>}
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

  return (
    <div>
      <Card>
        <div style={{ marginBottom: 16, display: 'flex', flexWrap: 'wrap', justifyContent: 'space-between', alignItems: 'center', gap: 8 }}>
          <span style={{ fontWeight: 600, fontSize: 16 }}>
            短信模板
            <span style={{ marginLeft: 12, fontWeight: 400, fontSize: 12, color: '#999' }}>
              短信正文在供应商侧备案审核，本地只存映射与审核状态；变量按事件字段名透传
            </span>
          </span>
          <div className="page-toolbar">
            {canEdit && <Button type="primary" icon={<PlusOutlined />} onClick={handleCreate}>新建模板</Button>}
          </div>
        </div>
        <ResizableTable dataSource={data} columns={columns} rowKey="id" loading={loading} scroll={{ x: 'max-content' }} pagination={{ ...pagination, showQuickJumper: true, showSizeChanger: true, pageSizeOptions: ['5', '10', '20', '50', '100'] }}
          onChange={(p) => loadData(p.current, p.pageSize)} />
      </Card>

      <Modal title={editing ? '编辑模板' : '新建模板'} open={modalVisible}
        onOk={handleSubmit} onCancel={() => setModalVisible(false)} width={680} destroyOnClose>
        <Form form={form} layout="vertical" style={{ marginTop: 16 }}>
          <Form.Item name="templateCode" label="模板编码" tooltip="本地唯一标识，仅作标识用（发送按绑定事件解析）" rules={[{ required: true }]}>
            <Input placeholder="如：systemAlertSms" />
          </Form.Item>
          <Form.Item name="templateName" label="模板名称" rules={[{ required: true }]}><Input /></Form.Item>
          <Form.Item name="eventCode" label="绑定事件" extra={selectedEventHint()} tooltip="同事件只能绑定一个短信模板；仅列出声明了 SMS 渠道的事件" rules={[{ required: true }]}>
            <Select
              options={smsEvents.map((e) => ({ label: `${e.name}（${e.code}）`, value: e.code }))}
              placeholder="选择系统事件"
              showSearch
              optionFilterProp="label"
            />
          </Form.Item>
          <Form.Item name="providerTemplateCode" label="供应商模板编码" tooltip="在供应商控制台申请备案通过后获得的模板编码（如阿里云 SMS_123456789）" rules={[{ required: true }]}>
            <Input placeholder="SMS_123456789" />
          </Form.Item>
          <Form.Item name="signName" label="签名（可选）" tooltip="留空使用供应商配置的签名">
            <Input />
          </Form.Item>
          <Form.Item name="contentSample" label="备案正文留档" tooltip="仅供本地核对，发送时正文由供应商按其备案模板生成">
            <Input.TextArea rows={3} placeholder={'您的验证码为${code}，10分钟内有效'} />
          </Form.Item>
          <Form.Item name="auditStatus" label="审核状态" tooltip="供应商侧报备流程的当前状态，仅作台账记录">
            <Select options={auditOptions.length ? auditOptions : Object.entries(AUDIT_FALLBACK).map(([v, o]) => ({ label: o.label, value: v }))} />
          </Form.Item>
          <Form.Item name="auditRemark" label="审核备注"><Input /></Form.Item>
          <Form.Item name="remark" label="备注"><Input.TextArea rows={2} /></Form.Item>
        </Form>
      </Modal>

      <Modal title={`试发模板：${sendTarget?.templateName || ''}`} open={sendVisible}
        onOk={handleSend} confirmLoading={sending} okText="发送"
        onCancel={() => setSendVisible(false)} destroyOnClose>
        <div style={{ marginTop: 16, display: 'flex', flexDirection: 'column', gap: 12 }}>
          <div>
            <div style={{ marginBottom: 4, fontSize: 13 }}>手机号</div>
            <Input value={sendTo} onChange={(e: any) => setSendTo(e.target.value)} placeholder="13800000000" />
          </div>
          {Object.keys(sendVars).length > 0 && (
            <div>
              <div style={{ marginBottom: 4, fontSize: 13 }}>变量值（按事件字段透传给供应商）</div>
              {Object.entries(sendVars).map(([k, v]) => (
                <div key={k} style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 8 }}>
                  <code style={{ minWidth: 80 }}>{k}</code>
                  <Input
                    value={v}
                    onChange={(e: any) => setSendVars((prev) => ({ ...prev, [k]: e.target.value }))}
                    placeholder={`${k} 的测试值`}
                  />
                </div>
              ))}
            </div>
          )}
          <div style={{ fontSize: 12, color: '#999' }}>
            真实调用供应商发送一条短信，走每日额度与权重供应商池；发送记录可在日志接口查看。
          </div>
        </div>
      </Modal>
    </div>
  );
}
