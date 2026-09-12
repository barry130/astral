import { useAuth } from '@/context/AuthContext';

/** 超级管理员通配权限（后端 sys_permission 中 `*:*:*`） */
export const SUPER_PERMISSION = '*:*:*';

/** 邮箱模块权限编码（与后端 sys_permission.permission_code 一致） */
export const MAIL_PERMISSIONS = {
  /** 邮箱管理（查看） */
  view: 'system:mail:view',
  /** 邮箱统计（查看） */
  statistics: 'system:mail:statistics:view',
  /** 邮箱账户维护 */
  accountEdit: 'system:mail:account:edit',
  /** 邮箱模板维护 */
  templateEdit: 'system:mail:template:edit',
  /** 发信授权维护 */
  pluginAuthEdit: 'system:mail:plugin-auth:edit',
} as const;

/**
 * 权限判断 Hook：基于登录用户权限列表判断是否具备指定权限编码。
 * 未传 code 时恒为 true（无权限要求的元素）；`*:*:*` 视为拥有全部权限。
 */
export function usePerm() {
  const { user } = useAuth();
  const permissions = user?.permissions || [];

  return (code?: string) => {
    if (!code) return true;
    return permissions.includes(code) || permissions.includes(SUPER_PERMISSION);
  };
}
