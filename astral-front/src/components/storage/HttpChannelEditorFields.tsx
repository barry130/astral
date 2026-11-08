'use client';

import {
  forwardRef,
  useCallback,
  useEffect,
  useImperativeHandle,
  useMemo,
  useRef,
  useState,
} from 'react';
import { AlertTriangle, CheckCircle2, Plus, X } from 'lucide-react';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { DictOption, fetchDictOptions } from '@/api/dict';
import {
  HttpApprovedTarget,
  HttpAuthSpec,
  HttpChannelValidation,
  HttpChannelView,
  HttpConditionSpec,
  HttpResponseSpec,
  HttpSecretChange,
  HttpUploadProfile,
  storageApi,
} from '@/api/storage';
import { cn } from '@/lib/utils';

/** 后端 HttpUploadProfile.HARD_MAX_BYTES：单文件硬上限 5 MiB */
const HARD_MAX_BYTES = 5242880;
const MIN_ATTEMPT_MS = 1000;
const MAX_ATTEMPT_MS = 60000;
const MAX_CONDITIONS = 8;
const DEFAULT_ATTEMPT_MS = 30000;
const NAME_LIMIT = 64;
const REMARK_LIMIT = 512;
/** 凭据名规则与后端 HttpSecretEnvelope.SECRET_NAME 一致 */
const SECRET_NAME = /^[A-Za-z][A-Za-z0-9_]{0,31}$/;
const SECRET_REF = /\$\{secret:([A-Za-z][A-Za-z0-9_]{0,31})\}/g;
const MIME = /^[a-z0-9][a-z0-9.+-]*\/[a-z0-9][a-z0-9.+-]*$/i;
/** 后端 HttpProfileValidator.SUPPORTED_MIMES 当前只有 PNG/JPEG（内容嗅探也只认这两种魔数） */
const SUPPORTED_MIMES = ['image/png', 'image/jpeg'];
/** 本期后端不接受匿名渠道（NONE 会返回 STORAGE034）；BASIC 已实现且有字典行（V20261106004） */
const ALLOWED_AUTH_MODES = ['HEADER', 'QUERY', 'FORM', 'BASIC'];

const selectClass =
  'h-9 w-full min-w-0 rounded-md border border-input bg-background px-2 text-sm text-foreground outline-none focus-visible:ring-2 focus-visible:ring-ring disabled:opacity-50';

type ParamRow = { key: string; value: string };
type ConditionRow = { pointer: string; op: string; type: string; value: string };
type SecretOp = 'KEEP' | 'SET' | 'CLEAR';

interface ChannelFormState {
  name: string;
  endpoint: string;
  remark: string;
  enabled: boolean;
  authMode: string;
  authName: string;
  authValueTemplate: string;
  usernameSecret: string;
  passwordSecret: string;
  fileField: string;
  fileNameTemplate: string;
  fields: ParamRow[];
  headers: ParamRow[];
  query: ParamRow[];
  maxBytes: number;
  allowedMimes: string;
  attemptMs: number;
  urlPointer: string;
  conditions: ConditionRow[];
}

interface SecretRowState {
  name: string;
  configured: boolean;
  updatedAt?: string;
}

function errorText(error: unknown) {
  return error instanceof Error ? error.message : '操作失败，请稍后重试';
}

/** 业务失败码：client.ts 把 errorCode 挂在 Error 实例上 */
function errorCodeOf(error: unknown) {
  if (error instanceof Error && 'errorCode' in error) {
    const code = (error as Error & { errorCode?: unknown }).errorCode;
    return typeof code === 'string' ? code : undefined;
  }
  return undefined;
}

/** 渠道相关错误码 → 可执行的中文提示；未登记的回退后端 msg */
function channelErrorHint(code?: string) {
  switch (code) {
    case 'STORAGE038':
      return '配置已被他人修改（版本冲突），请刷新后重试';
    case 'STORAGE040':
      return '渠道画像非法，请检查认证、参数与响应解析配置';
    case 'STORAGE034':
      return '渠道凭据信封损坏或认证方式非法（匿名渠道会被拒绝），请重新设置凭据';
    case 'STORAGE035':
      return '上传地址未精确匹配部署批准目标（路径也要一致；批准列表为空时同样返回该码），不会发出任何出站请求';
    case 'STORAGE036':
      return '缺少渠道必需的凭据，请先在「密钥」区设置';
    case 'STORAGE037':
      return '凭据与当前画像不匹配（多余、未引用或上传地址绑定已变化），请按下方详情调整';
    case 'STORAGE039':
      return '当前部署未启用 HTTP 渠道（custom-http.enabled=false），不会发出任何出站请求';
    case 'STORAGE002':
      return '渠道不存在或已被删除，请刷新列表';
    default:
      return undefined;
  }
}

function timeText(value?: string) {
  if (!value) return '—';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN', { hour12: false });
}

function emptyForm(): ChannelFormState {
  return {
    name: '',
    endpoint: '',
    remark: '',
    enabled: true,
    authMode: 'HEADER',
    authName: '',
    authValueTemplate: '',
    usernameSecret: '',
    passwordSecret: '',
    fileField: 'file',
    fileNameTemplate: '${publicId}.${extension}',
    fields: [],
    headers: [],
    query: [],
    maxBytes: HARD_MAX_BYTES,
    allowedMimes: 'image/png, image/jpeg',
    attemptMs: DEFAULT_ATTEMPT_MS,
    urlPointer: '/url',
    conditions: [{ pointer: '/success', op: 'EQ', type: 'BOOLEAN', value: 'true' }],
  };
}

