'use client';

/**
 * antd 兼容层：输入类控件
 *
 * Input（含 Password / TextArea / Search）、InputNumber、Select（含 Option / OptGroup）、
 * Switch、Radio（含 Group / Button）、Checkbox、AutoComplete、
 * DatePicker（含 RangePicker）、TreeSelect、Upload（含 Dragger）
 *
 * 全部保持 antd 的 props 形状，业务页只换 import 来源。
 */

import {
  Children,
  isValidElement,
  useCallback,
  useMemo,
  useRef,
  useState,
  type ChangeEvent,
  type CSSProperties,
  type DragEvent,
  type KeyboardEvent,
  type MouseEvent as ReactMouseEvent,
  type ReactNode,
} from 'react';
import dayjs, { type Dayjs } from 'dayjs';
import { Check, ChevronDown, Eye, EyeOff, Loader2, Search, X } from 'lucide-react';

import { Dialog, DialogContent, DialogTitle } from '@/components/ui/dialog';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import { cn } from '@/lib/utils';

/* ================================================================ 通用样式 */

const SIZE_H: Record<string, string> = {
  small: 'h-7 text-xs',
  middle: 'h-9 text-sm',
  default: 'h-9 text-sm',
  large: 'h-10 text-base',
};

const FIELD_BASE =
  'w-full min-w-0 rounded-md border border-input bg-transparent px-3 py-1 text-foreground shadow-xs outline-none transition-[color,box-shadow] placeholder:text-muted-foreground focus-visible:border-ring focus-visible:ring-[3px] focus-visible:ring-ring/40 disabled:cursor-not-allowed disabled:opacity-50';

/* ================================================================ Input */

export interface InputProps {
  value?: string | number;
  defaultValue?: string | number;
  onChange?: (e: ChangeEvent<HTMLInputElement>) => void;
  onPressEnter?: (e: KeyboardEvent<HTMLInputElement>) => void;
  onKeyDown?: (e: KeyboardEvent<HTMLInputElement>) => void;
  placeholder?: string;
  type?: string;
  size?: 'small' | 'middle' | 'large';
  disabled?: boolean;
  readOnly?: boolean;
  allowClear?: boolean;
  prefix?: ReactNode;
  suffix?: ReactNode;
  addonBefore?: ReactNode;
  addonAfter?: ReactNode;
  maxLength?: number;
  autoFocus?: boolean;
  autoComplete?: string;
  status?: 'error' | 'warning';
  className?: string;
  style?: CSSProperties;
  id?: string;
  name?: string;
  title?: string;
  onBlur?: () => void;
  onFocus?: () => void;
  /** 点击 allowClear 的清除按钮时触发 */
  onClear?: () => void;
  /** 无障碍：与外部 label 关联 */
  'aria-label'?: string;
}

function InputBase({
  value,
  defaultValue,
  onChange,
  onPressEnter,
  onKeyDown,
  placeholder,
  type = 'text',
  size = 'middle',
  disabled,
  readOnly,
  allowClear,
  prefix,
  suffix,
  addonBefore,
  addonAfter,
  maxLength,
  autoFocus,
  autoComplete,
  status,
  className,
  style,
  id,
  name,
  title,
  onBlur,
  onFocus,
  onClear,
  ...aria
}: InputProps) {
  const [inner, setInner] = useState<string>(defaultValue === undefined ? '' : String(defaultValue));
  const controlled = value !== undefined;
  const current = controlled ? (value === null || value === undefined ? '' : String(value)) : inner;

  const handleChange = (e: ChangeEvent<HTMLInputElement>) => {
    if (!controlled) setInner(e.target.value);
    onChange?.(e);
  };

  // 宽度类样式要落到**外层包装**上，而不是内层 <input>。
  // 原因：外层包装是 w-full、内层 input 也是 w-full，调用方的 `style={{ width: 220 }}`
  // 如果只作用在内层，外层依旧占满整行 —— 在 `.filter-bar`（flex + space-between）里
  // 就会把右侧按钮挤到第二行，表现为「搜索 + 新建」莫名变成两行。
  // 内联 style 优先级高于 w-full 类，所以：无 style → 保持 w-full（表单里照常填满），
  // 有 style.width → 按调用方给的宽度。其余样式（如 textAlign）仍留给 input。
  const { width, minWidth, maxWidth, ...restStyle } = style ?? {};
  const widthStyle: CSSProperties = {};
  if (width !== undefined) widthStyle.width = width;
  if (minWidth !== undefined) widthStyle.minWidth = minWidth;
  if (maxWidth !== undefined) widthStyle.maxWidth = maxWidth;

  const inputEl = (
    <input
      id={id}
      name={name}
      title={title}
      type={type}
      value={current}
      onChange={handleChange}
      onKeyDown={(e) => {
        onKeyDown?.(e);
        if (e.key === 'Enter') onPressEnter?.(e);
      }}
      onBlur={onBlur}
      onFocus={onFocus}
      placeholder={placeholder}
      disabled={disabled}
      readOnly={readOnly}
      maxLength={maxLength}
      // eslint-disable-next-line jsx-a11y/no-autofocus
      autoFocus={autoFocus}
      autoComplete={autoComplete}
      aria-label={aria['aria-label']}
      className={cn(FIELD_BASE, SIZE_H[size] ?? SIZE_H.middle, className)}
      style={restStyle}
    />
  );

  const clearable = allowClear && !disabled && current !== '';
  const hasAffix = prefix || suffix || clearable || addonBefore || addonAfter;

  if (!hasAffix) {
    return (
      <span
        className={cn('relative inline-flex w-full', status === 'error' && 'text-destructive')}
        style={widthStyle}
      >
        {inputEl}
      </span>
    );
  }

  const box = (
    <span className="relative flex w-full items-center">
      {prefix ? <span className="pointer-events-none absolute left-2.5 flex text-muted-foreground">{prefix}</span> : null}
      <span className={cn('w-full', prefix && '[&>input]:pl-8', (suffix || clearable) && '[&>input]:pr-8')}>{inputEl}</span>
      {suffix ? <span className="pointer-events-none absolute right-2.5 flex text-muted-foreground">{suffix}</span> : null}
      {clearable ? (
        <button
          type="button"
          aria-label="清空"
          className="absolute right-2 cursor-pointer rounded-sm text-muted-foreground/60 hover:text-foreground"
          onClick={() => {
            onChange?.({ target: { value: '' } } as unknown as ChangeEvent<HTMLInputElement>);
            onClear?.();
          }}
        >
          <X className="size-3.5" />
        </button>
      ) : null}
    </span>
  );

  if (!addonBefore && !addonAfter) {
    // 同上：宽度由这个包装层决定（无 style 时 w-full 填满，有 style.width 时按调用方）
    return <span className="relative inline-flex w-full" style={widthStyle}>{box}</span>;
  }

  return (
    <span className="flex w-full items-stretch" style={widthStyle}>
      {addonBefore ? (
        <span className="inline-flex items-center rounded-l-md border border-r-0 border-input bg-muted px-3 text-sm text-muted-foreground">
          {addonBefore}
        </span>
      ) : null}
      <span className={cn('min-w-0 flex-1', addonBefore && '[&>span>input]:rounded-l-none', addonAfter && '[&>span>input]:rounded-r-none')}>
        {box}
      </span>
      {addonAfter ? (
        <span className="inline-flex items-center rounded-r-md border border-l-0 border-input bg-muted px-3 text-sm text-muted-foreground">
          {addonAfter}
        </span>
      ) : null}
    </span>
  );
}

