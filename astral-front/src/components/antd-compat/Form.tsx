'use client';

/**
 * antd 兼容层：Form 表单系统
 *
 * 目标：**与 antd Form 的 API 同形**，内部用 React + Tailwind 自研实现，
 * 业务页只需把 `from 'antd'` 换成 `from '@/components/antd-compat'`，表单逻辑零改动。
 *
 * 覆盖的 antd 能力：
 * - `Form`（initialValues / layout / onFinish / onFinishFailed / form / style / className）
 * - `Form.Item`（name / label / rules / required / initialValue / valuePropName /
 *   getValueFromEvent / getValueProps / noStyle / shouldUpdate / tooltip / extra /
 *   help / validateStatus / dependencies）
 * - `Form.List`（fields / add / remove / move / insert）
 * - `Form.useForm()` / `Form.useWatch(name, form)`
 *
 * 值绑定沿用 antd 的「克隆子元素注入 value + onChange」策略，因此
 * `Input` / `Select` / `Switch` 等控件无需感知表单上下文即可被复用。
 */

import {
  cloneElement,
  createContext,
  isValidElement,
  useCallback,
  useContext,
  useEffect,
  useId,
  useMemo,
  useReducer,
  useRef,
  useSyncExternalStore,
  type CSSProperties,
  type FormEvent,
  type ReactElement,
  type ReactNode,
} from 'react';

import { cn } from '@/lib/utils';

/* ============================ 路径与对象工具 ============================ */

export type NamePath = string | number | (string | number)[];

const PATH_SEP = '\u0000';

/** 把 antd 的 name 写法统一成路径数组（支持 'a.b'、'list[0].name'、['a', 0, 'b']） */
export function toPath(name: NamePath | undefined): (string | number)[] {
  if (name === undefined || name === null) return [];
  if (Array.isArray(name)) return name.map((s) => (typeof s === 'number' ? s : String(s)));
  if (typeof name === 'number') return [name];
  return name.split('.').flatMap((seg) => {
    const out: (string | number)[] = [];
    const re = /([^[\]]+)|\[(\d+)\]/g;
    let m: RegExpExecArray | null;
    while ((m = re.exec(seg))) {
      if (m[1] !== undefined) out.push(m[1]);
      else if (m[2] !== undefined) out.push(Number(m[2]));
    }
    return out.length ? out : [seg];
  });
}

export const pathKey = (path: (string | number)[]): string => path.map(String).join(PATH_SEP);

export const keyToPath = (key: string): (string | number)[] =>
  key.split(PATH_SEP).map((s) => (/^\d+$/.test(s) ? Number(s) : s));

export type FormValues = Record<string, unknown>;

export function getAtPath(root: unknown, path: (string | number)[]): unknown {
  let cur = root;
  for (const k of path) {
    if (cur === null || cur === undefined || !Object.hasOwn(cur, k)) return undefined;
    // hasOwn also supports boxed primitives; retain their ordinary indexed reads.
    cur = (cur as Record<string | number, unknown>)[k];
  }
  return cur;
}

/** JSON 字段 __proto__ 应为自有数据属性，不得调用对象原型 setter。 */
function setOwnValue(target: object, key: string | number, value: unknown): void {
  if (key === '__proto__') {
    Object.defineProperty(target, key, {
      value,
      enumerable: true,
      writable: true,
      configurable: true,
    });
  } else {
    Reflect.set(target, key, value);
  }
}

/** 不可变写入：沿路径复制容器，未触及的分支保留原引用（保证 useWatch 的快照稳定） */
export function setAtPath(root: unknown, path: (string | number)[], value: unknown): unknown {
  if (path.length === 0) return value;
  const [head, ...rest] = path;
  const base =
    root === null || root === undefined || typeof root !== 'object'
      ? typeof head === 'number'
        ? []
        : {}
      : Array.isArray(root)
        ? [...root]
        : { ...root };
  setOwnValue(base, head, setAtPath(getAtPath(root, [head]), rest, value));
  return base;
}

/** 不可变删除 */
function deleteAtPath(root: unknown, path: (string | number)[]): unknown {
  if (path.length === 0) return root;
  const [head, ...rest] = path;
  if (root === null || root === undefined || typeof root !== 'object') return root;
  if (path.length === 1) {
    if (Array.isArray(root)) return root.filter((_, i) => i !== head);
    const next: Record<string | number, unknown> = { ...root };
    delete next[head];
    return next;
  }
  return setAtPath(
    root,
    [head],
    deleteAtPath((root as Record<string | number, unknown>)[head], rest),
  );
}

const isPlainObject = (v: unknown): v is FormValues =>
  v !== null &&
  typeof v === 'object' &&
  !Array.isArray(v) &&
  Object.getPrototypeOf(v) === Object.prototype;

export function deepClone<T>(v: T): T {
  if (Array.isArray(v)) return v.map((x) => deepClone(x)) as unknown as T;
  if (isPlainObject(v)) {
    const out: FormValues = {};
    for (const k of Object.keys(v)) setOwnValue(out, k, deepClone(v[k]));
    return out as T;
  }
  return v;
}

