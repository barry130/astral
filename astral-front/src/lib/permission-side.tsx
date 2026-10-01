import { Tag } from '@/components/antd-compat';

/**
 * 权限端别：权限编码首段（端:域:资源:操作[:范围]），与接口三层前缀一一对应。
 * 端段是编码规范的结构段（admin/user/all，见 AGENTS.md 约束 5），非业务可配置枚举，
 * 故不登记数据字典、前端维护文案（与权限域 domain 同口径，见权限管理页注释）。
 */
export type PermissionSide = 'admin' | 'user' | 'all';

/** 端别 → 展示文案 / 标签配色（筛选下拉同源于此） */
export const SIDE_META: Record<PermissionSide, { label: string; color: string }> = {
  admin: { label: '管理端', color: 'geekblue' },
  user: { label: '用户端', color: 'green' },
  all: { label: '通用', color: 'default' },
};

/** 从权限码取端别；编码不规范（无端段/历史遗留）时返回 null，调用方不渲染标签 */
export function sideOfCode(code?: string): PermissionSide | null {
  const first = (code ?? '').split(':')[0]?.trim().toLowerCase();
  return first === 'admin' || first === 'user' || first === 'all' ? first : null;
}

/** 端别标签：名称在管理端/用户端常重名（如 admin:feedback:view 与 user:feedback:view 都叫「反馈查看」），靠它区分 */
export function PermissionSideTag({ code }: { code?: string }) {
  const side = sideOfCode(code);
  if (!side) return null;
  const { label, color } = SIDE_META[side];
  return <Tag color={color}>{label}</Tag>;
}
