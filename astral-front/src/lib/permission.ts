import { useAuth } from '@/context/AuthContext';

export const hasPermission = (permission: string, permissions?: string[]): boolean => {
  const perms = permissions || useAuth().user?.permissions || [];
  return perms.includes(permission) || perms.includes('*:*:*');
};

export const hasRole = (role: string, roles?: string[]): boolean => {
  const userRoles = roles || useAuth().user?.roles || [];
  return userRoles.includes(role) || userRoles.includes('ADMIN');
};

export const hasAnyPermission = (permissions: string[], userPermissions?: string[]): boolean => {
  const perms = userPermissions || useAuth().user?.permissions || [];
  return permissions.some(p => perms.includes(p)) || perms.includes('*:*:*');
};

export const hasAllPermissions = (permissions: string[], userPermissions?: string[]): boolean => {
  const perms = userPermissions || useAuth().user?.permissions || [];
  return permissions.every(p => perms.includes(p)) || perms.includes('*:*:*');
};

export function usePermission() {
  const { user } = useAuth();
  
  return {
    hasPermission: (permission: string) => hasPermission(permission, user?.permissions),
    hasRole: (role: string) => hasRole(role, user?.roles),
    hasAnyPermission: (permissions: string[]) => hasAnyPermission(permissions, user?.permissions),
    hasAllPermissions: (permissions: string[]) => hasAllPermissions(permissions, user?.permissions),
    permissions: user?.permissions || [],
    roles: user?.roles || [],
  };
}