/** 深合并：patch 中显式写出的 `undefined` 会覆盖 base（支持「清空字段」语义） */
export function deepMerge(base: unknown, patch: unknown): unknown {
  if (!isPlainObject(patch)) return patch;
  const out: FormValues = isPlainObject(base) ? { ...base } : {};
  for (const k of Object.keys(patch)) {
    const pv = patch[k];
    if (pv === undefined) {
      setOwnValue(out, k, undefined);
      continue;
    }
    setOwnValue(out, k, isPlainObject(pv) ? deepMerge(getAtPath(out, [k]), pv) : pv);
  }
  return out;
}

/* ================================ 校验规则 ================================ */

export interface FormRule {
  required?: boolean;
  message?: string;
  type?: 'string' | 'number' | 'integer' | 'boolean' | 'array' | 'object' | 'email' | 'url';
  /** 数字时为值域，字符串/数组时为长度 */
  min?: number;
  max?: number;
  /** 精确长度 */
  len?: number;
  pattern?: RegExp;
  /** 禁止纯空白 */
  whitespace?: boolean;
  enum?: unknown[];
  transform?(value: unknown): unknown;
  /** 自定义校验：抛出 Error / reject 表示失败 */
  validator?(rule: FormRule, value: unknown): void | Promise<void>;
}

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

const isEmptyValue = (v: unknown): boolean =>
  v === undefined || v === null || v === '' || (Array.isArray(v) && v.length === 0);

/** 顺序执行规则，返回第一条错误信息；全部通过返回 undefined */
export async function runRules(value: unknown, rules: FormRule[]): Promise<string | undefined> {
  for (const rule of rules) {
    const v = rule.transform ? rule.transform(value) : value;
    const empty = isEmptyValue(v);

    if (rule.required && empty) return rule.message ?? '此项为必填项';
    if (!empty) {
      if (rule.whitespace && typeof v === 'string' && !v.trim())
        return rule.message ?? '不能为纯空白字符';

      switch (rule.type) {
        case 'email':
          if (!EMAIL_RE.test(String(v))) return rule.message ?? '邮箱格式不正确';
          break;
        case 'url':
          try {
            new URL(String(v));
          } catch {
            return rule.message ?? 'URL 格式不正确';
          }
          break;
        case 'number':
          if (Number.isNaN(Number(v))) return rule.message ?? '请输入数字';
          break;
        case 'integer':
          if (!Number.isInteger(Number(v))) return rule.message ?? '请输入整数';
          break;
        case 'array':
          if (!Array.isArray(v)) return rule.message ?? '应为数组';
          break;
        case 'boolean':
          if (typeof v !== 'boolean') return rule.message ?? '应为布尔值';
          break;
        case 'object':
          if (!isPlainObject(v)) return rule.message ?? '应为对象';
          break;
        default:
          break;
      }

      if (rule.len !== undefined && String(v).length !== rule.len) {
        return rule.message ?? `长度必须为 ${rule.len} 个字符`;
      }
      if (rule.min !== undefined) {
        const n = typeof v === 'number' ? v : Array.isArray(v) ? v.length : String(v).length;
        if (n < rule.min) return rule.message ?? `不能小于 ${rule.min}`;
      }
      if (rule.max !== undefined) {
        const n = typeof v === 'number' ? v : Array.isArray(v) ? v.length : String(v).length;
        if (n > rule.max) return rule.message ?? `不能大于 ${rule.max}`;
      }
      if (rule.pattern) {
        const re = rule.pattern.global
          ? new RegExp(rule.pattern.source, rule.pattern.flags.replace(/g/g, ''))
          : rule.pattern;
        if (!re.test(String(v))) return rule.message ?? '格式不正确';
      }
      if (rule.enum && !rule.enum.includes(v)) return rule.message ?? '取值不在允许范围内';
    }
    if (rule.validator) {
      try {
        await rule.validator(rule, v);
      } catch (err: unknown) {
        const msg = err instanceof Error ? err.message : undefined;
        return msg ?? rule.message ?? '校验失败';
      }
    }
  }
  return undefined;
}

/* ================================ FormInstance ================================ */

type PathSegmentValue<Value, Segment> = Segment extends keyof NonNullable<Value>
  ? NonNullable<Value>[Segment]
  : NonNullable<Value> extends readonly (infer Item)[]
    ? Segment extends number | `${number}`
      ? Item
      : unknown
    : unknown;

/** Literal keys, dotted paths and tuple paths retain the supplied form's field type. */
export type FormFieldValue<Values, Path> = Path extends readonly [infer Head, ...infer Rest]
  ? Rest extends []
    ? PathSegmentValue<Values, Head>
    : FormFieldValue<PathSegmentValue<Values, Head>, Rest>
  : Path extends `${infer Head}.${infer Rest}`
    ? FormFieldValue<PathSegmentValue<Values, Head>, Rest>
    : Path extends string | number
      ? PathSegmentValue<Values, Path>
      : unknown;

export type FormValuePatch<Values> = {
  [Key in keyof Values]?: Values[Key] extends readonly unknown[]
    ? Values[Key]
    : Values[Key] extends object
      ? FormValuePatch<Values[Key]>
      : Values[Key];
};