function rowValue(rows: ParamRow[]): Record<string, string> | undefined {
  const entries: [string, string][] = [];
  const seen = new Set<string>();
  for (const row of rows) {
    const key = row.key.trim();
    if (!key) continue;
    if (seen.has(key)) throw new Error(`参数名重复：${key}`);
    seen.add(key);
    entries.push([key, row.value]);
  }
  return entries.length ? Object.fromEntries(entries) : undefined;
}

function parseMimes(input: string): string[] {
  const tokens = input
    .split(/[\s,;]+/)
    .map((item) => item.trim().toLowerCase())
    .filter(Boolean);
  if (!tokens.length) throw new Error('至少填写一个允许的 MIME 类型');
  if (tokens.length > 16) throw new Error('允许的 MIME 类型最多 16 个');
  for (const token of tokens) {
    if (!MIME.test(token)) throw new Error(`MIME 类型格式非法：${token}`);
    if (!SUPPORTED_MIMES.includes(token))
      throw new Error(
        `当前后端仅支持 ${SUPPORTED_MIMES.join('、')}；GIF/WebP/AVIF 会被服务端内容校验拒绝`,
      );
  }
  return Array.from(new Set(tokens));
}

function buildAuth(form: ChannelFormState): HttpAuthSpec {
  const mode = form.authMode || '';
  if (!mode || mode === 'NONE')
    throw new Error(
      '后端本期不接受匿名渠道（会返回 STORAGE034），请选择 HEADER / QUERY / FORM / BASIC',
    );
  if (mode === 'BASIC') {
    if (!SECRET_NAME.test(form.usernameSecret.trim()))
      throw new Error('用户名凭据名非法：需以字母开头，仅含字母数字下划线，最长 32');
    if (!SECRET_NAME.test(form.passwordSecret.trim()))
      throw new Error('密码凭据名非法：需以字母开头，仅含字母数字下划线，最长 32');
    return {
      mode: 'BASIC',
      usernameSecret: form.usernameSecret.trim(),
      passwordSecret: form.passwordSecret.trim(),
    };
  }
  if (!form.authName.trim()) throw new Error('认证方式需要填写注入名');
  if (!form.authValueTemplate.trim()) throw new Error('认证方式需要填写值模板');
  return {
    mode,
    name: form.authName.trim(),
    valueTemplate: form.authValueTemplate.trim(),
  };
}

/**
 * 编辑器未提供输入的画像字段（缩略图/ID/尺寸/宽高指针与 preset 元数据）在编辑保存时原样回传，
 * 避免一次编辑把这些可选解析配置静默清空。
 */
function preservedUnmanaged(base?: HttpUploadProfile): {
  root: Partial<Pick<HttpUploadProfile, 'presetId' | 'presetVersion'>>;
  response: Partial<HttpResponseSpec>;
} {
  if (!base) return { root: {}, response: {} };
  const root: Partial<Pick<HttpUploadProfile, 'presetId' | 'presetVersion'>> = {};
  if (base.presetId) root.presetId = base.presetId;
  if (base.presetVersion != null) root.presetVersion = base.presetVersion;
  const response: Partial<HttpResponseSpec> = {};
  if (base.response.thumbPointer) response.thumbPointer = base.response.thumbPointer;
  if (base.response.idPointer) response.idPointer = base.response.idPointer;
  if (base.response.sizePointer) response.sizePointer = base.response.sizePointer;
  if (base.response.sizeUnit) response.sizeUnit = base.response.sizeUnit;
  if (base.response.widthPointer) response.widthPointer = base.response.widthPointer;
  if (base.response.heightPointer) response.heightPointer = base.response.heightPointer;
  if (base.response.numericStringPointers?.length)
    response.numericStringPointers = base.response.numericStringPointers;
  return { root, response };
}

