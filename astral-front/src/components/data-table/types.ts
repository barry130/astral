import type { CSSProperties, HTMLAttributes, ReactNode } from 'react';

/**
 * 自研数据表格类型定义（替代 antd Table 的类型面）。
 *
 * 设计取向：**刻意保持与 antd `ColumnsType` 的高度同形**——
 * 业务页面此前都以 `ColumnsType<T>` / `TableProps<T>` 标注，
 * 迁移时只需更换 import 来源，列定义写法保持不变（title / dataIndex /
 * render / width / align / ellipsis / key 全部沿用同名同义）。
 * 这样 30 个列表页的迁移从「重写」降级为「换 import + 少量微调」。
 */

/** 单元/表头对齐方式 */
export type DataTableAlign = 'left' | 'center' | 'right';

/** 排序方向 */
export type DataTableSortOrder = 'ascend' | 'descend' | null;

/** 列定义（antd ColumnType 的等价子集） */
export interface DataTableColumn<T> {
  /** 列唯一标识（缺省时取 dataIndex，再缺省按下标） */
  key?: string;
  /** 表头文案 */
  title?: ReactNode;
  /** 取值路径：单层或层级数组（如 ['user', 'name']） */
  dataIndex?: string | number | Array<string | number>;
  /** 自定义单元格渲染（与 antd 同签名：value / record / index） */
  /* eslint-disable-next-line @typescript-eslint/no-explicit-any */
  render?: (value: any, record: T, index: number) => ReactNode;
  /** 期望列宽：ResizableTable 会据此换算百分比或横向滚动 */
  width?: number | string;
  /** 对齐（默认 left；数值列建议 right） */
  align?: DataTableAlign;
  /** 超出省略（单行 + 省略号 + title 提示）；对象形式与 antd 兼容 */
  ellipsis?: boolean | { showTitle?: boolean };
  /** 本地排序比较函数；提供后表头可点击切换 升序/降序/取消 */
  sorter?: (a: T, b: T) => number;
  /** 固定列：仅 'right' 有实现（右侧操作列），'left' 预留 */
  fixed?: 'left' | 'right';
  /** 追加到该列单元格/表头的类名 */
  className?: string;
  /** 单元格属性（与 antd 同形，取常用子集） */
  onCell?: (record: T, index: number) => { className?: string; title?: string; style?: CSSProperties };
  /** 表头属性（与 antd 同形，取常用子集） */
  onHeaderCell?: () => { className?: string; style?: CSSProperties };
}

/** 分页配置（与 antd PaginationProps 常用字段同形） */
export interface DataTablePagination {
  current?: number;
  pageSize?: number;
  total?: number;
  /** 页码/页长变化回调（与 antd 同签名） */
  onChange?: (page: number, pageSize: number) => void;
  /** 每页条数变化回调（与 antd 同签名） */
  onShowSizeChange?: (current: number, size: number) => void;
  /** 是否显示「每页条数」选择器 */
  showSizeChanger?: boolean;
  /** 每页条数候选项（字符串数字，与 antd 一致） */
  pageSizeOptions?: string[];
  /** 是否显示快速跳页输入框 */
  showQuickJumper?: boolean;
  /** 单页时隐藏分页器 */
  hideOnSinglePage?: boolean;
  /** 总数文案自定义（与 antd 同形；未提供时用「共 N 条」默认文案） */
  showTotal?: (total: number, range: [number, number]) => ReactNode;
}

/** 展开行配置（antd expandable 的子集） */
export interface DataTableExpandable<T> {
  /** 展开后追加渲染的行内容 */
  expandedRowRender?: (record: T, index: number) => ReactNode;
  /** 受控：当前展开的行 key */
  expandedRowKeys?: Array<string | number>;
  /** 展开状态变化 */
  onExpand?: (expanded: boolean, record: T) => void;
  /** 该行是否可展开（返回 false 时隐藏展开按钮） */
  rowExpandable?: (record: T) => boolean;
}

/** 表格主体属性 */
export interface DataTableProps<T> {
  /** 列定义 */
  columns?: Array<DataTableColumn<T>>;
  /** 数据源 */
  dataSource?: readonly T[];
  /** 行唯一键：字段名或取值函数（与 antd 同形） */
  rowKey?: string | ((record: T) => string | number);
  /** 加载态：true 或对象（仅取 spinning） */
  loading?: boolean | { spinning?: boolean };
  /** 分页：false 关闭，对象开启 */
  pagination?: false | DataTablePagination;
  /** 展开行 */
  expandable?: DataTableExpandable<T>;
  /** 初始展开所有可展开行（与 antd 同名的常用项） */
  defaultExpandAllRows?: boolean;
  /** 尺寸：small 用于嵌入面板/密集场景 */
  size?: 'small' | 'middle';
  /** 表体最大高度（超过后内部纵向滚动） */
  scrollY?: number;
  /** 空态文案 */
  emptyText?: ReactNode;
  /** 是否渲染外边框容器（外层已有边框时传 false，避免双边框） */
  bordered?: boolean;
  /** 行级属性（与 antd 同形；支持原生 tr 属性如 draggable / onDragStart，用于拖拽排序） */
  onRow?: (record: T, index: number) => HTMLAttributes<HTMLTableRowElement>;
  /** 表格容器类名 */
  className?: string;
}

/** 行 key 解析（rowKey 缺省时用下标兜底） */
export function resolveRowKey<T>(
  record: T,
  index: number,
  rowKey?: string | ((record: T) => string | number),
): string {
  if (typeof rowKey === 'function') return String(rowKey(record));
  if (typeof rowKey === 'string') {
    const v = (record as Record<string, unknown>)[rowKey];
    return v === undefined || v === null ? String(index) : String(v);
  }
  return String(index);
}

/** 按 dataIndex 取值（支持层级数组） */
export function resolveCellValue<T>(record: T, dataIndex: DataTableColumn<T>['dataIndex']): unknown {
  if (dataIndex === undefined || dataIndex === null) return undefined;
  if (Array.isArray(dataIndex)) {
    return dataIndex.reduce<unknown>(
      (acc, k) => (acc === null || acc === undefined ? undefined : (acc as Record<string, unknown>)[String(k)]),
      record,
    );
  }
  return (record as Record<string, unknown>)[String(dataIndex)];
}

/** 列 key 解析：key > dataIndex > 下标 */
export function resolveColumnKey<T>(col: DataTableColumn<T>, index: number): string {
  if (col.key !== undefined && col.key !== null) return String(col.key);
  if (col.dataIndex !== undefined && col.dataIndex !== null) {
    return Array.isArray(col.dataIndex) ? col.dataIndex.join('.') : String(col.dataIndex);
  }
  return `col-${index}`;
}

/** 表头纯文本（列设置面板 / 卡片标签用） */
export function columnTitleText<T>(col: DataTableColumn<T>): string {
  if (typeof col.title === 'string') return col.title;
  if (typeof col.title === 'number') return String(col.title);
  if (col.dataIndex !== undefined && col.dataIndex !== null) {
    return Array.isArray(col.dataIndex) ? col.dataIndex.join('.') : String(col.dataIndex);
  }
  return '列';
}

/** 空值判定：undefined / null / 空串 / 空数组 */
export function isBlankValue(v: unknown): boolean {
  return v === undefined || v === null || v === '' || (Array.isArray(v) && v.length === 0);
}
