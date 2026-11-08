import { isRecord } from '@/lib/result';

/** Browser-visible identity contains no tokens or authorization lists. */
export interface SessionUserView {
  id: number;
  username: string;
  nickname: string | null;
  userType: string | null;
}

export interface ServerSessionUser extends SessionUserView {
  mustChangePassword: boolean;
  roles: string[];
  permissions: string[];
}

export function projectSessionUser(raw: unknown): SessionUserView | null {
  if (!isRecord(raw)) return null;
  const id =
    typeof raw.id === 'number' || (typeof raw.id === 'string' && /^\d+$/.test(raw.id))
      ? Number(raw.id)
      : NaN;
  if (
    raw.mustChangePassword != null &&
    ![true, false, 1, 0, '1', '0'].includes(raw.mustChangePassword as boolean | number | string)
  )
    return null;
  if (
    !Number.isSafeInteger(id) ||
    id <= 0 ||
    typeof raw.username !== 'string' ||
    !raw.username.trim()
  )
    return null;
  if (raw.nickname != null && typeof raw.nickname !== 'string') return null;
  if (raw.userType != null && typeof raw.userType !== 'string') return null;
  return {
    id,
    username: raw.username,
    nickname: typeof raw.nickname === 'string' ? raw.nickname : null,
    userType: typeof raw.userType === 'string' ? raw.userType : null,
  };
}

export function parseServerSessionUser(raw: unknown): ServerSessionUser | null {
  const identity = projectSessionUser(raw);
  if (!identity || !isRecord(raw)) return null;
  const stringList = (value: unknown): string[] | null => {
    if (value == null) return [];
    return Array.isArray(value) &&
      value.length <= 2048 &&
      value.every((item) => typeof item === 'string')
      ? value
      : null;
  };
  const roles = stringList(raw.roles);
  const permissions = stringList(raw.permissions);
  return roles && permissions
    ? {
        ...identity,
        roles,
        permissions,
        mustChangePassword:
          raw.mustChangePassword === true ||
          raw.mustChangePassword === 1 ||
          raw.mustChangePassword === '1',
      }
    : null;
}

export interface SessionSnapshot {
  loggedIn: boolean;
  canEdit: boolean;
  user: SessionUserView | null;
}

export function parseSessionSnapshot(raw: unknown): SessionSnapshot | null {
  if (!isRecord(raw) || typeof raw.loggedIn !== 'boolean') return null;
  if (!raw.loggedIn) return { loggedIn: false, canEdit: false, user: null };
  const user = projectSessionUser(raw.user);
  if (!user || typeof raw.canEdit !== 'boolean') return null;
  const forced =
    isRecord(raw.user) &&
    [true, 1, '1'].includes(raw.user.mustChangePassword as boolean | number | string);
  return { loggedIn: true, canEdit: raw.canEdit && user.userType === 'ADMIN' && !forced, user };
}
