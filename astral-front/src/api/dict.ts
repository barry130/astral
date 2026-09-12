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
}

/** 下拉选项 */
export interface DictOption {
  value: string;
  label: string;
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
export async function fetchDictOptions(
  codes: string[],
): Promise<Record<string, DictOption[]>> {
  const results = await Promise.all(codes.map((c) => dictApi.byCode(c)));
  const map: Record<string, DictOption[]> = {};
  results.forEach((res, i) => {
    const list = res.data || [];
    map[codes[i]] = list.map((d) => ({ value: String(d.dictValue), label: d.dictLabel }));
  });
  return map;
}
