/**
 * 独立答疑。
 *
 * 与管理助手是两个智能体（`admin_qa` vs `admin_assistant`），
 * 独立页面、独立 Prompt、独立模型与额度，也独立于管理助手的工具集——
 * 答疑只需要「查文档」，不该拿到管理助手那套写操作工具。
 *
 * 这页的重点是「答案可核验」：每条结论都要能点回文档出处，
 * 没有引用时明确提示，而不是给一段看起来很像但不保证正确的解释。
 */

'use client';

import React from 'react';

import { Alert, Space, Tag } from '@/components/antd-compat';
import { DownloadOutlined, FileTextOutlined } from '@/components/antd-compat/icons';

import ChatPanel from '@/components/ai/ChatPanel';
import { usePerm } from '@/lib/perm';

/** 与后端 `AiPlugin.SeedAgents.QA` 一致 */
const AGENT_CODE = 'admin_qa';

const SUGGESTIONS = [
  'AI 模块的 App 端接口都有哪些？',
  '新建一个插件需要实现哪些接口？',
  '存储插件的密钥是怎么加密保存的？',
  '权限码的命名规则是什么？',
  '请求状态 WAITING_APPROVAL 是什么意思？',
  '部署时 Flyway 迁移失败的常见原因？',
];

export default function AdminQaPage() {
  const hasPerm = usePerm();
  const allowed = hasPerm('admin:ai:qa:use');
  const canExport = hasPerm('admin:ai:qa:export');

  return (
    <div>
      <div style={{ marginBottom: 14 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
          <FileTextOutlined style={{ fontSize: 18, opacity: 0.75 }} />
          <h2 style={{ margin: 0, fontSize: 18, fontWeight: 600 }}>答疑</h2>
          <Tag color="default">基于文档</Tag>
          {canExport && (
            <Tag color="default">
              <Space size={4}>
                <DownloadOutlined style={{ fontSize: 11 }} />
                可导出
              </Space>
            </Tag>
          )}
        </div>
        <p style={{ color: 'var(--muted-foreground, #909399)', margin: '6px 0 0', fontSize: 13 }}>
          回答项目文档、接口契约、插件与部署相关的问题。每条结论都会附带文档出处，点引用可以直接看原文。
        </p>
      </div>

      {allowed ? (
        <ChatPanel
          agentCode={AGENT_CODE}
          emptyTitle="想了解什么？"
          suggestions={SUGGESTIONS}
          requireCitation
          deniedText="当前账号没有使用答疑的权限（admin:ai:qa:use），请联系超级管理员授权。"
          context={() => ({ pageContext: 'dashboard/plugin/ai/qa' })}
          banner={
            <Alert
              type="info"
              showIcon
              message="只回答文档里写过的内容"
              description="如果文档没有覆盖，助手会直接说「文档里没有」，不会凭印象编造。引用里的路径与章节可用于核对。"
            />
          }
        />
      ) : (
        <Alert
          type="warning"
          showIcon
          message="没有使用权限"
          description="需要 admin:ai:qa:use 权限。请让超级管理员在「系统 → 角色权限」中授予。"
        />
      )}
    </div>
  );
}
