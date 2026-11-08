import { dictApi, type DictOption } from '@/api/dict';
import { requireAdminSuccess } from './admin-task';
export { dictLabel, dictColor } from '@/api/dict';
export type { DictOption } from '@/api/dict';

/** Required admin options: HTTP 200 business failures and missing dictionaries are not empty successes. */
export async function loadAdminDictOptions(
  codes: ReadonlyArray<string>,
): Promise<Record<string, DictOption[]>> {
  const entries = await Promise.all(
    [...new Set(codes)].map(async (code) => {
      const rows = requireAdminSuccess(await dictApi.byCode(code), `加载字典失败：${code}`);
      if (!Array.isArray(rows) || !rows.length) throw new Error(`字典选项缺失：${code}`);
      const options = rows.map((row) => {
        if (!row || row.dictValue == null || typeof row.dictLabel !== 'string')
          throw new Error(`字典选项无效：${code}`);
        return {
          value: String(row.dictValue),
          label: row.dictLabel || String(row.dictValue),
          color: row.listClass || undefined,
        };
      });
      return [code, options] as const;
    }),
  );
  return Object.fromEntries(entries);
}
