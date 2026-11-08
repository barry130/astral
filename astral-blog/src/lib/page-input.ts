/** Spring pageNum is a positive signed int; repeated/partial query values are invalid. */
export function parsePageNumber(raw: string | string[] | undefined): number | null {
  if (raw === undefined) return 1;
  if (typeof raw !== 'string' || !/^[1-9]\d*$/.test(raw)) return null;
  const value = Number(raw);
  return Number.isSafeInteger(value) && value <= 2_147_483_647 ? value : null;
}

export function pageHref(base: string, page: number): string {
  return page > 1 ? `${base}?page=${page}` : base;
}
