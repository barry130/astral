/**
 * 结构化回答的附加块与引用。
 *
 * 后端把回答拆成 `answer`（正文）+ `blocks`（结构化数据块）+ `actions`（可点动作），
 * `blocks[].data` 的形状完全由智能体版本的 responseSchema 决定，前端不能假设固定字段，
 * 因此这里只做「类型标签 + 通用数据表」的保守渲染，遇到不认识的块就原样列出键值，
 * 既不丢失信息，也不会因为后台加了新块类型而白屏。
 */

'use client';

import React from 'react';
import { Card, Space, Tag, Tooltip } from '@/components/antd-compat';
import { FileTextOutlined, LinkOutlined } from '@/components/antd-compat/icons';

import type { AiBlock, AiCitation } from '@/api/ai';

/** 块类型的中文标签；未登记的类型直接显示原始 type */
const BLOCK_LABEL: Record<string, string> = {
  text: '文本',
  song_list: '歌曲列表',
  table: '表格',
  list: '清单',
  key_value: '指标',
  citations: '引用',
  chart: '图表',
};

const labelOf = (type?: string): string => (type ? (BLOCK_LABEL[type] ?? type) : '数据');

/** 从块数据里尽可能找到「条目数组」，不同智能体的 schema 字段名不同 */
function pickItems(data?: Record<string, unknown>): unknown[] | undefined {
  if (!data) return undefined;
  for (const key of ['items', 'rows', 'list', 'records', 'candidates']) {
    const value = data[key];
    if (Array.isArray(value)) return value;
  }
  return undefined;
}

const cellText = (value: unknown): string => {
  if (value === null || value === undefined) return '';
  if (typeof value === 'string') return value;
  if (typeof value === 'number' || typeof value === 'boolean') return String(value);
  if (Array.isArray(value)) return value.map(cellText).filter(Boolean).join('、');
  if (typeof value === 'object') {
    const record = value as Record<string, unknown>;
    // 常见条目形状：{ name, artist } / { title, value }
    const preferred = ['name', 'title', 'label', 'text', 'songName'];
    for (const key of preferred) {
      const hit = record[key];
      if (typeof hit === 'string' && hit.trim()) return hit;
    }
    try {
      return JSON.stringify(record);
    } catch {
      return '';
    }
  }
  return '';
};

/** 条目里挑一个可展示的副标题（歌手、来源之类） */
const subText = (item: unknown): string => {
  if (!item || typeof item !== 'object') return '';
  const record = item as Record<string, unknown>;
  for (const key of ['artist', 'album', 'source', 'sourceName', 'description', 'subtitle']) {
    const hit = record[key];
    if (typeof hit === 'string' && hit.trim()) return hit;
  }
  return '';
};

/* ==================== 单块渲染 ==================== */

function BlockCard({ block }: { block: AiBlock }) {
  const items = pickItems(block.data);
  const title = block.title || labelOf(block.type);

  return (
    <Card
      size="small"
      title={
        <Space size={6}>
          <Tag color="default">{labelOf(block.type)}</Tag>
          <span style={{ fontWeight: 500 }}>{title}</span>
        </Space>
      }
      style={{ marginTop: 10 }}
    >
      {items && items.length > 0 ? (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
          {items.map((item, index) => (
            <div
              key={index}
              style={{
                display: 'flex',
                alignItems: 'baseline',
                gap: 8,
                paddingBottom: 8,
                borderBottom:
                  index === items.length - 1
                    ? 'none'
                    : '1px solid var(--border, rgba(127,127,127,0.14))',
              }}
            >
              <span style={{ opacity: 0.55, fontSize: 12, minWidth: 18 }}>{index + 1}</span>
              <span style={{ flex: 1, minWidth: 0 }}>{cellText(item) || '（空）'}</span>
              {subText(item) && (
                <span style={{ fontSize: 12, opacity: 0.65, maxWidth: 220, textAlign: 'right' }}>
                  {subText(item)}
                </span>
              )}
            </div>
          ))}
        </div>
      ) : items ? (
        <div style={{ fontSize: 13, opacity: 0.6 }}>没有结果</div>
      ) : (
        // 无条目数组：按指标键值对平铺，保证任何 schema 都有可读呈现
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: '6px 20px', fontSize: 13 }}>
          {Object.entries(block.data ?? {}).map(([key, value]) => (
            <span key={key} style={{ display: 'inline-flex', gap: 6 }}>
              <span style={{ opacity: 0.6 }}>{key}</span>
              <span style={{ fontWeight: 500 }}>{cellText(value) || '-'}</span>
            </span>
          ))}
        </div>
      )}
    </Card>
  );
}