export interface FormValidationFailure<Values = FormValues> {
  values: Values;
  errorFields: { name: NamePath; errors: (string | undefined)[] }[];
  outOfDate?: boolean;
}

export interface FormInstance<Values extends object = FormValues> {
  getFieldsValue(names?: NamePath[] | true): Values;
  getFieldValue<const Path extends NamePath>(name: Path): FormFieldValue<Values, Path>;
  setFieldsValue(values: FormValuePatch<Values>): void;
  setFieldValue(name: NamePath, value: unknown): void;
  resetFields(names?: NamePath[]): void;
  validateFields(names?: NamePath[]): Promise<Values>;
  submit(): void;
  isFieldTouched(name: NamePath): boolean;
  getFieldError(name: NamePath): string[];
  scrollToField(name: NamePath): void;
  /* ---------- 内部状态（兼容层自用，业务代码请勿直接访问） ---------- */
  _store: FormValues;
  _touched: Set<string>;
  _errors: Record<string, string | undefined>;
  _fieldInitials: FormValues;
  _initialRegistered: Set<string>;
  _fieldRules: Map<string, FormRule[]>;
  _watchers: Set<() => void>;
  _rerender: () => void;
  _notify: () => void;
  _subscribe: (cb: () => void) => () => void;
  _value: (path: (string | number)[]) => unknown;
  _values: () => FormValues;
  _registerField: (path: (string | number)[], initialValue: unknown, rules?: FormRule[]) => void;
  _unregisterField: (path: (string | number)[]) => void;
  /** 表单标识，用于生成控件 id 与原生 submit 触发 */
  _formId?: string;
}

export function createFormInstance<Values extends object = FormValues>(): FormInstance<Values> {
  let valuesRevision = 0;
  let nextValidationVersion = 0;
  const validationVersions = new Map<string, number>();
  const inst: FormInstance<Values> = {
    _store: {} as FormValues,
    _touched: new Set<string>(),
    _errors: {} as Record<string, string | undefined>,
    _fieldInitials: {},
    _initialRegistered: new Set<string>(),
    _fieldRules: new Map<string, FormRule[]>(),
    _watchers: new Set<() => void>(),
    _rerender: () => {},

    _notify() {
      inst._watchers.forEach((w) => w());
      inst._rerender();
    },

    _subscribe(cb: () => void) {
      inst._watchers.add(cb);
      return () => {
        inst._watchers.delete(cb);
      };
    },

    _registerField(path: (string | number)[], initialValue: unknown, rules?: FormRule[]) {
      if (path.length === 0) return;
      const key = pathKey(path);
      if (!inst._initialRegistered.has(key)) {
        inst._initialRegistered.add(key);
        if (initialValue !== undefined) {
          inst._fieldInitials = setAtPath(inst._fieldInitials, path, initialValue) as FormValues;
        }
      }
      if (rules) inst._fieldRules.set(key, rules);
      else inst._fieldRules.delete(key);
    },

    _unregisterField(path: (string | number)[]) {
      const key = pathKey(path);
      inst._fieldRules.delete(key);
      delete inst._errors[key];
      validationVersions.delete(key);
      // Preserve draft values and initials when a conditional field is temporarily unmounted.
    },

    _value(path: (string | number)[]) {
      if (path.length === 0) return inst._values();
      const raw = getAtPath(inst._store, path);
      if (raw !== undefined) return raw;
      if (inst._touched.has(pathKey(path))) return undefined;
      return getAtPath(inst._fieldInitials, path);
    },

    _values() {
      return deepMerge(inst._fieldInitials, inst._store) as FormValues;
    },

    getFieldsValue(...args: [names?: NamePath[] | true]) {
      const all = inst._values();
      const names = args[0];
      if (!names || !Array.isArray(names) || names.length === 0) return all as Values;
      let out: unknown = {};
      for (const n of names) {
        const p = toPath(n);
        const v = getAtPath(all, p);
        if (p.length > 0 && v !== undefined) out = setAtPath(out, p, v);
      }
      return out as Values;
    },

    getFieldValue<const Path extends NamePath>(name: Path) {
      return inst._value(toPath(name)) as FormFieldValue<Values, Path>;
    },

    setFieldValue(name: NamePath, value: unknown) {
      const path = toPath(name);
      if (path.length === 0) return;
      valuesRevision += 1;
      inst._touched.add(pathKey(path));
      inst._store = setAtPath(inst._store, path, value) as FormValues;
      if (getAtPath(inst._errors, [pathKey(path)]))
        inst._errors = { ...inst._errors, [pathKey(path)]: undefined };
      inst._notify();
    },

    setFieldsValue(values: unknown) {
      if (!isPlainObject(values)) return;
      valuesRevision += 1;
      inst._store = deepMerge(inst._store, values) as FormValues;
      markTouched(values, []);
      inst._notify();
    },

    resetFields(names?: NamePath[]) {
      valuesRevision += 1;
      if (!names || names.length === 0) {
        inst._store = {};
        inst._touched.clear();
        inst._errors = {};
      } else {
        for (const n of names) {
          const p = toPath(n);
          const key = pathKey(p);
          inst._store = deleteAtPath(inst._store, p) as FormValues;
          for (const t of Array.from(inst._touched)) {
            if (t === key || t.startsWith(`${key}${PATH_SEP}`)) inst._touched.delete(t);
          }
          for (const e of Object.keys(inst._errors)) {
            if (e === key || e.startsWith(`${key}${PATH_SEP}`)) delete inst._errors[e];
          }
        }
      }
      inst._notify();
    },

    async validateFields(names?: NamePath[]) {
      const targets = collectKeys(inst, names);
      const revisionAtStart = valuesRevision;
      const versionsAtStart = new Map<string, number>();
      for (const key of targets) {
        const version = ++nextValidationVersion;
        versionsAtStart.set(key, version);
        validationVersions.set(key, version);
      }
      const ensureCurrentValidation = () => {
        if (
          valuesRevision !== revisionAtStart ||
          targets.some(
            (key) =>
              validationVersions.get(key) !== versionsAtStart.get(key) ||
              !inst._fieldRules.has(key),
          )
        ) {
          // 迟到结果仍向调用方报告失效，但绝不覆盖更新后的字段错误。
          throw { values: inst._values(), errorFields: [], outOfDate: true };
        }
      };
      const nextErrors: Record<string, string | undefined> = {};
      let firstKey: string | null = null;

      for (const key of targets) {
        const rules = inst._fieldRules.get(key);
        if (!rules || rules.length === 0) continue;
        const msg = await runRules(inst._value(keyToPath(key)), rules);
        ensureCurrentValidation();
        if (msg) {
          setOwnValue(nextErrors, key, msg);
          if (firstKey === null) firstKey = key;
        }
      }

      if (names?.length) {
        const retainedErrors = { ...inst._errors };
        for (const key of targets) delete retainedErrors[key];
        inst._errors = { ...retainedErrors, ...nextErrors };
      } else {
        inst._errors = nextErrors;
      }
      inst._notify();

      if (firstKey !== null) {
        throw {
          values: inst._values(),
          errorFields: Object.keys(nextErrors).map((k) => ({
            name: keyToPath(k),
            errors: [nextErrors[k]],
          })),
        };
      }
      return inst.getFieldsValue(names) as Values;
    },

    submit() {
      const formEl = document.querySelector<HTMLFormElement>(
        `form[data-form-id="${inst._formId ?? ''}"]`,
      );
      formEl?.requestSubmit?.();
    },

    isFieldTouched(name: NamePath) {
      return inst._touched.has(pathKey(toPath(name)));
    },

    getFieldError(name: NamePath) {
      const msg = getAtPath(inst._errors, [pathKey(toPath(name))]) as string | undefined;
      return msg ? [msg] : [];
    },

    scrollToField(name: NamePath) {
      const key = pathKey(toPath(name));
      const el = document.getElementById(
        `${inst._formId ?? 'antd-form'}_${encodeURIComponent(key)}`,
      );
      el?.scrollIntoView({ block: 'center', behavior: 'smooth' });
      (el as HTMLElement | null)?.focus?.();
    },

    /* 供 Form 组件回填 */
    _formId: undefined as string | undefined,
  };

  /** 递归标记 setFieldsValue 传入的叶子路径为「已触碰」 */
  function markTouched(values: FormValues, prefix: (string | number)[]) {
    for (const k of Object.keys(values)) {
      const v = values[k];
      const path = [...prefix, k];
      if (isPlainObject(v)) markTouched(v, path);
      else inst._touched.add(pathKey(path));
    }
  }

  return inst;
}

