'use client';

import { Button, Card, Form, Input, InputNumber, Select, Space, Switch, Tag } from '@/components/antd-compat';
import type { FormInstance } from '@/components/antd-compat';
import { DeleteOutlined, PlusOutlined } from '@/components/antd-compat/icons';
import { dictLabel, DictOption } from '@/api/dict';
import type { NotifyPayloadField, NotifyRecipientType } from '@/api/notify';

/** 收件人类型兜底（字典 notify_recipient_type 未就绪时使用） */
const RECIPIENT_FALLBACK: { value: NotifyRecipientType; label: string }[] = [
  { value: 'FIXED', label: '固定值' },
  { value: 'PAYLOAD_FIELD', label: '取事件载荷字段' },
  { value: 'ROLE', label: '按角色展开' },
];

/** 限额口径兜底（字典 notify_limit_scope 未就绪时使用） */
const SCOPE_FALLBACK = [
  { value: 'RECIPIENT', label: '按收件人' },
  { value: 'GLOBAL', label: '全局' },
];

export interface ChannelEditorProps {
  /** 渠道码（EMAIL / SMS / INAPP） */
  channel: string;
  /** 外层 Form 实例：切换收件人类型时清掉旧取值 */
  form: FormInstance;
  /** 事件声明的必达渠道：置灰、不可移除、不可停用 */
  required?: boolean;
  dicts: Record<string, DictOption[]>;
  /** 该渠道在当前事件下可选的模板（空数组 = 只能自动匹配） */
  templateOptions: { label: string; value: number }[];
  /** 事件载荷字段（PAYLOAD_FIELD 收件人的取值下拉） */
  payloadFields: NotifyPayloadField[];
  /** 当前收件人行（用于按行类型切换取值控件） */
  recipients?: { type?: string; value?: string }[];
  /** 移除该渠道 */
  onRemove: () => void;
}

/**
 * 单渠道配置卡：模板 / 启用 / 每日限额 + 口径 / 平台（仅 INAPP）/ 收件人列表。
 *
 * <p>所有字段都挂在 `channels.<channel>.*` 路径上，由外层 Form 统一收值；
 * 本组件不持有副本状态（副本与表单不同步会漏提交渠道配置）。</p>
 */
