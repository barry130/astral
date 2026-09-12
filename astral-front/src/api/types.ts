/** 分页结果通用接口 */
export interface PageResult<T> {
  /** 当前页数据列表 */
  records: T[];
  /** 总记录数 */
  total: number;
  /** 每页大小 */
  size: number;
  /** 当前页码 */
  current: number;
  /** 总页数 */
  pages: number;
}