/** 收集需要校验的字段 key（未指定 names 时取全部已注册字段） */
function collectKeys(inst: FormInstance<object>, names?: NamePath[]): string[] {
  const all = Array.from(inst._fieldRules.keys());
  if (!names || names.length === 0) return all;
  const prefixes = names.map((n) => pathKey(toPath(n)));
  return all.filter((key) => prefixes.some((p) => key === p || key.startsWith(`${p}${PATH_SEP}`)));
}

/* ================================ useForm / useWatch ================================ */

export function useForm<Values extends object = FormValues>(
  form?: FormInstance<Values>,
): [FormInstance<Values>] {
  const ref = useRef<FormInstance<Values> | null>(null);
  if (ref.current === null) ref.current = form ?? createFormInstance<Values>();
  return [ref.current];
}

/** 订阅表单字段值（对应 antd `Form.useWatch`，快照引用稳定，可用于 useSyncExternalStore） */
export function useWatch<Values extends object, const Path extends NamePath>(
  name: Path,
  form: FormInstance<Values>,
): FormFieldValue<Values, Path> | undefined;
export function useWatch(name: NamePath, form?: FormInstance<object>): unknown;
export function useWatch(name: NamePath, form?: FormInstance<object>): unknown {
  const ctx = useContext(FormContext);
  const inst = form ?? ctx?.form;
  const path = useMemo(() => toPath(name), [name]);
  const key = pathKey(path);

  const subscribe = useCallback(
    (onStoreChange: () => void) => {
      if (!inst) return () => {};
      return inst._subscribe(onStoreChange);
    },
    [inst],
  );

  const getSnapshot = useCallback(() => {
    if (!inst) return undefined;
    return inst._value(keyToPath(key));
  }, [inst, key]);

  return useSyncExternalStore(subscribe, getSnapshot, getSnapshot);
}

