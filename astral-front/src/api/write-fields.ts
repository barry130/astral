/** Project only explicitly writable fields; omitted fields remain omitted and explicit null is kept. */
export function pickWriteFields<T extends object, K extends keyof T>(
  values: T,
  fields: readonly K[],
): Partial<Pick<T, K>> {
  const payload: Partial<Pick<T, K>> = {};
  for (const field of fields) {
    if (Object.hasOwn(values, field) && values[field] !== undefined) payload[field] = values[field];
  }
  return payload;
}