/* ---------------- Input.Password ---------------- */

export function InputPassword(props: InputProps) {
  const [visible, setVisible] = useState(false);
  return (
    <InputBase
      {...props}
      type={visible ? 'text' : 'password'}
      suffix={
        <button
          type="button"
          aria-label={visible ? '隐藏密码' : '显示密码'}
          className="cursor-pointer"
          onClick={() => setVisible((v) => !v)}
          tabIndex={-1}
        >
          {visible ? <EyeOff className="size-4" /> : <Eye className="size-4" />}
        </button>
      }
    />
  );
}

/* ---------------- Input.TextArea ---------------- */

export interface TextAreaProps {
  value?: string;
  defaultValue?: string;
  onChange?: (e: ChangeEvent<HTMLTextAreaElement>) => void;
  placeholder?: string;
  rows?: number;
  maxLength?: number;
  disabled?: boolean;
  readOnly?: boolean;
  showCount?: boolean;
  autoSize?: boolean | { minRows?: number; maxRows?: number };
  className?: string;
  style?: CSSProperties;
  id?: string;
  status?: 'error' | 'warning';
}

export function InputTextArea({
  value,
  defaultValue,
  onChange,
  placeholder,
  rows = 3,
  maxLength,
  disabled,
  readOnly,
  showCount,
  className,
  style,
  id,
}: TextAreaProps) {
  const [inner, setInner] = useState(defaultValue ?? '');
  const controlled = value !== undefined;
  const current = controlled ? (value ?? '') : inner;

  return (
    <span className="relative block w-full">
      <textarea
        id={id}
        value={current}
        onChange={(e) => {
          if (!controlled) setInner(e.target.value);
          onChange?.(e);
        }}
        placeholder={placeholder}
        rows={rows}
        maxLength={maxLength}
        disabled={disabled}
        readOnly={readOnly}
        className={cn(FIELD_BASE, 'h-auto resize-y py-2 leading-relaxed', className)}
        style={style}
      />
      {showCount && maxLength ? (
        <span className="absolute bottom-1 right-2 text-xs text-muted-foreground">
          {current.length}/{maxLength}
        </span>
      ) : null}
    </span>
  );
}

/* ---------------- Input.Search ---------------- */

export interface InputSearchProps extends InputProps {
  onSearch?: (value: string, event?: unknown) => void;
  enterButton?: boolean | ReactNode;
  loading?: boolean;
}

export function InputSearch({ onSearch, enterButton, loading, allowClear, ...rest }: InputSearchProps) {
  const [inner, setInner] = useState<string>(rest.defaultValue === undefined ? '' : String(rest.defaultValue));
  const controlled = rest.value !== undefined;
  const current = controlled ? String(rest.value ?? '') : inner;

  const trigger = (v: string) => onSearch?.(v);

  // 宽度必须由**最外层**这个 span 承担。它下面还有「输入框 + 搜索按钮」两个子元素，
  // 若外层是 w-full，调用方的 style={{ width: 300 }} 只会缩到内层，
  // 外层仍占满整行 —— 在 `.filter-bar`（flex + space-between）里就会把右侧
  // 「新建」按钮挤到第二行，表现为工具栏莫名变成两行。
  // 无 width 时才用 w-full，保证放在窄容器里仍能填满。
  const { width, minWidth, maxWidth, ...innerStyle } = rest.style ?? {};
  const outerStyle: CSSProperties = {};
  if (width !== undefined) outerStyle.width = width;
  if (minWidth !== undefined) outerStyle.minWidth = minWidth;
  if (maxWidth !== undefined) outerStyle.maxWidth = maxWidth;

  return (
    <span
      className={cn('inline-flex items-stretch', width === undefined && 'w-full')}
      style={outerStyle}
    >
      <span className="min-w-0 flex-1">
        <InputBase
          {...rest}
          style={innerStyle}
          allowClear={allowClear}
          prefix={rest.prefix ?? <Search className="size-3.5" />}
          value={current}
          onChange={(e) => {
            if (!controlled) setInner(e.target.value);
            rest.onChange?.(e);
          }}
          onPressEnter={() => trigger(current)}
        />
      </span>
      <button
        type="button"
        aria-label="搜索"
        disabled={rest.disabled}
        onClick={() => trigger(current)}
        className={cn(
          'ml-[-1px] inline-flex shrink-0 cursor-pointer items-center gap-1 rounded-r-md border border-input bg-secondary px-3 text-sm hover:bg-accent disabled:opacity-50',
          SIZE_H[rest.size ?? 'middle'],
        )}
      >
        {loading ? <Loader2 className="size-4 animate-spin" /> : null}
        {enterButton && enterButton !== true ? enterButton : null}
      </button>
    </span>
  );
}

/* ---------------- Input.Group ---------------- */

export function InputGroup({ children, className, style, compact }: { children?: ReactNode; className?: string; style?: CSSProperties; compact?: boolean }) {
  return (
    <span className={cn('inline-flex w-full items-stretch', compact && '[&>*]:rounded-none [&>*:first-child]:rounded-l-md [&>*:last-child]:rounded-r-md', className)} style={style}>
      {children}
    </span>
  );
}

/* ================================================================ InputNumber */

export interface InputNumberProps {
  value?: number | null;
  defaultValue?: number | null;
  onChange?: (value: number | null) => void;
  min?: number;
  max?: number;
  step?: number;
  precision?: number;
  disabled?: boolean;
  placeholder?: string;
  size?: 'small' | 'middle' | 'large';
  style?: CSSProperties;
  className?: string;
  id?: string;
  status?: 'error' | 'warning';
  addonAfter?: ReactNode;
}