/* ================================ Form 上下文 ================================ */

interface FormContextValue {
  form: FormInstance<object>;
  layout: 'horizontal' | 'vertical' | 'inline';
  disabled?: boolean;
  /** 每次 store 变更都会换新对象引用，驱动所有 Form.Item 重渲染 */
  version: number;
}

const FormContext = createContext<FormContextValue | null>(null);

export const useFormContext = () => useContext(FormContext);

/**
 * Form.List 的路径前缀上下文。
 *
 * antd 里 `Form.List` 内的 `Form.Item name={[index, 'xxx']}` 会被自动拼上列表路径
 * （`['artifacts', index, 'xxx']`）。这里用 Context 传递该前缀，保证嵌套字段写入正确位置。
 */
const FormListContext = createContext<(string | number)[]>([]);

export interface FormProps<Values extends object = FormValues> {
  form?: FormInstance<Values>;
  initialValues?: FormValuePatch<NoInfer<Values>>;
  layout?: 'horizontal' | 'vertical' | 'inline';
  onFinish?: (values: Values) => void;
  // The existing submit catch also forwards synchronous consumer errors unchanged.
  onFinishFailed?: (errorInfo: unknown) => void;
  onValuesChange?: (changed: FormValuePatch<Values>, all: Values) => void;
  disabled?: boolean;
  labelCol?: { span?: number };
  wrapperCol?: { span?: number };
  className?: string;
  style?: CSSProperties;
  children?: ReactNode;
  /** antd 兼容占位（当前实现忽略） */
  requiredMark?: boolean | 'optional';
  scrollToFirstError?: boolean;
  name?: string;
  size?: 'small' | 'middle' | 'large';
}

function FormRoot<Values extends object = FormValues>({
  form,
  initialValues,
  layout = 'vertical',
  onFinish,
  onFinishFailed,
  onValuesChange: _onValuesChange,
  disabled,
  labelCol,
  wrapperCol,
  className,
  style,
  children,
  name,
}: FormProps<Values>) {
  const [internal] = useForm<Values>();
  const inst = form ?? internal;
  const [, forceRender] = useReducer((x: number) => x + 1, 0);
  const automaticId = useId();

  // 首帧同步把 initialValues / 重渲染钩子写入实例（避免 useEffect 造成的空值闪烁）
  const inited = useRef(false);
  if (!inited.current) {
    inited.current = true;
    if (initialValues)
      inst._fieldInitials = deepMerge(inst._fieldInitials, initialValues) as FormValues;
  }
  inst._rerender = forceRender;
  inst._formId = name || `antd-form-${automaticId}`;

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();
    e.stopPropagation();
    try {
      const values = await inst.validateFields();
      onFinish?.(values);
    } catch (errorInfo: unknown) {
      onFinishFailed?.(errorInfo);
    }
  };

  const ctxValue: FormContextValue = { form: inst, layout, disabled, version: 0 };

  return (
    <FormContext.Provider value={ctxValue}>
      <form
        data-form-id={inst._formId ?? ''}
        className={cn('w-full', className)}
        style={style}
        onSubmit={handleSubmit}
        noValidate
        aria-label={name}
      >
        <FormLayoutContext.Provider value={{ layout, labelCol, wrapperCol }}>
          {children}
        </FormLayoutContext.Provider>
      </form>
    </FormContext.Provider>
  );
}

/* ================================ Form.Item ================================ */

interface FormLayoutContextValue {
  layout: 'horizontal' | 'vertical' | 'inline';
  labelCol?: { span?: number };
  wrapperCol?: { span?: number };
}

const FormLayoutContext = createContext<FormLayoutContextValue>({ layout: 'vertical' });

export interface FormItemProps<Values extends object = FormValues> {
  name?: NamePath;
  label?: ReactNode;
  /** 支持 antd 的函数式规则：`({ getFieldValue }) => ({ validator })` */
  rules?: Array<FormRule | ((utils: FormItemRenderUtils<Values>) => FormRule)>;
  required?: boolean;
  initialValue?: unknown;
  /** 默认 'value'；Switch 场景传 'checked' */
  valuePropName?: string;
  trigger?: string;
  getValueFromEvent?(...args: unknown[]): unknown;
  getValueProps?(value: unknown): Record<string, unknown>;
  /** 只渲染控件，不渲染 label / 错误信息 */
  noStyle?: boolean;
  /** 传入后按 render-prop 渲染，children 收到表单操作对象 */
  shouldUpdate?: boolean | ((prev: Values, next: Values) => boolean);
  dependencies?: NamePath[];
  tooltip?: ReactNode;
  extra?: ReactNode;
  help?: ReactNode;
  validateStatus?: 'success' | 'warning' | 'error' | 'validating';
  hidden?: boolean;
  className?: string;
  style?: CSSProperties;
  children?: ReactNode | ((utils: FormItemRenderUtils<Values>) => ReactNode);
}

