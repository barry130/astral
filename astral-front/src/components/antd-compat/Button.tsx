'use client';

/**
 * antd 兼容层：Button
 *
 * 保留 antd 的 props 形状（type / size / icon / loading / block / danger / htmlType），
 * 内部渲染 shadcn Button。业务页只需把 `from 'antd'` 换成 `from '@/components/antd-compat'`。
 */

import type { ButtonHTMLAttributes, ReactNode } from 'react';
import { Loader2 } from 'lucide-react';

import { Button as ShadcnButton } from '@/components/ui/button';
import { cn } from '@/lib/utils';

type AntdButtonType = 'primary' | 'default' | 'dashed' | 'link' | 'text';
type AntdButtonSize = 'large' | 'middle' | 'small';

export interface ButtonProps extends Omit<ButtonHTMLAttributes<HTMLButtonElement>, 'type'> {
  /** antd 语义：primary 实心 / default 描边 / dashed 虚线 / link 下划线链接 / text 无边框 */
  type?: AntdButtonType;
  size?: AntdButtonSize;
  icon?: ReactNode;
  /** 加载态：显示 spinner 并禁用 */
  loading?: boolean;
  /** 撑满父容器宽度 */
  block?: boolean;
  /** 危险操作（红色） */
  danger?: boolean;
  /** 仅图标按钮（正方形） */
  shape?: 'default' | 'circle' | 'round';
  htmlType?: 'button' | 'submit' | 'reset';
}

const SIZE_MAP: Record<AntdButtonSize, 'sm' | 'default' | 'lg'> = {
  small: 'sm',
  middle: 'default',
  large: 'lg',
};

export function Button({
  type = 'default',
  size = 'middle',
  icon,
  loading,
  block,
  danger,
  shape,
  htmlType = 'button',
  className,
  children,
  disabled,
  ...rest
}: ButtonProps) {
  const variant = danger
    ? type === 'primary'
      ? 'destructive'
      : 'outline'
    : type === 'primary'
      ? 'default'
      : type === 'text'
        ? 'ghost'
        : type === 'link'
          ? 'link'
          : 'outline';

  const isIconOnly = !children && !!icon;

  return (
    <ShadcnButton
      type={htmlType}
      variant={variant}
      size={isIconOnly ? 'icon' : SIZE_MAP[size]}
      disabled={disabled || loading}
      className={cn(
        block && 'w-full',
        danger && type !== 'primary' && 'text-destructive hover:text-destructive',
        type === 'dashed' && 'border-dashed',
        shape === 'circle' && 'rounded-full',
        shape === 'round' && 'rounded-full px-4',
        className,
      )}
      {...rest}
    >
      {loading ? <Loader2 className="animate-spin" /> : icon}
      {children}
    </ShadcnButton>
  );
}