export function InputNumber({
  value,
  defaultValue,
  onChange,
  min,
  max,
  step,
  precision,
  disabled,
  placeholder,
  size = 'middle',
  style,
  className,
  id,
  addonAfter,
}: InputNumberProps) {
  const [inner, setInner] = useState<number | null>(defaultValue ?? null);
  const controlled = value !== undefined;
  const current = controlled ? value : inner;

  const resolvedStep = step ?? (precision !== undefined ? 1 / 10 ** precision : undefined);

  const emit = (raw: string) => {
    if (raw === '') {
      if (!controlled) setInner(null);
      onChange?.(null);
      return;
    }
    const n = Number(raw);
    if (Number.isNaN(n)) return;
    if (!controlled) setInner(n);
    onChange?.(n);
  };

  const numInput = (
    <input
      id={id}
      inputMode="decimal"
      type="number"
      value={current === null || current === undefined ? '' : String(current)}
      min={min}
      max={max}
      step={resolvedStep}
      onChange={(e) => emit(e.target.value)}
      placeholder={placeholder}
      disabled={disabled}
      className={cn(FIELD_BASE, SIZE_H[size] ?? SIZE_H.middle, 'tabular-nums', className)}
      style={addonAfter ? undefined : style}
    />
  );

  if (!addonAfter) return numInput;

  return (
    <span className="flex w-full items-stretch" style={style}>
      <span className="min-w-0 flex-1 [&>input]:rounded-r-none">{numInput}</span>
      <span className="inline-flex items-center rounded-r-md border border-l-0 border-input bg-muted px-3 text-sm text-muted-foreground">
        {addonAfter}
      </span>
    </span>
  );
}

/* ================================================================ Select */

export interface SelectOption {
  label?: ReactNode;
  value?: string | number | boolean | null;
  disabled?: boolean;
}

export interface SelectProps {
  value?: unknown;
  defaultValue?: unknown;
  onChange?: (value: any, option?: SelectOption | SelectOption[]) => void;
  options?: SelectOption[];
  children?: ReactNode;
  mode?: 'multiple' | 'tags';
  placeholder?: ReactNode;
  allowClear?: boolean;
  showSearch?: boolean;
  filterOption?: boolean | ((input: string, option: SelectOption) => boolean);
  optionFilterProp?: string;
  onSearch?: (value: string) => void;
  onSelect?: (value: any, option: SelectOption) => void;
  onDeselect?: (value: any, option: SelectOption) => void;
  disabled?: boolean;
  loading?: boolean;
  size?: 'small' | 'middle' | 'large';
  status?: 'error' | 'warning';
  tokenSeparators?: string[];
  maxTagCount?: number | 'responsive';
  notFoundContent?: ReactNode;
  className?: string;
  style?: CSSProperties;
  id?: string;
  /** 允许任意值（tags 模式自动开启） */
  dropdownStyle?: CSSProperties;
}

/** 从 `<Select.Option>` 子元素提取选项 */
function optionsFromChildren(children: ReactNode): SelectOption[] {
  const out: SelectOption[] = [];
  Children.forEach(children, (child) => {
    if (!isValidElement(child)) return;
    const props = child.props as { value?: any; disabled?: boolean; children?: ReactNode };
    if (props.children !== undefined && props.value === undefined) {
      out.push(...optionsFromChildren(props.children));
      return;
    }
    out.push({ value: props.value, label: props.children, disabled: props.disabled });
  });
  return out;
}

