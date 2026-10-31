/**
 * 管理助手。
 *
 * 与答疑页共用同一个 `ChatPanel`，差异只来自后端：智能体编码 `admin_assistant`
 * 决定了用哪套 Prompt、哪组工具、哪个模型与额度。
 * 这里不写任何业务分支——后台换个智能体配置，这页的行为就跟着变。
 */

'use client';

import React from 'react';

import { Alert } from '@/components/antd-compat';

import ChatPanel from '@/components/ai/ChatPanel';
import { usePerm } from '@/lib/perm';
import { PageHeader } from '@/components/page-header';

/** 智能体编码（与后端 `AiPlugin.SeedAgents.ADMIN` 一致，可在后台改名但不能改编码） */
const AGENT_CODE = 'admin_assistant';

const SUGGESTIONS = [
  '现在系统整体健康吗？有没有异常的提供方？',
  '最近三天有哪些插件被停用了？',
  '帮我统计一下存储用量最高的几个用户',
  '起草一条关于今晚维护的站内公告',
  '最近一周反馈里抱怨最多的问题是什么？',
];

export default function AdminAssistantPage() {
  const hasPerm = usePerm();
  const allowed = hasPerm('admin:ai:assistant:use');

  return (
    <div className="space-y-4">
      <PageHeader
        title="管理助手"
        eyebrow="只读为主"
        description="用自然语言查询系统状态、用户与插件情况，起草公告和邮件。涉及写操作时会先给出确认卡片，不会直接执行。"
      />

      {allowed ? (
        <ChatPanel
          agentCode={AGENT_CODE}
          emptyTitle="问点什么？"
          suggestions={SUGGESTIONS}
          deniedText="当前账号没有使用管理助手的权限（admin:ai:assistant:use），请联系超级管理员授权。"
          context={() => ({ pageContext: 'dashboard/plugin/ai/assistant' })}
          banner={
            <Alert
              type="info"
              showIcon
              message="回答基于系统当前数据，但不替代人工核对"
              description="助手会调用只读工具查实时数据；数字类结论建议点开对应页面复核。写操作一律需要你二次确认。"
            />
          }
        />
      ) : (
        <Alert
          type="warning"
          showIcon
          message="没有使用权限"
          description="需要 admin:ai:assistant:use 权限。请让超级管理员在「系统 → 角色权限」中授予。"
        />
      )}
    </div>
  );
}