export interface FormItemRenderUtils<Values extends object = FormValues> {
  getFieldValue<const Path extends NamePath>(name: Path): FormFieldValue<Values, Path> | undefined;
  getFieldsValue: () => Values | undefined;
  setFieldValue: (name: NamePath, value: unknown) => void;
  setFieldsValue: (values: FormValuePatch<Values>) => void;
  resetFields: (names?: NamePath[]) => void;
  validateFields: (names?: NamePath[]) => Promise<Values | FormValues>;
  isFieldTouched: (name: NamePath) => boolean;
}

/** 标记：用于识别「子元素本身是 Form.Item」的嵌套写法，此时不做值注入（与 antd 一致） */
const FORM_ITEM_FLAG = '__antdCompatFormItem';

/**
 * antd 默认的事件取值策略：控件直接抛 DOM 事件时，取 `event.target[valuePropName]`。
 * 例如 `<Input />` 的 onChange 传的是 ChangeEvent，字段值应取 `e.target.value`；
 * 而 `<Switch />` 直接抛布尔值，则原样返回。
 */
function defaultGetValueFromEvent(valuePropName: string, args: unknown[]): unknown {
  // Optional event targets describe only the properties this compatibility boundary reads.
  // The assertion is erased; direct scalar values still fall through unchanged.
  const event = args[0] as
    | { target?: Record<string, unknown>; currentTarget?: Record<string, unknown> }
    | null
    | undefined;
  if (event && event.target && valuePropName in event.target) return event.target[valuePropName];
  if (event && event.currentTarget && valuePropName in event.currentTarget) {
    return event.currentTarget[valuePropName];
  }
  return event;
}