function SelectBase({
  value,
  defaultValue,
  onChange,
  options,
  children,
  mode,
  placeholder,
  allowClear,
  showSearch,
  filterOption,
  optionFilterProp,
  onSearch,
  onSelect,
  onDeselect,
  disabled,
  loading,
  size = 'middle',
  status,
  tokenSeparators,
  maxTagCount,
  notFoundContent,
  className,
  style,
  id,
}: SelectProps) {
  const multiple = mode === 'multiple' || mode === 'tags';
  const [open, setOpen] = useState(false);
  const [keyword, setKeyword] = useState('');
  const [inner, setInner] = useState<any>(defaultValue);
  const controlled = value !== undefined;
  const current = controlled ? value : inner;

  const optionList = useMemo<SelectOption[]>(() => {
    if (options && options.length) return options;
    return optionsFromChildren(children);
  }, [options, children]);

  // 空串在单选下等价于「无值」：显示 placeholder 而不是一个看不见的空标签。
  // 与 antd 行为一致，也让父组件用 value='' 清空时能真正清掉（否则会退化成
  // 非受控模式、回落到内部旧状态，清空失效）。
  const isEmptyValue = (v: any) => v === undefined || v === null || v === '';
  const selected: any[] = multiple
    ? Array.isArray(current)
      ? current
      : isEmptyValue(current)
        ? []
        : [current]
    : isEmptyValue(current)
      ? []
      : [current];

  const labelOf = useCallback(
    (v: any): ReactNode => {
      const hit = optionList.find((o) => o.value === v);
      if (hit) return hit.label ?? String(v);
      return String(v);
    },
    [optionList],
  );

  const filterKey = optionFilterProp ?? 'label';

  const visibleOptions = useMemo(() => {
    if (!keyword) return optionList;
    if (filterOption === false) return optionList;
    if (typeof filterOption === 'function') return optionList.filter((o) => filterOption(keyword, o));
    const kw = keyword.toLowerCase();
    return optionList.filter((o) => String((o as Record<string, unknown>)[filterKey] ?? o.label ?? '').toLowerCase().includes(kw));
  }, [optionList, keyword, filterOption, filterKey]);

  const commit = (next: any, option?: SelectOption) => {
    if (!controlled) setInner(next);
    onChange?.(next, option);
  };

  const pick = (option: SelectOption) => {
    if (option.disabled) return;
    if (multiple) {
      const has = selected.some((v) => v === option.value);
      if (has) {
        commit(
          selected.filter((v) => v !== option.value),
          option,
        );
        onDeselect?.(option.value, option);
      } else {
        commit([...selected, option.value], option);
        onSelect?.(option.value, option);
      }
      setKeyword('');
      return;
    }
    commit(option.value, option);
    onSelect?.(option.value, option);
    setOpen(false);
    setKeyword('');
  };

  /** tags 模式：允许把输入的任意文本加为标签 */
  const commitKeywordAsTag = () => {
    if (mode !== 'tags' || !keyword.trim()) return;
    const v = keyword.trim();
    if (!selected.some((s) => s === v)) commit([...selected, v]);
    setKeyword('');
  };

  const clearAll = (e: ReactMouseEvent) => {
    e.stopPropagation();
    e.preventDefault();
    commit(multiple ? [] : undefined);
  };

  const shownTags = maxTagCount && typeof maxTagCount === 'number' ? selected.slice(0, maxTagCount) : selected;

  return (
    <Popover
      open={disabled ? false : open}
      onOpenChange={(next) => {
        setOpen(next);
        if (!next) {
          commitKeywordAsTag();
          setKeyword('');
        }
      }}
    >
      <PopoverTrigger asChild>
        <div
          id={id}
          role="combobox"
          aria-expanded={open}
          aria-disabled={disabled}
          tabIndex={disabled ? -1 : 0}
          className={cn(
            'flex w-full cursor-pointer items-center gap-1 rounded-md border border-input bg-transparent px-2.5 py-1 shadow-xs outline-none focus-visible:border-ring focus-visible:ring-[3px] focus-visible:ring-ring/40',
            SIZE_H[size] ?? SIZE_H.middle,
            disabled && 'cursor-not-allowed opacity-50',
            status === 'error' && 'border-destructive',
            className,
          )}
          style={style}
        >
          <span className="flex min-w-0 flex-1 flex-wrap items-center gap-1 overflow-hidden">
            {selected.length === 0 ? (
              <span className="truncate text-muted-foreground">{placeholder}</span>
            ) : multiple ? (
              <>
                {shownTags.map((v) => (
                  <span
                    key={String(v)}
                    className="inline-flex max-w-[10rem] items-center gap-1 rounded bg-secondary px-1.5 py-0.5 text-xs"
                  >
                    <span className="truncate">{labelOf(v)}</span>
                    <button
                      type="button"
                      aria-label="移除"
                      className="cursor-pointer text-muted-foreground hover:text-foreground"
                      onClick={(e) => {
                        e.stopPropagation();
                        commit(selected.filter((s) => s !== v));
                      }}
                    >
                      <X className="size-3" />
                    </button>
                  </span>
                ))}
                {selected.length > shownTags.length ? (
                  <span className="text-xs text-muted-foreground">+{selected.length - shownTags.length}</span>
                ) : null}
              </>
            ) : (
              <span className="truncate">{labelOf(selected[0])}</span>
            )}
          </span>
          {loading ? <Loader2 className="size-3.5 animate-spin text-muted-foreground" /> : null}
          {allowClear && selected.length > 0 && !disabled ? (
            <button
              type="button"
              aria-label="清空"
              className="cursor-pointer text-muted-foreground/60 hover:text-foreground"
              onClick={clearAll}
            >
              <X className="size-3.5" />
            </button>
          ) : null}
          <ChevronDown className={cn('size-3.5 shrink-0 text-muted-foreground transition-transform', open && 'rotate-180')} />
        </div>
      </PopoverTrigger>

      {/* 下拉宽度随最长选项生长（antd popupMatchSelectWidth=false 的默认近似）：
          下限取触发器宽（窄选择器不缩）、上限钳在视口内，选项放不下时换行——
          固定等于触发器宽 + truncate 会把「PAYLOAD_FIELD（事件字段）」这类长标签截断。
          宽度钳制走内联 style：Tailwind 任意值类对 max(var(...)) 的编译不可靠 */}
      <PopoverContent
        align="start"
        className="w-auto p-1"
        style={{
          minWidth: 'max(var(--radix-popover-trigger-width, 10rem), 10rem)',
          maxWidth: 'min(420px, calc(100vw - 2rem))',
        }}
      >
        {(showSearch || mode === 'tags') && (
          <div className="mb-1 border-b border-border pb-1">
            <input
              autoFocus
              value={keyword}
              placeholder="搜索…"
              onChange={(e) => {
                setKeyword(e.target.value);
                onSearch?.(e.target.value);
              }}
              onKeyDown={(e) => {
                if (e.key === 'Enter') {
                  e.preventDefault();
                  commitKeywordAsTag();
                }
              }}
              className="h-7 w-full rounded-sm bg-transparent px-2 text-sm outline-none placeholder:text-muted-foreground"
            />
          </div>
        )}
        <div className="max-h-64 overflow-y-auto">
          {visibleOptions.length === 0 ? (
            <div className="px-2 py-3 text-center text-sm text-muted-foreground">{notFoundContent ?? '无匹配数据'}</div>
          ) : (
            visibleOptions.map((option, i) => {
              const active = selected.some((v) => v === option.value);
              return (
                <button
                  key={`${String(option.value)}-${i}`}
                  type="button"
                  disabled={option.disabled}
                  onClick={() => pick(option)}
                  className={cn(
                    'flex w-full cursor-pointer items-center justify-between gap-2 rounded-sm px-2 py-1.5 text-left text-sm hover:bg-accent',
                    active && 'bg-accent/60 font-medium',
                    option.disabled && 'cursor-not-allowed opacity-40',
                  )}
                >
                  <span className="whitespace-normal break-words">{option.label ?? String(option.value)}</span>
                  {active ? <Check className="size-3.5 shrink-0 text-primary" /> : null}
                </button>
              );
            })
          )}
        </div>
        {mode === 'tags' && keyword.trim() ? (
          <button
            type="button"
            onClick={commitKeywordAsTag}
            className="mt-1 w-full cursor-pointer rounded-sm border-t border-border px-2 py-1.5 text-left text-xs text-muted-foreground hover:bg-accent"
          >
            添加「{keyword.trim()}」
          </button>
        ) : null}
      </PopoverContent>
    </Popover>
  );
}

/** `<Select.Option>` 占位组件：仅用于让 JSX 结构合法，值由 Select 解析 children 得到 */
function SelectOption(_props: { value?: any; disabled?: boolean; children?: ReactNode; key?: unknown }) {
  return null;
}

function SelectOptGroup(_props: { label?: ReactNode; children?: ReactNode }) {
  return null;
}

export const Select = Object.assign(SelectBase, { Option: SelectOption, OptGroup: SelectOptGroup });

/* ================================================================ Switch */

export interface SwitchProps {
  checked?: boolean;
  defaultChecked?: boolean;
  onChange?: (checked: boolean, event?: unknown) => void;
  checkedChildren?: ReactNode;
  unCheckedChildren?: ReactNode;
  disabled?: boolean;
  loading?: boolean;
  size?: 'default' | 'small';
  className?: string;
  style?: CSSProperties;
  id?: string;
  autoFocus?: boolean;
}

/**
 * 带文字标签的 Switch 轨道尺寸阶梯（默认尺寸）。
 * 文字容器可用宽 = 轨道宽 - 34；位移 = 轨道宽 - 24。
 * 类名必须是字面量（Tailwind 靠扫源码收集），所以用常量表而非模板拼接。
 */
const TRACK_LADDER = [
  { cls: 'w-16', travel: 'translate-x-10' }, // 可用 30px：≤2 个汉字（启用/禁用/强制）
  { cls: 'w-20', travel: 'translate-x-14' }, // 可用 46px：3 个汉字（非强制/未发布/已发布）
  { cls: 'w-24', travel: 'translate-x-18' }, // 可用 62px：4 个汉字或 GitHub直链
  { cls: 'w-28', travel: 'translate-x-22' }, // 可用 78px：更长标签
] as const;

