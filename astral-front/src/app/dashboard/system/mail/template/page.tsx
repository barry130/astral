'use client';

import { useEffect, useState } from 'react';
import { Card, Button, Space, Modal, Form, Input, Select, Tag, message, Popconfirm } from '@/components/antd-compat';
import { PlusOutlined, EditOutlined, DeleteOutlined, EyeOutlined, SendOutlined } from '@/components/antd-compat/icons';
import { mailApi, MailTemplate, NotifyEventDef } from '@/api/mail';
import { usePerm, MAIL_PERMISSIONS } from '@/lib/perm';
import { ResizableTable } from '@/components/ResizableTable';

export default function MailTemplatePage() {
  const [loading, setLoading] = useState(false);
  const [data, setData] = useState<MailTemplate[]>([]);
  const [pagination, setPagination] = useState({ current: 1, pageSize: 10, total: 0 });
  const [modalVisible, setModalVisible] = useState(false);
  const [editing, setEditing] = useState<MailTemplate | null>(null);
  const [form] = Form.useForm();

  /** 事件注册表（代码注册，后端 /notify/event/list 只读下发） */
  const [events, setEvents] = useState<NotifyEventDef[]>([]);
  /** 表单里当前选中的场景码（决定必需变量提示与试发/预览的变量说明） */
  const [selectedScene, setSelectedScene] = useState<string | undefined>(undefined);

  const [previewVisible, setPreviewVisible] = useState(false);
  const [previewHtml, setPreviewHtml] = useState('');

  const [sendVisible, setSendVisible] = useState(false);
  const [sendTarget, setSendTarget] = useState<MailTemplate | null>(null);
  const [sendTo, setSendTo] = useState('');
  const [sendVars, setSendVars] = useState<Record<string, string>>({});
  const [sending, setSending] = useState(false);

  const hasPerm = usePerm();
  /** 是否具备邮箱模板维护权限（无权限时隐藏维护按钮；预览为只读不受限） */
  const canEdit = hasPerm(MAIL_PERMISSIONS.templateEdit);

  useEffect(() => { loadData(); loadEvents(); }, []);

  const loadData = (page = 1, size = 10) => {
    setLoading(true);
    mailApi.templatePage(page, size)
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
      .catch(() => { /* 事件列表加载失败不阻塞页面，仅退化为自由展示 */ });
  };

  const eventOptions = events.map((s) => ({ label: `${s.name}（${s.code}）`, value: s.code }));
  const eventDefOf = (code?: string) => (code ? events.find((s) => s.code === code) : undefined);

  /** 解析模板 variables 字段（JSON 数组，容忍非法值） */
  const parseDeclaredVars = (raw?: string): string[] => {
    try {
      const arr = JSON.parse(raw || '[]');
      return Array.isArray(arr) ? arr.filter((k: any) => typeof k === 'string') : [];
    } catch { return []; }
  };

  /** 选中事件后：把 payload 字段补进 variables（保留已声明的）、渠道对齐到事件声明的渠道 */
  const handleScenePicked = (code: string) => {
    setSelectedScene(code);
    const def = eventDefOf(code);
    if (!def) return;
    const merged = Array.from(new Set([...parseDeclaredVars(form.getFieldValue('variables')), ...(def.payloadFields || []).map((f) => f.name)]));
    form.setFieldsValue({ variables: JSON.stringify(merged) });
    const channels = def.channels || [];
    if (channels.length && !channels.includes(form.getFieldValue('channel'))) {
      form.setFieldsValue({ channel: channels[0] });
    }
  };

  const handleCreate = () => {
    setEditing(null);
    setSelectedScene(undefined);
    form.resetFields();
    form.setFieldsValue({ channel: 'EMAIL' });
    setModalVisible(true);
  };

  const handleEdit = (r: MailTemplate) => {
    setEditing(r);
    setSelectedScene(r.scene || undefined);
    form.setFieldsValue(r);
    setModalVisible(true);
  };

  const handleDelete = async (id?: number) => {
    if (!id) return;
    try { await mailApi.templateDelete(id); message.success('删除成功'); loadData(); }
    catch (e: any) { message.error(e.message); }
  };

  const handlePreview = async (r: MailTemplate) => {
    try {
      const vars: Record<string, string> = {};
      parseDeclaredVars(r.variables).forEach((k) => (vars[k] = '123456'));
      const res: any = await mailApi.templatePreview(r.id!, vars);
      if (res.code === 200) {
        setPreviewHtml(res.data || '');
        setPreviewVisible(true);
      }
    } catch (e: any) { message.error(e.message); }
  };

  const openSend = (r: MailTemplate) => {
    setSendTarget(r);
    setSendTo('');
    const vars: Record<string, string> = {};
    parseDeclaredVars(r.variables).forEach((k) => (vars[k] = ''));
    setSendVars(vars);
    setSendVisible(true);
  };

  const handleSend = async () => {
    if (!sendTarget?.id) return;
    if (!sendTo.trim()) { message.error('请填写收件邮箱'); return; }
    setSending(true);
    try {
      await mailApi.templateSend(sendTarget.id, { toEmail: sendTo.trim(), variables: sendVars });
      message.success('试发成功，请查收邮件（发送记录见「邮箱统计」）');
      setSendVisible(false);
    } catch (e: any) { message.error(e.message); }
    finally { setSending(false); }
  };

  const handleSubmit = async () => {
    const values = await form.validateFields();
    try {
      if (editing?.id) await mailApi.templateUpdate(editing.id, values);
      else await mailApi.templateCreate(values);
      message.success('保存成功');
      setModalVisible(false);
      loadData();
    } catch (e: any) { message.error(e.message); }
  };

  const selectedDef = eventDefOf(selectedScene);
  const sceneHint = selectedDef
    ? `${selectedDef.description || ''}${selectedDef.payloadFields?.length ? `（可用变量：${selectedDef.payloadFields.map((f) => `${f.name}=${f.description || f.name}`).join('、')}）` : ''}`
    : undefined;
  // sys_mail_template 只存本地渲染型模板（EMAIL，后续 INAPP）；SMS 模板是供应商侧映射，
  // 走「短信模板」Tab（sys_sms_template），事件声明里虽有 SMS 也要滤掉
  const channelOptions = (selectedDef?.channels || ['EMAIL'])
    .filter((c) => c !== 'SMS')
    .map((c) => ({ label: c, value: c }));

  const columns = [
    { title: '模板编码', dataIndex: 'templateCode', key: 'templateCode', render: (v: string) => <code>{v}</code> },
    { title: '模板名称', dataIndex: 'templateName', key: 'templateName' },
    { title: '主题', dataIndex: 'subject', key: 'subject' },
    {
      title: '绑定场景', dataIndex: 'scene', key: 'scene',
      render: (v: string) => {
        if (!v) return '-';
        const def = eventDefOf(v);
        return <Tag color="blue">{def ? `${def.name}（${v}）` : v}</Tag>;
      },
    },
    { title: '变量', dataIndex: 'variables', key: 'variables', render: (v: string) => v ? <code>{v}</code> : '-' },
    {
      title: '操作', key: 'action', width: 200,
      render: (_: any, r: MailTemplate) => (
        <Space>
          <Button type="link" icon={<EyeOutlined />} onClick={() => handlePreview(r)}>预览</Button>
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
        <div className="filter-bar" style={{ marginBottom: 16, display: 'flex', justifyContent: 'space-between' }}>
          <span style={{ fontWeight: 600, fontSize: 16 }}>邮件模板</span>
          <div className="page-toolbar">
            {canEdit && <Button type="primary" icon={<PlusOutlined />} onClick={handleCreate}>新建模板</Button>}
          </div>
        </div>
        <ResizableTable dataSource={data} columns={columns} rowKey="id" loading={loading} scroll={{ x: 'max-content' }} pagination={{ ...pagination, showQuickJumper: true, showSizeChanger: true, pageSizeOptions: ['5', '10', '20', '50', '100'] }}
          onChange={(p) => loadData(p.current, p.pageSize)} />
      </Card>

      <Modal title={editing ? '编辑模板' : '新建模板'} open={modalVisible}
        onOk={handleSubmit} onCancel={() => setModalVisible(false)} width={720} destroyOnClose>
        <Form form={form} layout="vertical" style={{ marginTop: 16 }}>
          <Form.Item name="templateCode" label="模板编码" tooltip="模板的唯一标识；发送侧按「绑定事件」解析模板，编码仅作标识与兼容回退" rules={[{ required: true }]}>
            <Input placeholder="如：changePasswordByEmail" />
          </Form.Item>
          <Form.Item name="templateName" label="模板名称" rules={[{ required: true }]}><Input /></Form.Item>
          <Form.Item name="subject" label="邮件主题（支持 ${变量} 占位）" rules={[{ required: true }]}><Input /></Form.Item>
          <Form.Item name="scene" label="绑定事件" extra={sceneHint} tooltip="事件由代码注册（如找回密码验证码、系统告警）。发送方按事件取模板：选对事件，后台改文案立即生效，无需改代码；同事件同渠道只能绑定一个模板" rules={[{ required: true }]}>
            <Select
              options={eventOptions}
              placeholder="选择系统事件"
              showSearch
              optionFilterProp="label"
              onSelect={handleScenePicked}
            />
          </Form.Item>
          <Form.Item name="channel" label="投递渠道" tooltip="当前已实现 EMAIL；SMS/站内信渠道落地后同事件可按渠道各绑模板" rules={[{ required: true }]}>
            <Select options={channelOptions} placeholder="选择渠道" />
          </Form.Item>
          <Form.Item name="content" label="邮件正文(支持 ${变量} 占位)" rules={[{ required: true }]}>
            <Input.TextArea rows={8} placeholder={'您好，您的验证码为 ${code}'} />
          </Form.Item>
          <Form.Item name="variables" label="变量列表(JSON数组)" tooltip='须覆盖场景必需变量，如 ["code"]'>
            <Input placeholder='["code"]' />
          </Form.Item>
          <Form.Item name="remark" label="备注"><Input.TextArea rows={2} /></Form.Item>
        </Form>
      </Modal>

      <Modal title="模板预览" open={previewVisible} footer={null} width={520} onCancel={() => setPreviewVisible(false)}>
        {/* 模板正文是可写内容，曾用 dangerouslySetInnerHTML 直插 DOM：编辑者在正文埋脚本即可
            打到仅持查看权限的管理员会话（存储型 XSS）。改 sandbox iframe 渲染——
            无 allow-* 令牌时不执行脚本、不提交表单、不同源访问父页，样式/图片正常预览。 */}
        <iframe
          title="模板预览"
          srcDoc={previewHtml}
          sandbox=""
          style={{ width: '100%', height: 360, border: '1px solid var(--color-border-light)', borderRadius: 8, background: '#fff' }}
        />
      </Modal>

      <Modal title={`试发模板：${sendTarget?.templateName || ''}`} open={sendVisible}
        onOk={handleSend} confirmLoading={sending} okText="发送"
        onCancel={() => setSendVisible(false)} destroyOnClose>
        <div style={{ marginTop: 16, display: 'flex', flexDirection: 'column', gap: 12 }}>
          <div>
            <div style={{ marginBottom: 4, fontSize: 13 }}>收件邮箱</div>
            <Input value={sendTo} onChange={(e: any) => setSendTo(e.target.value)} placeholder="me@example.com" />
          </div>
          {Object.keys(sendVars).length > 0 && (
            <div>
              <div style={{ marginBottom: 4, fontSize: 13 }}>变量值</div>
              {Object.entries(sendVars).map(([k, v]) => (
                <div key={k} style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 8 }}>
                  <code style={{ minWidth: 80 }}>{k}</code>
                  <Input
                    value={v}
                    onChange={(e: any) => setSendVars((prev) => ({ ...prev, [k]: e.target.value }))}
                    placeholder={`\${${k}} 的测试值`}
                  />
                </div>
              ))}
            </div>
          )}
          <div style={{ fontSize: 12, color: '#999' }}>
            按当前保存的模板渲染后真实发送一封（不占插件每日额度），发送记录可在「邮箱统计」查看。
          </div>
        </div>
      </Modal>
    </div>
  );
}
