import { cache } from 'react';
import { backendFetch, canViewContent, getSessionUser } from '@/lib/auth-server';
import { parseStatusDictionary, type StatusOption } from '@/lib/editor';
import { parseResult } from '@/lib/result';

/** Login-only dictionary reads also support CMS readers without system-dict permission. */
export const getContentStatuses = cache(async (): Promise<StatusOption[]> => {
  const user = await getSessionUser();
  if (!canViewContent(user)) return [];
  try {
    const response = await backendFetch(
      '/api/v1/admin/system/dict/data/byCode?code=cms_content_status',
    );
    const body = parseResult(await response.json());
    return response.status === 200 && body?.code === 200
      ? (parseStatusDictionary(body.data) ?? [])
      : [];
  } catch {
    return [];
  }
});