export function Switch({
  checked,
  defaultChecked,
  onChange,
  checkedChildren,
  unCheckedChildren,
  disabled,
  loading,
  size = 'default',
  className,
  style,
  id,
}: SwitchProps) {
  const [inner, setInner] = useState(!!defaultChecked);
  const controlled = checked !== undefined;
  const on = controlled ? !!checked : inner;

  const toggle = () => {
    if (disabled || loading) return;
    const next = !on;
    if (!controlled) setInner(next);
    onChange?.(next);
  };

  const hasText = checkedChildren !== undefined || unCheckedChildren !== undefined;
  const small = size === 'small';

  // 文字标签的轨道宽度必须按标签长度算，不能固定 w-16：
  // 文字容器可用宽 = 轨道宽 - 左缩进 24 - 右缩进 8 - 边框 2 = 轨道宽 - 34，
  // w-16(64) 只剩 30px，而 11px 字号下「非强制」「未发布」约 33px、「普通直链」约 44px ⇒ 折成两行。
  // Tailwind 只认源码里出现过的字面量类名，所以轨道宽度写成常量表，不能模板拼接。
  // 滑块位移沿用原有换算关系：位移 = 轨道宽 - 24（原本 w-16 ↔ translate-x-10 即 40 = 64 - 24）。
  const labelWidthPx = (v: ReactNode) => {
    if (typeof v !== 'string') return 0;
    let px = 0;
    for (const ch of v) px += /[\x00-\xff]/.test(ch) ? 6.2 : 11; // ASCII ≈ 6.2px，中文/全角 ≈ 11px
    return Math.ceil(px);
  };
  const textNeed = Math.max(labelWidthPx(checkedChildren), labelWidthPx(unCheckedChildren));
  const track = TRACK_LADDER[textNeed <= 30 ? 0 : textNeed <= 46 ? 1 : textNeed <= 62 ? 2 : 3];

  return (
    <button
      id={id}
      type="button"
      role="switch"
      aria-checked={on}
      disabled={disabled || loading}
      onClick={toggle}
      style={style}
      className={cn(
        'relative inline-flex shrink-0 cursor-pointer items-center rounded-full border border-transparent transition-colors',
        on ? 'bg-primary' : 'bg-input',
        small ? 'h-4 w-8' : 'h-5 w-10',
        hasText && (small ? 'h-5 w-14 px-1.5' : cn('h-6 px-2', track.cls)),
        (disabled || loading) && 'cursor-not-allowed opacity-50',
        className,
      )}
    >
      <span
        className={cn(
          'pointer-events-none inline-block rounded-full bg-background shadow-sm transition-transform',
          small ? 'size-3' : 'size-4',
          on ? (hasText ? (small ? 'translate-x-9' : track.travel) : small ? 'translate-x-4' : 'translate-x-5') : 'translate-x-0',
        )}
      />
      {hasText ? (
        <span
          className={cn(
            // whitespace-nowrap：标签永不折行；overflow-hidden 兜底，估算偏差时在轨道内裁掉而不是溢出按钮外
            'pointer-events-none absolute inset-y-0 flex items-center overflow-hidden whitespace-nowrap text-[11px] font-medium text-primary-foreground',
            on ? 'left-2 right-6 justify-start' : 'left-6 right-2 justify-end',
          )}
        >
          {loading ? <Loader2 className="size-3 animate-spin" /> : on ? checkedChildren : unCheckedChildren}
        </span>
      ) : null}
    </button>
  );
}

/* ================================================================ Checkbox */

export interface CheckboxProps {
  checked?: boolean;
  defaultChecked?: boolean;
  onChange?: (e: { target: { checked: boolean } }) => void;
  disabled?: boolean;
  indeterminate?: boolean;
  children?: ReactNode;
  className?: string;
  style?: CSSProperties;
}

export function Checkbox({ checked, defaultChecked, onChange, disabled, indeterminate, children, className, style }: CheckboxProps) {
  const [inner, setInner] = useState(!!defaultChecked);
  const controlled = checked !== undefined;
  const on = controlled ? !!checked : inner;

  return (
    <label className={cn('inline-flex cursor-pointer items-center gap-2 text-sm', disabled && 'cursor-not-allowed opacity-50', className)} style={style}>
      <button
        type="button"
        role="checkbox"
        aria-checked={on}
        disabled={disabled}
        onClick={() => {
          const next = !on;
          if (!controlled) setInner(next);
          onChange?.({ target: { checked: next } });
        }}
        className={cn(
          'flex size-4 shrink-0 items-center justify-center rounded-[4px] border border-input transition-colors',
          (on || indeterminate) && 'border-primary bg-primary text-primary-foreground',
        )}
      >
        {indeterminate && !on ? (
          <span className="h-0.5 w-2 rounded bg-primary-foreground" />
        ) : on ? (
          <Check className="size-3" strokeWidth={3} />
        ) : null}
      </button>
      {children ? <span>{children}</span> : null}
    </label>
  );
}

/* ================================================================ Radio */

export interface RadioProps {
  value?: any;
  checked?: boolean;
  disabled?: boolean;
  children?: ReactNode;
  className?: string;
  style?: CSSProperties;
}

function RadioBase({ children, className, style }: RadioProps) {
  return (
    <span className={cn('inline-flex items-center gap-2 text-sm', className)} style={style}>
      {children}
    </span>
  );
}
export function RadioButton({ children, className, style }: RadioProps) {
  return <RadioBase {...{ className, style }}>{children}</RadioBase>;
}

export interface RadioGroupProps {
  value?: any;
  defaultValue?: any;
  onChange?: (e: { target: { value: any } }) => void;
  options?: Array<{ label: ReactNode; value: any; disabled?: boolean }>;
  optionType?: 'default' | 'button';
  buttonStyle?: 'outline' | 'solid';
  size?: 'small' | 'middle' | 'large';
  disabled?: boolean;
  className?: string;
  style?: CSSProperties;
  children?: ReactNode;
}

export function RadioGroup({
  value,
  defaultValue,
  onChange,
  options,
  optionType = 'default',
  buttonStyle = 'outline',
  size = 'middle',
  disabled,
  className,
  style,
  children,
}: RadioGroupProps) {
  const [inner, setInner] = useState(defaultValue);
  const controlled = value !== undefined;
  const current = controlled ? value : inner;

  const items = options ?? optionsFromChildren(children).map((o) => ({ label: o.label, value: o.value, disabled: o.disabled }));

  const select = (v: any) => {
    if (!controlled) setInner(v);
    onChange?.({ target: { value: v } });
  };

  if (optionType === 'button') {
    return (
      <span
        role="radiogroup"
        className={cn('inline-flex items-center overflow-hidden rounded-md border border-input', disabled && 'opacity-50', className)}
        style={style}
      >
        {items.map((item, i) => {
          const active = current === item.value;
          return (
            <button
              key={`${String(item.value)}-${i}`}
              type="button"
              role="radio"
              aria-checked={active}
              disabled={disabled || item.disabled}
              onClick={() => select(item.value)}
              className={cn(
                'cursor-pointer border-input px-3 transition-colors',
                SIZE_H[size] ?? SIZE_H.middle,
                i > 0 && 'border-l',
                active ? (buttonStyle === 'solid' ? 'bg-primary text-primary-foreground' : 'bg-accent font-medium') : 'hover:bg-accent/60',
                (disabled || item.disabled) && 'cursor-not-allowed',
              )}
            >
              {item.label}
            </button>
          );
        })}
      </span>
    );
  }

  return (
    <span role="radiogroup" className={cn('inline-flex flex-wrap items-center gap-4', className)} style={style}>
      {items.map((item, i) => {
        const active = current === item.value;
        return (
          <button
            key={`${String(item.value)}-${i}`}
            type="button"
            role="radio"
            aria-checked={active}
            disabled={disabled || item.disabled}
            onClick={() => select(item.value)}
            className={cn('inline-flex cursor-pointer items-center gap-2 text-sm', (disabled || item.disabled) && 'cursor-not-allowed opacity-50')}
          >
            <span className={cn('flex size-4 items-center justify-center rounded-full border transition-colors', active ? 'border-primary' : 'border-input')}>
              {active ? <span className="size-2 rounded-full bg-primary" /> : null}
            </span>
            {item.label}
          </button>
        );
      })}
    </span>
  );
}

