'use client';

import React, { type ReactNode } from 'react';
import { Empty, Space } from 'antd';

/**
 * 统一空状态
 *
 * 所有列表 / 抽屉 / 弹窗在「无数据」时使用，保证文案层级、图标、操作按钮风格一致。
 * 图标请用 var(--icon-lg) 之类的尺寸令牌，勿裸写 fontSize。
 */
export interface EmptyStateProps {
  /** 主文案 */
  description: ReactNode;
  /** 辅助说明（灰字，可选） */
  hint?: ReactNode;
  /** 语义图标：替代 antd 默认插画，与 image 二选一 */
  icon?: ReactNode;
  /** 完全自定义插画（优先于 icon） */
  image?: ReactNode;
  /** 底部操作（如「新建」按钮） */
  action?: ReactNode;
  /** 上下内边距（px） */
  padding?: number;
  /** 屏幕阅读器名称；不传时取 description 的字符串值 */
  ariaLabel?: string;
}

export function EmptyState({
  description,
  hint,
  icon,
  image,
  action,
  padding = 32,
  ariaLabel,
}: EmptyStateProps) {
  const label = ariaLabel ?? (typeof description === 'string' ? description : undefined);

  return (
    <div
      role="status"
      aria-label={label}
      style={{
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        padding: `${padding}px 0`,
      }}
    >
      {image ? (
        <div style={{ marginBottom: 12 }}>{image}</div>
      ) : icon ? (
        <div style={{ marginBottom: 12, color: 'var(--color-text-tertiary)' }} aria-hidden>
          {icon}
        </div>
      ) : (
        <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} imageStyle={{ height: 64, marginBottom: 12 }} />
      )}

      <div style={{ textAlign: 'center', maxWidth: 420 }}>
        <div style={{ fontSize: 13.5, color: 'var(--color-text-secondary)', lineHeight: 1.6 }}>
          {description}
        </div>
        {hint ? (
          <div style={{ fontSize: 12.5, color: 'var(--color-text-tertiary)', lineHeight: 1.6, marginTop: 4 }}>
            {hint}
          </div>
        ) : null}
      </div>

      {action ? (
        <div style={{ marginTop: 12 }}>
          <Space wrap>{action}</Space>
        </div>
      ) : null}
    </div>
  );
}
