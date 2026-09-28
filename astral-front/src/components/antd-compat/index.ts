'use client';

/**
 * antd 兼容层统一出口
 *
 * 用法：把业务页里的
 *   `import { Button, Form, Select } from 'antd';`
 * 换成
 *   `import { Button, Form, Select } from '@/components/antd-compat';`
 *
 * 图标：`import { PlusOutlined } from '@ant-design/icons';`
 *   → `import { PlusOutlined } from '@/components/antd-compat/icons';`
 *
 * 设计目标：**同名 + 同 props 形状**，业务逻辑零改动。
 * 内部全部由 shadcn/ui + Tailwind 自研实现，便于最终卸载 antd。
 */

/* ------------------------------ 基础 ------------------------------ */
export { Button } from './Button';
export type { ButtonProps } from './Button';

/* ------------------------------ 布局 ------------------------------ */
export { Card, Space, Row, Col, Divider, Typography } from './Layout';
export type {
  CardProps,
  SpaceProps,
  RowProps,
  ColProps,
  DividerProps,
  TypographyTitleProps,
  TypographyTextProps,
  TypographyParagraphProps,
} from './Layout';

/* ------------------------------ 反馈 ------------------------------ */
export { message, notification, Spin, Empty, Alert, Result, Tooltip, Popconfirm, Pagination } from './Feedback';
export type {
  SpinProps,
  EmptyProps,
  AlertProps,
  ResultProps,
  TooltipProps,
  PopconfirmProps,
  PaginationProps,
} from './Feedback';

/* ------------------------------ 表单 ------------------------------ */
export { Form, useForm, useWatch, createFormInstance } from './Form';
export type {
  FormProps,
  FormItemProps,
  FormInstance,
  FormRule,
  FormListProps,
  FormListField,
  FormListOperation,
  FormItemRenderUtils,
  NamePath,
} from './Form';

/* ------------------------------ 输入 ------------------------------ */
export {
  Input,
  InputNumber,
  Select,
  Switch,
  Checkbox,
  Radio,
  RadioButton,
  RadioGroup,
  AutoComplete,
  DatePicker,
  RangePicker,
  TreeSelect,
  Upload,
  UploadDragger,
  Image,
  InputPassword,
  InputTextArea,
  InputSearch,
  InputGroup,
} from './Inputs';
export type {
  InputProps,
  TextAreaProps,
  InputSearchProps,
  InputNumberProps,
  SelectProps,
  SelectOption,
  SwitchProps,
  CheckboxProps,
  RadioProps,
  RadioGroupProps,
  AutoCompleteProps,
  DatePickerProps,
  RangePickerProps,
  TreeSelectProps,
  TreeSelectNode,
  UploadProps,
  UploadRequestOption,
  DraggerProps,
  ImageProps,
} from './Inputs';

/* ------------------------------ 弹层 ------------------------------ */
export { Modal, Drawer, Tabs } from './Overlay';
export type { ModalProps, DrawerProps, TabsProps, TabItem } from './Overlay';

/* ------------------------------ 展示 ------------------------------ */
export { Tag, Statistic, Timeline, Badge, Descriptions, Table } from './Display';
export type { TagProps, StatisticProps, TimelineProps, TimelineItem, BadgeProps, DescriptionsProps, TableProps } from './Display';