/** antd 的 `Radio` 命名空间：`Radio.Group` / `Radio.Button` */
export const Radio = Object.assign(RadioBase, { Group: RadioGroup, Button: RadioButton });

/* ================================================================ AutoComplete */

export interface AutoCompleteProps {
  value?: string;
  defaultValue?: string;
  onChange?: (value: string) => void;
  onSelect?: (value: string, option: SelectOption) => void;
  onSearch?: (value: string) => void;
  /** 点击清除按钮时触发（antd 同形） */
  onClear?: () => void;
  options?: SelectOption[];
  placeholder?: string;
  disabled?: boolean;
  allowClear?: boolean;
  filterOption?: boolean | ((input: string, option: SelectOption) => boolean);
  style?: CSSProperties;
  className?: string;
}

export function AutoComplete({
  value,
  defaultValue,
  onChange,
  onSelect,
  onSearch,
  onClear,
  options = [],
  placeholder,
  disabled,
  allowClear,
  filterOption,
  style,
  className,
}: AutoCompleteProps) {
  const [inner, setInner] = useState(defaultValue ?? '');
  const [open, setOpen] = useState(false);
  const controlled = value !== undefined;
  const current = controlled ? (value ?? '') : inner;

  const visible = useMemo(() => {
    if (!current) return options;
    if (filterOption === false) return options;
    if (typeof filterOption === 'function') return options.filter((o) => filterOption(current, o));
    const kw = current.toLowerCase();
    return options.filter((o) => String(o.label ?? o.value ?? '').toLowerCase().includes(kw));
  }, [options, current, filterOption]);

  const setValue = (v: string) => {
    if (!controlled) setInner(v);
    onChange?.(v);
  };

  return (
    <Popover open={disabled ? false : open && visible.length > 0} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <span className="w-full" style={style}>
          <InputBase
            value={current}
            onChange={(e) => {
              setValue(e.target.value);
              onSearch?.(e.target.value);
              setOpen(true);
            }}
            onFocus={() => setOpen(true)}
            placeholder={placeholder}
            disabled={disabled}
            allowClear={allowClear}
            onClear={onClear}
            className={className}
          />
        </span>
      </PopoverTrigger>
      <PopoverContent
        align="start"
        className="w-auto p-1"
        style={{
          minWidth: 'max(var(--radix-popover-trigger-width, 10rem), 10rem)',
          maxWidth: 'min(420px, calc(100vw - 2rem))',
        }}
      >
        <div className="max-h-56 overflow-y-auto">
          {visible.map((o, i) => (
            <button
              key={`${String(o.value)}-${i}`}
              type="button"
              onClick={() => {
                const v = String(o.value ?? '');
                setValue(v);
                onSelect?.(v, o);
                setOpen(false);
              }}
              className="w-full cursor-pointer whitespace-normal break-words rounded-sm px-2 py-1.5 text-left text-sm hover:bg-accent"
            >
              {o.label ?? String(o.value)}
            </button>
          ))}
        </div>
      </PopoverContent>
    </Popover>
  );
}

/* ================================================================ DatePicker */

const DATE_FMT = 'YYYY-MM-DD';
const DATETIME_FMT = 'YYYY-MM-DD HH:mm:ss';

export interface DatePickerProps {
  value?: Dayjs | null;
  defaultValue?: Dayjs | null;
  /** antd 签名：(date, dateString) */
  onChange?: (date: Dayjs | null, dateString: string) => void;
  showTime?: boolean;
  format?: string;
  placeholder?: string;
  disabled?: boolean;
  allowClear?: boolean;
  size?: 'small' | 'middle' | 'large';
  status?: 'error' | 'warning';
  style?: CSSProperties;
  className?: string;
  id?: string;
}

export function DatePickerBase({ value, defaultValue, onChange, showTime, placeholder, disabled, allowClear, size = 'middle', status, style, className, id }: DatePickerProps) {
  const [inner, setInner] = useState<Dayjs | null>(defaultValue ?? null);
  const controlled = value !== undefined;
  const current = controlled ? (value ?? null) : inner;

  const inputType = showTime ? 'datetime-local' : 'date';
  const displayFmt = showTime ? DATETIME_FMT : DATE_FMT;
  const nativeFmt = showTime ? 'YYYY-MM-DDTHH:mm' : DATE_FMT;

  const handle = (raw: string) => {
    if (!raw) {
      if (!controlled) setInner(null);
      onChange?.(null, '');
      return;
    }
    const next = dayjs(raw);
    if (!controlled) setInner(next);
    onChange?.(next, next.format(displayFmt));
  };

  return (
    <input
      id={id}
      type={inputType}
      value={current ? current.format(nativeFmt) : ''}
      onChange={(e) => handle(e.target.value)}
      placeholder={placeholder ?? displayFmt}
      disabled={disabled}
      className={cn(
        FIELD_BASE,
        SIZE_H[size] ?? SIZE_H.middle,
        'cursor-pointer',
        !current && 'text-muted-foreground',
        status === 'error' && 'border-destructive',
        className,
      )}
      style={style}
      aria-label={placeholder ?? '选择日期'}
    />
  );
}

export interface RangePickerProps {
  value?: [Dayjs | null, Dayjs | null] | null;
  defaultValue?: [Dayjs | null, Dayjs | null] | null;
  onChange?: (dates: [Dayjs, Dayjs] | null, dateStrings: [string, string]) => void;
  showTime?: boolean;
  format?: string;
  disabled?: boolean;
  allowClear?: boolean;
  size?: 'small' | 'middle' | 'large';
  style?: CSSProperties;
  className?: string;
}

