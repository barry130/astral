'use client';

/**
 * Promise 版确认弹窗 —— `window.confirm` 的替代实现（no-alert 整改）。
 *
 * 后台页里危险操作（删除/撤销）原本用 `window.confirm` 做守卫式确认，为过 no-alert
 * 规则统一换成这里：内部用 antd 兼容层 Modal（shadcn Dialog 实现）挂一次性 root 渲染，
 * 确定/Esc/遮罩/关闭按钮分别 resolve(true/false)，弹窗随即卸载。
 * 调用处保持原有异步流程，只把
 *   if (!window.confirm(msg)) return;
 * 换成
 *   if (!(await confirmDialog({ title: msg }))) return;
 */
import { createRoot } from 'react-dom/client';

import { Modal } from '@/components/antd-compat';

export interface ConfirmDialogOptions {
  /** 弹窗标题，即原 confirm 消息文案 */
  title: string;
  /** 正文补充说明，可省略（省略时压缩正文内边距，只留标题） */
  description?: string;
  okText?: string;
  cancelText?: string;
  /** 危险操作（删除/撤销类）默认红色确定键 */
  danger?: boolean;
}

export function confirmDialog(options: ConfirmDialogOptions): Promise<boolean> {
  const { title, description, okText = '确定', cancelText = '取消', danger = true } = options;
  // SSR 兜底：服务端不存在用户交互，一律视为取消
  if (typeof document === 'undefined') return Promise.resolve(false);
  return new Promise<boolean>((resolve) => {
    const host = document.createElement('div');
    document.body.appendChild(host);
    const root = createRoot(host);
    let settled = false;
    const settle = (result: boolean) => {
      if (settled) return;
      settled = true;
      root.unmount();
      host.remove();
      resolve(result);
    };
    root.render(
      <Modal
        title={title}
        open
        width={420}
        okText={okText}
        cancelText={cancelText}
        okButtonProps={{ danger }}
        onOk={() => settle(true)}
        onCancel={() => settle(false)}
        bodyStyle={description ? undefined : { padding: 0 }}
      >
        {description ? <p className="text-muted-foreground text-sm">{description}</p> : null}
      </Modal>,
    );
  });
}
