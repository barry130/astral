'use client';

import { useEffect, useMemo, useState } from 'react';
import {
  Alert, Button, Card, Form, Input, InputNumber, Modal, Popconfirm, Select, Space, Table, Tabs,
  Tag, Tooltip, Upload, message,
} from 'antd';
import {
  CloudUploadOutlined, DeleteOutlined, ExperimentOutlined, PlusOutlined,
  ReloadOutlined, StarOutlined, LinkOutlined,
} from '@ant-design/icons';
import { storageApi } from '@/api/storage';
import { fetchDictOptions, DictOption } from '@/api/dict';
import type { StorageConfig, StorageFile, StorageFolder, StoragePageResult, StorageTask, StorageAudit, S3ProviderOptions } from '@/api/storage';

function fmtBytes(bytes?: number): string {
  if (!bytes && bytes !== 0) return '-';
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(2)} MB`;
}

/** S3 SigV4 家族（含七牛 S3 网关）：预签名 PUT 直传 + secretAccessKey 密钥 */
const S3_FAMILY = ['R2', 'S3_COMPATIBLE', 'QINIU'];
/** 需要 provider_options 的对象存储 Provider 集合 */
const OBJECT_PROVIDERS = ['R2', 'S3_COMPATIBLE', 'QINIU', 'COS', 'OSS', 'UPYUN'];

/** providerOptions JSON 字符串 → 对象（解析失败返回空对象） */
function parseOptions(json?: string | null): S3ProviderOptions {
  if (!json) return {};
  try { return JSON.parse(json) as S3ProviderOptions; } catch { return {}; }
}

/** 状态 → Tag 颜色（展示配色属前端逻辑，保留映射；文案标签走字典） */
const STATUS_COLOR: Record<string, string> = {
  ENABLED: 'success', AVAILABLE: 'success', SUCCESS: 'success',
  PENDING: 'processing', DELETING: 'processing', RUNNING: 'processing',
};

export default function StoragePage() {
  // ==================== 数据字典（枚举值走字典，见 AGENTS.md §3） ====================
  const [dict, setDict] = useState<Record<string, DictOption[]>>({});
  const dictLabel = (code: string, value?: string) =>
    dict[code]?.find((o) => o.value === value)?.label || value || '-';

  // ==================== 存储配置 ====================
  const [configs, setConfigs] = useState<StorageConfig[]>([]);
  const [configModal, setConfigModal] = useState<{ open: boolean; editing?: StorageConfig }>({ open: false });
  const [configForm] = Form.useForm();

  const loadConfigs = () => {
    storageApi.listConfigs().then((res) => {
      if (res.code === 200) setConfigs(res.data || []);
    }).catch((e) => message.error(e.message));
  };

  const submitConfig = async () => {
    const values = await configForm.validateFields();
    try {
      if (configModal.editing?.id) {
        await storageApi.updateConfig(configModal.editing.id, values);
      } else {
        await storageApi.createConfig(values);
      }
      message.success('已保存');
      setConfigModal({ open: false });
      loadConfigs();
    } catch (e: any) {
      message.error(e.message);
    }
  };

  /** 弹窗当前编辑的 Provider 类型（未选时按 TELEGRAM 展示） */
  const configProvider = Form.useWatch('providerType', configForm) || configModal.editing?.providerType || 'TELEGRAM';
  const isS3Config = S3_FAMILY.includes(configProvider);
  const isCOSConfig = configProvider === 'COS';
  const isOSSConfig = configProvider === 'OSS';
  const isUpyunConfig = configProvider === 'UPYUN';
  const isObjectConfig = OBJECT_PROVIDERS.includes(configProvider);

  const testConfig = async (config: StorageConfig) => {
    try {
      const res = await storageApi.testConfig(config.id!);
      if (res.code === 200) {
        if (res.data.healthStatus === 'UP') message.success(res.data.message);
        else message.error(res.data.message);
        loadConfigs();
      }
    } catch (e: any) {
      message.error(e.message);
    }
  };

  // ==================== 文件夹 ====================
  const [folders, setFolders] = useState<StorageFolder[]>([]);
  const [folderModal, setFolderModal] = useState<{ open: boolean; editing?: StorageFolder }>({ open: false });
  const [folderForm] = Form.useForm();
  const [permModal, setPermModal] = useState<{ open: boolean; folder?: StorageFolder; rows: any[] }>({ open: false, rows: [] });

  const loadFolders = () => {
    storageApi.listFolders().then((res) => {
      if (res.code === 200) setFolders(res.data || []);
    }).catch((e) => message.error(e.message));
  };

  const submitFolder = async () => {
    const values = await folderForm.validateFields();
    try {
      if (folderModal.editing?.id) {
        await storageApi.updateFolder(folderModal.editing.id, values);
      } else {
        await storageApi.createFolder(values);
      }
      message.success('已保存');
      setFolderModal({ open: false });
      loadFolders();
    } catch (e: any) {
      message.error(e.message);
    }
  };

  const openPermissions = async (folder: StorageFolder) => {
    try {
      const res = await storageApi.getFolderPermissions(folder.id!);
      if (res.code === 200) {
        setPermModal({ open: true, folder, rows: (res.data || []).map((r: any) => ({ ...r, key: r.id })) });
      }
    } catch (e: any) {
      message.error(e.message);
    }
  };

  const savePermissions = async () => {
    const rows = permModal.rows.map((r) => ({ subjectType: r.subjectType, subjectId: r.subjectId, permissions: r.permissions }));
    try {
      await storageApi.saveFolderPermissions(permModal.folder!.id!, rows);
      message.success('授权已保存');
      setPermModal({ open: false, rows: [] });
    } catch (e: any) {
      message.error(e.message);
    }
  };

  // ==================== 文件 ====================
  const [files, setFiles] = useState<StoragePageResult<StorageFile>>({ records: [], total: 0, size: 20, current: 1 });
  const [fileQuery, setFileQuery] = useState({ current: 1, size: 20, folderId: undefined as number | undefined, keyword: '' });
  const [uploadFolderId, setUploadFolderId] = useState<number | undefined>();
  const [uploading, setUploading] = useState(false);

  const loadFiles = (query = fileQuery) => {
    storageApi.pageFiles(query).then((res) => {
      if (res.code === 200) setFiles(res.data);
    }).catch((e) => message.error(e.message));
  };

  const copyDownloadUrl = async (file: StorageFile) => {
    try {
      const res = await storageApi.downloadUrl(file.publicId!);
      if (res.code === 200) {
        await navigator.clipboard.writeText(res.data.url);
        message.success(`下载地址已复制（${new Date(res.data.expiresAt * 1000).toLocaleTimeString()} 过期）`);
      }
    } catch (e: any) {
      message.error(e.message || '生成下载地址失败');
    }
  };

  const copyPermanentUrl = async (file: StorageFile) => {
    try {
      const res = await storageApi.permanentUrl(file.publicId!);
      if (res.code === 200) {
        await navigator.clipboard.writeText(res.data.url);
        message.success('永久公开链接已复制（仅公开文件可用）');
      }
    } catch (e: any) {
      message.error(e.message || '生成永久链接失败');
    }
  };

  /** 统一文件直传：TELEGRAM 走 Worker（POST multipart），S3 系/COS/OSS 走预签名 PUT，UPYUN 走表单 API */
  const uploadFileDirect = async (folderId: number, file: File): Promise<string> => {
    const ticketRes = await storageApi.uploadTicket({
      folderId,
      fileName: file.name,
      contentType: file.type || 'application/octet-stream',
      sizeBytes: file.size,
    });
    if (ticketRes.code !== 200) throw new Error(ticketRes.message);
    const ticket = ticketRes.data;
    let publicId: string;
    if (ticket.method === 'PUT') {
      // S3 系（R2/S3/七牛）/COS/OSS：浏览器直传对象存储，完成后回 Astral 登记元数据
      const putResp = await fetch(ticket.uploadUrl, { method: 'PUT', body: file });
      if (!putResp.ok) throw new Error(`对象存储返回 ${putResp.status}`);
      const regRes = await storageApi.registerUpload(ticket.uploadId);
      if (regRes.code !== 200) throw new Error(regRes.message);
      publicId = regRes.data.publicId;
    } else if (ticket.form) {
      // UPYUN：表单 API 直传（policy/authorization + file），完成后回 Astral 登记元数据
      const form = new FormData();
      form.append('policy', ticket.form.policy);
      form.append('authorization', ticket.form.authorization);
      form.append(ticket.formField || 'file', file);
      const resp = await fetch(ticket.uploadUrl, { method: 'POST', body: form });
      if (!resp.ok) throw new Error(`又拍云返回 ${resp.status}`);
      const regRes = await storageApi.registerUpload(ticket.uploadId);
      if (regRes.code !== 200) throw new Error(regRes.message);
      publicId = regRes.data.publicId;
    } else {
      // TELEGRAM：Worker 转存 Telegram 并回调登记
      const form = new FormData();
      form.append(ticket.formField || 'file', file);
      const resp = await fetch(ticket.uploadUrl, { method: ticket.method, body: form });
      const json = await resp.json().catch(() => ({}));
      if (!resp.ok || !json.ok) {
        throw new Error(json.message || `Worker 返回 ${resp.status}`);
      }
      publicId = json.publicId;
    }
    return publicId;
  };

  /** 浏览器直传（Astral 只签发凭证；文件正文不经过 Astral） */
  const customUploadRequest = async (options: any) => {
    if (!uploadFolderId) {
      message.warning('请先选择上传文件夹');
      options.onError?.(new Error('no folder'));
      return;
    }
    setUploading(true);
    try {
      const publicId = await uploadFileDirect(uploadFolderId, options.file);
      message.success(`上传成功：${publicId}`);
      options.onSuccess?.({ publicId });
      loadFiles({ ...fileQuery, current: 1 });
    } catch (e: any) {
      message.error(e.message || '上传失败');
      options.onError?.(e);
    } finally {
      setUploading(false);
    }
  };

  // ==================== 上传测试 ====================
  const [testFolderId, setTestFolderId] = useState<number | undefined>();
  const [testRunning, setTestRunning] = useState(false);
  /** 四步链路状态：凭证签发 / 直传 / 登记 / 下载验证 */
  const [testSteps, setTestSteps] = useState<{ title: string; state: 'wait' | 'run' | 'ok' | 'fail'; detail?: string }[]>([
    { title: '向 Astral 换取上传凭证', state: 'wait' },
    { title: '携带凭证直传 Cloudflare Worker', state: 'wait' },
    { title: 'Worker 转存 Telegram 并回调登记', state: 'wait' },
    { title: '签发下载地址并验证可访问', state: 'wait' },
  ]);
  const setStep = (idx: number, state: 'run' | 'ok' | 'fail', detail?: string) => {
    setTestSteps((steps) => steps.map((s, i) => (i === idx ? { ...s, state, detail } : s)));
  };
  const resetTest = () => setTestSteps((steps) => steps.map((s) => ({ ...s, state: 'wait', detail: undefined })));

  /** 测试文件夹绑定的存储配置（未绑定则取默认配置）；随选择联动 */
  const activeTestConfig = useMemo(() => {
    const folder = folders.find((f) => f.id === testFolderId);
    if (folder?.storageConfigId) return configs.find((c) => c.id === folder.storageConfigId);
    return configs.find((c) => c.isDefault === 1) || undefined;
  }, [testFolderId, folders, configs]);
  const testProvider = activeTestConfig?.providerType || 'TELEGRAM';

  /** 按 Provider 生成四步文案（测试开始时固化，避免运行中切换文件夹导致步骤错位） */
  const buildTestSteps = (provider: string) => {
    const isTelegram = provider === 'TELEGRAM';
    const isUpyun = provider === 'UPYUN';
    const providerName = isTelegram ? 'Telegram' : isUpyun ? '又拍云' : undefined;
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
    if (p === 'TELEGRAM') return '端到端测试：自动生成一张测试 PNG，完整走「凭证签发 → Worker 直传 → Telegram 回调登记 → 下载验证」四个环节。测试文件会真实进入 Telegram 频道，验证后可在文件管理中删除。';
    if (p === 'UPYUN') return '端到端测试：自动生成一张测试 PNG，完整走「凭证签发 → 表单直传又拍云 → HEAD 确认登记 → 下载验证」四个环节，验证又拍云通道（操作员凭证 / save-key / 空间域名）是否可用。测试对象会真实写入又拍云空间，验证后可删除。';
    if (p === 'COS') return '端到端测试：自动生成一张测试 PNG，完整走「凭证签发 → COS 预签名 PUT 直传 → HEAD 确认登记 → 下载验证」四个环节，验证腾讯云 COS 通道（SecretId/Key / 桶 / 地域）是否可用。测试对象会真实写入 COS 桶，验证后可删除。';
    if (p === 'OSS') return '端到端测试：自动生成一张测试 PNG，完整走「凭证签发 → OSS URL 签名 PUT 直传 → HEAD 确认登记 → 下载验证」四个环节，验证阿里云 OSS 通道（AccessKey / 桶 / Endpoint）是否可用。测试对象会真实写入 OSS 桶，验证后可删除。';
    if (p === 'QINIU') return '端到端测试：自动生成一张测试 PNG，完整走「凭证签发 → S3 SigV4 预签名 PUT 直传 → HEAD 确认登记 → 下载验证」四个环节，验证七牛 S3 网关通道（AK/SK / 空间 / S3 端点）是否可用。测试对象会真实写入七牛空间，验证后可删除。';
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
      return [...common, `浏览器直传需要 CORS：允许 PUT（上传域）与 GET（下载域）来自管理端域名，暴露 ETag 头`];
    }
    if (p === 'R2' || p === 'S3_COMPATIBLE' || p === 'QINIU') {
      return [...common, `桶 CORS 允许来自管理端域名的 PUT/GET（R2 控制台 → Settings → CORS；七牛空间 → S3 网关 CORS）`];
    }
    if (p === 'UPYUN') {
      return [...common, '空间绑定域名（publicBaseUrl）可公开访问；如开启 Token 防盗链，tokenKey 需与控制台一致；表单上传无 CORS 要求'];
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
    const steps = buildTestSteps(provider);
    setTestSteps(steps);
    setTestRunning(true);
    try {
      // 生成一张 2x2 的 PNG 测试图（合法 PNG 字节，无需画布依赖）
      const pngBytes = Uint8Array.from(atob(
        'iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAYAAACZgbYnAAAAEklEQVR4nGP8z8DAwMDAxIAEAAAAAP//AwB0EQIAAAAA//8DAP0BBQMBAAA=',
      ), (c) => c.charCodeAt(0));
      const testFile = new File([pngBytes], 'astral-upload-test.png', { type: 'image/png' });

      // 1. 凭证签发
      setStep(0, 'run');
      const ticketRes = await storageApi.uploadTicket({
        folderId: testFolderId,
        fileName: testFile.name,
        contentType: testFile.type,
        sizeBytes: testFile.size,
      });
      if (ticketRes.code !== 200) throw new Error(ticketRes.message);
      const isTelegram = ticketRes.data.method !== 'PUT' && !ticketRes.data.form;
      setStep(0, 'ok', `凭证已签发（${isTelegram ? 'TELEGRAM/Worker' : ticketRes.data.form ? 'UPYUN 表单直传' : '预签名 PUT'}，有效期至 ${new Date(ticketRes.data.expiresAt * 1000).toLocaleTimeString()}）`);

      // 2. 直传（TELEGRAM → Worker；对象存储系 → 各 Provider 直传）
      setStep(1, 'run');
      const publicId: string = await uploadFileDirect(testFolderId, testFile);
      setStep(1, 'ok', isTelegram ? '文件已送达 Worker' : '对象已写入存储桶');

      // 3. 回执登记（成功响应即表示登记已完成；此处刷新列表核对落库）
      setStep(2, 'run');
      const pageRes = await storageApi.pageFiles({ current: 1, size: 50, folderId: testFolderId });
      const registered = (pageRes.code === 200 ? pageRes.data.records || [] : [])
        .some((f: StorageFile) => f.publicId === publicId);
      if (!registered) throw new Error('文件未在列表中出现，登记可能失败');
      setStep(2, 'ok', `已登记：${publicId.slice(0, 12)}...`);

      // 4. 下载验证
      setStep(3, 'run');
      const dlRes = await storageApi.downloadUrl(publicId);
      if (dlRes.code !== 200) throw new Error(dlRes.message);
      const dlResp = await fetch(dlRes.data.url);
      if (!dlResp.ok) throw new Error(`下载验证失败：HTTP ${dlResp.status}`);
      const blob = await dlResp.blob();
      if (blob.size === 0) throw new Error('下载内容为空');
      setStep(3, 'ok', `下载验证通过（${blob.size} 字节，地址有效）`);
      message.success(`上传测试通过：${isTelegram ? 'Telegram' : provider} 通道完整链路正常`);
    } catch (e: any) {
      setTestSteps((steps) => {
        const failedIdx = steps.findIndex((s) => s.state === 'run');
        return failedIdx >= 0
          ? steps.map((s, i) => (i === failedIdx ? { ...s, state: 'fail' as const, detail: e.message } : s))
          : steps;
      });
      message.error(`测试失败：${e.message}`);
    } finally {
      setTestRunning(false);
    }
  };

  // ==================== 任务与审计 ====================
  const [tasks, setTasks] = useState<StoragePageResult<StorageTask>>({ records: [], total: 0, size: 20, current: 1 });
  const [audits, setAudits] = useState<StoragePageResult<StorageAudit>>({ records: [], total: 0, size: 20, current: 1 });

  const loadTasks = () => {
    storageApi.pageTasks({ current: 1, size: 20 }).then((res) => {
      if (res.code === 200) setTasks(res.data);
    }).catch((e) => message.error(e.message));
  };
  const loadAudits = () => {
    storageApi.pageAudit({ current: 1, size: 20 }).then((res) => {
      if (res.code === 200) setAudits(res.data);
    }).catch((e) => message.error(e.message));
  };

  useEffect(() => {
    loadConfigs();
    loadFolders();
    loadFiles();
    // 枚举选项走数据字典（storage_*，V5__storage_dict.sql）；失败时降级为字典原值展示
    fetchDictOptions([
      'storage_provider_type', 'storage_status', 'storage_file_status',
      'storage_task_status', 'storage_visibility', 'storage_permission',
    ]).then(setDict).catch(() => {});
  }, []);

  useEffect(() => {
    loadFiles();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [fileQuery.current, fileQuery.size, fileQuery.folderId, fileQuery.keyword]);

  const folderName = (id?: number) => folders.find((f) => f.id === id)?.folderName || '-';

  /** Provider 展示配色（纯前端观感，保留映射；文案标签走 storage_provider_type 字典） */
  const PROVIDER_COLOR: Record<string, string> = {
    R2: 'blue', S3_COMPATIBLE: 'purple', QINIU: 'geekblue',
    COS: 'cyan', OSS: 'orange', UPYUN: 'green', TELEGRAM: 'orange',
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
  const fileStatusTag = (status?: string) => (
    <Tag color={STATUS_COLOR[status || ''] || 'default'}>{dictLabel('storage_file_status', status)}</Tag>
  );
  const taskStatusTag = (status?: string) => (
    <Tag color={STATUS_COLOR[status || ''] || 'default'}>{dictLabel('storage_task_status', status)}</Tag>
  );

  const configColumns = [
    { title: '名称', dataIndex: 'name', width: 160 },
    { title: '类型', dataIndex: 'providerType', width: 100, render: (v: string) => providerTag(v) },
    { title: '目标', width: 200, ellipsis: true, render: (_: any, r: StorageConfig) => (
      OBJECT_PROVIDERS.includes(r.providerType || 'TELEGRAM')
        ? (parseOptions(r.providerOptions).bucket || '-')
        : r.chatId
    ) },
    { title: 'Worker/公开域名', dataIndex: 'workerBaseUrl', ellipsis: true, render: (_: any, r: StorageConfig) => (
      OBJECT_PROVIDERS.includes(r.providerType || 'TELEGRAM')
        ? (parseOptions(r.providerOptions).publicBaseUrl || '-')
        : r.workerBaseUrl
    ) },
    { title: '健康', dataIndex: 'healthStatus', width: 90, render: (v: string) => (
      <Tag color={v === 'UP' ? 'success' : v === 'DOWN' ? 'error' : 'default'}>{v || 'UNKNOWN'}</Tag>
    ) },
    { title: '默认', dataIndex: 'isDefault', width: 70, render: (v: number) => (v === 1 ? <Tag color="gold">默认</Tag> : '-') },
    { title: '状态', dataIndex: 'status', width: 90, render: (v: string) => statusTag(v) },
    {
      title: '操作', key: 'action', width: 280,
      render: (_: any, record: StorageConfig) => (
        <Space>
          <Button size="small" icon={<ExperimentOutlined />} onClick={() => testConfig(record)}>测试</Button>
          <Button size="small" icon={<StarOutlined />} onClick={async () => {
            await storageApi.setDefaultConfig(record.id!); message.success('已设为默认'); loadConfigs();
          }}>设默认</Button>
          <Button size="small" onClick={() => {
            const editing = { ...record } as StorageConfig;
            const opts = parseOptions(record.providerOptions);
            // Secret 打码回显：留空即保持不变（四类密钥字段统一处理）
            delete (opts as any).secretAccessKey;
            delete (opts as any).secretKey;
            delete (opts as any).accessKeySecret;
            delete (opts as any).password;
            delete (opts as any).tokenKey;
            setConfigModal({ open: true, editing });
            configForm.setFieldsValue({
              name: editing.name,
              chatId: editing.chatId,
              workerBaseUrl: editing.workerBaseUrl,
              providerOptions: OBJECT_PROVIDERS.includes(record.providerType || 'TELEGRAM') ? opts : undefined,
              maxFileSize: editing.maxFileSize,
              remark: editing.remark,
              status: editing.status,
            });
          }}>编辑</Button>
          <Popconfirm title="确定删除该配置？" onConfirm={async () => {
            try { await storageApi.deleteConfig(record.id!); message.success('已删除'); loadConfigs(); }
            catch (e: any) { message.error(e.message); }
          }}>
            <Button size="small" danger icon={<DeleteOutlined />} />
          </Popconfirm>
        </Space>
      ),
    },
  ];

  const folderColumns = [
    { title: '路径', dataIndex: 'folderPath', ellipsis: true },
    { title: '可见性', dataIndex: 'visibility', width: 90, render: (v: string) => (
      <Tag color={v === 'PUBLIC' ? 'green' : 'blue'}>{dictLabel('storage_visibility', v)}</Tag>
    ) },
    { title: '所有者', width: 130, render: (_: any, r: StorageFolder) => `${r.ownerType}:${r.ownerId}` },
    { title: '状态', dataIndex: 'status', width: 90, render: (v: string) => statusTag(v) },
    {
      title: '操作', key: 'action', width: 260,
      render: (_: any, record: StorageFolder) => (
        <Space>
          <Button size="small" onClick={() => openPermissions(record)}>授权</Button>
          <Button size="small" onClick={() => { setFolderModal({ open: true, editing: record }); folderForm.setFieldsValue(record); }}>编辑</Button>
          <Popconfirm title="确定删除该文件夹？" onConfirm={async () => {
            try { await storageApi.deleteFolder(record.id!); message.success('已删除'); loadFolders(); }
            catch (e: any) { message.error(e.message); }
          }}>
            <Button size="small" danger icon={<DeleteOutlined />} />
          </Popconfirm>
        </Space>
      ),
    },
  ];

  const fileColumns = [
    { title: '文件名', dataIndex: 'originalName', ellipsis: true },
    { title: '文件夹', dataIndex: 'folderId', width: 120, render: (v: number) => folderName(v) },
    { title: '类型', dataIndex: 'contentType', width: 120, ellipsis: true },
    { title: '大小', dataIndex: 'sizeBytes', width: 90, render: (v: number) => fmtBytes(v) },
    { title: '可见性', dataIndex: 'visibility', width: 90, render: (v: string) => (
      <Tag color={v === 'PUBLIC' ? 'green' : 'blue'}>{dictLabel('storage_visibility', v)}</Tag>
    ) },
    { title: '状态', dataIndex: 'status', width: 110, render: (v: string) => fileStatusTag(v) },
    { title: '时间', dataIndex: 'createTime', width: 160, render: (v: string) => (v || '').replace('T', ' ').slice(0, 19) },
    {
      title: '操作', key: 'action', width: 280,
      render: (_: any, record: StorageFile) => (
        <Space>
          <Tooltip title="生成短时下载地址并复制">
            <Button size="small" icon={<LinkOutlined />} onClick={() => copyDownloadUrl(record)}>地址</Button>
          </Tooltip>
          <Tooltip title="生成永久公开链接（仅公开文件；对象存储系需配置公开域名，Telegram 需重新部署 Worker）">
            <Button size="small" icon={<LinkOutlined />} onClick={() => copyPermanentUrl(record)}>永久</Button>
          </Tooltip>
          <Popconfirm title="删除后会移除远端对象（Telegram 为异步任务），确定？" onConfirm={async () => {
            try { await storageApi.deleteFile(record.publicId!); message.success('已进入删除流程'); loadFiles(); }
            catch (e: any) { message.error(e.message); }
          }}>
            <Button size="small" danger icon={<DeleteOutlined />} />
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <div>
      <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <div>
          <h2 style={{ margin: 0 }}>文件存储</h2>
          <p style={{ color: '#909399', margin: '4px 0 0' }}>
            支持 Telegram（Worker 中转）、Cloudflare R2、S3 兼容存储（AWS/MinIO/七牛）、腾讯云 COS、阿里云 OSS、又拍云；文件由浏览器直传目标存储，Astral 只管理权限与元数据
          </p>
        </div>
      </div>

      <Alert
        style={{ marginBottom: 16 }}
        type="info" showIcon
        message="Bot Token / Access Key 只进入对应存储端，不经过本系统；公开文件可生成永久直链，私有文件只发短时签名地址。"
      />

      <Card>
        <Tabs
          items={[
            {
              key: 'files', label: '文件管理', forceRender: true,
              children: (
                <div>
                  <div style={{ marginBottom: 12, display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'center' }}>
                    <Select
                      allowClear placeholder="按文件夹筛选" style={{ width: 200 }}
                      options={folders.map((f) => ({ label: f.folderPath || f.folderName, value: f.id! }))}
                      value={fileQuery.folderId}
                      onChange={(v) => setFileQuery((q) => ({ ...q, folderId: v, current: 1 }))}
                    />
                    <Input.Search
                      placeholder="按文件名搜索" style={{ width: 220 }}
                      onSearch={(v) => setFileQuery((q) => ({ ...q, keyword: v, current: 1 }))}
                    />
                    <Select
                      placeholder="上传到文件夹" style={{ width: 200 }} value={uploadFolderId}
                      options={folders.filter((f) => f.status === 'ENABLED').map((f) => ({ label: f.folderPath || f.folderName, value: f.id! }))}
                      onChange={setUploadFolderId}
                    />
                    <Upload
                      customRequest={customUploadRequest}
                      showUploadList={false}
                      accept="image/jpeg,image/png,image/webp,image/gif"
                    >
                      <Button type="primary" icon={<CloudUploadOutlined />} loading={uploading} disabled={!uploadFolderId}>
                        上传图片
                      </Button>
                    </Upload>
                    <Button icon={<ReloadOutlined />} onClick={() => loadFiles()}>刷新</Button>
                  </div>
                  <PagedTable files={files} columns={fileColumns} onPage={(current, size) => setFileQuery((q) => ({ ...q, current, size }))} rowKey="publicId" />
                </div>
              ),
            },
            {
              key: 'folders', label: '文件夹与授权',
              children: (
                <div>
                  <div style={{ marginBottom: 12 }}>
                    <Button type="primary" icon={<PlusOutlined />} onClick={() => { setFolderModal({ open: true }); folderForm.resetFields(); }}>
                      新建文件夹
                    </Button>
                  </div>
                  <PagedTable files={folders} columns={folderColumns} rowKey="id" />
                </div>
              ),
            },
            {
              key: 'configs', label: '存储配置',
              children: (
                <div>
                  <div style={{ marginBottom: 12 }}>
                    <Button type="primary" icon={<PlusOutlined />} onClick={() => { setConfigModal({ open: true }); configForm.resetFields(); }}>
                      新建配置
                    </Button>
                  </div>
                  <PagedTable files={configs} columns={configColumns} rowKey="id" />
                </div>
              ),
            },
            {
              key: 'uploadTest', label: '上传测试',
              children: (
                <div>
                  <p style={{ color: '#909399' }}>
                    {testDescription}
                  </p>
                  <Space style={{ marginBottom: 16 }} wrap>
                    <Select
                      placeholder="选择测试文件夹" style={{ width: 220 }} value={testFolderId}
                      options={folders.filter((f) => f.status === 'ENABLED').map((f) => {
                        const cfg = f.storageConfigId ? configs.find((c) => c.id === f.storageConfigId) : configs.find((c) => c.isDefault === 1);
                        const p = cfg?.providerType || 'TELEGRAM';
                        const label = `${f.folderPath || f.folderName}（${p === 'TELEGRAM' ? 'Telegram' : p === 'S3_COMPATIBLE' ? 'S3' : p === 'QINIU' ? '七牛' : p === 'COS' ? '腾讯COS' : p === 'OSS' ? '阿里OSS' : p === 'UPYUN' ? '又拍云' : p}）`;
                        return { label, value: f.id! };
                      })}
                      onChange={setTestFolderId}
                    />
                    <Button type="primary" icon={<ExperimentOutlined />} loading={testRunning} disabled={!testFolderId} onClick={runUploadTest}>
                      开始测试
                    </Button>
                    <Button disabled={testRunning} onClick={resetTest}>重置</Button>
                    <span>
                      本次测试通道：{providerTag(testProvider)}
                      {activeTestConfig && <span style={{ color: '#909399', marginLeft: 6 }}>({activeTestConfig.name})</span>}
                    </span>
                  </Space>
                  <div>
                    {testSteps.map((step, idx) => (
                      <div key={idx} style={{ display: 'flex', alignItems: 'flex-start', gap: 12, marginBottom: 12 }}>
                        <span style={{ fontSize: 16, width: 24, textAlign: 'center', lineHeight: '22px' }}>
                          {step.state === 'ok' ? '✅' : step.state === 'fail' ? '❌' : step.state === 'run' ? '⏳' : `${idx + 1}.`}
                        </span>
                        <div>
                          <div style={{ fontWeight: step.state === 'fail' ? 600 : 400, color: step.state === 'fail' ? '#cf1322' : undefined }}>
                            {step.title}
                          </div>
                          {step.detail && (
                            <div style={{ color: step.state === 'fail' ? '#cf1322' : '#595959', fontSize: 12, marginTop: 2, wordBreak: 'break-all' }}>
                              {step.detail}
                            </div>
                          )}
                        </div>
                      </div>
                    ))}
                  </div>
                  <Alert
                    type="warning" showIcon style={{ marginTop: 8 }}
                    message="前置条件"
                    description={
                      <ul style={{ margin: 0, paddingLeft: 18 }}>
                        {testPrerequisites.map((item, i) => <li key={i}>{item}</li>)}
                      </ul>
                    }
                  />
                </div>
              ),
            },
            {
              key: 'tasks', label: '任务',
              children: (
                <div>
                  <Button icon={<ReloadOutlined />} style={{ marginBottom: 12 }} onClick={loadTasks}>刷新</Button>
                  <PagedTable
                    files={tasks} rowKey="id"
                    columns={[
                      { title: 'ID', dataIndex: 'id', width: 100 },
                      { title: '类型', dataIndex: 'taskType', width: 200 },
                      { title: '状态', dataIndex: 'status', width: 100, render: (v: string) => taskStatusTag(v) },
                      { title: '重试', dataIndex: 'retryCount', width: 80 },
                      { title: '错误', dataIndex: 'errorMessage', ellipsis: true },
                      { title: '时间', dataIndex: 'createTime', width: 160, render: (v: string) => (v || '').replace('T', ' ').slice(0, 19) },
                    ]}
                  />
                </div>
              ),
            },
            {
              key: 'audit', label: '审计',
              children: (
                <div>
                  <Button icon={<ReloadOutlined />} style={{ marginBottom: 12 }} onClick={loadAudits}>刷新</Button>
                  <PagedTable
                    files={audits} rowKey="id"
                    columns={[
                      { title: '动作', dataIndex: 'action', width: 160 },
                      { title: '主体', width: 140, render: (_: any, r: StorageAudit) => `${r.subjectType || ''}:${r.subjectId || ''}` },
                      { title: '目标', width: 120, render: (_: any, r: StorageAudit) => `${r.targetType || ''} ${r.targetId || ''}` },
                      { title: '结果', dataIndex: 'result', width: 90, render: (v: string) => (
                        <Tag color={v === 'OK' ? 'success' : v === 'ERROR' ? 'error' : 'warning'}>{v}</Tag>
                      ) },
                      { title: '详情', dataIndex: 'detail', ellipsis: true },
                      { title: '时间', dataIndex: 'createTime', width: 160, render: (v: string) => (v || '').replace('T', ' ').slice(0, 19) },
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
        destroyOnClose
      >
        <Form form={configForm} layout="vertical">
          <Form.Item name="name" label="配置名称" rules={[{ required: !configModal.editing, message: '请输入名称' }]}>
            <Input disabled={!!configModal.editing} maxLength={128} placeholder="如：Telegram 图床 / R2 备份" />
          </Form.Item>
          {!configModal.editing && (
            <Form.Item name="providerType" label="存储类型" initialValue="TELEGRAM"
              extra="TELEGRAM 经 Cloudflare Worker 中转；R2/S3/七牛走 S3 预签名 PUT；COS/OSS 走各自预签名 PUT；又拍云走表单直传">
              <Select options={dict['storage_provider_type'] || []} />
            </Form.Item>
          )}
          {!isObjectConfig ? (
            <>
              <Form.Item name="chatId" label="Telegram Chat ID" rules={[{ required: true, message: '请输入频道/群 ID' }]}
                extra="私有频道数字 ID（如 -100xxxxxxxxxx）或 @频道名">
                <Input placeholder="-100xxxxxxxxxx" />
              </Form.Item>
              <Form.Item name="workerBaseUrl" label="Cloudflare Worker 地址" rules={[{ required: true, message: '请输入 Worker 地址' }]}
                extra="如 https://img.example.com 或 astral-storage.xxx.workers.dev">
                <Input placeholder="https://img.example.com" />
              </Form.Item>
            </>
          ) : isS3Config ? (
            <>
              <Form.Item name={['providerOptions', 'endpoint']} label="S3 Endpoint" rules={[{ required: true, message: '请输入 S3 端点' }]}
                extra="R2：https://<account_id>.r2.cloudflarestorage.com；AWS：https://s3.<region>.amazonaws.com；七牛：https://s3.cn-east-1.qiniu.com 等">
                <Input placeholder="https://xxxx.r2.cloudflarestorage.com" />
              </Form.Item>
              <Form.Item name={['providerOptions', 'bucket']} label="Bucket" rules={[{ required: true, message: '请输入桶名' }]}>
                <Input placeholder="astral-storage" />
              </Form.Item>
              <Form.Item name={['providerOptions', 'region']} label="Region"
                extra="R2 填 auto；AWS 填如 us-east-1；七牛填自动生成的 S3 区域（如 cn-east-1）">
                <Input placeholder="auto" />
              </Form.Item>
              <Form.Item name={['providerOptions', 'accessKeyId']} label="Access Key ID" rules={[{ required: true, message: '请输入 Access Key ID' }]}>
                <Input placeholder="Access Key ID" />
              </Form.Item>
              <Form.Item name={['providerOptions', 'secretAccessKey']} label="Secret Access Key"
                rules={configModal.editing ? [] : [{ required: true, message: '请输入 Secret Access Key' }]}
                extra={configModal.editing ? '已保存（返回时打码为 ******）；留空表示不修改' : undefined}>
                <Input.Password placeholder="Secret Access Key" />
              </Form.Item>
              <Form.Item name={['providerOptions', 'publicBaseUrl']} label="公开访问域名（永久链接用，可选）"
                extra="桶的公开访问域名；填写后公开文件可生成永久直链（如 https://cdn.example.com/bucket）">
                <Input placeholder="https://cdn.example.com" />
              </Form.Item>
            </>
          ) : isCOSConfig ? (
            <>
              <Form.Item name={['providerOptions', 'bucket']} label="Bucket" rules={[{ required: true, message: '请输入桶名' }]}>
                <Input placeholder="astral-1250000000" />
              </Form.Item>
              <Form.Item name={['providerOptions', 'region']} label="Region（地域）" rules={[{ required: true, message: '请输入地域' }]}
                extra="如 ap-guangzhou / ap-shanghai / ap-beijing">
                <Input placeholder="ap-guangzhou" />
              </Form.Item>
              <Form.Item name={['providerOptions', 'secretId']} label="SecretId" rules={[{ required: true, message: '请输入 SecretId' }]}>
                <Input placeholder="SecretId" />
              </Form.Item>
              <Form.Item name={['providerOptions', 'secretKey']} label="SecretKey"
                rules={configModal.editing ? [] : [{ required: true, message: '请输入 SecretKey' }]}
                extra={configModal.editing ? '已保存（返回时打码为 ******）；留空表示不修改' : undefined}>
                <Input.Password placeholder="SecretKey" />
              </Form.Item>
              <Form.Item name={['providerOptions', 'publicBaseUrl']} label="公开访问域名（永久链接用，可选）"
                extra="如 https://cdn.example.com（自定义 CDN 域名或默认桶域名均可）">
                <Input placeholder="https://cdn.example.com" />
              </Form.Item>
            </>
          ) : isOSSConfig ? (
            <>
              <Form.Item name={['providerOptions', 'bucket']} label="Bucket" rules={[{ required: true, message: '请输入桶名' }]}>
                <Input placeholder="astral-storage" />
              </Form.Item>
              <Form.Item name={['providerOptions', 'endpoint']} label="Endpoint（地域域名）" rules={[{ required: true, message: '请输入 Endpoint' }]}
                extra="如 oss-cn-hangzhou.aliyuncs.com（不带 bucket 前缀、不带 http(s)://）">
                <Input placeholder="oss-cn-hangzhou.aliyuncs.com" />
              </Form.Item>
              <Form.Item name={['providerOptions', 'accessKeyId']} label="AccessKeyId" rules={[{ required: true, message: '请输入 AccessKeyId' }]}>
                <Input placeholder="AccessKeyId" />
              </Form.Item>
              <Form.Item name={['providerOptions', 'accessKeySecret']} label="AccessKeySecret"
                rules={configModal.editing ? [] : [{ required: true, message: '请输入 AccessKeySecret' }]}
                extra={configModal.editing ? '已保存（返回时打码为 ******）；留空表示不修改' : undefined}>
                <Input.Password placeholder="AccessKeySecret" />
              </Form.Item>
              <Form.Item name={['providerOptions', 'publicBaseUrl']} label="公开访问域名（永久链接用，可选）"
                extra="如 https://cdn.example.com（绑定自定义域名或桶默认外网域名）">
                <Input placeholder="https://cdn.example.com" />
              </Form.Item>
            </>
          ) : (
            <>
              <Form.Item name={['providerOptions', 'bucket']} label="服务名（Bucket）" rules={[{ required: true, message: '请输入服务名' }]}>
                <Input placeholder="astral-storage" />
              </Form.Item>
              <Form.Item name={['providerOptions', 'operator']} label="操作员名" rules={[{ required: true, message: '请输入操作员名' }]}>
                <Input placeholder="operator" />
              </Form.Item>
              <Form.Item name={['providerOptions', 'password']} label="操作员密码"
                rules={configModal.editing ? [] : [{ required: true, message: '请输入操作员密码' }]}
                extra={configModal.editing ? '已保存（返回时打码为 ******）；留空表示不修改' : undefined}>
                <Input.Password placeholder="操作员密码" />
              </Form.Item>
              <Form.Item name={['providerOptions', 'endpoint']} label="API 线路域名（可选）"
                extra="默认 v0.api.upyun.com（智能选路）；电信 v1/联通 v2/移动 v3.api.upyun.com">
                <Input placeholder="v0.api.upyun.com" />
              </Form.Item>
              <Form.Item name={['providerOptions', 'publicBaseUrl']} label="空间绑定域名（下载/永久链接用）"
                rules={[{ required: true, message: '请输入空间绑定的加速域名' }]}
                extra="又拍云 REST 不支持 URL 预签名，下载走该域名；配置 Token 防盗链后签名 _upt">
                <Input placeholder="https://cdn.example.com" />
              </Form.Item>
              <Form.Item name={['providerOptions', 'tokenKey']} label="Token 防盗链密钥（可选）"
                extra="又拍云控制台「防盗链 → Token 防盗链」中设置的密钥；未开启防盗链可留空。编辑时已保存则留空保持不变">
                <Input.Password placeholder="token 密钥" />
              </Form.Item>
            </>
          )}
          <Form.Item name="maxFileSize" label="单文件上限（字节，留空使用全局 20MiB）">
            <InputNumber style={{ width: '100%' }} min={1} max={isObjectConfig ? 5368709120 : 20971520} />
          </Form.Item>
          <Form.Item name="remark" label="备注">
            <Input maxLength={256} />
          </Form.Item>
        </Form>
      </Modal>

      {/* 文件夹弹窗 */}
      <Modal
        title={folderModal.editing ? '编辑文件夹' : '新建文件夹'}
        open={folderModal.open}
        onOk={submitFolder}
        onCancel={() => setFolderModal({ open: false })}
        destroyOnClose
      >
        <Form form={folderForm} layout="vertical">
          <Form.Item name="parentId" label="父文件夹（可选）">
            <Select allowClear options={folders.map((f) => ({ label: f.folderPath || f.folderName, value: f.id! }))} />
          </Form.Item>
          <Form.Item name="folderName" label="名称" rules={[{ required: !folderModal.editing, message: '请输入名称' }]}>
            <Input maxLength={128} />
          </Form.Item>
          <Form.Item name="visibility" label="默认可见性" initialValue="PRIVATE">
            <Select options={dict['storage_visibility'] || []} />
          </Form.Item>
          {!folderModal.editing && (
            <Form.Item name="configId" label="存储配置（可选，默认使用默认配置）">
              <Select allowClear options={configs.filter((c) => c.status === 'ENABLED').map((c) => ({ label: c.name, value: c.id! }))} />
            </Form.Item>
          )}
        </Form>
      </Modal>

      {/* 授权弹窗 */}
      <Modal
        title={`文件夹授权：${permModal.folder?.folderName || ''}`}
        open={permModal.open}
        onOk={savePermissions}
        onCancel={() => setPermModal({ open: false, rows: [] })}
        width={640}
        okText="保存授权"
      >
        <p style={{ color: '#909399' }}>
          授权为全量替换；权限集合：READ / UPLOAD / UPDATE / DELETE / MANAGE。
          Qt 等插件调用方使用 PLUGIN 主体（预留）。
        </p>
        {permModal.rows.map((row, idx) => (
          <Space key={idx} style={{ display: 'flex', marginBottom: 8 }}>
            <Select
              style={{ width: 110 }} value={row.subjectType}
              options={[{ label: 'USER', value: 'USER' }, { label: 'PLUGIN', value: 'PLUGIN' }]}
              onChange={(v) => setPermModal((m) => ({ ...m, rows: m.rows.map((r, i) => (i === idx ? { ...r, subjectType: v } : r)) }))}
            />
            <Input
              style={{ width: 160 }} placeholder="主体 ID（用户ID）" value={row.subjectId}
              onChange={(e) => setPermModal((m) => ({ ...m, rows: m.rows.map((r, i) => (i === idx ? { ...r, subjectId: e.target.value } : r)) }))}
            />
            <Select
              style={{ width: 220 }} mode="multiple" placeholder="权限"
              options={dict['storage_permission'] || []}
              value={(row.permissions || '').split(',').filter(Boolean)}
              onChange={(v) => setPermModal((m) => ({ ...m, rows: m.rows.map((r, i) => (i === idx ? { ...r, permissions: v.join(',') } : r)) }))}
            />
            <Button danger onClick={() => setPermModal((m) => ({ ...m, rows: m.rows.filter((_, i) => i !== idx) }))}>
              移除
            </Button>
          </Space>
        ))}
        <Button
          type="dashed" block icon={<PlusOutlined />}
          onClick={() => setPermModal((m) => ({ ...m, rows: [...m.rows, { subjectType: 'USER', subjectId: '', permissions: 'READ,UPLOAD' }] }))}
        >
          添加授权
        </Button>
      </Modal>
    </div>
  );
}

/** 简单分页表格封装 */
function PagedTable({ files, columns, onPage, rowKey }: {
  files: any;
  columns: any[];
  onPage?: (current: number, size: number) => void;
  rowKey: string;
}) {
  const plain = Array.isArray(files);
  const records: any[] = plain ? files : files.records || [];
  return (
    <Table
      rowKey={rowKey}
      columns={columns}
      dataSource={records}
      pagination={plain ? {
        hideOnSinglePage: true,
      } : {
        current: files.current, pageSize: files.size, total: files.total,
        showSizeChanger: true, onChange: (current: number, size: number) => onPage?.(current, size),
      }}
    />
  );
}
