import { request, ApiResult } from './client';

/** 字典数据项（来自 /api/v1/admin/system/dict/data/byCode） */
export interface DictDataItem {
  id: number;
  dictTypeId: number;
  dictLabel: string;
  dictValue: string;
  dictSort: number;
  status: number;
  description?: string;
  /** 标签样式类，如 success / processing */
  cssClass?: string;
  /** 表格/标签回显颜色，如 green / blue / red */
  listClass?: string;
}

/** 下拉选项（color 来自字典 list_class，可直接用于 Tag color） */
export interface DictOption {
  value: string;
  label: string;
  color?: string;
}

/** 数据字典 API */
export const dictApi = {
  /** 按字典编码查询启用的字典数据（返回 DictData 列表，按 dict_sort 升序） */
  byCode: (code: string): Promise<ApiResult<DictDataItem[]>> =>
    request.get('/api/v1/admin/system/dict/data/byCode', { params: { code } }),
};

/**
 * 拉取多个字典编码对应的选项，返回 { [code]: DictOption[] }
 */
export async function fetchDictOptions(codes: string[]): Promise<Record<string, DictOption[]>> {
  const results = await Promise.all(codes.map((c) => dictApi.byCode(c)));
  const map: Record<string, DictOption[]> = {};
  results.forEach((res, i) => {
    const list = res.data || [];
    map[codes[i]] = list.map((d) => ({
      value: String(d.dictValue),
      label: d.dictLabel,
      color: d.listClass || undefined,
    }));
  });
  return map;
}

/** 取字典项文字；字典未加载或缺失时回退到原值 */
export function dictLabel(
  options: DictOption[] | undefined,
  value: unknown,
  fallback = '-',
): string {
  if (value === null || value === undefined || value === '') return fallback;
  const hit = options?.find((o) => o.value === String(value));
  return hit?.label ?? String(value);
}

/** 取字典项颜色（Tag color），缺省返回 undefined 用默认色 */
export function dictColor(options: DictOption[] | undefined, value: unknown): string | undefined {
  if (value === null || value === undefined) return undefined;
  return options?.find((o) => o.value === String(value))?.color;
}
