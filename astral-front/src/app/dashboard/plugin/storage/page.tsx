'use client';

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  Alert,
  Button,
  Card,
  Form,
  Input,
  InputNumber,
  Modal,
  Popconfirm,
  Select,
  Space,
  Switch,
  Tabs,
  Tag,
  message,
} from '@/components/antd-compat';
import { ResizableTable } from '@/components/ResizableTable';
import type { DataTableColumn } from '@/components/data-table/types';
import {
  DeleteOutlined,
  ExperimentOutlined,
  PlusOutlined,
  ReloadOutlined,
  StarOutlined,
} from '@/components/antd-compat/icons';
import { fetchDictOptions, DictOption } from '@/api/dict';
import { usePerm } from '@/lib/perm';
import FileManager from '@/components/storage/FileManager';
import ServerSideUpload from '@/components/storage/ServerSideUpload';
import HttpChannelEditorFields, {
  type HttpChannelFieldsHandle,
} from '@/components/storage/HttpChannelEditorFields';
import { uploadWithStorageTicket } from '@/lib/storage-upload';
import { PageHeader } from '@/components/page-header';
import { StatCard } from '@/components/stat-card';
import { FilterBar } from '@/components/filter-bar';
import {
  storageApi,
  type StorageConfig,
  type StorageFile,
  type StorageFolder,
  type StoragePageResult,
  type StorageTask,
  type StorageAudit,
  type StorageOverview,
  type S3ProviderOptions,
  type FolderUploadPolicy,
  type StorageBindingView,
  type HttpApprovedTarget,
  type HttpChannelView,
} from '@/api/storage';

/** S3 SigV4 家族（含七牛 S3 网关）：预签名 PUT 直传 + secretAccessKey 密钥 */
const S3_FAMILY = ['R2', 'S3_COMPATIBLE', 'QINIU'];
/** 需要 provider_options 的对象存储 Provider 集合 */
const OBJECT_PROVIDERS = ['R2', 'S3_COMPATIBLE', 'QINIU', 'COS', 'OSS', 'UPYUN'];

/** providerOptions JSON 字符串 → 对象（解析失败返回空对象） */
function parseOptions(json?: string | null): S3ProviderOptions {
  if (!json) return {};
  try {
    return JSON.parse(json) as S3ProviderOptions;
  } catch {
    return {};
  }
}

/** 统一错误文案：Error 取 message，非 Error（如表单校验对象）给可执行兜底 */
function errText(error: unknown, fallback = '操作失败，请稍后重试') {
  if (error instanceof Error && error.message) return error.message;
  if (typeof error === 'object' && error && 'errorFields' in error) return '请检查表单必填项';
  return fallback;
}

/** 状态 → Tag 颜色（展示配色属前端逻辑，保留映射；文案标签走字典） */
const STATUS_COLOR: Record<string, string> = {
  ENABLED: 'success',
  AVAILABLE: 'success',
  SUCCESS: 'success',
  PENDING: 'processing',
  DELETING: 'processing',
  RUNNING: 'processing',
};

/** 授权弹窗行：后端 FolderPermView（含 id/configs 视图）+ 绑定子集选择态；「添加授权」新增行无 id/key */
type PermRow = {
  id?: number;
  key?: number;
  subjectType: string;
  subjectId: string;
  permissions: string;
  configs?: StorageBindingView[];
  /** 每行独立的配置绑定（configId + priority），不再把所有绑定压成一个优先级 */
  permConfigs?: { storageConfigId: number; priority: number }[];
};

/** 文件夹弹窗内一行存储绑定（configId × 优先级）；新行未选配置时 id 为空 */
type FolderBindingRow = { storageConfigId?: number; priority?: number };

/** 文件夹弹窗表单值：文件夹字段 + 策略开关与各策略字段（policy* 由 buildFolderPolicy 序列化为 FolderUploadPolicy） */
type FolderFormValues = {
  parentId?: number;
  folderName?: string;
  visibility?: string;
  status?: string;
  policyEnabled?: boolean;
  policyRequireLogin?: boolean;
  policyMinSizeBytes?: number;
  policyMaxSizeMb?: number;
  policyDailyLimit?: number;
  policyMimes?: string[];
  policyExtensions?: string[];
  policyForceVisibility?: string;
  policyVerifyContent?: string;
  policyMaxPixels?: number;
};