/* ==================== 引用 ==================== */

export interface CitationListProps {
  citations?: AiCitation[];
  /** 必引却为空时给出警示（答疑助手的硬要求：答案必须可核验） */
  required?: boolean;
}

export function CitationList({ citations, required = false }: CitationListProps) {
  const list = citations ?? [];
  if (list.length === 0) {
    return required ? (
      <div style={{ marginTop: 10, fontSize: 12.5, opacity: 0.7 }}>
        本次回答未附引用，请通过「查看原文」核对，或在知识库中补充对应文档。
      </div>
    ) : null;
  }

  return (
    <div
      style={{
        marginTop: 10,
        paddingTop: 10,
        borderTop: '1px dashed var(--border, rgba(127,127,127,0.25))',
      }}
    >
      <Space size={6} style={{ marginBottom: 6, fontSize: 12.5, opacity: 0.75 }}>
        <LinkOutlined />
        引用来源
      </Space>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
        {list.map((citation, index) => (
          <div
            key={`${citation.sourceId ?? 's'}-${index}`}
            style={{ display: 'flex', gap: 8, alignItems: 'flex-start', fontSize: 12.5 }}
          >
            <span
              style={{
                flex: 'none',
                minWidth: 18,
                height: 18,
                borderRadius: 4,
                fontSize: 11,
                lineHeight: '18px',
                textAlign: 'center',
                background: 'var(--muted, rgba(127,127,127,0.16))',
              }}
            >
              {index + 1}
            </span>
            <div style={{ minWidth: 0, flex: 1 }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 6, flexWrap: 'wrap' }}>
                <FileTextOutlined style={{ opacity: 0.6 }} />
                <span style={{ fontWeight: 500 }}>
                  {citation.title || citation.path || '未命名文档'}
                </span>
                {citation.section && <Tag color="default">{citation.section}</Tag>}
                {citation.version && (
                  <span style={{ opacity: 0.6 }}>v{citation.version.replace(/^v/i, '')}</span>
                )}
              </div>
              {citation.path && (
                <div style={{ opacity: 0.55, fontSize: 12, wordBreak: 'break-all' }}>
                  {citation.path}
                </div>
              )}
              {citation.matchedText && (
                <Tooltip title={citation.matchedText}>
                  <div
                    style={{
                      marginTop: 2,
                      opacity: 0.75,
                      display: '-webkit-box',
                      WebkitLineClamp: 2,
                      WebkitBoxOrient: 'vertical',
                      overflow: 'hidden',
                    }}
                  >
                    {citation.matchedText}
                  </div>
                </Tooltip>
              )}
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}

/* ==================== 汇总 ==================== */

export interface AnswerBlocksProps {
  blocks?: AiBlock[];
  citations?: AiCitation[];
  citationRequired?: boolean;
}

export default function AnswerBlocks({ blocks, citations, citationRequired }: AnswerBlocksProps) {
  const list = blocks ?? [];
  return (
    <>
      {list.map((block, index) => (
        <BlockCard key={index} block={block} />
      ))}
      <CitationList citations={citations} required={citationRequired} />
    </>
  );
}