export default function ChannelEditor({
  channel,
  form,
  required,
  dicts,
  templateOptions,
  payloadFields,
  recipients,
  onRemove,
}: ChannelEditorProps) {
  const channelName = dictLabel(dicts.notify_delivery_channel, channel, channel);
  const recipientOptions = (dicts.notify_recipient_type?.length
    ? dicts.notify_recipient_type.map((o) => ({ label: o.label, value: o.value }))
    : RECIPIENT_FALLBACK.map((o) => ({ label: o.label, value: o.value })));
  const scopeOptions = (dicts.notify_limit_scope?.length
    ? dicts.notify_limit_scope.map((o) => ({ label: o.label, value: o.value }))
    : SCOPE_FALLBACK.map((o) => ({ label: o.label, value: o.value })));
  // 平台定向取值来自字典 notice_channel；all 用「留空」表达，故不在选项里重复
  const platformOptions = (dicts.notice_channel || [])
    .filter((o) => o.value !== 'all')
    .map((o) => ({ label: o.label, value: o.value }));
  const payloadOptions = payloadFields.map((f) => ({
    label: f.description ? `${f.name}（${f.description}）` : f.name,
    value: f.name,
  }));

  return (
    <Card
      size="small"
      style={{ marginBottom: 12 }}
      title={(
        <Space>
          <span>{channelName}</span>
          <Tag color={required ? 'red' : 'blue'}>{required ? '必达' : channel}</Tag>
          {required && <span style={{ fontSize: 12, color: '#999' }}>该事件安全相关渠道，不可移除</span>}
        </Space>
      )}
      extra={(
        <Button type="link" danger size="small" icon={<DeleteOutlined />} disabled={required} onClick={onRemove}>
          移除渠道
        </Button>
      )}
    >
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 16 }}>
        <Form.Item
          name={['channels', channel, 'templateId']}
          label="模板"
          tooltip="留空 = 按「事件 × 渠道」自动匹配；EMAIL/INAPP 取邮件模板，SMS 取短信模板"
          style={{ marginBottom: 8, minWidth: 320 }}
        >
          <Select
            options={templateOptions}
            placeholder={templateOptions.length ? '留空 = 自动匹配' : '暂无可用模板（自动匹配）'}
            showSearch
            optionFilterProp="label"
            allowClear
          />
        </Form.Item>
        <Form.Item
          name={['channels', channel, 'enabled']}
          label="启用"
          valuePropName="checked"
          style={{ marginBottom: 8 }}
        >
          <Switch checkedChildren="启用" unCheckedChildren="停用" disabled={required} />
        </Form.Item>
        <Form.Item
          name={['channels', channel, 'dailyLimit']}
          label="每日限额"
          tooltip="0 = 不限；超出后当天该渠道不再发送"
          style={{ marginBottom: 8 }}
        >
          <InputNumber min={0} precision={0} placeholder="0 = 不限" style={{ width: 140 }} />
        </Form.Item>
        <Form.Item
          name={['channels', channel, 'limitScope']}
          label="限额口径"
          tooltip="按收件人 = 每个收件人单独计数；全局 = 该渠道整体计数"
          style={{ marginBottom: 8 }}
        >
          <Select options={scopeOptions} style={{ width: 150 }} />
        </Form.Item>
        {channel === 'INAPP' && (
          <Form.Item
            name={['channels', channel, 'platform']}
            label="平台定向"
            tooltip="仅站内信：投放到哪些端的消息中心；留空 = 全部平台（all）"
            style={{ marginBottom: 8, minWidth: 280 }}
          >
            <Select
              mode="multiple"
              allowClear
              options={platformOptions}
              placeholder="留空 = 全部平台"
              maxTagCount="responsive"
            />
          </Form.Item>
        )}
      </div>

      <div style={{ marginTop: 4 }}>
        <div style={{ fontSize: 13, fontWeight: 500, marginBottom: 8 }}>收件人</div>
        <Form.List name={['channels', channel, 'recipients']}>
          {(fields, { add, remove }) => (
            <div>
              {fields.length === 0 && (
                <div style={{ fontSize: 12, color: '#999', marginBottom: 8 }}>
                  还没有收件人，至少配置一条（固定值 / 事件字段 / 角色）
                </div>
              )}
              {fields.map((field) => {
                const rowType = recipients?.[field.name]?.type || RECIPIENT_FALLBACK[0].value;
                return (
                  <div key={field.key} style={{ display: 'flex', alignItems: 'flex-start', gap: 8, marginBottom: 8 }}>
                    <Form.Item name={[field.name, 'type']} style={{ marginBottom: 0 }}>
                      <Select
                        options={recipientOptions}
                        style={{ width: 170 }}
                        onChange={() => form.setFieldValue(['channels', channel, 'recipients', field.name, 'value'], undefined)}
                      />
                    </Form.Item>
                    <Form.Item name={[field.name, 'value']} style={{ marginBottom: 0 }}>
                      {rowType === 'PAYLOAD_FIELD' ? (
                        <Select
                          options={payloadOptions}
                          style={{ width: 300 }}
                          placeholder="选择事件载荷字段"
                          showSearch
                          optionFilterProp="label"
                          allowClear
                        />
                      ) : (
                        <Input
                          style={{ width: 300 }}
                          placeholder={rowType === 'ROLE' ? '角色编码，如 ADMIN' : '邮箱 / 手机号 / 用户 ID'}
                        />
                      )}
                    </Form.Item>
                    <Button type="link" danger icon={<DeleteOutlined />} onClick={() => remove(field.name)}>
                      删除
                    </Button>
                  </div>
                );
              })}
              <Button
                size="small"
                icon={<PlusOutlined />}
                onClick={() => add({ type: RECIPIENT_FALLBACK[0].value, value: '' })}
              >
                添加收件人
              </Button>
            </div>
          )}
        </Form.List>
      </div>
    </Card>
  );
}