/** 表单 → 上传画像；allowedOrigins 只能来自部署批准清单（界面不可编辑） */
function buildProfile(
  form: ChannelFormState,
  allowedOrigins: string[],
  base?: HttpUploadProfile,
): HttpUploadProfile {
  const endpoint = form.endpoint.trim();
  if (!/^https:\/\//i.test(endpoint))
    throw new Error('上传地址需以 https:// 开头（后端只批准 HTTPS 目标，http 会被拒绝）');
  if (!form.fileField.trim()) throw new Error('multipart 文件字段名不能为空');
  const fileNameTemplate = form.fileNameTemplate.trim();
  if (!fileNameTemplate) throw new Error('上传文件名模板不能为空');
  // 与后端 HttpProfileValidator 同口径：剥离两个合法占位符后只允许字母/数字/点/下划线/短横线
  const templateBody = fileNameTemplate
    .replace(/\$\{publicId\}/g, '')
    .replace(/\$\{extension\}/g, '');
  if (!/^[A-Za-z0-9._-]*$/.test(templateBody))
    throw new Error(
      '上传文件名模板只允许字母/数字/点/下划线/短横线与 ${publicId}、${extension} 占位符',
    );
  const maxBytes = Math.trunc(form.maxBytes);
  if (!Number.isFinite(maxBytes) || maxBytes < 1 || maxBytes > HARD_MAX_BYTES)
    throw new Error(`单文件上限需在 1–${HARD_MAX_BYTES} 字节之间`);
  const attemptMs = Math.trunc(form.attemptMs);
  if (!Number.isFinite(attemptMs) || attemptMs < MIN_ATTEMPT_MS || attemptMs > MAX_ATTEMPT_MS)
    throw new Error(`单次上传超时需在 ${MIN_ATTEMPT_MS}–${MAX_ATTEMPT_MS} 毫秒之间`);
  if (!form.urlPointer.trim()) throw new Error('响应 URL 指针不能为空');
  if (!form.conditions.length) throw new Error('至少需要一条成功条件');
  if (form.conditions.length > MAX_CONDITIONS) throw new Error(`成功条件最多 ${MAX_CONDITIONS} 条`);

  const success: HttpConditionSpec[] = form.conditions.map((item) => {
    const pointer = item.pointer.trim();
    if (!pointer) throw new Error('成功条件的指针不能为空');
    if (!/^\//.test(pointer)) throw new Error(`JSON 指针需以 / 开头：${pointer}`);
    const op = item.op || 'EQ';
    if (op === 'EXISTS') return { pointer, op };
    if (op !== 'EQ') throw new Error(`成功条件操作非法：${op}`);
    const type = item.type || 'STRING';
    if (!item.value.trim()) throw new Error(`相等条件需要填写期望值：${pointer}`);
    return { pointer, op, type, value: item.value.trim() };
  });

  const kept = preservedUnmanaged(base);
  return {
    schemaVersion: 1,
    ...kept.root,
    endpoint,
    method: 'POST',
    query: rowValue(form.query),
    headers: rowValue(form.headers),
    auth: buildAuth(form),
    body: {
      mode: 'MULTIPART',
      fileField: form.fileField.trim(),
      fileNameTemplate,
      fields: rowValue(form.fields),
    },
    response: {
      ...kept.response,
      success,
      urlPointer: form.urlPointer.trim(),
      allowedUrlOrigins: allowedOrigins,
    },
    limits: {
      maxBytes,
      allowedMimes: parseMimes(form.allowedMimes),
    },
    timeouts: { attemptMs },
  };
}

/** 表单级凭据引用扫描：不依赖画像完整性，避免某个无关字段非法时把已引用凭据行藏起来 */
function referencedSecretsFromForm(form: ChannelFormState): string[] {
  const names = new Set<string>();
  if (form.authMode === 'BASIC') {
    if (form.usernameSecret.trim()) names.add(form.usernameSecret.trim());
    if (form.passwordSecret.trim()) names.add(form.passwordSecret.trim());
  }
  const scan = (value?: string) => {
    if (!value) return;
    SECRET_REF.lastIndex = 0;
    let match: RegExpExecArray | null;
    while ((match = SECRET_REF.exec(value))) names.add(match[1]);
  };
  scan(form.authValueTemplate);
  for (const row of [...form.query, ...form.headers, ...form.fields]) scan(row.value);
  return Array.from(names).sort();
}

function formFromView(view: HttpChannelView): ChannelFormState {
  const profile = view.profile;
  return {
    name: view.name || '',
    endpoint: profile?.endpoint || '',
    remark: view.remark || '',
    enabled: view.status === 'ENABLED',
    authMode: profile?.auth?.mode || 'NONE',
    authName: profile?.auth?.name || '',
    authValueTemplate: profile?.auth?.valueTemplate || '',
    usernameSecret: profile?.auth?.usernameSecret || '',
    passwordSecret: profile?.auth?.passwordSecret || '',
    fileField: profile?.body?.fileField || 'file',
    fileNameTemplate: profile?.body?.fileNameTemplate || '${publicId}.${extension}',
    fields: Object.entries(profile?.body?.fields || {}).map(([key, value]) => ({
      key,
      value,
    })),
    headers: Object.entries(profile?.headers || {}).map(([key, value]) => ({ key, value })),
    query: Object.entries(profile?.query || {}).map(([key, value]) => ({ key, value })),
    maxBytes: profile?.limits?.maxBytes ?? HARD_MAX_BYTES,
    allowedMimes: (profile?.limits?.allowedMimes || []).join(', '),
    attemptMs: profile?.timeouts?.attemptMs ?? DEFAULT_ATTEMPT_MS,
    urlPointer: profile?.response?.urlPointer || '/url',
    conditions: (profile?.response?.success || []).map((item) => ({
      pointer: item.pointer,
      op: item.op || 'EQ',
      type: item.type || 'STRING',
      value: item.value || '',
    })),
  };
}

function ParamEditor({
  title,
  hint,
  rows,
  onChange,
  keyPlaceholder,
  valuePlaceholder,
  disabled,
}: {
  title: string;
  hint?: string;
  rows: ParamRow[];
  onChange: (rows: ParamRow[]) => void;
  keyPlaceholder: string;
  valuePlaceholder: string;
  disabled?: boolean;
}) {
  const patch = (index: number, next: Partial<ParamRow>) =>
    onChange(rows.map((row, i) => (i === index ? { ...row, ...next } : row)));
  return (
    <div className="space-y-2">
      <div className="flex items-center justify-between gap-2">
        <div>
          <p className="text-sm font-medium">{title}</p>
          {hint && <p className="text-muted-foreground text-xs">{hint}</p>}
        </div>
        <Button
          type="button"
          size="sm"
          variant="outline"
          disabled={disabled}
          onClick={() => onChange([...rows, { key: '', value: '' }])}
        >
          <Plus className="size-4" />
          添加
        </Button>
      </div>
      {!rows.length && <p className="text-muted-foreground text-xs">暂无参数</p>}
      {rows.map((row, index) => (
        <div key={index} className="flex items-start gap-2">
          <Input
            aria-label={`${title}参数名 ${index + 1}`}
            placeholder={keyPlaceholder}
            value={row.key}
            disabled={disabled}
            onChange={(event) => patch(index, { key: event.target.value })}
          />
          <Input
            aria-label={`${title}参数值 ${index + 1}`}
            placeholder={valuePlaceholder}
            value={row.value}
            disabled={disabled}
            onChange={(event) => patch(index, { value: event.target.value })}
          />
          <Button
            type="button"
            size="icon"
            variant="ghost"
            aria-label="删除参数"
            disabled={disabled}
            onClick={() => onChange(rows.filter((_, i) => i !== index))}
          >
            <X className="size-4" />
          </Button>
        </div>
      ))}
    </div>
  );
}

/** 提交句柄：由外层「存储配置」弹窗的确定/离线校验按钮调用 */
export interface HttpChannelFieldsHandle {
  /** 提交当前表单；成功返回 null，失败返回错误文案（组件内部已就地展示） */
  submit: (options: {
    name: string;
    mode: 'create' | 'edit';
    channelId?: number;
    expectedVersion?: number;
  }) => Promise<string | null>;
  /** 离线校验当前表单（结果就地渲染） */
  validateOffline: () => Promise<void>;
}

interface HttpChannelEditorFieldsProps {
  mode: 'create' | 'edit';
  /** 编辑模式：页面拉取到的渠道详情；未到达时展示加载态 */
  channel: HttpChannelView | null;
  /** 部署批准目标（页面在弹窗打开时拉取，界面只读） */
  approvedTargets: HttpApprovedTarget[];
  onBusyChange?: (busy: boolean) => void;
}

/**
 * CUSTOM_HTTP 渠道表单字段（无外壳）：嵌入「存储配置」弹窗就地展示。
 *
 * <p>安全边界：凭据只以 KEEP/SET/CLEAR 提交，明文只进 <c>secretValuesRef</c>（不渲染、不日志），
 * 保存后立即清空；响应里的 configuredSecrets 仅含 {name,configured,updatedAt}。
 * allowedUrlOrigins 来自部署批准目标，界面只读。</p>
 */
const HttpChannelEditorFields = forwardRef<HttpChannelFieldsHandle, HttpChannelEditorFieldsProps>(
  function HttpChannelEditorFields({ mode, channel, approvedTargets, onBusyChange }, ref) {
    const [form, setForm] = useState<ChannelFormState>(emptyForm());
    const [formError, setFormError] = useState('');
    const [saving, setSaving] = useState(false);
    const [secretRows, setSecretRows] = useState<SecretRowState[]>([]);
    const [secretOps, setSecretOps] = useState<Record<string, SecretOp>>({});
    /** 待提交的 SET 明文：刻意不进 React state，避免被渲染/序列化 */
    const secretValuesRef = useRef<Record<string, string>>({});
    /** 就地编辑的凭据（替代嵌套弹窗：宿主是 antd 弹窗，嵌套 Radix 弹窗有层级问题） */
    const [secretEditing, setSecretEditing] = useState<{ name: string; value: string } | null>(
      null,
    );
    const [validation, setValidation] = useState<HttpChannelValidation | null>(null);
    const [validating, setValidating] = useState(false);
    const [dict, setDict] = useState<Record<string, DictOption[]>>({});
    /** 同步锁：state 更新前的同一事件循环内重复点击也必须被拦住 */
    const submitLockRef = useRef(false);
    const validateLockRef = useRef(false);

    useEffect(() => {
      let cancelled = false;
      void fetchDictOptions(['storage_http_auth_mode'])
        .then((dicts) => {
          if (!cancelled) setDict(dicts);
        })
        .catch(() => {
          // 字典加载失败：authModeOptions 回退原始值展示
        });
      return () => {
        cancelled = true;
      };
    }, []);

    // 弹窗 destroyOnClose：挂载即打开；编辑模式等详情到达后填充
    useEffect(() => {
      if (mode === 'edit') {
        if (!channel) return;
        setForm(formFromView(channel));
        resetSecretState(channel);
      } else {
        setForm(emptyForm());
        resetSecretState();
      }
      setFormError('');
      setValidation(null);
    }, [mode, channel]);

    const busy = saving || validating;
    useEffect(() => {
      onBusyChange?.(busy);
    }, [busy, onBusyChange]);

    const matchedApprovedTarget = useMemo(() => {
      const endpoint = form.endpoint.trim();
      return approvedTargets.find((target) => `${target.origin}${target.path || ''}` === endpoint);
    }, [form.endpoint, approvedTargets]);

    const planOrigins = useMemo(() => {
      // 只使用当前 endpoint 精确对应的部署批准目标：已保存画像的 allowedUrlOrigins 可能已过期，
      // 只有部署批准清单本身为空（功能未启用）时才回退到画像里的历史值。
      // 未匹配时不能回退到「全部批准来源」，否则会把错误来源当成合法配置提交。
      if (matchedApprovedTarget) return matchedApprovedTarget.urlOrigins ?? [];
      if (approvedTargets.length === 0 && mode === 'edit') {
        return channel?.profile?.response?.allowedUrlOrigins ?? [];
      }
      return [];
    }, [matchedApprovedTarget, approvedTargets.length, mode, channel]);

    /** 后端本期拒绝匿名渠道（NONE → STORAGE034），只暴露三种注入方式 */
    const authModeOptions = useMemo<DictOption[]>(() => {
      const fromDict = (dict.storage_http_auth_mode || []).filter((option) =>
        ALLOWED_AUTH_MODES.includes(option.value),
      );
      if (fromDict.length) return fromDict;
      // 字典不可用时回退展示原始值（不硬编码业务文案）
      return ALLOWED_AUTH_MODES.map((value) => ({ value, label: value }));
    }, [dict.storage_http_auth_mode]);

    const patchForm = (patch: Partial<ChannelFormState>) =>
      setForm((previous) => ({ ...previous, ...patch }));

    const resetSecretState = (view?: HttpChannelView) => {
      secretValuesRef.current = {};
      setSecretOps({});
      setSecretRows(
        (view?.configuredSecrets || []).map((item) => ({
          name: item.name,
          configured: item.configured,
          updatedAt: item.updatedAt,
        })),
      );
    };

    const referencedNames = useMemo(() => referencedSecretsFromForm(form), [form]);

    /** 密钥清单 = 画像引用名 ∪ 已配置名（已配置但不再引用的也要能清除） */
    const secretList = useMemo(() => {
      const map = new Map<string, SecretRowState>();
      for (const row of secretRows) map.set(row.name, row);
      for (const name of referencedNames) {
        if (!map.has(name)) map.set(name, { name, configured: false });
      }
      return Array.from(map.values()).sort((a, b) => a.name.localeCompare(b.name));
    }, [secretRows, referencedNames]);

    const secretLabel = (row: SecretRowState) => {
      const op = secretOps[row.name] || 'KEEP';
      const referenced = referencedNames.includes(row.name);
      if (op === 'SET') return '待设置（保存后生效）';
      if (op === 'CLEAR') return row.configured ? '已标记清除' : '未配置';
      if (row.configured && !referenced) return '已配置但未被画像引用（保存时自动清除）';
      return row.configured ? `已配置 · ${timeText(row.updatedAt)}` : '未配置';
    };

    /** 后端 HttpSecretEnvelope 的绑定 = channelId + endpoint origin + path；地址变化后旧凭据不可 KEEP */
    const secretBindingChanged = useMemo(() => {
      const baseEndpoint = mode === 'edit' ? channel?.profile?.endpoint : undefined;
      if (!baseEndpoint) return false;
      try {
        const before = new URL(baseEndpoint);
        const after = new URL(form.endpoint.trim());
        return before.origin !== after.origin || before.pathname !== after.pathname;
      } catch {
        return false;
      }
    }, [mode, channel, form.endpoint]);

    const buildSecretChanges = useCallback((): Record<string, HttpSecretChange> => {
      const changes: Record<string, HttpSecretChange> = {};
      const referenced = new Set(referencedNames);
      const names = new Set<string>([
        ...secretList.map((row) => row.name),
        ...Object.keys(secretOps),
      ]);
      for (const name of names) {
        const op = secretOps[name] || 'KEEP';
        const configured = secretList.find((row) => row.name === name)?.configured === true;
        if (op === 'SET') {
          const value = secretValuesRef.current[name];
          if (!referenced.has(name))
            throw new Error(`凭据 ${name} 未被当前画像引用，不能设置（后端会返回 STORAGE037）`);
          if (!value) throw new Error(`凭据 ${name} 已标记设置但内容为空，请重新输入`);
          changes[name] = { op: 'SET', value };
        } else if (op === 'CLEAR') {
          // 从未配置过的凭据不需要发 CLEAR（后端对不存在的 key 虽容忍，但无意义）
          if (configured) changes[name] = { op: 'CLEAR' };
        } else if (configured) {
          if (!referenced.has(name)) {
            // 画像已不再引用：保留会在后端触发「凭据未被画像引用」STORAGE037，自动清除
            changes[name] = { op: 'CLEAR' };
            continue;
          }
          if (secretBindingChanged) {
            throw new Error(
              `上传地址已变化，已配置凭据 ${name} 不能沿用；请重新输入该凭据或标记清除后再保存`,
            );
          }
          changes[name] = { op: 'KEEP' };
        }
      }
      return changes;
    }, [secretList, secretOps, referencedNames, secretBindingChanged]);

    const submit = useCallback(
      async (options: {
        name: string;
        mode: 'create' | 'edit';
        channelId?: number;
        expectedVersion?: number;
      }): Promise<string | null> => {
        if (submitLockRef.current || saving || validating) return '正在处理中，请稍候';
        submitLockRef.current = true;
        setFormError('');
        setSaving(true);
        try {
          const name = (options.name || '').trim();
          if (!name) throw new Error('渠道名称不能为空');
          if (name.length > NAME_LIMIT) throw new Error(`渠道名称最长 ${NAME_LIMIT} 个字符`);
          if (form.remark.length > REMARK_LIMIT) throw new Error(`备注最长 ${REMARK_LIMIT} 个字符`);
          const profile = buildProfile(
            form,
            planOrigins,
            options.mode === 'edit' ? channel?.profile : undefined,
          );
          const secretChanges = buildSecretChanges();
          const base = {
            name,
            profile,
            secretChanges,
            status: form.enabled ? 'ENABLED' : 'DISABLED',
            remark: form.remark.trim() || undefined,
          };
          const result =
            options.mode === 'create'
              ? await storageApi.createHttpChannel(base)
              : await storageApi.updateHttpChannel(options.channelId!, {
                  ...base,
                  expectedVersion: options.expectedVersion!,
                });
          if (result.code !== 200) throw new Error(result.msg);
          // 提交成功后立刻丢弃明文，避免残留在内存
          secretValuesRef.current = {};
          setSecretOps({});
          setValidation(null);
          return null;
        } catch (error) {
          const code = errorCodeOf(error);
          const hint = channelErrorHint(code);
          const text = hint ? `${hint}（${errorText(error) || '操作失败'}）` : errorText(error);
          setFormError(text);
          return text;
        } finally {
          submitLockRef.current = false;
          setSaving(false);
        }
      },
      [saving, validating, form, planOrigins, buildSecretChanges, channel],
    );

    const runValidate = useCallback(async () => {
      if (validateLockRef.current || validating || saving) return;
      validateLockRef.current = true;
      setFormError('');
      setValidating(true);
      try {
        const profile = buildProfile(
          form,
          planOrigins,
          mode === 'edit' ? channel?.profile : undefined,
        );
        const result = await storageApi.validateHttpChannel({
          profile,
          secretChanges: buildSecretChanges(),
          status: form.enabled ? 'ENABLED' : 'DISABLED',
        });
        if (result.code !== 200) throw new Error(result.msg);
        setValidation(result.data);
      } catch (error) {
        const code = errorCodeOf(error);
        const hint = channelErrorHint(code);
        setFormError(hint ? `${hint}（${errorText(error)}）` : errorText(error));
        setValidation(null);
      } finally {
        validateLockRef.current = false;
        setValidating(false);
      }
    }, [validating, saving, form, planOrigins, buildSecretChanges, mode, channel]);

    useImperativeHandle(ref, () => ({ submit, validateOffline: runValidate }), [
      submit,
      runValidate,
    ]);

    return (
      <div className="space-y-5">
        <p className="text-muted-foreground text-xs">
          渠道以 POST + multipart 上传单个文件，单文件硬上限 5 MiB（仅
          JPEG/PNG）；服务端凭据只进加密信封，接口不回显明文。保存前可先离线校验（不联网）。
        </p>

        <section className="space-y-3">
          <p className="text-sm font-medium">基本信息</p>
          <label className="block space-y-1 text-sm sm:w-1/2">
            <span>状态</span>
            <select
              className={selectClass}
              value={form.enabled ? 'ENABLED' : 'DISABLED'}
              disabled={saving}
              onChange={(event) => patchForm({ enabled: event.target.value === 'ENABLED' })}
            >
              <option value="ENABLED">启用</option>
              <option value="DISABLED">停用</option>
            </select>
          </label>
          <label className="block space-y-1 text-sm">
            <span>上传地址（endpoint）</span>
            <Input
              placeholder="https://img.example.com/upload"
              value={form.endpoint}
              disabled={saving}
              onChange={(event) => patchForm({ endpoint: event.target.value })}
            />
          </label>
          <div className="grid gap-3 sm:grid-cols-2">
            <label className="space-y-1 text-sm">
              <span>请求方法</span>
              <Input value="POST（固定）" disabled readOnly />
            </label>
            <label className="space-y-1 text-sm">
              <span>multipart 文件字段名</span>
              <Input
                value={form.fileField}
                disabled={saving}
                onChange={(event) => patchForm({ fileField: event.target.value })}
              />
            </label>
          </div>
          <label className="block space-y-1 text-sm">
            <span>上传文件名模板</span>
            <Input
              placeholder="${publicId}.${extension}"
              value={form.fileNameTemplate}
              disabled={saving}
              onChange={(event) => patchForm({ fileNameTemplate: event.target.value })}
            />
            <span className="text-muted-foreground block text-xs">
              上传到上游时的文件名，只允许 {'${publicId}'} 与 {'${extension}'} 两个占位符。
            </span>
          </label>
          <label className="block space-y-1 text-sm">
            <span>备注</span>
            <Textarea
              rows={2}
              maxLength={REMARK_LIMIT}
              value={form.remark}
              disabled={saving}
              onChange={(event) => patchForm({ remark: event.target.value })}
            />
          </label>
        </section>

        <section className="space-y-3">
          <p className="text-sm font-medium">认证</p>
          <div className="grid gap-3 sm:grid-cols-2">
            <label className="space-y-1 text-sm">
              <span>认证方式</span>
              <select
                className={selectClass}
                value={form.authMode}
                disabled={saving}
                onChange={(event) => patchForm({ authMode: event.target.value })}
              >
                {authModeOptions.map((option) => (
                  <option key={option.value} value={option.value}>
                    {option.label}
                  </option>
                ))}
              </select>
            </label>
            {form.authMode !== 'NONE' && form.authMode !== 'BASIC' && (
              <>
                <label className="space-y-1 text-sm">
                  <span>注入名</span>
                  <Input
                    placeholder={form.authMode === 'HEADER' ? 'Authorization' : 'token'}
                    value={form.authName}
                    disabled={saving}
                    onChange={(event) => patchForm({ authName: event.target.value })}
                  />
                </label>
                <label className="space-y-1 text-sm sm:col-span-2">
                  <span>值模板</span>
                  <Input
                    placeholder="Bearer ${secret:imgToken}"
                    value={form.authValueTemplate}
                    disabled={saving}
                    onChange={(event) => patchForm({ authValueTemplate: event.target.value })}
                  />
                  <span className="text-muted-foreground text-xs">
                    用 {'${secret:凭据名}'} 引用密钥区的凭据，明文不会出现在配置里。
                  </span>
                </label>
              </>
            )}
            {form.authMode === 'BASIC' && (
              <>
                <label className="space-y-1 text-sm">
                  <span>用户名凭据名</span>
                  <Input
                    placeholder="basicUser"
                    value={form.usernameSecret}
                    disabled={saving}
                    onChange={(event) => patchForm({ usernameSecret: event.target.value })}
                  />
                </label>
                <label className="space-y-1 text-sm">
                  <span>密码凭据名</span>
                  <Input
                    placeholder="basicPass"
                    value={form.passwordSecret}
                    disabled={saving}
                    onChange={(event) => patchForm({ passwordSecret: event.target.value })}
                  />
                </label>
              </>
            )}
          </div>
        </section>

        <section className="space-y-4">
          <p className="text-sm font-medium">请求参数</p>
          <ParamEditor
            title="查询参数"
            hint="追加到 endpoint 的 query string"
            rows={form.query}
            onChange={(rows) => patchForm({ query: rows })}
            keyPlaceholder="参数名"
            valuePlaceholder="值 / ${secret:name}"
            disabled={saving}
          />
          <ParamEditor
            title="请求头"
            hint="不允许覆盖 multipart 相关头"
            rows={form.headers}
            onChange={(rows) => patchForm({ headers: rows })}
            keyPlaceholder="头名称"
            valuePlaceholder="值 / ${secret:name}"
            disabled={saving}
          />
          <ParamEditor
            title="multipart 附加字段"
            hint="以表单字段形式随文件一起提交"
            rows={form.fields}
            onChange={(rows) => patchForm({ fields: rows })}
            keyPlaceholder="字段名"
            valuePlaceholder="值 / ${secret:name}"
            disabled={saving}
          />
        </section>

        <section className="space-y-3">
          <p className="text-sm font-medium">限额与超时</p>
          <div className="grid gap-3 sm:grid-cols-3">
            <label className="space-y-1 text-sm">
              <span>单文件上限（字节）</span>
              <Input
                type="number"
                min={1}
                max={HARD_MAX_BYTES}
                value={form.maxBytes}
                disabled={saving}
                onChange={(event) => patchForm({ maxBytes: Number(event.target.value) })}
              />
            </label>
            <label className="space-y-1 text-sm">
              <span>单次超时（毫秒）</span>
              <Input
                type="number"
                min={MIN_ATTEMPT_MS}
                max={MAX_ATTEMPT_MS}
                value={form.attemptMs}
                disabled={saving}
                onChange={(event) => patchForm({ attemptMs: Number(event.target.value) })}
              />
            </label>
            <label className="space-y-1 text-sm sm:col-span-3">
              <span>允许的 MIME 类型（逗号分隔；当前仅支持 image/png、image/jpeg）</span>
              <Input
                value={form.allowedMimes}
                disabled={saving}
                onChange={(event) => patchForm({ allowedMimes: event.target.value })}
              />
            </label>
          </div>
        </section>

        <section className="space-y-3">
          <p className="text-sm font-medium">响应解析</p>
          <label className="block space-y-1 text-sm">
            <span>文件 URL 的 JSON 指针</span>
            <Input
              placeholder="/data/url"
              value={form.urlPointer}
              disabled={saving}
              onChange={(event) => patchForm({ urlPointer: event.target.value })}
            />
          </label>
          <div className="space-y-2">
            <div className="flex items-center justify-between">
              <div>
                <p className="text-sm">成功条件</p>
                <p className="text-muted-foreground text-xs">
                  最多 {MAX_CONDITIONS} 条，全部满足才算成功；JSON 指针以 / 开头。
                </p>
              </div>
              <Button
                type="button"
                size="sm"
                variant="outline"
                disabled={saving || form.conditions.length >= MAX_CONDITIONS}
                onClick={() =>
                  patchForm({
                    conditions: [
                      ...form.conditions,
                      { pointer: '', op: 'EQ', type: 'STRING', value: '' },
                    ],
                  })
                }
              >
                <Plus className="size-4" />
                添加
              </Button>
            </div>
            {form.conditions.map((condition, index) => (
              <div key={index} className="flex flex-wrap items-start gap-2">
                <Input
                  aria-label={`成功条件指针 ${index + 1}`}
                  placeholder="/data/width"
                  value={condition.pointer}
                  disabled={saving}
                  onChange={(event) =>
                    patchForm({
                      conditions: form.conditions.map((item, i) =>
                        i === index ? { ...item, pointer: event.target.value } : item,
                      ),
                    })
                  }
                />
                <select
                  aria-label={`成功条件操作 ${index + 1}`}
                  className={cn(selectClass, 'sm:w-28')}
                  value={condition.op}
                  disabled={saving}
                  onChange={(event) =>
                    patchForm({
                      conditions: form.conditions.map((item, i) =>
                        i === index ? { ...item, op: event.target.value } : item,
                      ),
                    })
                  }
                >
                  <option value="EQ">相等</option>
                  <option value="EXISTS">存在</option>
                </select>
                {condition.op === 'EQ' && (
                  <>
                    <select
                      aria-label={`成功条件类型 ${index + 1}`}
                      className={cn(selectClass, 'sm:w-28')}
                      value={condition.type}
                      disabled={saving}
                      onChange={(event) =>
                        patchForm({
                          conditions: form.conditions.map((item, i) =>
                            i === index ? { ...item, type: event.target.value } : item,
                          ),
                        })
                      }
                    >
                      <option value="STRING">字符串</option>
                      <option value="NUMBER">数字</option>
                      <option value="BOOLEAN">布尔</option>
                    </select>
                    <Input
                      aria-label={`成功条件期望值 ${index + 1}`}
                      placeholder="true"
                      value={condition.value}
                      disabled={saving}
                      onChange={(event) =>
                        patchForm({
                          conditions: form.conditions.map((item, i) =>
                            i === index ? { ...item, value: event.target.value } : item,
                          ),
                        })
                      }
                    />
                  </>
                )}
                <Button
                  type="button"
                  size="icon"
                  variant="ghost"
                  aria-label="删除成功条件"
                  disabled={saving}
                  onClick={() =>
                    patchForm({
                      conditions: form.conditions.filter((_, i) => i !== index),
                    })
                  }
                >
                  <X className="size-4" />
                </Button>
              </div>
            ))}
          </div>
        </section>

        <section className="space-y-2">
          <p className="text-sm font-medium">批准的 URL 来源（只读）</p>
          <p className="text-muted-foreground text-xs">
            由部署配置决定，页面不能新增；上传返回的 URL 必须落在这些来源下。
          </p>
          {form.endpoint.trim() && !matchedApprovedTarget && (
            <p className="text-destructive text-xs">
              {approvedTargets.length === 0
                ? '当前部署未配置批准目标（custom-http.enabled=false 或 approved-targets 为空），保存会被后端拒绝（STORAGE039/STORAGE035），不会发出任何请求。'
                : '当前上传地址未精确匹配部署批准目标（路径也必须一致），保存会被后端拒绝（STORAGE035），不会发出任何请求。'}
            </p>
          )}
          <div className="flex flex-wrap gap-1">
            {planOrigins.length ? (
              planOrigins.map((origin) => (
                <Badge key={origin} variant="outline">
                  {origin}
                </Badge>
              ))
            ) : (
              <span className="text-muted-foreground text-xs">部署未批准任何来源</span>
            )}
          </div>
        </section>

        <section className="space-y-2">
          <p className="text-sm font-medium">密钥</p>
          <p className="text-muted-foreground text-xs">
            按画像里引用的凭据名列出；设置后只显示「已配置」，明文不会回显。
          </p>
          {!secretList.length && (
            <p className="text-muted-foreground text-xs">
              尚未引用任何凭据（如认证模板里使用 {'${secret:name}'}）。
            </p>
          )}
          <div className="space-y-2">
            {secretList.map((row) => {
              const op = secretOps[row.name] || 'KEEP';
              const editingThis = secretEditing?.name === row.name;
              return (
                <div key={row.name} className="space-y-2 rounded-md border px-3 py-2">
                  <div className="flex flex-wrap items-center justify-between gap-2">
                    <div className="text-sm">
                      <span className="font-mono">{row.name}</span>
                      <span
                        className={cn(
                          'ml-2 text-xs',
                          op === 'SET'
                            ? 'text-primary'
                            : op === 'CLEAR'
                              ? 'text-destructive'
                              : 'text-muted-foreground',
                        )}
                      >
                        {secretLabel(row)}
                      </span>
                    </div>
                    <div className="flex gap-1">
                      <Button
                        type="button"
                        size="sm"
                        variant="outline"
                        disabled={saving}
                        onClick={() => setSecretEditing({ name: row.name, value: '' })}
                      >
                        设置
                      </Button>
                      <Button
                        type="button"
                        size="sm"
                        variant="ghost"
                        disabled={saving || (!row.configured && op !== 'SET')}
                        onClick={() => {
                          delete secretValuesRef.current[row.name];
                          setSecretOps((previous) => ({ ...previous, [row.name]: 'CLEAR' }));
                        }}
                      >
                        清除
                      </Button>
                    </div>
                  </div>
                  {editingThis && (
                    <div className="bg-muted/30 space-y-2 rounded-md border p-3">
                      <p className="text-muted-foreground text-xs">
                        凭据 {row.name}
                        ：内容只提交给后端加密保存，不回显。
                      </p>
                      <div className="flex gap-2">
                        <Input
                          type="password"
                          autoComplete="off"
                          value={secretEditing.value}
                          onChange={(event) =>
                            setSecretEditing((previous) =>
                              previous ? { ...previous, value: event.target.value } : previous,
                            )
                          }
                        />
                        <Button
                          type="button"
                          size="sm"
                          disabled={!secretEditing.value}
                          onClick={() => {
                            if (!secretEditing) return;
                            secretValuesRef.current[secretEditing.name] = secretEditing.value;
                            setSecretOps((previous) => ({
                              ...previous,
                              [secretEditing.name]: 'SET',
                            }));
                            setSecretEditing(null);
                          }}
                        >
                          保存到本表单
                        </Button>
                        <Button
                          type="button"
                          size="sm"
                          variant="ghost"
                          onClick={() => setSecretEditing(null)}
                        >
                          取消
                        </Button>
                      </div>
                    </div>
                  )}
                </div>
              );
            })}
          </div>
        </section>

        {formError && (
          <p role="alert" className="text-destructive text-sm">
            {formError}
          </p>
        )}

        {validation && (
          <div
            className={cn(
              'space-y-2 rounded-md border p-3 text-sm',
              validation.valid ? 'border-border' : 'border-destructive/50',
            )}
          >
            <p className="flex items-center gap-2 font-medium">
              {validation.valid ? (
                <CheckCircle2 className="text-primary size-4" />
              ) : (
                <AlertTriangle className="text-destructive size-4" />
              )}
              离线校验：{validation.valid ? '通过' : '未通过'}
            </p>
            {validation.missingSecrets?.length ? (
              <p className="text-destructive text-xs">
                缺少凭据：{validation.missingSecrets.join('、')}
              </p>
            ) : null}
            <details>
              <summary className="text-muted-foreground cursor-pointer text-xs">
                后端规范化后的画像（不含明文）
              </summary>
              <pre className="bg-muted/40 mt-2 max-h-64 overflow-auto rounded p-2 text-xs">
                {JSON.stringify(validation.profile, null, 2)}
              </pre>
            </details>
          </div>
        )}
      </div>
    );
  },
);

export default HttpChannelEditorFields;
