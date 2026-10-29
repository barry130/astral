/**
 * 通知跳转链接解析（宿主的「可点通知」）。
 *
 * <p>宿主在写通知时把跳转目标写进 `sys_notice.url`：站内目标用自定义协议
 * `astral://<目标>/<id>`（如 `astral://feedback/1000003`），外链则直接存 http(s) 地址。
 * 浏览器不认识 `astral://`，所以渲染前必须先解析成前端路由，不能直接塞进 href。</p>
 *
 * <p>与桌面端 `qt-pc/src/lib/notice-link.ts`、App 端 `qt-uniappx/services/notice.ts` 保持同一套协议；
 * 本文件负责管理端（astral-front）的解析与路由映射。</p>
 */

/** 站内目标类型，与后端 `NoticeLink` 写侧保持一致 */
export type NoticeTargetKind = 'feedback' | 'notice';

export type NoticeLink =
  /** 站内可路由目标：用 next 路由前进，不走浏览器协议 */
  | { kind: 'internal'; target: NoticeTargetKind; id: string; href: string; label: string }
  /** http(s) 外链：新标签页打开 */
  | { kind: 'external'; url: string }
  /** 认得出是宿主协议但目标暂不支持：只展示原文，不跳转（避免出现「浏览器不支持」死链） */
  | { kind: 'unsupported'; raw: string };

/** `astral://<目标>/<id>`，目标段只允许字母数字和连字符 */
const ASTRAL_PROTOCOL = /^astral:\/\/([a-z0-9-]+)\/([A-Za-z0-9_-]+)$/i;

/** 反馈详情：带上 feedbackId，反馈页打开后自动拉详情抽屉 */
export function feedbackDetailHref(id: string | number): string {
  return `/dashboard/feedback?feedbackId=${encodeURIComponent(String(id))}`;
}

/** 通知管理：切到「通知管理」页签（列表页不做单条定位） */
export function noticeAdminHref(): string {
  return '/dashboard/system/mail?tab=notice';
}

/** 站内目标的展示文案 */
const TARGET_LABEL: Record<NoticeTargetKind, string> = {
  feedback: '打开反馈详情',
  notice: '打开通知管理',
};

/**
 * 解析通知里的链接。空值返回 null，由调用方决定不渲染链接区域。
 */
export function parseNoticeLink(raw?: string | null): NoticeLink | null {
  const value = (raw ?? '').trim();
  if (!value) {
    return null;
  }
  if (/^https?:\/\//i.test(value)) {
    return { kind: 'external', url: value };
  }
  const matched = ASTRAL_PROTOCOL.exec(value);
  if (!matched) {
    return { kind: 'unsupported', raw: value };
  }
  const target = matched[1].toLowerCase();
  const id = matched[2];
  if (target === 'feedback') {
    return {
      kind: 'internal',
      target: 'feedback',
      id,
      href: feedbackDetailHref(id),
      label: TARGET_LABEL.feedback,
    };
  }
  if (target === 'notice') {
    return {
      kind: 'internal',
      target: 'notice',
      id,
      href: noticeAdminHref(),
      label: TARGET_LABEL.notice,
    };
  }
  return { kind: 'unsupported', raw: value };
}