function FormItem<Values extends object = FormValues>({
  name,
  label,
  rules,
  required,
  initialValue,
  valuePropName = 'value',
  trigger = 'onChange',
  getValueFromEvent,
  getValueProps,
  noStyle,
  shouldUpdate: _shouldUpdate,
  tooltip,
  extra,
  help,
  validateStatus,
  hidden,
  className,
  style,
  children,
}: FormItemProps<Values>) {
  const ctx = useFormContext();
  const layoutCtx = useContext(FormLayoutContext);
  const listPrefix = useContext(FormListContext);
  const inst = ctx?.form;
  const path = useMemo(() => [...listPrefix, ...toPath(name)], [listPrefix, name]);
  const key = path.length ? pathKey(path) : '';
  const fieldId =
    inst && key ? `${inst._formId ?? 'antd-form'}_${encodeURIComponent(key)}` : undefined;

  const isRenderProp = typeof children === 'function';
  const registration = useRef<{ initialValue: unknown; rules?: FormRule[] }>({ initialValue });

  useEffect(() => {
    if (!inst || !key) return;
    const fieldPath = keyToPath(key);
    // Re-register on StrictMode's effect replay as well as ordinary remounts.
    inst._registerField(fieldPath, registration.current.initialValue, registration.current.rules);
    return () => inst._unregisterField(fieldPath);
  }, [inst, key]);

  // 注册字段（initialValue + rules），在渲染期同步完成，保证首帧即取到初值
  if (inst && key) {
    // 函数式规则在此展开为普通规则对象（validator 内部仍读取 form 实时值）
    const utils: FormItemRenderUtils<Values> = {
      getFieldValue: (n) => inst._value(toPath(n)) as FormFieldValue<Values, typeof n> | undefined,
      getFieldsValue: () => inst._values() as Values,
      setFieldValue: (n, v) => inst.setFieldValue(n, v),
      setFieldsValue: (v) => inst.setFieldsValue(v),
      resetFields: (n) => inst.resetFields(n),
      validateFields: (n) => inst.validateFields(n) as Promise<Values>,
      isFieldTouched: (n) => inst.isFieldTouched(n),
    };
    const expanded: FormRule[] = (rules ?? []).map((r) => (typeof r === 'function' ? r(utils) : r));
    const effRules =
      required !== undefined && required !== false
        ? [{ required: true }, ...expanded]
        : expanded.length
          ? expanded
          : undefined;
    inst._registerField(path, initialValue, effRules);
    registration.current = { initialValue, rules: effRules };
  }

  /* ---------- render-prop 模式（shouldUpdate / 函数 children） ---------- */
  if (isRenderProp) {
    const renderUtils: FormItemRenderUtils<Values> = {
      getFieldValue: (n) => inst?._value(toPath(n)) as FormFieldValue<Values, typeof n> | undefined,
      getFieldsValue: () => inst?._values() as Values | undefined,
      setFieldValue: (n, v) => inst?.setFieldValue(n, v),
      setFieldsValue: (v) => inst?.setFieldsValue(v),
      resetFields: (n) => inst?.resetFields(n),
      validateFields: (n) =>
        (inst?.validateFields(n) as Promise<Values> | undefined) ?? Promise.resolve({}),
      isFieldTouched: (n) => inst?.isFieldTouched(n) ?? false,
    };
    const rendered = (children as (u: FormItemRenderUtils<Values>) => ReactNode)(renderUtils);
    if (noStyle) return <>{rendered}</>;
    return (
      <FormItemFrame
        {...{ label, tooltip, extra, help, validateStatus, className, style, layoutCtx, fieldId }}
      >
        {rendered}
      </FormItemFrame>
    );
  }

  /* ---------- 无 name：纯布局包装（支持嵌套 Form.Item） ---------- */
  if (!key || !inst) {
    if (noStyle) return <>{children as ReactNode}</>;
    return (
      <FormItemFrame
        {...{ label, tooltip, extra, help, validateStatus, className, style, layoutCtx, fieldId }}
      >
        {children as ReactNode}
      </FormItemFrame>
    );
  }

  /* ---------- 普通字段：注入 value / onChange ---------- */
  const value = inst._value(path);
  const error = getAtPath(inst._errors, [key]) as string | undefined;
  const status = validateStatus ?? (error ? 'error' : undefined);
  const displayedHelp = help !== undefined ? help : error;

  const valueProps = getValueProps ? getValueProps(value) : undefined;

  const injected: Record<string, unknown> = { ...(valueProps ?? {}) };
  if (!valueProps || !(valuePropName in valueProps)) injected[valuePropName] = value;
  if (fieldId) injected.id = fieldId;
  if (status === 'error') injected['aria-invalid'] = true;
  if (required || inst._fieldRules.get(key)?.some((rule) => rule.required)) {
    injected['aria-required'] = true;
  }
  const childDescription = isValidElement(children)
    ? (children.props as Record<string, unknown>)['aria-describedby']
    : undefined;
  const descriptionIds = [typeof childDescription === 'string' ? childDescription : undefined];
  if (fieldId && (noStyle ? error : displayedHelp)) descriptionIds.push(`${fieldId}-help`);
  if (fieldId && !noStyle && extra) descriptionIds.push(`${fieldId}-extra`);
  const descriptions = descriptionIds.filter((id): id is string => Boolean(id));
  if (descriptions.length) injected['aria-describedby'] = descriptions.join(' ');
  // 子组件自己声明的 onChange 不会被 cloneElement 覆盖掉：与 antd 一致，
  // 注入的 handler 只负责把事件写进表单，写完后继续执行子组件原本的 onChange。
  const childTriggerHandler = isValidElement(children)
    ? (children.props as Record<string, unknown> | null)?.[trigger]
    : undefined;
  injected[trigger] = (...args: unknown[]) => {
    const next = getValueFromEvent
      ? getValueFromEvent(...args)
      : defaultGetValueFromEvent(valuePropName, args);
    const hadError = Boolean(getAtPath(inst._errors, [key]));
    inst.setFieldValue(path, next);
    if (hadError) {
      void inst.validateFields([path]).catch(() => {
        // 校验失败已写入字段错误；事件触发的校验不再产生未处理的 Promise rejection。
      });
    }
    if (typeof childTriggerHandler === 'function') childTriggerHandler(...args);
  };

  const isFormItemChild =
    isValidElement(children) &&
    (children.type as { [FORM_ITEM_FLAG]?: boolean })?.[FORM_ITEM_FLAG] === true;

  const control = isFormItemChild
    ? (children as ReactNode)
    : isValidElement(children)
      ? cloneElement(children as ReactElement<Record<string, unknown>>, injected)
      : children;

  if (noStyle) {
    return (
      <>
        {control}
        {error ? (
          <p
            id={fieldId ? `${fieldId}-help` : undefined}
            role="alert"
            className="text-destructive mt-1 text-xs"
          >
            {error}
          </p>
        ) : null}
      </>
    );
  }

  return (
    <FormItemFrame
      {...{
        label,
        tooltip,
        extra,
        help: displayedHelp,
        validateStatus: status,
        className,
        style,
        layoutCtx,
        fieldId,
      }}
      required={
        required !== undefined
          ? required
          : rules?.some((r) => typeof r !== 'function' && r.required)
      }
      hidden={hidden}
    >
      {control}
    </FormItemFrame>
  );
}

(FormItem as typeof FormItem & { [FORM_ITEM_FLAG]: boolean })[FORM_ITEM_FLAG] = true;

/* ---------- Form.Item 外壳（label / 必填星号 / 提示 / 错误） ---------- */

interface FrameProps {
  label?: ReactNode;
  required?: boolean;
  tooltip?: ReactNode;
  extra?: ReactNode;
  help?: ReactNode;
  validateStatus?: string;
  hidden?: boolean;
  className?: string;
  style?: CSSProperties;
  fieldId?: string;
  layoutCtx: FormLayoutContextValue;
  children?: ReactNode;
}