export function RangePicker({ value, defaultValue, onChange, showTime, disabled, size = 'middle', style, className }: RangePickerProps) {
  const [inner, setInner] = useState<[Dayjs | null, Dayjs | null] | null>(defaultValue ?? null);
  const controlled = value !== undefined;
  const current = (controlled ? value : inner) ?? [null, null];

  const inputType = showTime ? 'datetime-local' : 'date';
  const displayFmt = showTime ? DATETIME_FMT : DATE_FMT;
  const nativeFmt = showTime ? 'YYYY-MM-DDTHH:mm' : DATE_FMT;

  const emit = (next: [Dayjs | null, Dayjs | null]) => {
    if (!controlled) setInner(next);
    if (!next[0] || !next[1]) {
      onChange?.(null, ['', '']);
      return;
    }
    onChange?.([next[0], next[1]], [next[0].format(displayFmt), next[1].format(displayFmt)]);
  };

  return (
    <span className={cn('inline-flex items-center gap-2', className)} style={style}>
      <input
        type={inputType}
        value={current[0] ? current[0].format(nativeFmt) : ''}
        onChange={(e) => emit([e.target.value ? dayjs(e.target.value) : null, current[1]])}
        disabled={disabled}
        aria-label="开始时间"
        className={cn(FIELD_BASE, SIZE_H[size] ?? SIZE_H.middle, 'cursor-pointer')}
      />
      <span className="text-muted-foreground">~</span>
      <input
        type={inputType}
        value={current[1] ? current[1].format(nativeFmt) : ''}
        onChange={(e) => emit([current[0], e.target.value ? dayjs(e.target.value) : null])}
        disabled={disabled}
        aria-label="结束时间"
        className={cn(FIELD_BASE, SIZE_H[size] ?? SIZE_H.middle, 'cursor-pointer')}
      />
    </span>
  );
}

export const DatePicker = Object.assign(DatePickerBase, { RangePicker });

/* ================================================================ TreeSelect */

export interface TreeSelectNode {
  title?: ReactNode;
  value?: any;
  key?: string | number;
  disabled?: boolean;
  children?: TreeSelectNode[];
}

export interface TreeSelectProps {
  treeData?: TreeSelectNode[];
  value?: any;
  onChange?: (value: any) => void;
  treeCheckable?: boolean;
  showCheckedStrategy?: 'SHOW_ALL' | 'SHOW_PARENT' | 'SHOW_CHILD';
  placeholder?: ReactNode;
  disabled?: boolean;
  allowClear?: boolean;
  style?: CSSProperties;
  className?: string;
  size?: 'small' | 'middle' | 'large';
}

/** 自身 + 全部后代的值（用于展示与勾选状态计算） */
const subtreeValues = (node: TreeSelectNode): any[] => [
  ...(node.value !== undefined ? [node.value] : []),
  ...(node.children?.length ? node.children.flatMap(subtreeValues) : []),
];

function TreeCheckList({
  nodes,
  selected,
  onToggle,
  depth = 0,
}: {
  nodes: TreeSelectNode[];
  selected: any[];
  onToggle: (node: TreeSelectNode) => void;
  depth?: number;
}) {
  return (
    <ul className="space-y-0.5">
      {nodes.map((node, i) => {
        const hasChildren = !!node.children?.length;
        const values = subtreeValues(node);
        const checked = !hasChildren
          ? node.value !== undefined && selected.includes(node.value)
          : values.length > 0 && values.every((v) => selected.includes(v));
        return (
          <li key={node.key ?? `${String(node.value)}-${i}`}>
            <label
              className={cn('flex cursor-pointer items-center gap-2 rounded-sm px-1.5 py-1 text-sm hover:bg-accent', node.disabled && 'cursor-not-allowed opacity-50')}
              style={{ paddingLeft: depth * 16 + 6 }}
            >
              <button
                type="button"
                role="checkbox"
                aria-checked={checked}
                disabled={node.disabled}
                onClick={() => onToggle(node)}
                className={cn(
                  'flex size-4 shrink-0 items-center justify-center rounded-[4px] border border-input transition-colors',
                  checked && 'border-primary bg-primary text-primary-foreground',
                )}
              >
                {checked ? <Check className="size-3" strokeWidth={3} /> : null}
              </button>
              <span className="truncate">{node.title}</span>
            </label>
            {hasChildren ? <TreeCheckList nodes={node.children!} selected={selected} onToggle={onToggle} depth={depth + 1} /> : null}
          </li>
        );
      })}
    </ul>
  );
}

/** 勾选一个节点时要一并增删的值集合 */

