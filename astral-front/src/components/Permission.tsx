import { ReactNode } from 'react';
import { usePermission } from '@/lib/permission';

interface PermissionProps {
  permission: string;
  fallback?: ReactNode;
  children: ReactNode;
}

export default function Permission({ permission, fallback = null, children }: PermissionProps) {
  const { hasPermission } = usePermission();
  
  if (!hasPermission(permission)) {
    return <>{fallback}</>;
  }
  
  return <>{children}</>;
}