function FormItemFrame({
  label,
  required,
  tooltip,
  extra,
  help,
  validateStatus,
  hidden,
  className,
  style,
  fieldId,
  layoutCtx,
  children,
}: FrameProps) {
  if (hidden) return null;

  const isError = validateStatus === 'error';
  const labelNode = label ? (
    <label
      className={cn(
        'text-foreground flex items-center gap-1 text-[13px] leading-none font-medium',
        layoutCtx.layout === 'vertical' ? 'mb-1.5' : 'pt-2',
      )}
      htmlFor={fieldId}
    >
      {required ? <span className="text-destructive">*</span> : null}
      <span>{label}</span>
      {tooltip ? (
        <span
          className="text-muted-foreground cursor-help"
          title={typeof tooltip === 'string' ? tooltip : undefined}
        >
          <svg
            viewBox="0 0 24 24"
            className="size-3.5"
            fill="none"
            stroke="currentColor"
            strokeWidth="2"
          >
            <circle cx="12" cy="12" r="10" />
            <path d="M12 16v-4M12 8h.01" />
          </svg>
        </span>
      ) : null}
    </label>
  ) : null;

  const body = (
    <>
      {children}
      {help ? (
        <p
          id={fieldId ? `${fieldId}-help` : undefined}
          role={isError ? 'alert' : undefined}
          className={cn('mt-1 text-xs', isError ? 'text-destructive' : 'text-muted-foreground')}
        >
          {help}
        </p>
      ) : null}
      {extra ? (
        <p
          id={fieldId ? `${fieldId}-extra` : undefined}
          className="text-muted-foreground mt-1 text-xs"
        >
          {extra}
        </p>
      ) : null}
    </>
  );

  if (layoutCtx.layout === 'horizontal' && label) {
    return (
      <div className={cn('flex items-start gap-3', className)} style={style}>
        <div className="w-24 shrink-0 text-right">{labelNode}</div>
        <div className="min-w-0 flex-1">{body}</div>
      </div>
    );
  }

  if (layoutCtx.layout === 'inline') {
    return (
      <div className={cn('flex items-center gap-2', className)} style={style}>
        {labelNode}
        <div className="min-w-0">{body}</div>
      </div>
    );
  }

  return (
    <div className={cn('mb-3', className)} style={style}>
      {labelNode}
      {body}
    </div>
  );
}

/* ================================ Form.List ================================ */

export interface FormListField {
  /** 在数组中的下标 */
  name: number;
  /** React key（与 name 一致，用于稳定渲染） */
  key: number;
  fieldKey: number;
}

export interface FormListOperation {
  add: (defaultValue?: unknown, insertIndex?: number) => void;
  remove: (index: number) => void;
  move: (from: number, to: number) => void;
  insert: (index: number, value: unknown) => void;
}

export interface FormListProps {
  name: NamePath;
  initialValue?: unknown[];
  children: (
    fields: FormListField[],
    operation: FormListOperation,
    meta: { errors: ReactNode[] },
  ) => ReactNode;
}

function FormList({ name, initialValue, children }: FormListProps) {
  const ctx = useFormContext();
  const listPrefix = useContext(FormListContext);
  const inst = ctx?.form;
  const path = useMemo(() => [...listPrefix, ...toPath(name)], [listPrefix, name]);

  // 数组初值（Form.List 的 initialValue 语义）
  if (inst) {
    const arr = inst._value(path);
    if (arr === undefined && initialValue !== undefined && inst._fieldInitials) {
      inst._fieldInitials = setAtPath(inst._fieldInitials, path, initialValue) as FormValues;
    }
  }

  const list = ((inst ? inst._value(path) : undefined) ?? []) as unknown[];
  const fields: FormListField[] = list.map((_, i) => ({ name: i, key: i, fieldKey: i }));

  const operation: FormListOperation = {
    add: (defaultValue?: unknown, insertIndex?: number) => {
      if (!inst) return;
      const current: unknown[] = (getAtPath(inst._store, path) ??
        getAtPath(inst._fieldInitials, path) ??
        []) as unknown[];
      const next = [...current];
      const idx =
        insertIndex === undefined ? next.length : Math.max(0, Math.min(insertIndex, next.length));
      next.splice(idx, 0, defaultValue);
      inst.setFieldValue(path, next);
    },
    remove: (index: number) => {
      if (!inst) return;
      const current: unknown[] = (getAtPath(inst._store, path) ??
        getAtPath(inst._fieldInitials, path) ??
        []) as unknown[];
      inst.setFieldValue(
        path,
        current.filter((_, i) => i !== index),
      );
    },
    move: (from: number, to: number) => {
      if (!inst) return;
      const current: unknown[] = [
        ...((getAtPath(inst._store, path) ??
          getAtPath(inst._fieldInitials, path) ??
          []) as unknown[]),
      ];
      if (from < 0 || from >= current.length || to < 0 || to >= current.length) return;
      const [item] = current.splice(from, 1);
      current.splice(to, 0, item);
      inst.setFieldValue(path, current);
    },
    insert: (index: number, value: unknown) => {
      if (!inst) return;
      const current: unknown[] = [
        ...((getAtPath(inst._store, path) ??
          getAtPath(inst._fieldInitials, path) ??
          []) as unknown[]),
      ];
      current.splice(index, 0, value);
      inst.setFieldValue(path, current);
    },
  };

  return (
    <FormListContext.Provider value={path}>
      {children(fields, operation, { errors: [] })}
    </FormListContext.Provider>
  );
}

(FormList as typeof FormList & { [FORM_ITEM_FLAG]: boolean })[FORM_ITEM_FLAG] = true;

/* ================================ 导出 ================================ */

export const Form = Object.assign(FormRoot, {
  Item: FormItem,
  List: FormList,
  useForm,
  useWatch,
  createFormInstance,
  /** antd 的 `Form.ErrorList` 占位（业务页未使用，仅防意外引用） */
  ErrorList: () => null,
});