export function TreeSelect({
  treeData = [],
  value,
  onChange,
  treeCheckable,
  showCheckedStrategy,
  placeholder,
  disabled,
  allowClear,
  style,
  className,
  size = 'middle',
}: TreeSelectProps) {
  const [open, setOpen] = useState(false);
  const selected: any[] = Array.isArray(value) ? value : value === undefined || value === null ? [] : [value];
  void showCheckedStrategy;
  const checkable = treeCheckable !== false && treeCheckable !== undefined;

  /** value → 标题的反查表 */
  const titleMap = useMemo(() => {
    const map = new Map<any, ReactNode>();
    const walk = (nodes: TreeSelectNode[]) => {
      nodes.forEach((n) => {
        if (n.value !== undefined) map.set(n.value, n.title);
        if (n.children) walk(n.children);
      });
    };
    walk(treeData);
    return map;
  }, [treeData]);

  const toggle = (node: TreeSelectNode) => {
    if (treeCheckable === false || treeCheckable === undefined) {
      onChange?.(node.value);
      setOpen(false);
      return;
    }
    // 勾选/取消：连同整棵子树一起增删（父节点值同样计入，对应 SHOW_ALL 语义）
    const values = subtreeValues(node);
    if (values.length === 0) return;
    const allIn = values.every((v) => selected.includes(v));
    const next = allIn
      ? selected.filter((v) => !values.includes(v))
      : [...new Set([...selected, ...values])];
    onChange?.(next);
  };

  return (
    <Popover open={disabled ? false : open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <div
          role="combobox"
          aria-expanded={open}
          tabIndex={disabled ? -1 : 0}
          className={cn(
            'flex w-full cursor-pointer items-center gap-1 rounded-md border border-input bg-transparent px-2.5 py-1 shadow-xs outline-none focus-visible:border-ring focus-visible:ring-[3px] focus-visible:ring-ring/40',
            // 多选：不能沿用固定高度——已选项一多会换行，固定 h-9 会溢出；
            // 改用「最小高度」+ 标签区自身上限/内部滚动（见下方 max-h-20）。
            // 单选保持原固定高度，不影响其它调用方。
            checkable ? 'min-h-9 py-1' : (SIZE_H[size] ?? SIZE_H.middle),
            disabled && 'cursor-not-allowed opacity-50',
            className,
          )}
          style={style}
        >
          {/* max-h-20 + overflow-y-auto 放在标签区自身：触发器是 items-center，
              子项高度不受容器 max-height 约束，上限必须挂在这里才生效。
              overflow-y-auto 会让 overflow-x 一并计算为 auto，标签绝不会横向漏出。 */}
          <span className="flex max-h-20 min-w-0 flex-1 flex-wrap items-center gap-1 overflow-y-auto">
            {selected.length === 0 ? (
              <span className="truncate text-muted-foreground">{placeholder}</span>
            ) : (
              selected.map((v) => titleMap.get(v) ?? String(v)).filter(Boolean).map((t, i) =>
                checkable ? (
                  // max-w-full + truncate：单个标签再长也不会把容器撑宽；
                  // 换行（flex-wrap）让「标签总宽」不再等于 max-content，弹窗宽度因此不再被撑爆
                  <span
                    key={i}
                    className="inline-flex max-w-full items-center truncate rounded-sm bg-muted px-1.5 py-0.5 text-xs leading-5"
                  >
                    {t}
                  </span>
                ) : (
                  <span key={i} className="mr-1 truncate">
                    {t}
                  </span>
                ),
              )
            )}
          </span>
          {allowClear && selected.length > 0 && !disabled ? (
            <button
              type="button"
              aria-label="清空"
              className="cursor-pointer text-muted-foreground/60 hover:text-foreground"
              onClick={(e) => {
                e.stopPropagation();
                onChange?.(treeCheckable ? [] : undefined);
              }}
            >
              <X className="size-3.5" />
            </button>
          ) : null}
          <ChevronDown className={cn('size-3.5 shrink-0 text-muted-foreground transition-transform', open && 'rotate-180')} />
        </div>
      </PopoverTrigger>
      <PopoverContent
        align="start"
        collisionPadding={12}
        className="w-[var(--radix-popover-trigger-width)] min-w-56 max-w-[min(560px,calc(100vw-2rem))] p-1"
      >
        <div className="max-h-72 overflow-y-auto">
          <TreeCheckList nodes={treeData} selected={selected} onToggle={toggle} />
        </div>
      </PopoverContent>
    </Popover>
  );
}

/* ================================================================ Upload */

export interface UploadRequestOption {
  file: File;
  filename?: string;
  onSuccess?: (body?: unknown, xhr?: unknown) => void;
  onError?: (err: Error) => void;
  onProgress?: (e: { percent: number }) => void;
}

export interface UploadProps {
  customRequest?: (options: UploadRequestOption) => void | Promise<void>;
  action?: string;
  showUploadList?: boolean;
  multiple?: boolean;
  accept?: string;
  disabled?: boolean;
  beforeUpload?: (file: File, fileList: File[]) => boolean | Promise<boolean>;
  onChange?: (info: { file: File; fileList: File[] }) => void;
  onClick?: () => void;
  className?: string;
  style?: CSSProperties;
  children?: ReactNode;
  directory?: boolean;
}

function useUploader({ customRequest, multiple, accept, disabled, beforeUpload, onChange }: UploadProps) {
  const inputRef = useRef<HTMLInputElement>(null);

  const submit = useCallback(
    async (files: File[]) => {
      let list = files;
      if (beforeUpload) {
        const checks = await Promise.all(files.map((f) => beforeUpload(f, files)));
        list = files.filter((_, i) => checks[i] !== false);
      }
      for (const file of list) {
        onChange?.({ file, fileList: list });
        if (customRequest) {
          await customRequest({ file, filename: file.name });
        }
      }
    },
    [customRequest, beforeUpload, onChange],
  );

  const open = () => {
    if (disabled) return;
    inputRef.current?.click();
  };

  const input = (
    <input
      ref={inputRef}
      type="file"
      hidden
      multiple={multiple}
      accept={accept}
      disabled={disabled}
      onChange={(e) => {
        const files = Array.from(e.target.files ?? []);
        if (files.length) void submit(files);
        e.target.value = '';
      }}
    />
  );

  return { open, input, submit };
}

function UploadBase(props: UploadProps) {
  const { open, input } = useUploader(props);
  return (
    <>
      <span
        role="button"
        tabIndex={props.disabled ? -1 : 0}
        className={cn('inline-flex', props.disabled && 'cursor-not-allowed opacity-50', props.className)}
        style={props.style}
        onClick={() => {
          props.onClick?.();
          open();
        }}
        onKeyDown={(e) => {
          if (e.key === 'Enter' || e.key === ' ') open();
        }}
      >
        {props.children}
      </span>
      {input}
    </>
  );
}

export interface DraggerProps extends UploadProps {}

export function UploadDragger({ disabled, className, style, children, multiple, accept, ...rest }: DraggerProps) {
  const [hovering, setHovering] = useState(false);
  const { open, input, submit } = useUploader({ multiple, accept, disabled, ...rest });

  const handleDrop = (e: DragEvent<HTMLDivElement>) => {
    e.preventDefault();
    setHovering(false);
    if (disabled) return;
    const files = Array.from(e.dataTransfer.files ?? []);
    if (files.length) void submit(files);
  };

  return (
    <>
      <div
        role="button"
        tabIndex={disabled ? -1 : 0}
        aria-disabled={disabled}
        onClick={open}
        onKeyDown={(e) => {
          if (e.key === 'Enter' || e.key === ' ') open();
        }}
        onDragOver={(e) => {
          e.preventDefault();
          if (!disabled) setHovering(true);
        }}
        onDragLeave={() => setHovering(false)}
        onDrop={handleDrop}
        className={cn(
          'w-full cursor-pointer rounded-lg border border-dashed border-input p-6 text-center transition-colors',
          hovering && 'border-primary bg-accent/50',
          disabled && 'cursor-not-allowed opacity-50',
          className,
        )}
        style={style}
      >
        {children}
      </div>
      {input}
    </>
  );
}

export const Upload = Object.assign(UploadBase, { Dragger: UploadDragger });

/* ================================================================ Input.Image */

export interface ImageProps {
  src?: string;
  alt?: string;
  width?: number | string;
  height?: number | string;
  style?: CSSProperties;
  className?: string;
  preview?: boolean;
  fallback?: string;
}

export function Image({ src, alt, width, height, style, className, preview = true, fallback }: ImageProps) {
  const [open, setOpen] = useState(false);
  const [broken, setBroken] = useState(false);

  const url = broken && fallback ? fallback : src;

  return (
    <>
      {/* eslint-disable-next-line @next/next/no-img-element */}
      <img
        src={url}
        alt={alt ?? ''}
        width={width}
        height={height}
        style={style}
        onError={() => setBroken(true)}
        onClick={() => preview && setOpen(true)}
        className={cn(preview && 'cursor-zoom-in', className)}
      />
      {preview ? (
        <Dialog open={open} onOpenChange={setOpen}>
          <DialogContent className="max-w-[min(90vw,60rem)] p-2" showCloseButton>
            <DialogTitle className="sr-only">{alt ?? '图片预览'}</DialogTitle>
            {/* eslint-disable-next-line @next/next/no-img-element */}
            <img src={url} alt={alt ?? ''} className="mx-auto max-h-[80vh] w-auto object-contain" />
          </DialogContent>
        </Dialog>
      ) : null}
    </>
  );
}

/* ================================================================ 导出聚合 */

/** antd 的 `Input` 命名空间：`Input.Password` / `Input.TextArea` / `Input.Search` / `Input.Group` */
export const Input = Object.assign(InputBase, {
  Password: InputPassword,
  TextArea: InputTextArea,
  Search: InputSearch,
  Group: InputGroup,
});

export type { Dayjs, KeyboardEvent };