export default function StoragePage() {
  const hasPerm = usePerm();
  /** 是否具备存储维护权限（须与后端 AdminStorageController 上的 @RequiresPermission("admin:storage:edit") 一致；无权限时只读） */
  const canEdit = hasPerm('admin:storage:edit');
  /** HTTP 渠道权限（与后端 AdminStorageHttpConfigController 的 admin:storage:channel:{edit,test} 一致；渠道入口即存储配置弹窗） */
  const canEditChannel = hasPerm('admin:storage:channel:edit');
  const canTestChannel = hasPerm('admin:storage:channel:test');

  // ==================== 数据字典（枚举值走字典，见 AGENTS.md §3） ====================
  const [dict, setDict] = useState<Record<string, DictOption[]>>({});
  const dictLabel = (code: string, value?: string) =>
    dict[code]?.find((o) => o.value === value)?.label || value || '-';
  /** 后端 savePermissions 当前只接受 SUBJECT_USER，界面不再暴露 PLUGIN 等未实现主体 */
  const subjectTypeOptions = useMemo(() => {
    const users = (dict['storage_subject_type'] || []).filter((option) => option.value === 'USER');
    return users.length ? users : [{ value: 'USER', label: 'USER' }];
  }, [dict]);

  /**
   * 存储类型选项按权限裁剪：普通配置新建/编辑走 admin:storage:edit，CUSTOM_HTTP 渠道走
   * admin:storage:channel:edit。两类权限可由不同角色持有，不能把无权创建的选项暴露给用户。
   */
  const providerOptions = useMemo(
    () =>
      (dict['storage_provider_type'] || []).filter((option) =>
        option.value === 'CUSTOM_HTTP' ? canEditChannel : canEdit,
      ),
    [dict, canEdit, canEditChannel],
  );

  // ==================== 存储配置 ====================
  const [configs, setConfigs] = useState<StorageConfig[]>([]);
  /** 概览计数（统计条）：随配置/文件夹列表一并刷新 */
  const [overview, setOverview] = useState<StorageOverview | null>(null);
  /** useCallback 固定引用：供 loadConfigs/loadFolders 复用且保持 exhaustive-deps 可证明稳定 */
  const loadOverview = useCallback(() => {
    storageApi
      .getOverview()
      .then((res) => {
        if (res.code === 200) setOverview(res.data);
      })
      .catch((e) => message.error(errText(e)));
  }, []);
  const [configModal, setConfigModal] = useState<{ open: boolean; editing?: StorageConfig }>({
    open: false,
  });
  /** CUSTOM_HTTP 渠道：弹窗内就地渲染 HttpChannelEditorFields（无独立页签/弹窗） */
  const [httpChannelDetail, setHttpChannelDetail] = useState<HttpChannelView | null>(null);
  const [httpApprovedTargets, setHttpApprovedTargets] = useState<HttpApprovedTarget[]>([]);
  const [httpBusy, setHttpBusy] = useState(false);
  const httpEditorRef = useRef<HttpChannelFieldsHandle>(null);
  /** 同步锁：validateFields / 渠道详情 await 期间重复点击「确定」必须被拦住 */
  const configSubmitLockRef = useRef(false);
  const [configForm] = Form.useForm<
    Parameters<typeof storageApi.createConfig>[0] & Pick<StorageConfig, 'status'>
  >();

  const loadConfigs = () => {
    loadOverview();
    storageApi
      .listConfigs()
      .then((res) => {
        if (res.code === 200) setConfigs(res.data || []);
      })
      .catch((e) => message.error(errText(e)));
  };

  const submitConfig = async () => {
    if (configSubmitLockRef.current) return;
    configSubmitLockRef.current = true;
    try {
      const values = await configForm.validateFields();
      // CUSTOM_HTTP：编辑态走 configModal.editing.providerType（编辑时类型下拉不渲染），新建态走表单值
      const isHttp =
        configModal.editing?.providerType === 'CUSTOM_HTTP' ||
        (!configModal.editing && values.providerType === 'CUSTOM_HTTP');
      if (isHttp) {
        const name = (values.name || '').trim();
        if (configModal.editing?.id && !httpChannelDetail) {
          message.warning('渠道详情加载中，请稍候再试');
          return;
        }
        const editor = httpEditorRef.current;
        if (!editor) {
          message.error('渠道编辑器尚未加载，请稍后重试');
          return;
        }
        const error = await editor.submit({
          name,
          mode: configModal.editing?.id ? 'edit' : 'create',
          channelId: configModal.editing?.id,
          expectedVersion: httpChannelDetail?.configVersion,
        });
        // 编辑器内部已把错误内联展示在表单上方，这里不再弹 toast，避免同一错误出现两次
        if (error) return;
        message.success('已保存');
        setConfigModal({ open: false });
        loadConfigs();
        return;
      }
      if (configModal.editing?.id) {
        await storageApi.updateConfig(configModal.editing.id, values);
      } else {
        await storageApi.createConfig(values);
      }
      message.success('已保存');
      setConfigModal({ open: false });
      loadConfigs();
    } catch (e: unknown) {
      // 必填校验失败时 validateFields 抛的是 { errorFields } 不是 Error，取不到 message，兜底一句中文提示；
      // 字段级错误仍由表单内联展示，这里只保证「点了确定没有任何反馈」不再发生
      message.error(errText(e, '请检查表单填写是否完整'));
    } finally {
      configSubmitLockRef.current = false;
    }
  };

  /** 弹窗当前编辑的 Provider 类型（未选时按 TELEGRAM 展示） */
  const configProvider =
    Form.useWatch('providerType', configForm) || configModal.editing?.providerType || 'TELEGRAM';
  const isS3Config = S3_FAMILY.includes(configProvider);
  const isCOSConfig = configProvider === 'COS';
  const isOSSConfig = configProvider === 'OSS';
  const isObjectConfig = OBJECT_PROVIDERS.includes(configProvider);
  const isHttpChannelConfig = configProvider === 'CUSTOM_HTTP';

  /** CUSTOM_HTTP：弹窗打开时拉取渠道详情（编辑）与部署批准目标；就地渲染专用字段的数据源 */
  useEffect(() => {
    if (!configModal.open) {
      setHttpChannelDetail(null);
      return;
    }
    let cancelled = false;
    const editingHttp =
      configModal.editing?.id != null && configModal.editing.providerType === 'CUSTOM_HTTP';
    if (editingHttp) {
      setHttpChannelDetail(null);
      storageApi
        .getHttpChannel(configModal.editing!.id!)
        .then((res) => {
          if (res.code !== 200) throw new Error(res.msg);
          if (!cancelled) setHttpChannelDetail(res.data);
        })
        .catch((e: unknown) => {
          if (!cancelled) message.error(errText(e));
        });
    }
    if (isHttpChannelConfig || editingHttp) {
      storageApi
        .listApprovedTargets()
        .then((res) => {
          if (res.code === 200 && !cancelled) setHttpApprovedTargets(res.data || []);
        })
        .catch((e: unknown) => {
          if (!cancelled) message.warning(`批准目标加载失败：${errText(e)}`);
        });
    }
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [configModal.open, configModal.editing?.id, isHttpChannelConfig]);

  const testConfig = async (config: StorageConfig) => {
    try {
      const res = await storageApi.testConfig(config.id!);
      if (res.code === 200) {
        if (res.data.healthStatus === 'UP') message.success(res.data.message);
        else message.error(res.data.message);
        loadConfigs();
      } else {
        message.error(res.msg || '测试失败');
      }
    } catch (e: unknown) {
      message.error(errText(e));
    }
  };

  // ==================== 文件夹 ====================
  const [folders, setFolders] = useState<StorageFolder[]>([]);
  /** 文件夹弹窗：绑定列表与调度策略不放 Form，走组件状态（Form 只管名称/可见性/策略字段） */
  const [folderModal, setFolderModal] = useState<{
    open: boolean;
    editing?: StorageFolder;
    bindings: FolderBindingRow[];
    strategy: string;
  }>({ open: false, bindings: [], strategy: 'FAILOVER' });
  const [folderForm] = Form.useForm<FolderFormValues & { folderName: string }>();
  const [permModal, setPermModal] = useState<{
    open: boolean;
    folder?: StorageFolder;
    rows: PermRow[];
  }>({ open: false, rows: [] });

  const loadFolders = () => {
    loadOverview();
    storageApi
      .listFolders()
      .then((res) => {
        if (res.code === 200) setFolders(res.data || []);
      })
      .catch((e) => message.error(errText(e)));
  };

  // 收集弹窗中的策略表单字段并序列化为后端 policy 对象：
  // 启用 → 携带各字段（未填 = 不限制）；停用且为编辑 → 传空对象（后端清除策略）
  const buildFolderPolicy = (values: FolderFormValues): FolderUploadPolicy | undefined => {
    if (!values.policyEnabled) return folderModal.editing ? {} : undefined;
    return {
      requireLogin: values.policyRequireLogin ?? true,
      minSizeBytes: values.policyMinSizeBytes || undefined,
      maxSizeBytes: values.policyMaxSizeMb
        ? Math.round(values.policyMaxSizeMb * 1024 * 1024)
        : undefined,
      allowedMimes: values.policyMimes?.length ? values.policyMimes : undefined,
      allowedExtensions: values.policyExtensions?.length ? values.policyExtensions : undefined,
      dailyUploadLimit: values.policyDailyLimit || undefined,
      forceVisibility: values.policyForceVisibility || undefined,
      verifyContent: values.policyVerifyContent || undefined,
      maxPixels: values.policyMaxPixels || undefined,
    };
  };

  const submitFolder = async () => {
    try {
      if (folderModal.bindings.some((b) => !b.storageConfigId)) {
        message.error('存在未选择存储配置的绑定行，请补全或删除');
        return;
      }
      const values = await folderForm.validateFields();
      const payload = {
        ...values,
        policy: buildFolderPolicy(values),
        storageStrategy: folderModal.strategy || 'FAILOVER',
        configs: folderModal.bindings.map((b) => ({
          storageConfigId: b.storageConfigId!,
          priority: b.priority ?? 100,
        })),
      };
      delete payload.policyEnabled;
      (
        [
          'policyRequireLogin',
          'policyMinSizeBytes',
          'policyMaxSizeMb',
          'policyDailyLimit',
          'policyMimes',
          'policyExtensions',
          'policyForceVisibility',
          'policyVerifyContent',
          'policyMaxPixels',
        ] as const
      ).forEach((k) => delete payload[k]);
      if (folderModal.editing?.id) {
        await storageApi.updateFolder(folderModal.editing.id, payload);
      } else {
        await storageApi.createFolder(payload);
      }
      message.success('已保存');
      setFolderModal({ open: false, bindings: [], strategy: 'FAILOVER' });
      loadFolders();
    } catch (e: unknown) {
      // 同 submitConfig：校验失败不会带上 message，兜底中文提示
      message.error(errText(e, '请检查表单填写是否完整'));
    }
  };

  // 打开文件夹弹窗；编辑时把 upload_policy JSON 回显到策略表单字段，绑定/策略回显到组件状态
  const openFolderModal = (record?: StorageFolder) => {
    const bindings: FolderBindingRow[] = (record?.configs || []).map((c) => ({
      storageConfigId: c.configId,
      priority: c.priority,
    }));
    setFolderModal({
      open: true,
      editing: record,
      bindings,
      strategy: record?.storageStrategy || 'FAILOVER',
    });
    if (!record) {
      folderForm.resetFields();
      return;
    }
    const echo: FolderFormValues & Partial<StorageFolder> = { ...record };
    try {
      if (record.uploadPolicy) {
        const p = JSON.parse(record.uploadPolicy);
        echo.policyEnabled = true;
        echo.policyRequireLogin = p.requireLogin !== false;
        echo.policyMinSizeBytes = p.minSizeBytes;
        echo.policyMaxSizeMb = p.maxSizeBytes ? p.maxSizeBytes / (1024 * 1024) : undefined;
        echo.policyDailyLimit = p.dailyUploadLimit;
        echo.policyMimes = p.allowedMimes;
        echo.policyExtensions = p.allowedExtensions;
        echo.policyForceVisibility = p.forceVisibility;
        echo.policyVerifyContent = p.verifyContent;
        echo.policyMaxPixels = p.maxPixels;
      }
    } catch {
      /* 策略 JSON 非法时按未配置回显 */
    }
    folderForm.setFieldsValue(echo);
  };

  const openPermissions = async (folder: StorageFolder) => {
    try {
      const res = await storageApi.getFolderPermissions(folder.id!);
      if (res.code === 200) {
        setPermModal({
          open: true,
          folder,
          rows: (res.data || []).map((r) => ({
            ...r,
            key: r.id,
            permConfigs: (r.configs || []).map((c) => ({
              storageConfigId: c.configId,
              priority: c.priority ?? 100,
            })),
          })),
        });
      } else {
        message.error(res.msg || '授权加载失败');
      }
    } catch (e: unknown) {
      message.error(errText(e, '授权加载失败'));
    }
  };

  const savePermissions = async () => {
    // 后端会静默跳过 subjectId/permissions 为空的行（导致「保存成功但什么都没写」），必须先在前端拒绝
    for (let i = 0; i < permModal.rows.length; i += 1) {
      const row = permModal.rows[i];
      if (!row.subjectId.trim()) {
        message.error(`第 ${i + 1} 行缺少授权主体 ID`);
        return;
      }
      if (!row.permissions.trim()) {
        message.error(`第 ${i + 1} 行至少选择一个权限`);
        return;
      }
    }
    const seen = new Set<string>();
    for (const row of permModal.rows) {
      const key = `${row.subjectType}:${row.subjectId.trim()}`;
      if (seen.has(key)) {
        message.error(`授权主体重复：${row.subjectType} / ${row.subjectId}，请合并后再保存`);
        return;
      }
      seen.add(key);
    }
    const rows = permModal.rows.map((r) => ({
      subjectType: r.subjectType,
      subjectId: r.subjectId.trim(),
      permissions: r.permissions,
      // 授权绑定：空 = 跟随文件夹绑定；选定项必须是文件夹绑定的子集（后端 STORAGE058 校验）
      configs: r.permConfigs?.length
        ? r.permConfigs.map((binding) => ({
            storageConfigId: binding.storageConfigId,
            priority: binding.priority ?? 100,
          }))
        : undefined,
    }));
    try {
      await storageApi.saveFolderPermissions(permModal.folder!.id!, rows);
      message.success('授权已保存');
      setPermModal({ open: false, rows: [] });
    } catch (e: unknown) {
      message.error(errText(e, '授权保存失败'));
    }
  };

  // ==================== 上传测试 ====================
  const [testFolderId, setTestFolderId] = useState<number | undefined>();
  const [testRunning, setTestRunning] = useState(false);
  /** 四步链路状态：凭证签发 / 直传 / 登记 / 下载验证 */
  const [testSteps, setTestSteps] = useState<
    { title: string; state: 'wait' | 'run' | 'ok' | 'fail'; detail?: string }[]
  >([
    { title: '向 Astral 换取上传凭证', state: 'wait' },
    { title: '携带凭证直传 Cloudflare Worker', state: 'wait' },
    { title: 'Worker 转存 Telegram 并回调登记', state: 'wait' },
    { title: '签发下载地址并验证可访问', state: 'wait' },
  ]);
  const setStep = (idx: number, state: 'run' | 'ok' | 'fail', detail?: string) => {
    setTestSteps((steps) => steps.map((s, i) => (i === idx ? { ...s, state, detail } : s)));
  };
  const resetTest = () =>
    setTestSteps((steps) => steps.map((s) => ({ ...s, state: 'wait', detail: undefined })));

  /** 测试文件夹解析出的存储配置（后端 resolved* 字段；无绑定则取平台默认） */
  const activeTestConfig = useMemo(() => {
    const folder = folders.find((f) => f.id === testFolderId);
    if (folder?.resolvedConfigId) return configs.find((c) => c.id === folder.resolvedConfigId);
    return configs.find((c) => c.isDefault === 1) || undefined;
  }, [testFolderId, folders, configs]);
  const testProvider = activeTestConfig?.providerType || 'TELEGRAM';

  /** 按 Provider 生成四步文案（测试开始时固化，避免运行中切换文件夹导致步骤错位） */
  const buildTestSteps = (provider: string) => {
    const isTelegram = provider === 'TELEGRAM';
    const isUpyun = provider === 'UPYUN';
    const step2 = isTelegram
      ? '携带凭证直传 Cloudflare Worker'
      : isUpyun
        ? '携带 policy/authorization 表单直传又拍云'
        : '携带预签名 PUT 直传对象存储';
    const step3 = isTelegram
      ? 'Worker 转存 Telegram 并回调登记'
      : `Astral HEAD 确认对象存在并登记（provider=${provider}）`;
    const step4 = isTelegram
      ? '签发下载地址并验证可访问'
      : provider === 'COS' || provider === 'OSS'
        ? `签发${provider === 'COS' ? ' COS' : ' OSS'}预签名下载地址并验证可访问`
        : isUpyun
          ? '签发又拍云下载地址（Token 防盗链）并验证可访问'
          : '签发 S3 预签名下载地址并验证可访问';
    return [
      { title: '向 Astral 换取上传凭证', state: 'wait' as const },
      { title: step2, state: 'wait' as const },
      { title: step3, state: 'wait' as const },
      { title: step4, state: 'wait' as const },
    ];
  };

  /** 测试页描述与前置条件按 Provider 生成 */
  const testDescription = useMemo(() => {
    const p = testProvider;
    if (p === 'TELEGRAM')
      return '端到端测试：自动生成一张测试 PNG，完整走「凭证签发 → Worker 直传 → Telegram 回调登记 → 下载验证」四个环节。测试文件会真实进入 Telegram 频道，验证后可在文件管理中删除。';
    if (p === 'UPYUN')
      return '端到端测试：自动生成一张测试 PNG，完整走「凭证签发 → 表单直传又拍云 → HEAD 确认登记 → 下载验证」四个环节，验证又拍云通道（操作员凭证 / save-key / 空间域名）是否可用。测试对象会真实写入又拍云空间，验证后可删除。';
    if (p === 'COS')
      return '端到端测试：自动生成一张测试 PNG，完整走「凭证签发 → COS 预签名 PUT 直传 → HEAD 确认登记 → 下载验证」四个环节，验证腾讯云 COS 通道（SecretId/Key / 桶 / 地域）是否可用。测试对象会真实写入 COS 桶，验证后可删除。';
    if (p === 'OSS')
      return '端到端测试：自动生成一张测试 PNG，完整走「凭证签发 → OSS URL 签名 PUT 直传 → HEAD 确认登记 → 下载验证」四个环节，验证阿里云 OSS 通道（AccessKey / 桶 / Endpoint）是否可用。测试对象会真实写入 OSS 桶，验证后可删除。';
    if (p === 'QINIU')
      return '端到端测试：自动生成一张测试 PNG，完整走「凭证签发 → S3 SigV4 预签名 PUT 直传 → HEAD 确认登记 → 下载验证」四个环节，验证七牛 S3 网关通道（AK/SK / 空间 / S3 端点）是否可用。测试对象会真实写入七牛空间，验证后可删除。';
    return '端到端测试：自动生成一张测试 PNG，完整走「凭证签发 → 预签名 PUT 直传 → HEAD 确认登记 → 下载验证」四个环节，验证 R2/S3 通道是否可用。测试对象会真实写入存储桶，验证后可删除。';
  }, [testProvider]);

  const testPrerequisites = useMemo(() => {
    const p = testProvider;
    if (p === 'TELEGRAM') {
      return [
        '存储配置已保存且「测试连接」通过（Worker /healthz 正常）',
        '所选文件夹已授权当前用户 UPLOAD 权限',
        'Worker 已配置 TG_BOT_TOKEN 且 Bot 在频道内有发消息权限',
      ];
    }
    const common = [
      `所选文件夹绑定的存储配置（provider=${p}）已保存且「测试连接」通过`,
      '所选文件夹已授权当前用户 UPLOAD 权限',
    ];
    if (p === 'COS' || p === 'OSS') {
      return [
        ...common,
        `浏览器直传需要 CORS：允许 PUT（上传域）与 GET（下载域）来自管理端域名，暴露 ETag 头`,
      ];
    }
    if (p === 'R2' || p === 'S3_COMPATIBLE' || p === 'QINIU') {
      return [
        ...common,
        `桶 CORS 允许来自管理端域名的 PUT/GET（R2 控制台 → Settings → CORS；七牛空间 → S3 网关 CORS）`,
      ];
    }
    if (p === 'UPYUN') {
      return [
        ...common,
        '空间绑定域名（publicBaseUrl）可公开访问；如开启 Token 防盗链，tokenKey 需与控制台一致；表单上传无 CORS 要求',
      ];
    }
    return common;
  }, [testProvider]);

  /**
   * 端到端上传测试：生成一张纯色测试图（不依赖本地文件），完整走一遍
   * 凭证 → 直传 → 登记 → 下载 四步，用于验证所选文件夹绑定的存储通道是否可用。
   */
  const runUploadTest = async () => {
    if (!testFolderId) {
      message.warning('请先选择测试文件夹');
      return;
    }
    const provider = testProvider;
    if (provider === 'CUSTOM_HTTP') {
      message.warning(
        'CUSTOM_HTTP 渠道走服务端两步上传，不支持这里的直传端到端测试；请到「文件」页签使用两步上传，或在渠道编辑器执行离线校验/测试连接',
      );
      return;
    }
    const steps = buildTestSteps(provider);
    setTestSteps(steps);
    setTestRunning(true);
    try {
      // 生成一张 2x2 的 PNG 测试图（合法 PNG 字节，无需画布依赖）
      const pngBytes = Uint8Array.from(
        atob(
          'iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAYAAACZgbYnAAAAEklEQVR4nGP8z8DAwMDAxIAEAAAAAP//AwB0EQIAAAAA//8DAP0BBQMBAAA=',
        ),
        (c) => c.charCodeAt(0),
      );
      const testFile = new File([pngBytes], 'astral-upload-test.png', { type: 'image/png' });

      // 1. 凭证签发
      setStep(0, 'run');
      const ticketRes = await storageApi.managerTicket(
        {
          folderId: testFolderId,
          fileName: testFile.name,
          contentType: testFile.type,
          sizeBytes: testFile.size,
        },
        true,
      );
      if (ticketRes.code !== 200) throw new Error(ticketRes.msg);
      const isTelegram = ticketRes.data.method !== 'PUT' && !ticketRes.data.form;
      setStep(
        0,
        'ok',
        `凭证已签发（${isTelegram ? 'TELEGRAM/Worker' : ticketRes.data.form ? 'UPYUN 表单直传' : '预签名 PUT'}，有效期至 ${new Date(ticketRes.data.expiresAt * 1000).toLocaleTimeString()}）`,
      );

      // 2. 直传（TELEGRAM → Worker；对象存储系 → 各 Provider 直传）
      setStep(1, 'run');
      const publicId: string = await uploadWithStorageTicket(ticketRes.data, testFile);
      setStep(1, 'ok', isTelegram ? '文件已送达 Worker' : '对象已写入存储桶');

      // 3. 回执登记（成功响应即表示登记已完成；此处刷新列表核对落库）
      setStep(2, 'run');
      const pageRes = await storageApi.pageFiles({ current: 1, size: 100, folderId: testFolderId });
      if (pageRes.code !== 200) throw new Error(pageRes.msg || '文件列表查询失败');
      const registered = (pageRes.data.records || []).some(
        (f: StorageFile) => f.publicId === publicId,
      );
      if (!registered) throw new Error('文件未在列表中出现，登记可能失败');
      setStep(2, 'ok', `已登记：${publicId.slice(0, 12)}...`);

      // 4. 下载验证
      setStep(3, 'run');
      const dlRes = await storageApi.managerUrl(publicId, false, true);
      if (dlRes.code !== 200) throw new Error(dlRes.msg);
      const dlResp = await fetch(dlRes.data.url);
      if (!dlResp.ok) throw new Error(`下载验证失败：HTTP ${dlResp.status}`);
      const blob = await dlResp.blob();
      if (blob.size === 0) throw new Error('下载内容为空');
      setStep(3, 'ok', `下载验证通过（${blob.size} 字节，地址有效）`);
      message.success(`上传测试通过：${isTelegram ? 'Telegram' : provider} 通道完整链路正常`);
    } catch (e: unknown) {
      const detail = errText(e, '上传测试失败');
      setTestSteps((steps) => {
        const failedIdx = steps.findIndex((s) => s.state === 'run');
        return failedIdx >= 0
          ? steps.map((s, i) => (i === failedIdx ? { ...s, state: 'fail' as const, detail } : s))
          : steps;
      });
      message.error(`测试失败：${detail}`);
    } finally {
      setTestRunning(false);
    }
  };

  // ==================== 任务与审计 ====================
  const [tasks, setTasks] = useState<StoragePageResult<StorageTask>>({
    records: [],
    total: 0,
    size: 20,
    current: 1,
  });
  const [audits, setAudits] = useState<StoragePageResult<StorageAudit>>({
    records: [],
    total: 0,
    size: 20,
    current: 1,
  });

  const loadTasks = (current = 1, size = 20) => {
    storageApi
      .pageTasks({ current, size })
      .then((res) => {
        if (res.code === 200) setTasks(res.data);
      })
      .catch((e) => message.error(errText(e)));
  };
  const loadAudits = (current = 1, size = 20) => {
    storageApi
      .pageAudit({ current, size })
      .then((res) => {
        if (res.code === 200) setAudits(res.data);
      })
      .catch((e) => message.error(errText(e)));
  };

  useEffect(() => {
    loadConfigs();
    loadFolders();
    loadTasks();
    loadAudits();
    // 枚举选项走数据字典（storage_*，V5__storage_dict.sql）；失败时降级为字典原值展示
    fetchDictOptions([
      'storage_provider_type',
      'storage_status',
      'storage_file_status',
      'storage_task_status',
      'storage_visibility',
      'storage_permission',
      'storage_verify_content',
      'storage_subject_type',
      'storage_target_strategy',
    ])
      .then(setDict)
      .catch((error) => {
        message.warning(`枚举字典加载失败，下拉与标签将显示原始值：${errText(error)}`);
      });
    // 挂载一次性拉取；loadConfigs/loadFolders 每渲染重建，列入依赖会变成每渲染重拉（仓库既有 disable 惯例）
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  /** Provider 展示配色（纯前端观感，保留映射；文案标签走 storage_provider_type 字典） */
  const PROVIDER_COLOR: Record<string, string> = {
    R2: 'blue',
    S3_COMPATIBLE: 'purple',
    QINIU: 'geekblue',
    COS: 'cyan',
    OSS: 'orange',
    UPYUN: 'green',
    TELEGRAM: 'orange',
    CUSTOM_HTTP: 'magenta',
  };
  const providerTag = (v?: string) => (
    <Tag color={PROVIDER_COLOR[v || 'TELEGRAM'] || 'orange'}>
      {dictLabel('storage_provider_type', v || 'TELEGRAM')}
    </Tag>
  );

  /** 状态列渲染：配色走 STATUS_COLOR（展示逻辑），文案走 storage_*_status 字典 */
  const statusTag = (status?: string) => (
    <Tag color={STATUS_COLOR[status || ''] || 'default'}>{dictLabel('storage_status', status)}</Tag>
  );
  const taskStatusTag = (status?: string) => (
    <Tag color={STATUS_COLOR[status || ''] || 'default'}>
      {dictLabel('storage_task_status', status)}
    </Tag>
  );

  const configColumns = [
    { title: '名称', dataIndex: 'name', width: 160 },
    { title: '类型', dataIndex: 'providerType', width: 100, render: (v: string) => providerTag(v) },
    {
      title: '目标',
      width: 200,
      ellipsis: true,
      render: (_: unknown, r: StorageConfig) =>
        r.providerType === 'CUSTOM_HTTP'
          ? '见渠道详情'
          : OBJECT_PROVIDERS.includes(r.providerType || 'TELEGRAM')
            ? parseOptions(r.providerOptions).bucket || '-'
            : r.chatId,
    },
    {
      title: 'Worker/公开域名',
      dataIndex: 'workerBaseUrl',
      ellipsis: true,
      render: (_: unknown, r: StorageConfig) =>
        r.providerType === 'CUSTOM_HTTP'
          ? '见渠道详情'
          : OBJECT_PROVIDERS.includes(r.providerType || 'TELEGRAM')
            ? parseOptions(r.providerOptions).publicBaseUrl || '-'
            : r.workerBaseUrl,
    },
    {
      title: '健康',
      dataIndex: 'healthStatus',
      width: 90,
      render: (v: string) => (
        <Tag color={v === 'UP' ? 'success' : v === 'DOWN' ? 'error' : 'default'}>
          {v || 'UNKNOWN'}
        </Tag>
      ),
    },
    {
      title: '默认',
      dataIndex: 'isDefault',
      width: 70,
      render: (v: number) => (v === 1 ? <Tag color="gold">默认</Tag> : '-'),
    },
    { title: '状态', dataIndex: 'status', width: 90, render: (v: string) => statusTag(v) },
    {
      title: '操作',
      key: 'action',
      width: 280,
      render: (_: unknown, record: StorageConfig) => {
        // CUSTOM_HTTP 走 http-configs 接口：测试/编辑/删除全部换成专用实现（旧五入口后端拒绝 STORAGE039）
        if (record.providerType === 'CUSTOM_HTTP') {
          return (
            <Space>
              <Popconfirm
                title="向该渠道发起一次真实测试上传？"
                description="会先校验部署开关与批准目标；未启用/未配置时只返回错误码，不出站。"
                disabled={!canTestChannel}
                onConfirm={async () => {
                  try {
                    const res = await storageApi.testHttpChannel(record.id!);
                    if (res.code === 200) {
                      const ok = res.data.success && res.data.safeCode === 'OK';
                      if (ok) {
                        message.success(`测试通过（${res.data.safeCode || 'OK'}）`);
                      } else {
                        message.error(
                          `测试未通过${res.data.safeCode ? `（${res.data.safeCode}）` : ''}${
                            res.data.safeMessage ? `：${res.data.safeMessage}` : ''
                          }`,
                        );
                      }
                      loadConfigs();
                    }
                  } catch (e: unknown) {
                    message.warning(errText(e, '测试请求失败'));
                  }
                }}
              >
                <Button size="small" icon={<ExperimentOutlined />} disabled={!canTestChannel}>
                  测试
                </Button>
              </Popconfirm>
              <Button
                size="small"
                disabled={!canEditChannel}
                onClick={() => {
                  setConfigModal({ open: true, editing: record });
                  configForm.setFieldsValue({
                    name: record.name,
                    providerType: record.providerType,
                    status: record.status,
                  });
                }}
              >
                编辑
              </Button>
              <Popconfirm
                title="确定删除该 HTTP 渠道？"
                description="仍被文件夹绑定或授权绑定时后端会拒绝删除。"
                disabled={!canEditChannel}
                onConfirm={async () => {
                  try {
                    await storageApi.deleteHttpChannel(record.id!);
                    message.success('已删除');
                    loadConfigs();
                  } catch (e: unknown) {
                    message.error(errText(e));
                  }
                }}
              >
                <Button size="small" danger disabled={!canEditChannel} icon={<DeleteOutlined />} />
              </Popconfirm>
            </Space>
          );
        }
        return (
          <Space>
            <Button
              size="small"
              icon={<ExperimentOutlined />}
              disabled={!canEdit}
              onClick={() => testConfig(record)}
            >
              测试
            </Button>
            <Button
              size="small"
              icon={<StarOutlined />}
              disabled={!canEdit}
              onClick={async () => {
                try {
                  await storageApi.setDefaultConfig(record.id!);
                  message.success('已设为默认');
                  loadConfigs();
                } catch (e: unknown) {
                  message.error(errText(e, '设为默认失败'));
                }
              }}
            >
              设默认
            </Button>
            <Button
              size="small"
              disabled={!canEdit}
              onClick={() => {
                const editing = { ...record } as StorageConfig;
                const opts = parseOptions(record.providerOptions);
                // Secret 打码回显：留空即保持不变（四类密钥字段统一处理）
                delete opts.secretAccessKey;
                delete opts.secretKey;
                delete opts.accessKeySecret;
                delete opts.password;
                delete opts.tokenKey;
                setConfigModal({ open: true, editing });
                configForm.setFieldsValue({
                  name: editing.name,
                  chatId: editing.chatId,
                  workerBaseUrl: editing.workerBaseUrl,
                  providerOptions: OBJECT_PROVIDERS.includes(record.providerType || 'TELEGRAM')
                    ? opts
                    : undefined,
                  maxFileSize: editing.maxFileSize,
                  remark: editing.remark,
                  status: editing.status,
                });
              }}
            >
              编辑
            </Button>
            <Popconfirm
              title="确定删除该配置？"
              disabled={!canEdit}
              onConfirm={async () => {
                try {
                  await storageApi.deleteConfig(record.id!);
                  message.success('已删除');
                  loadConfigs();
                } catch (e: unknown) {
                  message.error(errText(e));
                }
              }}
            >
              <Button size="small" danger disabled={!canEdit} icon={<DeleteOutlined />} />
            </Popconfirm>
          </Space>
        );
      },
    },
  ];

  const folderColumns = [
    { title: '路径', dataIndex: 'folderPath', ellipsis: true },
    {
      title: '存储绑定',
      key: 'bindings',
      ellipsis: true,
      render: (_: unknown, r: StorageFolder) =>
        r.configs?.length ? (
          <Space size={4} wrap>
            {r.configs.map((c) => (
              <Tag key={c.configId} color={c.configStatus === 'ENABLED' ? 'blue' : 'default'}>
                {c.configName || `#${c.configId}`}·{c.priority}
              </Tag>
            ))}
            {r.storageStrategy === 'ROUND_ROBIN' && <Tag color="purple">轮询</Tag>}
          </Space>
        ) : (
          <Tag color="red">未绑定（禁止上传）</Tag>
        ),
    },
    {
      title: '可见性',
      dataIndex: 'visibility',
      width: 90,
      render: (v: string) => (
        <Tag color={v === 'PUBLIC' ? 'green' : 'blue'}>{dictLabel('storage_visibility', v)}</Tag>
      ),
    },
    {
      title: '所有者',
      width: 130,
      render: (_: unknown, r: StorageFolder) => `${r.ownerType}:${r.ownerId}`,
    },
    {
      title: '策略',
      dataIndex: 'uploadPolicy',
      width: 80,
      render: (v: string) => (v ? <Tag color="purple">已配置</Tag> : <Tag>默认</Tag>),
    },
    { title: '状态', dataIndex: 'status', width: 90, render: (v: string) => statusTag(v) },
    {
      title: '操作',
      key: 'action',
      width: 260,
      render: (_: unknown, record: StorageFolder) => (
        <Space>
          <Button size="small" disabled={!canEdit} onClick={() => openPermissions(record)}>
            授权
          </Button>
          <Button size="small" disabled={!canEdit} onClick={() => openFolderModal(record)}>
            编辑
          </Button>
          <Popconfirm
            title="确定删除该文件夹？"
            disabled={!canEdit}
            onConfirm={async () => {
              try {
                await storageApi.deleteFolder(record.id!);
                message.success('已删除');
                loadFolders();
              } catch (e: unknown) {
                message.error(errText(e));
              }
            }}
          >
            <Button size="small" danger disabled={!canEdit} icon={<DeleteOutlined />} />
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <div className="space-y-4">
      <PageHeader
        title="文件存储"
        description="先建存储配置（含自定义 HTTP 图床），再在文件夹上绑定配置并授权；文件由浏览器直传目标存储，Astral 只管理权限与元数据"
      />

      <div className="grid grid-cols-2 gap-3 md:grid-cols-4">
        <StatCard
          size="compact"
          label="存储配置"
          value={String(overview?.configCount ?? '-')}
          sub={`启用 ${overview?.enabledConfigCount ?? '-'} 个`}
        />
        <StatCard
          size="compact"
          label="异常渠道"
          value={String(overview?.downConfigCount ?? '-')}
          tone={overview ? (overview.downConfigCount > 0 ? 'danger' : 'success') : 'normal'}
          sub={
            overview && overview.downConfigCount > 0 ? '测试消息见存储配置页签' : '健康探测全部正常'
          }
        />
        <StatCard
          size="compact"
          label="文件夹"
          value={String(overview?.folderCount ?? '-')}
          sub="含上传策略与授权"
        />
        <StatCard
          size="compact"
          label="文件"
          value={String(overview?.fileCount ?? '-')}
          sub="已登记对象"
        />
      </div>

      <Card>
        <Tabs
          items={[
            {
              key: 'configs',
              label: '存储配置',
              children: (
                <div className="space-y-3">
                  <FilterBar
                    actions={
                      <>
                        <Button icon={<ReloadOutlined />} onClick={loadConfigs}>
                          刷新
                        </Button>
                        {(canEdit || canEditChannel) && (
                          <Button
                            type="primary"
                            icon={<PlusOutlined />}
                            onClick={() => {
                              setConfigModal({ open: true });
                              configForm.resetFields();
                            }}
                          >
                            新建配置
                          </Button>
                        )}
                      </>
                    }
                  >
                    <span className="text-sm text-[color:var(--color-text-tertiary)]">
                      凭据只进入对应存储端；公开文件可生成永久直链，私有文件只发短时签名地址。
                    </span>
                  </FilterBar>
                  <PagedTable files={configs} columns={configColumns} rowKey="id" />
                </div>
              ),
            },
            {
              key: 'folders',
              label: '文件夹与授权',
              children: (
                <div className="space-y-3">
                  <FilterBar
                    actions={
                      <>
                        <Button icon={<ReloadOutlined />} onClick={loadFolders}>
                          刷新
                        </Button>
                        {canEdit && (
                          <Button
                            type="primary"
                            icon={<PlusOutlined />}
                            onClick={() => openFolderModal()}
                          >
                            新建文件夹
                          </Button>
                        )}
                      </>
                    }
                  >
                    <span className="text-sm text-[color:var(--color-text-tertiary)]">
                      文件夹可绑定多个存储配置按优先级调度；未绑定任何配置时禁止上传。
                    </span>
                  </FilterBar>
                  <PagedTable files={folders} columns={folderColumns} rowKey="id" />
                </div>
              ),
            },
            {
              key: 'files',
              label: '文件管理',
              children: (
                <div className="space-y-4">
                  {canEdit && <ServerSideUpload canUpload={canEdit} />}
                  <FileManager admin canEdit={canEdit} />
                </div>
              ),
            },
            {
              key: 'uploadTest',
              label: '上传测试',
              children: (
                <div>
                  <p style={{ color: '#909399' }}>{testDescription}</p>
                  <Space style={{ marginBottom: 16 }} wrap>
                    <Select
                      placeholder="选择测试文件夹"
                      style={{ width: 220 }}
                      value={testFolderId}
                      options={folders
                        .filter((f) => f.status === 'ENABLED')
                        .map((f) => {
                          const cfg = f.resolvedConfigId
                            ? configs.find((c) => c.id === f.resolvedConfigId)
                            : undefined;
                          const p = cfg?.providerType || f.resolvedProviderType || 'TELEGRAM';
                          const label = `${f.folderPath || f.folderName}（${p === 'TELEGRAM' ? 'Telegram' : p === 'S3_COMPATIBLE' ? 'S3' : p === 'QINIU' ? '七牛' : p === 'COS' ? '腾讯COS' : p === 'OSS' ? '阿里OSS' : p === 'UPYUN' ? '又拍云' : p}）`;
                          return {
                            label,
                            value: f.id!,
                            // CUSTOM_HTTP 是服务端两步上传，本页四步直传链路不适用
                            disabled: p === 'CUSTOM_HTTP',
                          };
                        })}
                      onChange={setTestFolderId}
                    />
                    <Button
                      type="primary"
                      icon={<ExperimentOutlined />}
                      loading={testRunning}
                      disabled={!testFolderId || testProvider === 'CUSTOM_HTTP'}
                      onClick={runUploadTest}
                    >
                      开始测试
                    </Button>
                    <Button disabled={testRunning} onClick={resetTest}>
                      重置
                    </Button>
                    <span>
                      本次测试通道：{providerTag(testProvider)}
                      {activeTestConfig && (
                        <span style={{ color: '#909399', marginLeft: 6 }}>
                          ({activeTestConfig.name})
                        </span>
                      )}
                    </span>
                  </Space>
                  <div>
                    {testSteps.map((step, idx) => (
                      <div
                        key={idx}
                        style={{
                          display: 'flex',
                          alignItems: 'flex-start',
                          gap: 12,
                          marginBottom: 12,
                        }}
                      >
                        <span
                          style={{
                            fontSize: 16,
                            width: 24,
                            textAlign: 'center',
                            lineHeight: '22px',
                          }}
                        >
                          {step.state === 'ok'
                            ? '✅'
                            : step.state === 'fail'
                              ? '❌'
                              : step.state === 'run'
                                ? '⏳'
                                : `${idx + 1}.`}
                        </span>
                        <div>
                          <div
                            style={{
                              fontWeight: step.state === 'fail' ? 600 : 400,
                              color: step.state === 'fail' ? '#cf1322' : undefined,
                            }}
                          >
                            {step.title}
                          </div>
                          {step.detail && (
                            <div
                              style={{
                                color: step.state === 'fail' ? '#cf1322' : '#595959',
                                fontSize: 12,
                                marginTop: 2,
                                wordBreak: 'break-all',
                              }}
                            >
                              {step.detail}
                            </div>
                          )}
                        </div>
                      </div>
                    ))}
                  </div>
                  <Alert
                    type="warning"
                    showIcon
                    style={{ marginTop: 8 }}
                    message="前置条件"
                    description={
                      <ul style={{ margin: 0, paddingLeft: 18 }}>
                        {testPrerequisites.map((item, i) => (
                          <li key={i}>{item}</li>
                        ))}
                      </ul>
                    }
                  />
                </div>
              ),
            },
            {
              key: 'tasks',
              label: '任务',
              children: (
                <div className="space-y-3">
                  <FilterBar
                    actions={
                      <Button icon={<ReloadOutlined />} onClick={() => loadTasks()}>
                        刷新
                      </Button>
                    }
                  />
                  <PagedTable
                    files={tasks}
                    rowKey="id"
                    onPage={(current, size) => loadTasks(current, size)}
                    columns={[
                      { title: 'ID', dataIndex: 'id', width: 100 },
                      { title: '类型', dataIndex: 'taskType', width: 200 },
                      {
                        title: '状态',
                        dataIndex: 'status',
                        width: 100,
                        render: (v: string) => taskStatusTag(v),
                      },
                      { title: '重试', dataIndex: 'retryCount', width: 80 },
                      { title: '错误', dataIndex: 'errorMessage', ellipsis: true },
                      {
                        title: '时间',
                        dataIndex: 'createTime',
                        width: 160,
                        render: (v: string) => (v || '').replace('T', ' ').slice(0, 19),
                      },
                    ]}
                  />
                </div>
              ),
            },
            {
              key: 'audit',
              label: '审计',
              children: (
                <div className="space-y-3">
                  <FilterBar
                    actions={
                      <Button icon={<ReloadOutlined />} onClick={() => loadAudits()}>
                        刷新
                      </Button>
                    }
                  />
                  <PagedTable
                    files={audits}
                    rowKey="id"
                    onPage={(current, size) => loadAudits(current, size)}
                    columns={[
                      { title: '动作', dataIndex: 'action', width: 160 },
                      {
                        title: '主体',
                        width: 140,
                        render: (_: unknown, r: StorageAudit) =>
                          `${r.subjectType || ''}:${r.subjectId || ''}`,
                      },
                      {
                        title: '目标',
                        width: 120,
                        render: (_: unknown, r: StorageAudit) =>
                          `${r.targetType || ''} ${r.targetId || ''}`,
                      },
                      {
                        title: '结果',
                        dataIndex: 'result',
                        width: 90,
                        render: (v: string) => (
                          <Tag color={v === 'OK' ? 'success' : v === 'ERROR' ? 'error' : 'warning'}>
                            {v}
                          </Tag>
                        ),
                      },
                      { title: '详情', dataIndex: 'detail', ellipsis: true },
                      {
                        title: '时间',
                        dataIndex: 'createTime',
                        width: 160,
                        render: (v: string) => (v || '').replace('T', ' ').slice(0, 19),
                      },
                    ]}
                  />
                </div>
              ),
            },
          ]}
        />
      </Card>

      {/* 存储配置弹窗 */}
      <Modal
        title={configModal.editing ? '编辑存储配置' : '新建存储配置'}
        open={configModal.open}
        onOk={submitConfig}
        onCancel={() => setConfigModal({ open: false })}
        okButtonProps={{ disabled: !canEdit }}
        destroyOnClose
        width={760}
        footer={
          isHttpChannelConfig || configModal.editing?.providerType === 'CUSTOM_HTTP'
            ? [
                <Button
                  key="validate"
                  type="link"
                  disabled={httpBusy || !canEditChannel}
                  onClick={() => void httpEditorRef.current?.validateOffline()}
                >
                  离线校验
                </Button>,
                <Button key="cancel" onClick={() => setConfigModal({ open: false })}>
                  取消
                </Button>,
                <Button
                  key="ok"
                  type="primary"
                  loading={httpBusy}
                  disabled={!canEditChannel}
                  onClick={() => void submitConfig()}
                >
                  确定
                </Button>,
              ]
            : undefined
        }
      >
        <Form form={configForm} layout="vertical">
          <p className="text-sm font-medium">基本信息</p>
          <Form.Item
            name="name"
            label="配置名称"
            rules={[{ required: !configModal.editing, message: '请输入名称' }]}
          >
            <Input
              disabled={!!configModal.editing}
              maxLength={128}
              placeholder="如：Telegram 图床 / R2 备份"
            />
          </Form.Item>
          {!configModal.editing && (
            <Form.Item
              name="providerType"
              label="存储类型"
              initialValue={canEdit ? 'TELEGRAM' : 'CUSTOM_HTTP'}
              extra="第三方 HTTP 接口：填好名称后点「确定」进入专用渠道编辑器；其余：TELEGRAM 经 Cloudflare Worker 中转，R2/S3/七牛走 S3 预签名 PUT，COS/OSS 走各自预签名 PUT，又拍云走表单直传"
            >
              <Select options={providerOptions} />
            </Form.Item>
          )}
          {!isObjectConfig && !isHttpChannelConfig ? (
            <p className="mt-1 text-sm font-medium">目标存储（Telegram）</p>
          ) : isHttpChannelConfig ? null : (
            <p className="mt-1 text-sm font-medium">目标存储</p>
          )}
          {!isObjectConfig && !isHttpChannelConfig ? (
            <>
              <Form.Item
                name="chatId"
                label="Telegram Chat ID"
                rules={[{ required: true, message: '请输入频道/群 ID' }]}
                extra="私有频道数字 ID（如 -100xxxxxxxxxx）或 @频道名"
              >
                <Input placeholder="-100xxxxxxxxxx" />
              </Form.Item>
              <Form.Item
                name="workerBaseUrl"
                label="Cloudflare Worker 地址"
                rules={[{ required: true, message: '请输入 Worker 地址' }]}
                extra="如 https://img.example.com 或 astral-storage.xxx.workers.dev"
              >
                <Input placeholder="https://img.example.com" />
              </Form.Item>
            </>
          ) : isHttpChannelConfig ? null : isS3Config ? (
            <>
              <Form.Item
                name={['providerOptions', 'endpoint']}
                label="S3 Endpoint"
                rules={[{ required: true, message: '请输入 S3 端点' }]}
                extra="R2：https://<account_id>.r2.cloudflarestorage.com；AWS：https://s3.<region>.amazonaws.com；七牛：https://s3.cn-east-1.qiniu.com 等"
              >
                <Input placeholder="https://xxxx.r2.cloudflarestorage.com" />
              </Form.Item>
              <Form.Item
                name={['providerOptions', 'bucket']}
                label="Bucket"
                rules={[{ required: true, message: '请输入桶名' }]}
              >
                <Input placeholder="astral-storage" />
              </Form.Item>
              <Form.Item
                name={['providerOptions', 'region']}
                label="Region"
                extra="R2 填 auto；AWS 填如 us-east-1；七牛填自动生成的 S3 区域（如 cn-east-1）"
              >
                <Input placeholder="auto" />
              </Form.Item>
              <Form.Item
                name={['providerOptions', 'accessKeyId']}
                label="Access Key ID"
                rules={[{ required: true, message: '请输入 Access Key ID' }]}
              >
                <Input placeholder="Access Key ID" />
              </Form.Item>
              <Form.Item
                name={['providerOptions', 'secretAccessKey']}
                label="Secret Access Key"
                rules={
                  configModal.editing
                    ? []
                    : [{ required: true, message: '请输入 Secret Access Key' }]
                }
                extra={
                  configModal.editing ? '已保存（返回时打码为 ******）；留空表示不修改' : undefined
                }
              >
                <Input.Password placeholder="Secret Access Key" />
              </Form.Item>
              <Form.Item
                name={['providerOptions', 'publicBaseUrl']}
                label="公开访问域名（永久链接用，可选）"
                extra="桶的公开访问域名；填写后公开文件可生成永久直链（如 https://cdn.example.com/bucket）"
              >
                <Input placeholder="https://cdn.example.com" />
              </Form.Item>
            </>
          ) : isCOSConfig ? (
            <>
              <Form.Item
                name={['providerOptions', 'bucket']}
                label="Bucket"
                rules={[{ required: true, message: '请输入桶名' }]}
              >
                <Input placeholder="astral-1250000000" />
              </Form.Item>
              <Form.Item
                name={['providerOptions', 'region']}
                label="Region（地域）"
                rules={[{ required: true, message: '请输入地域' }]}
                extra="如 ap-guangzhou / ap-shanghai / ap-beijing"
              >
                <Input placeholder="ap-guangzhou" />
              </Form.Item>
              <Form.Item
                name={['providerOptions', 'secretId']}
                label="SecretId"
                rules={[{ required: true, message: '请输入 SecretId' }]}
              >
                <Input placeholder="SecretId" />
              </Form.Item>
              <Form.Item
                name={['providerOptions', 'secretKey']}
                label="SecretKey"
                rules={configModal.editing ? [] : [{ required: true, message: '请输入 SecretKey' }]}
                extra={
                  configModal.editing ? '已保存（返回时打码为 ******）；留空表示不修改' : undefined
                }
              >
                <Input.Password placeholder="SecretKey" />
              </Form.Item>
              <Form.Item
                name={['providerOptions', 'publicBaseUrl']}
                label="公开访问域名（永久链接用，可选）"
                extra="如 https://cdn.example.com（自定义 CDN 域名或默认桶域名均可）"
              >
                <Input placeholder="https://cdn.example.com" />
              </Form.Item>
            </>
          ) : isOSSConfig ? (
            <>
              <Form.Item
                name={['providerOptions', 'bucket']}
                label="Bucket"
                rules={[{ required: true, message: '请输入桶名' }]}
              >
                <Input placeholder="astral-storage" />
              </Form.Item>
              <Form.Item
                name={['providerOptions', 'endpoint']}
                label="Endpoint（地域域名）"
                rules={[{ required: true, message: '请输入 Endpoint' }]}
                extra="如 oss-cn-hangzhou.aliyuncs.com（不带 bucket 前缀、不带 http(s)://）"
              >
                <Input placeholder="oss-cn-hangzhou.aliyuncs.com" />
              </Form.Item>
              <Form.Item
                name={['providerOptions', 'accessKeyId']}
                label="AccessKeyId"
                rules={[{ required: true, message: '请输入 AccessKeyId' }]}
              >
                <Input placeholder="AccessKeyId" />
              </Form.Item>
              <Form.Item
                name={['providerOptions', 'accessKeySecret']}
                label="AccessKeySecret"
                rules={
                  configModal.editing ? [] : [{ required: true, message: '请输入 AccessKeySecret' }]
                }
                extra={
                  configModal.editing ? '已保存（返回时打码为 ******）；留空表示不修改' : undefined
                }
              >
                <Input.Password placeholder="AccessKeySecret" />
              </Form.Item>
              <Form.Item
                name={['providerOptions', 'publicBaseUrl']}
                label="公开访问域名（永久链接用，可选）"
                extra="如 https://cdn.example.com（绑定自定义域名或桶默认外网域名）"
              >
                <Input placeholder="https://cdn.example.com" />
              </Form.Item>
            </>
          ) : (
            <>
              <Form.Item
                name={['providerOptions', 'bucket']}
                label="服务名（Bucket）"
                rules={[{ required: true, message: '请输入服务名' }]}
              >
                <Input placeholder="astral-storage" />
              </Form.Item>
              <Form.Item
                name={['providerOptions', 'operator']}
                label="操作员名"
                rules={[{ required: true, message: '请输入操作员名' }]}
              >
                <Input placeholder="operator" />
              </Form.Item>
              <Form.Item
                name={['providerOptions', 'password']}
                label="操作员密码"
                rules={configModal.editing ? [] : [{ required: true, message: '请输入操作员密码' }]}
                extra={
                  configModal.editing ? '已保存（返回时打码为 ******）；留空表示不修改' : undefined
                }
              >
                <Input.Password placeholder="操作员密码" />
              </Form.Item>
              <Form.Item
                name={['providerOptions', 'endpoint']}
                label="API 线路域名（可选）"
                extra="默认 v0.api.upyun.com（智能选路）；电信 v1/联通 v2/移动 v3.api.upyun.com"
              >
                <Input placeholder="v0.api.upyun.com" />
              </Form.Item>
              <Form.Item
                name={['providerOptions', 'publicBaseUrl']}
                label="空间绑定域名（下载/永久链接用）"
                rules={[{ required: true, message: '请输入空间绑定的加速域名' }]}
                extra="又拍云 REST 不支持 URL 预签名，下载走该域名；配置 Token 防盗链后签名 _upt"
              >
                <Input placeholder="https://cdn.example.com" />
              </Form.Item>
              <Form.Item
                name={['providerOptions', 'tokenKey']}
                label="Token 防盗链密钥（可选）"
                extra="又拍云控制台「防盗链 → Token 防盗链」中设置的密钥；未开启防盗链可留空。编辑时已保存则留空保持不变"
              >
                <Input.Password placeholder="token 密钥" />
              </Form.Item>
            </>
          )}
          {isHttpChannelConfig ? (
            <div style={{ marginTop: 8 }}>
              <HttpChannelEditorFields
                ref={httpEditorRef}
                mode={configModal.editing?.id ? 'edit' : 'create'}
                channel={httpChannelDetail}
                approvedTargets={httpApprovedTargets}
                onBusyChange={setHttpBusy}
              />
            </div>
          ) : (
            <>
              <p className="mt-1 text-sm font-medium">其他设置</p>
              <Form.Item name="maxFileSize" label="单文件上限（字节，留空使用全局 20MiB）">
                <InputNumber
                  style={{ width: '100%' }}
                  min={1}
                  max={isObjectConfig ? 5368709120 : 20971520}
                />
              </Form.Item>
              <Form.Item name="remark" label="备注">
                <Input maxLength={256} />
              </Form.Item>
            </>
          )}
        </Form>
      </Modal>

      {/* 文件夹弹窗 */}
      <Modal
        title={folderModal.editing ? '编辑文件夹' : '新建文件夹'}
        open={folderModal.open}
        onOk={submitFolder}
        onCancel={() => setFolderModal({ open: false, bindings: [], strategy: 'FAILOVER' })}
        okButtonProps={{ disabled: !canEdit }}
        destroyOnClose
        width={760}
      >
        <Form form={folderForm} layout="vertical">
          <p className="text-sm font-medium">基本信息</p>
          <Form.Item name="parentId" label="父文件夹（可选）">
            <Select
              allowClear
              options={folders.map((f) => ({ label: f.folderPath || f.folderName, value: f.id! }))}
            />
          </Form.Item>
          <Form.Item
            name="folderName"
            label="名称"
            rules={[{ required: !folderModal.editing, message: '请输入名称' }]}
          >
            <Input maxLength={128} />
          </Form.Item>
          <Form.Item name="visibility" label="默认可见性" initialValue="PRIVATE">
            <Select options={dict['storage_visibility'] || []} />
          </Form.Item>
          <p className="mt-1 text-sm font-medium">存储绑定与调度</p>
          <Form.Item label="存储绑定（可多选；空 = 禁止上传，不再回落默认配置）">
            <Space direction="vertical" style={{ width: '100%' }} size={8}>
              {folderModal.bindings.length === 0 && (
                <span style={{ color: '#f56c6c' }}>未绑定任何存储配置：该文件夹将禁止上传</span>
              )}
              {folderModal.bindings.map((b, idx) => (
                <Space key={idx} wrap>
                  <Select
                    style={{ width: 260 }}
                    placeholder="选择存储配置"
                    value={b.storageConfigId}
                    options={configs.map((c) => ({
                      label: `${c.name}${c.status === 'ENABLED' ? '' : '（已禁用）'}`,
                      value: c.id!,
                      // 已绑定的禁用配置必须仍可见可移除；新选择只允许启用中的配置
                      disabled: c.status !== 'ENABLED' && b.storageConfigId !== c.id,
                    }))}
                    onChange={(v) =>
                      setFolderModal((m) => ({
                        ...m,
                        bindings: m.bindings.map((row, i) =>
                          i === idx ? { ...row, storageConfigId: v } : row,
                        ),
                      }))
                    }
                  />
                  <InputNumber
                    style={{ width: 140 }}
                    min={0}
                    max={10000}
                    placeholder="优先级"
                    value={b.priority ?? 100}
                    onChange={(v) =>
                      setFolderModal((m) => ({
                        ...m,
                        bindings: m.bindings.map((row, i) =>
                          i === idx ? { ...row, priority: v ?? 100 } : row,
                        ),
                      }))
                    }
                  />
                  <Button
                    size="small"
                    danger
                    icon={<DeleteOutlined />}
                    onClick={() =>
                      setFolderModal((m) => ({
                        ...m,
                        bindings: m.bindings.filter((_, i) => i !== idx),
                      }))
                    }
                  />
                </Space>
              ))}
              <Button
                type="dashed"
                size="small"
                icon={<PlusOutlined />}
                disabled={folderModal.bindings.length >= 16}
                onClick={() =>
                  setFolderModal((m) => ({
                    ...m,
                    bindings: [...m.bindings, { storageConfigId: undefined, priority: 100 }],
                  }))
                }
              >
                添加绑定
              </Button>
              <span style={{ color: '#909399' }}>
                优先级 0–10000，数值小者优先；FAILOVER 按优先级故障转移，ROUND_ROBIN 同层轮询。
              </span>
            </Space>
          </Form.Item>
          <Form.Item label="调度策略">
            <Select
              style={{ width: 260 }}
              value={folderModal.strategy}
              options={dict['storage_target_strategy'] || []}
              onChange={(v) => setFolderModal((m) => ({ ...m, strategy: v }))}
            />
          </Form.Item>
          <Form.Item
            name="policyEnabled"
            label="上传策略"
            valuePropName="checked"
            initialValue={false}
          >
            <Switch checkedChildren="启用" unCheckedChildren="停用" />
          </Form.Item>
          <Form.Item
            noStyle
            shouldUpdate={(a: FolderFormValues, b: FolderFormValues) =>
              a.policyEnabled !== b.policyEnabled
            }
          >
            {({ getFieldValue }) =>
              getFieldValue('policyEnabled') ? (
                <>
                  <Alert
                    type="info"
                    showIcon
                    style={{ marginBottom: 12 }}
                    message="策略约束浏览器直传的全部环节（签发/存储/登记校验）；拥有管理员权限的用户上传时跳过策略限制。未填写的项表示不限制。"
                  />
                  <Form.Item
                    name="policyRequireLogin"
                    label="要求登录"
                    valuePropName="checked"
                    initialValue={true}
                  >
                    <Switch />
                  </Form.Item>
                  <Form.Item name="policyMaxSizeMb" label="单文件大小上限（MB）">
                    <InputNumber min={0.01} max={20} step={0.5} style={{ width: '100%' }} />
                  </Form.Item>
                  <Form.Item name="policyMinSizeBytes" label="单文件大小下限（字节）">
                    <InputNumber min={0} precision={0} style={{ width: '100%' }} />
                  </Form.Item>
                  <Form.Item name="policyDailyLimit" label="每用户每日上传次数上限">
                    <InputNumber min={1} precision={0} style={{ width: '100%' }} />
                  </Form.Item>
                  <Form.Item name="policyMimes" label="允许的 MIME 类型（留空 = 不限制）">
                    <Select
                      mode="tags"
                      allowClear
                      tokenSeparators={[',']}
                      placeholder="如 image/png"
                      options={[
                        { label: 'image/png', value: 'image/png' },
                        { label: 'image/jpeg', value: 'image/jpeg' },
                        { label: 'image/webp', value: 'image/webp' },
                      ]}
                    />
                  </Form.Item>
                  <Form.Item name="policyExtensions" label="允许的扩展名（留空 = 不限制）">
                    <Select
                      mode="tags"
                      allowClear
                      tokenSeparators={[',']}
                      placeholder="如 png"
                      options={[
                        { label: 'png', value: 'png' },
                        { label: 'jpg', value: 'jpg' },
                        { label: 'jpeg', value: 'jpeg' },
                        { label: 'webp', value: 'webp' },
                      ]}
                    />
                  </Form.Item>
                  <Form.Item
                    name="policyForceVisibility"
                    label="强制可见性（留空 = 沿用文件夹默认可见性）"
                  >
                    <Select allowClear options={dict['storage_visibility'] || []} />
                  </Form.Item>
                  <Form.Item name="policyVerifyContent" label="登记后内容校验" initialValue="none">
                    <Select allowClear options={dict['storage_verify_content'] || []} />
                  </Form.Item>
                  <Form.Item name="policyMaxPixels" label="图片最大像素数（仅全量校验时检查）">
                    <InputNumber min={0} precision={0} style={{ width: '100%' }} />
                  </Form.Item>
                </>
              ) : null
            }
          </Form.Item>
        </Form>
      </Modal>

      {/* 授权弹窗 */}
      <Modal
        title={`文件夹授权：${permModal.folder?.folderName || ''}`}
        open={permModal.open}
        onOk={savePermissions}
        onCancel={() => setPermModal({ open: false, rows: [] })}
        width={760}
        okText="保存授权"
        okButtonProps={{ disabled: !canEdit }}
      >
        <p style={{ color: '#909399' }}>
          授权为全量替换；权限集合：READ / UPLOAD / UPDATE / DELETE /
          MANAGE。可选「限定配置」必须是该文件夹绑定的子集，留空 =
          跟随文件夹绑定；每个限定配置可单独设置优先级。 当前仅支持 USER（用户）主体。
        </p>
        {permModal.rows.map((row, idx) => (
          <div
            key={row.key ?? `row-${idx}`}
            style={{
              border: '1px solid #f0f0f0',
              borderRadius: 6,
              padding: 8,
              marginBottom: 8,
            }}
          >
            <Space style={{ display: 'flex' }}>
              <Select
                style={{ width: 110 }}
                value={row.subjectType}
                options={subjectTypeOptions}
                onChange={(v) =>
                  setPermModal((m) => ({
                    ...m,
                    rows: m.rows.map((r, i) => (i === idx ? { ...r, subjectType: v } : r)),
                  }))
                }
              />
              <Input
                style={{ width: 160 }}
                placeholder="主体 ID（用户ID）"
                value={row.subjectId}
                onChange={(e) =>
                  setPermModal((m) => ({
                    ...m,
                    rows: m.rows.map((r, i) =>
                      i === idx ? { ...r, subjectId: e.target.value } : r,
                    ),
                  }))
                }
              />
              <Select
                style={{ width: 220 }}
                mode="multiple"
                placeholder="权限"
                options={dict['storage_permission'] || []}
                value={(row.permissions || '').split(',').filter(Boolean)}
                onChange={(v) =>
                  setPermModal((m) => ({
                    ...m,
                    rows: m.rows.map((r, i) =>
                      i === idx ? { ...r, permissions: v.join(',') } : r,
                    ),
                  }))
                }
              />
              <Button
                danger
                disabled={!canEdit}
                onClick={() =>
                  setPermModal((m) => ({ ...m, rows: m.rows.filter((_, i) => i !== idx) }))
                }
              >
                移除
              </Button>
            </Space>
            <Space style={{ display: 'flex', marginTop: 4 }} direction="vertical">
              <Select
                style={{ width: 320 }}
                mode="multiple"
                placeholder="限定配置（可选，必须是文件夹绑定的子集）"
                options={(permModal.folder?.configs || []).map((c) => ({
                  label: `${c.configName || `#${c.configId}`}（文件夹优先级 ${c.priority}）`,
                  value: c.configId,
                }))}
                value={(row.permConfigs || []).map((binding) => binding.storageConfigId)}
                disabled={!permModal.folder?.configs?.length}
                onChange={(ids: number[]) =>
                  setPermModal((m) => ({
                    ...m,
                    rows: m.rows.map((r, i) => {
                      if (i !== idx) return r;
                      const previous = r.permConfigs || [];
                      return {
                        ...r,
                        permConfigs: ids.map((id) => {
                          const existing = previous.find((item) => item.storageConfigId === id);
                          const folderBinding = m.folder?.configs?.find((c) => c.configId === id);
                          return (
                            existing || {
                              storageConfigId: id,
                              priority: folderBinding?.priority ?? 100,
                            }
                          );
                        }),
                      };
                    }),
                  }))
                }
              />
              {(row.permConfigs || []).map((binding) => {
                const config = permModal.folder?.configs?.find(
                  (item) => item.configId === binding.storageConfigId,
                );
                return (
                  <Space key={binding.storageConfigId}>
                    <span>{config?.configName || `#${binding.storageConfigId}`}</span>
                    <InputNumber
                      style={{ width: 140 }}
                      min={0}
                      max={10000}
                      placeholder="优先级"
                      value={binding.priority}
                      onChange={(value) =>
                        setPermModal((m) => ({
                          ...m,
                          rows: m.rows.map((r, i) =>
                            i === idx
                              ? {
                                  ...r,
                                  permConfigs: (r.permConfigs || []).map((item) =>
                                    item.storageConfigId === binding.storageConfigId
                                      ? { ...item, priority: value ?? 100 }
                                      : item,
                                  ),
                                }
                              : r,
                          ),
                        }))
                      }
                    />
                  </Space>
                );
              })}
            </Space>
            {!permModal.folder?.configs?.length && (
              <p style={{ color: '#f56c6c', marginBottom: 0 }}>
                该文件夹未绑定存储配置，无法限定授权配置
              </p>
            )}
          </div>
        ))}
        <Button
          type="dashed"
          block
          icon={<PlusOutlined />}
          disabled={!canEdit}
          onClick={() =>
            setPermModal((m) => ({
              ...m,
              rows: [
                ...m.rows,
                {
                  subjectType: 'USER',
                  subjectId: '',
                  permissions: 'READ,UPLOAD',
                  permConfigs: [],
                },
              ],
            }))
          }
        >
          添加授权
        </Button>
      </Modal>
    </div>
  );
}

/** 简单分页表格封装 */
function PagedTable<T extends object>({
  files,
  columns,
  onPage,
  rowKey,
}: {
  files: StoragePageResult<T> | T[];
  columns: DataTableColumn<T>[];
  onPage?: (current: number, size: number) => void;
  rowKey: string;
}) {
  const plain = Array.isArray(files);
  const records = plain ? files : files.records || [];
  return (
    <ResizableTable<T>
      resizeKey={`storage-${rowKey}`}
      rowKey={rowKey}
      columns={columns}
      dataSource={records}
      pagination={
        plain
          ? {
              hideOnSinglePage: true,
            }
          : {
              current: files.current,
              pageSize: files.size,
              total: files.total,
              showSizeChanger: true,
              onChange: (current: number, size: number) => onPage?.(current, size),
            }
      }
    />
  );
}
