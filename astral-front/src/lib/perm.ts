import { useAuth } from '@/context/AuthContext';

/** 超级管理员通配权限（由后端 sys_role.is_super 角色在权限列表中合成） */
export const SUPER_PERMISSION = '*:*:*';

/** 邮箱模块权限编码（与后端 sys_permission.permission_code 一致） */
export const MAIL_PERMISSIONS = {
  /** 邮箱管理（查看） */
  view: 'admin:system:mail:view',
  /** 邮箱统计（查看） */
  statistics: 'admin:system:mail:statistics:view',
  /** 邮箱账户维护 */
  accountEdit: 'admin:system:mail:account:edit',
  /** 邮箱模板维护 */
  templateEdit: 'admin:system:mail:template:edit',
  /** 发信授权维护 */
  pluginAuthEdit: 'admin:system:mail:plugin-auth:edit',
} as const;

/** 轻听插件权限编码 */
export const QT_PERMISSIONS = {
  /** 轻听后台管理 */
  admin: 'admin:qt:admin',
  /** 结果级权限：版本更新 beta 渠道可见资格 */
  updateChannelBeta: 'user:qt:update:channel:beta',
  /** 结果级权限：音源包 beta 渠道可见资格 */
  sourceChannelBeta: 'user:qt:source:channel:beta',
} as const;

/**
 * 层级通配匹配：持有项 `system:user:*` 可匹配 `admin:system:user:view`。
 * 与后端 PermissionChecker.matches 保持一致。
 */
export function matchPermission(held: string, required: string): boolean {
  if (!held || !required) return false;
  if (held === required || held === SUPER_PERMISSION) return true;
  if (held.endsWith(':*')) {
    return required.startsWith(held.slice(0, -1));
  }
  return false;
}

/** 权限列表是否命中某权限码（含超管通配与层级通配） */
export function hasPermissionIn(permissions: string[] | undefined | null, code?: string): boolean {
  if (!code) return true;
  const list = permissions || [];
  return list.some((held) => matchPermission(held, code));
}

/**
 * 权限判断 Hook：基于登录用户权限列表判断是否具备指定权限编码。
 * 未传 code 时恒为 true（无权限要求的元素）；`*:*:*` 视为拥有全部权限。
 */
export function usePerm() {
  const { user } = useAuth();
  const permissions = user?.permissions || [];

  return (code?: string) => hasPermissionIn(permissions, code);
}
