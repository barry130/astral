'use client';

/**
 * 音源包热更新管理 Tab（SOURCE_UPDATE_DESIGN §五/§七）
 *
 * 发布渠道复用版本更新字典 qt_update_channel（stable=正式版 / beta=测试版）：
 * 正式版所有用户都能收到；测试版仅对拥有 user:qt:source:channel:beta 结果级权限（含超管）的用户可见，
 * 服务端在「可见渠道集合」内取版本号最大者——正式包版本号更高时所有用户都收到正式包。
 *
 * 发布流程对应「先拿号 → 上传文件 → 回填 artifacts → 发布」四步：
 * 1. 新建：只填平台/渠道/准入/说明，后端生成版本号（如 2026091801）随响应返回；
 * 2. 把变更文件上传到 source/<版本号>/ 目录（图床/存储页），拿到永久地址；
 * 3. 编辑该 release，按 path 填 {path, url}（version 留空自动 +1；url 可手填永久直链
 *    或行内上传生成，上传会覆盖手填值）；
 * 4. 点「发布」。
 *
 * artifacts 语义（与后端 QtSourceService#mergeArtifacts 对齐）：
 *  - 编辑：提交 replaceArtifacts=true，弹窗里的列表就是「当前生效全集」，删掉的行保存后即消失；
 *  - 新建：不送该标志，按 path 增量合并，未提交的 path 继承上一版（「只发变更文件」）；
 *  - 两个方向都只认字典 qt_source_artifact_path 里仍启用的 path：已停用的旧单包产物
 *    （chain.json / source-bundle.js）既不会被继承，也不会被提交，保存后自动从该版本消失。
 */
import { useEffect, useState } from 'react';
import {
  Button, Checkbox, Form, Input, InputNumber, Modal, Popconfirm, Select, Space, Tag, Typography, Upload, message,
} from '@/components/antd-compat';
import { BarChartOutlined, PlusOutlined, UploadOutlined } from '@/components/antd-compat/icons';
import { sourceReleaseApi, qtAdminApi, QtSourceRelease, QtSourceArtifact, QtSourceStatRow, enumLabel } from '@/api/qt';
import { fetchDictOptions, DictOption } from '@/api/dict';
import { usePerm, QT_PERMISSIONS } from '@/lib/perm';
import { storageApi } from '@/api/storage';
import { ResizableTable } from '@/components/ResizableTable';

const { Paragraph } = Typography;

/**
 * 枚举全部走数据字典（AGENTS.md §3：禁止前端硬编码值→文案映射）：
 *  - 平台   qt_update_platform（与版本更新同源值集）
 *  - 渠道   qt_update_channel（stable/beta）
 *  - 结果   qt_source_report_result（ok/smoke_failed）
 *  - 状态   qt_source_release_state（unpublished/published/bad，由 published/bad 派生）
 *  - 产物path qt_source_artifact_path
 * 下列 FALLBACK_* 仅在字典拉取失败时兜底展示，不作为常规文案来源。
 */
const FALLBACK_PLATFORM_OPTS = [
  { value: 1101, label: '安卓' },
  { value: 1102, label: 'iOS' },
  { value: 1103, label: 'Windows' },
  { value: 1104, label: 'Linux' },
  { value: 1105, label: 'macOS' },
  { value: 1106, label: 'HarmonyOS' },
];

/**
 * 新建发布单的默认适用平台。刻意只默认 Windows（1103）：平台是「哪些客户端能收到这个包」，
 * 而一个音源包通常先只出 Windows 产物；勾上 Linux/macOS/鸿蒙却没上传对应产物，会把不完整的包
 * 分发给这些端。需要多平台时由发布人手动勾选（后端 QtSourceRelease 已支持 1104/1105/1106）。
 */
const DEFAULT_PLATFORMS = [1103];

const FALLBACK_CHANNEL_OPTS = [
  { value: 'stable', label: '正式版' },
  { value: 'beta', label: '测试版' },
];

const FALLBACK_STATE_LABEL: Record<string, string> = {
  unpublished: '未发布', published: '已发布', bad: '坏包',
};

/**
 * artifacts 编辑行：path 从数据字典下拉选择；url 既可行内上传生成（永久地址），
 * 也可手填已有的永久直链（上传成功会覆盖手填值）。
 */
type ArtifactRow = { path?: string; url?: string };

/** 按平台准入编辑行：unlimited=不限制（提交时省略该平台键）；touched=已显式设置过，晚到的版本列表不再自动全选 */
type AdmissionRow = { unlimited: boolean; codes: number[]; touched?: boolean };

/**
 * 现存应用版本（来自「版本更新」管理）。它是准入下拉的唯一候选源。
 * <p>刻意<b>不过滤</b> isPublished / channel：未发布版本用于「先把音源包挂上去、应用版本稍后才发」的联调，
 * 测试版用于灰度内测，两者都必须能被勾选；「是哪一种」交给 label 上的标注（测试版 / 未发布）去表达，
 * 而不是从候选里剔掉——剔掉之后运营只能等应用发版才能配准入。</p>
 */
type AppVersionInfo = {
  code: number;
  name?: string;
  channel?: string;
  published: boolean;
  /** 该版本投放过的平台码（platforms 逗号列拆出来的） */
  platforms: number[];
};

/** 发布状态派生键：坏包 > 已发布 > 未发布（与服务端 bad/published 两布尔字段对应） */
const stateKeyOf = (r: QtSourceRelease): string =>
  r.bad ? 'bad' : r.published ? 'published' : 'unpublished';

export default function SourceReleasesTab() {
  const [releases, setReleases] = useState<QtSourceRelease[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [loading, setLoading] = useState(false);
  const [channelFilter, setChannelFilter] = useState<string | undefined>();
  const [modal, setModal] = useState(false);
  const [statsModal, setStatsModal] = useState(false);
  const [stats, setStats] = useState<QtSourceStatRow[]>([]);
  const [form] = Form.useForm();

  /** 数据字典选项（按 dict_code 动态拉取，避免写死枚举文案） */
  const [dict, setDict] = useState<Record<string, DictOption[]>>({});
  /** 行内上传中状态，按 Form.List 字段索引标记 */
  const [uploadingIdx, setUploadingIdx] = useState<number | null>(null);
  /** 现存 App 版本全量目录（含未发布 / 测试版），准入下拉的候选源 */
  const [versionCatalog, setVersionCatalog] = useState<AppVersionInfo[]>([]);
  /** 平台码 → 该平台投放过的 versionCode（降序）：只用于「新建时默认全选」 */
  const [appVersions, setAppVersions] = useState<Record<number, number[]>>({});
  /** 按平台准入选择状态（key=平台码），随「适用平台」联动增删 */
  const [admission, setAdmission] = useState<Record<number, AdmissionRow>>({});

  const hasPerm = usePerm();
  // 轻听后台管理权限：必须与后端 QtAdminController 类级 @RequiresPermission("admin:qt:admin") 保持一致
  const canEdit = hasPerm(QT_PERMISSIONS.admin);

  // 由数据字典派生出的下拉/标签选项（数值类将 value 转为 number 以便比较）
  const platformOpts = (dict.qt_update_platform?.length ? dict.qt_update_platform : FALLBACK_PLATFORM_OPTS)
    .map((o) => ({ value: Number(o.value), label: o.label }));
  const channelOpts = dict.qt_update_channel?.length ? dict.qt_update_channel : FALLBACK_CHANNEL_OPTS;
  const pathOpts = dict.qt_source_artifact_path || [];
  /**
   * 字典里仍启用的产物 path。字典未拉到（pathOpts 为空）时不做判断——否则会把所有行
   * 误标成「已停用」。不在集合中的行属于已停用/未登记的 path（如单包时代的 chain.json、
   * source-bundle.js），后端继承时会自动摘掉它们，这里给用户一个显式提示。
   */
  const enabledPaths = new Set(pathOpts.map((o) => String(o.value)));
  const isDeprecatedPath = (p?: string): boolean =>
    !!p && pathOpts.length > 0 && !enabledPaths.has(p);
  const resultOpts = dict.qt_source_report_result || [];
  const stateOpts = dict.qt_source_release_state || [];

  /** 平台标签：字典优先，未命中回退原始值 */
  const platformLabel = (v: number) => enumLabel(platformOpts, v);
  /** 发布状态标签：字典优先，字典缺失时回退固定映射 */
  const stateLabel = (r: QtSourceRelease): string => {
    const key = stateKeyOf(r);
    const fromDict = enumLabel(stateOpts, key);
    return fromDict !== key ? fromDict : (FALLBACK_STATE_LABEL[key] ?? key);
  };

  /** 监听「适用平台」：准入选择行随其联动 */
  const watchedPlatforms: number[] = Form.useWatch('platforms', form) || [];
  /** 监听产物行：用于标出「已从字典停用」的历史 path（这些行不会被继承到新版本） */
  const watchedArtifacts: ArtifactRow[] = Form.useWatch('artifacts', form) || [];

  const load = (p = page) => {
    setLoading(true);
    sourceReleaseApi.list(p, 10, { channel: channelFilter }).then((res) => {
      if (res.code === 200) {
        setReleases(res.data.records || []);
        setTotal(res.data.total || 0);
      }
    }).finally(() => setLoading(false));
  };

  useEffect(() => { load(1); /* eslint-disable-next-line react-hooks/exhaustive-deps */ }, [channelFilter]);

  useEffect(() => {
    fetchDictOptions([
      'qt_update_platform', 'qt_update_channel',
      'qt_source_artifact_path', 'qt_source_report_result', 'qt_source_release_state',
    ]).then(setDict).catch(() => {});
  }, []);

  /**
   * 拉取应用版本目录（「版本更新」管理，接口本身不过滤发布状态与渠道）。
   * <p>每次打开弹窗都重拉一次：版本更新与本 Tab 同处一个页面且 Tabs 不销毁，
   * 只在挂载时拉一次会让「刚在另一个 Tab 新建的版本」在准入下拉里缺席。</p>
   */
  const loadAppVersions = async (): Promise<void> => {
    try {
      const res = await qtAdminApi.updates(1, 200);
      if (res.code !== 200) return;
      const catalog: AppVersionInfo[] = [];
      const grouped: Record<number, number[]> = {};
      for (const u of res.data.records || []) {
        if (u.versionCode == null) continue;
        // platforms 是逗号分隔多值列：一个版本可同时投放多个平台，每个平台都要收进准入候选
        const platforms = String(u.platforms ?? '')
          .split(',')
          .map((s) => Number(s.trim()))
          .filter((p) => Number.isFinite(p) && p > 0);
        catalog.push({
          code: u.versionCode,
          name: u.versionName,
          channel: u.channel,
          published: u.isPublished === 1,
          platforms,
        });
        for (const p of platforms) (grouped[p] = grouped[p] || []).push(u.versionCode);
      }
      catalog.sort((a, b) => b.code - a.code);
      for (const k of Object.keys(grouped)) {
        grouped[Number(k)] = Array.from(new Set(grouped[Number(k)])).sort((a, b) => b - a);
      }
      setVersionCatalog(catalog);
      setAppVersions(grouped);
    } catch {
      // 拉取失败保留上一次的目录：准入下拉退化为「上一次已知版本」，不阻塞编辑
    }
  };

  useEffect(() => { void loadAppVersions(); /* eslint-disable-next-line react-hooks/exhaustive-deps */ }, []);

  // 平台增删时同步准入行：新增平台默认全选现存版本，删除平台连带移除。
  // 版本目录晚到（打开弹窗才刷新）时，把「用户还没动过」的行重新对齐到最新目录——
  // 否则新建弹窗会停在上一次缓存的旧版本集，刚建的版本仍然勾不到。
  const platformKey = watchedPlatforms.join(',');
  useEffect(() => {
    setAdmission((prev) => {
      const next: Record<number, AdmissionRow> = {};
      for (const p of watchedPlatforms) {
        const old = prev[p];
        const codes = appVersions[p] || [];
        if (old) {
          // 用户没动过的行要跟着最新目录走：包括「打开弹窗时缓存还是空的，被默认成了不限制」
          // 这种情况——目录回来后要把它改回显式全选，否则下拉是空的、看起来像没有版本可选。
          const stale = !old.touched && codes.length > 0
            && (old.unlimited || old.codes.length !== codes.length || codes.some((c) => !old.codes.includes(c)));
          next[p] = stale ? { ...old, unlimited: false, codes: [...codes] } : old;
          continue;
        }
        next[p] = { unlimited: !codes.length, codes: [...codes] };
      }
      return next;
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [platformKey, appVersions]);

  // 提交预览：与 submit 同一规则，生成将写入的 appVersionCodes
  const admissionPreview: Record<string, number[]> = {};
  for (const p of watchedPlatforms) {
    const row = admission[p];
    if (!row || row.unlimited || !row.codes.length) continue;
    admissionPreview[String(p)] = [...row.codes].sort((a, b) => b - a);
  }

  const refreshAfter = (msg: string) => {
    message.success(msg);
    load();
  };

  /**
   * 准入下拉里单个版本的显示文案：`版本名（版本码）· 测试版 · 未发布 · 安卓`
   * 只写裸数字的话，运营看不出哪条是刚建好还没发的版本、哪条是灰度测试版。
   */
  const versionLabel = (v: AppVersionInfo, forPlatform: number): string => {
    const parts: string[] = [v.name?.trim() ? `${v.name.trim()}（${v.code}）` : String(v.code)];
    if (v.channel && v.channel !== 'stable') {
      const ch = enumLabel(channelOpts, v.channel);
      parts.push(ch === v.channel ? v.channel : ch);
    }
    if (!v.published) {
      const un = enumLabel(stateOpts, 'unpublished');
      parts.push(un === 'unpublished' ? FALLBACK_STATE_LABEL.unpublished : un);
    }
    // 非本平台的版本也列进来（预置下一版音源包时应用可能还没在该平台发版），标出平台避免误选
    if (!v.platforms.includes(forPlatform)) {
      parts.push(v.platforms.length ? v.platforms.map(platformLabel).join('/') : '未标平台');
    }
    return parts.join(' · ');
  };

  /** 某平台的准入候选：本平台投放过的版本在前，其他平台的版本在后 */
  const versionOptions = (p: number) => [
    ...versionCatalog.filter((v) => v.platforms.includes(p)),
    ...versionCatalog.filter((v) => !v.platforms.includes(p)),
  ].map((v) => ({ value: v.code, label: versionLabel(v, p) }));

  const openModal = (record?: QtSourceRelease) => {
    setModal(true);
    if (record) {
      // 存量准入配置反显：键存在=显式版本集；键缺省或空数组=该平台不限制
      const adm: Record<number, AdmissionRow> = {};
      const stored = record.appVersionCodes || {};
      for (const p of record.platforms || []) {
        const codes = stored[String(p)];
        adm[p] = codes?.length
          ? { unlimited: false, codes: [...codes].sort((a, b) => b - a), touched: true }
          : { unlimited: true, codes: [], touched: true };
      }
      setAdmission(adm);
      form.setFieldsValue({
        id: record.id,
        platforms: record.platforms,
        channel: record.channel,
        hostApiVersion: record.hostApiVersion,
        notes: record.notes,
        // 回传全集（不含 version）：url 没变的条目后端保持原 version，url 变了才 +1
        artifacts: (record.artifacts || []).map((a) => ({ path: a.path, url: a.url })),
      });
    } else {
      form.resetFields();
      form.setFieldsValue({ platforms: [...DEFAULT_PLATFORMS], channel: 'stable', hostApiVersion: 1, artifacts: [] });
      // 新建默认：已选平台各自全选现存版本（用户自行删除不需要的）。
      // 先用当前缓存垫一层，等刷新回来再用最新目录补一次默认全选（touched 的不会被覆盖）。
      setAdmission(() => {
        const adm: Record<number, AdmissionRow> = {};
        for (const p of DEFAULT_PLATFORMS) {
          const codes = appVersions[p] || [];
          adm[p] = { unlimited: !codes.length, codes: [...codes] };
        }
        return adm;
      });
    }
    // 每次打开都刷新版本目录：另一个 Tab 里刚建的版本要能立刻出现在准入下拉里。
    // 刷新回来的目录由下方 platformKey/appVersions 那个 effect 负责给「尚未动过」的行补默认全选。
    void loadAppVersions();
  };

  const submit = async () => {
    const values = await form.validateFields();
    // 准入：勾「不限制」或一个未选的平台不写键（=该平台不限制，含未来新版本）
    const appVersionCodes: Record<string, number[]> = {};
    for (const p of values.platforms || []) {
      const row = admission[p];
      if (!row || row.unlimited || !row.codes.length) continue;
      appVersionCodes[String(p)] = [...row.codes].sort((a, b) => b - a);
    }
    const artifacts: QtSourceArtifact[] = (values.artifacts || [])
      .filter((a: ArtifactRow) => a.path)
      // 已停用的 path（单包时代的 chain.json / source-bundle.js）不再提交：两端客户端都只认
      // meta/play，这些条目纯属历史垃圾。后端对「显式提交」的停用 path 是保留的（为了能编辑
      // 历史版本），所以清理必须由这里做——否则编辑弹窗原样回传，保存多少次都还是 4 条。
      // 字典没拉到（pathOpts 为空）时 isDeprecatedPath 恒为 false，不会误删任何行。
      .filter((a: ArtifactRow) => !isDeprecatedPath(a.path))
      .map((a: ArtifactRow) => ({
        path: a.path!,
        url: a.url?.trim() || undefined,
      }));
    const body: QtSourceRelease = {
      platforms: values.platforms,
      channel: values.channel,
      hostApiVersion: values.hostApiVersion,
      notes: values.notes,
      appVersionCodes,
      artifacts,
      // 编辑弹窗回传的就是「当前生效全集」，所以声明为替换语义：弹窗里删掉的条目
      // 才会真正消失（否则后端按 path 合并会把删掉的项继承回来）。
      // 新建不送该标志：保持「只填变更文件、其余继承上一版」的增量语义。
      ...(values.id != null ? { replaceArtifacts: true } : {}),
    };
    try {
      if (values.id != null) {
        const res = await sourceReleaseApi.update(values.id, body);
        if (res.code === 200) refreshAfter('已保存（产物以本次列表为准，删除的条目不再保留）');
      } else {
        const res = await sourceReleaseApi.create(body);
        if (res.code === 200) {
          const r = res.data;
          message.success(`已创建，版本号 ${r.sourceVersionCode}（${r.sourceVersionName}），请上传文件到 source/${r.sourceVersionCode}/ 后回填 artifacts 并发布`, 6);
          load(1);
        }
      }
      setModal(false);
    } catch (error: any) {
      message.error(error.message || '操作失败');
    }
  };

  const showStats = () => {
    sourceReleaseApi.stats().then((res) => {
      if (res.code === 200) {
        setStats(res.data || []);
        setStatsModal(true);
      }
    }).catch(() => message.error('统计加载失败'));
  };

  /**
   * 行内直传（走存储插件用户端接口，与图床同链路）：
   * upload-ticket 拿票据 → PUT 预签名 / UPYUN 表单 / TELEGRAM Worker 三通道直传
   * → register 登记 → permanent-url 换永久地址。
   */
  const uploadToMyFolder = async (file: File): Promise<string> => {
    const foldersRes = await storageApi.listMyFolders();
    if (foldersRes.code !== 200 || !(foldersRes.data || []).length) {
      throw new Error('没有可用的「我的文件夹」，请先在存储页创建');
    }
    const folderId = foldersRes.data[0].id;
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
      const putResp = await fetch(ticket.uploadUrl, { method: 'PUT', body: file });
      if (!putResp.ok) throw new Error(`对象存储返回 ${putResp.status}`);
      const regRes = await storageApi.registerUpload(ticket.uploadId);
      if (regRes.code !== 200) throw new Error(regRes.message);
      publicId = regRes.data.publicId;
    } else if (ticket.form) {
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
      const form = new FormData();
      form.append(ticket.formField || 'file', file);
      const resp = await fetch(ticket.uploadUrl, { method: ticket.method, body: form });
      const json = await resp.json().catch(() => ({}));
      if (!resp.ok || !json.ok) throw new Error(json.message || `Worker 返回 ${resp.status}`);
      publicId = json.publicId;
    }
    const urlRes = await storageApi.permanentUrl(publicId);
    if (urlRes.code !== 200 || !urlRes.data?.url) throw new Error(urlRes.message || '获取永久地址失败');
    return urlRes.data.url;
  };

  const customUpload = (options: any, fieldName: number) => {
    setUploadingIdx(fieldName);
    uploadToMyFolder(options.file as File)
      .then((url) => {
        form.setFieldValue(['artifacts', fieldName, 'url'], url);
        message.success('上传成功，已填入永久地址');
        options.onSuccess?.(url);
      })
      .catch((e: any) => {
        message.error(e.message || '上传失败');
        options.onError?.(e);
      })
      .finally(() => setUploadingIdx(null));
  };

  const columns = [
    { title: 'ID', dataIndex: 'id', width: 70 },
    { title: '版本号', dataIndex: 'sourceVersionCode', width: 120 },
    { title: '版本名', dataIndex: 'sourceVersionName', width: 120 },
    {
      title: '平台', dataIndex: 'platforms', width: 160,
      render: (list: number[]) => (list || []).map((p) => <Tag key={p} color="blue">{platformLabel(p)}</Tag>),
    },
    { title: '渠道', dataIndex: 'channel', width: 90, render: (v: string) => (
      <Tag color={v === 'beta' ? 'orange' : 'purple'}>{enumLabel(channelOpts, v)}</Tag>
    ) },
    { title: '说明', dataIndex: 'notes', ellipsis: true },
    {
      title: '产物', dataIndex: 'artifacts', width: 120,
      render: (list: QtSourceArtifact[]) => (
        <span>{(list || []).length ? `${list.length} 个文件` : <Typography.Text type="secondary">未回填</Typography.Text>}</span>
      ),
    },
    {
      title: '状态', dataIndex: 'published', width: 130,
      render: (_: any, r: QtSourceRelease) => (
        <Tag color={r.bad ? 'error' : r.published ? 'success' : 'default'}>{stateLabel(r)}</Tag>
      ),
    },
    { title: '发布时间', dataIndex: 'publishedAt', width: 110, render: (v: string) => v?.slice(0, 10) || '-' },
    {
      title: '操作', key: 'action', width: 300, fixed: 'right' as const,
      render: (_: any, r: QtSourceRelease) => (
        <Space size={0} wrap>
          <Button type="link" size="small" disabled={!canEdit} onClick={() => openModal(r)}>编辑</Button>
          {r.published ? (
            <Popconfirm title="撤回后客户端停止投递该版本，确认？" disabled={!canEdit} onConfirm={async () => {
              const res = await sourceReleaseApi.unpublish(r.id!);
              if (res.code === 200) refreshAfter('已撤回');
            }}><Button type="link" size="small" disabled={!canEdit}>撤回</Button></Popconfirm>
          ) : (
            <Popconfirm title="发布后客户端立即可拉到该版本，确认？" disabled={!canEdit} onConfirm={async () => {
              const res = await sourceReleaseApi.publish(r.id!);
              if (res.code === 200) refreshAfter(`已发布 ${r.sourceVersionCode}`);
            }}><Button type="link" size="small" disabled={!canEdit}>发布</Button></Popconfirm>
          )}
          {!r.bad && (
            <Popconfirm title="标坏后客户端会回退并拉黑该版本，确认？" disabled={!canEdit} onConfirm={async () => {
              const res = await sourceReleaseApi.markBad(r.id!);
              if (res.code === 200) refreshAfter('已标记坏包');
            }}><Button type="link" size="small" danger disabled={!canEdit}>标坏</Button></Popconfirm>
          )}
          {!r.published && (
            <Popconfirm title="确认删除该 release？" disabled={!canEdit} onConfirm={async () => {
              const res = await sourceReleaseApi.remove(r.id!);
              if (res.code === 200) refreshAfter('已删除');
              else message.error(res.message || '删除失败');
            }}><Button type="link" size="small" danger disabled={!canEdit}>删除</Button></Popconfirm>
          )}
        </Space>
      ),
    },
  ];

  const statsColumns = [
    { title: '版本号', dataIndex: 'source_version_code', width: 130 },
    { title: '平台', dataIndex: 'platform', width: 100, render: platformLabel },
    {
      title: '结果', dataIndex: 'result', width: 120,
      render: (v: string) => v === 'ok'
        ? <Tag color="success">{enumLabel(resultOpts, v)}</Tag>
        : <Tag color="error">{enumLabel(resultOpts, v)}</Tag>,
    },
    { title: '设备数', dataIndex: 'cnt', width: 100 },
  ];

  return (
    <div>
      <div className="filter-bar" style={{ marginBottom: 12 }}>
        <Space>
          {canEdit && <Button type="primary" icon={<PlusOutlined />} onClick={() => openModal()}>新建 release</Button>}
          {!canEdit && <Tag>只读</Tag>}
          <Button icon={<BarChartOutlined />} onClick={showStats}>装机分布</Button>
          <Select
            allowClear placeholder="全部渠道" style={{ width: 140 }} value={channelFilter}
            options={channelOpts} onChange={(v) => setChannelFilter(v)}
          />
        </Space>
      </div>
      <ResizableTable
        rowKey="id"
        columns={columns}
        dataSource={releases}
        loading={loading}
        scroll={{ x: 'max-content' }}
        pagination={{ current: page, total, pageSize: 10, onChange: (p) => { setPage(p); load(p); } }}
      />

      {/* 新建/编辑弹窗 */}
      <Modal
        title={form.getFieldValue('id') != null ? '编辑音源包 release' : '新建音源包 release'}
        open={modal}
        onCancel={() => setModal(false)}
        onOk={submit}
        okButtonProps={{ disabled: !canEdit }}
        width={760}
        destroyOnClose
      >
        <Form form={form} layout="vertical">
          <Form.Item name="id" hidden><Input /></Form.Item>
          <Space size="large" wrap style={{ display: 'flex' }}>
            <Form.Item name="platforms" label="适用平台" rules={[{ required: true, message: '至少选一个平台' }]}>
              <Select mode="multiple" options={platformOpts} style={{ minWidth: 220 }} placeholder="可多选，一个包服务多平台" />
            </Form.Item>
            <Form.Item
              name="channel" label="发布渠道" rules={[{ required: true }]}
              tooltip="正式版（stable）所有用户都能收到；测试版（beta）仅对拥有 user:qt:source:channel:beta 权限（含超管）的用户可见，与版本更新渠道同源（正式包版本号更高时所有人都收到正式包）"
            >
              <Select options={channelOpts} style={{ width: 140 }} />
            </Form.Item>
            <Form.Item name="hostApiVersion" label="宿主契约版本">
              <InputNumber min={1} style={{ width: 100 }} />
            </Form.Item>
          </Space>
          <Form.Item
            name="notes" label="更新说明"
            rules={[{ max: 1024, message: '最长 1024 字' }]}
          >
            <Input.TextArea rows={2} placeholder="客户端设置页展示，如：酷我母带接口修复；kw 链顺序调整" />
          </Form.Item>
          <Form.Item
            label="按平台准入的应用版本号"
            tooltip="候选包含未发布版本与测试版（会标注），可直接勾选。默认每个平台全选现存版本，可删除不需要的（灰度放量）；勾「不限制」或一个不选=该平台不限制（含未来新版本）"
          >
            {watchedPlatforms.length ? watchedPlatforms.map((p) => {
              const row = admission[p] || { unlimited: true, codes: [] as number[] };
              const options = versionOptions(p);
              const ownCount = versionCatalog.filter((v) => v.platforms.includes(p)).length;
              return (
                <Space key={p} align="baseline" style={{ display: 'flex', marginBottom: 4 }}>
                  <Tag color="blue" style={{ marginRight: 0 }}>{platformLabel(p)}</Tag>
                  <Checkbox
                    checked={row.unlimited}
                    onChange={(e) => setAdmission((prev) => ({
                      ...prev, [p]: { ...row, unlimited: e.target.checked, touched: true },
                    }))}
                  >不限制</Checkbox>
                  <Select
                    mode="multiple" allowClear showSearch disabled={row.unlimited}
                    value={row.unlimited ? [] : row.codes}
                    placeholder={options.length ? '默认全选，可删除不需要的版本' : '暂无应用版本，可勾「不限制」'}
                    style={{ minWidth: 260 }}
                    options={options}
                    onChange={(vals: number[]) => setAdmission((prev) => ({
                      ...prev, [p]: { ...row, codes: vals, touched: true },
                    }))}
                  />
                  <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                    本平台 {ownCount} 个 / 共 {versionCatalog.length} 个
                  </Typography.Text>
                </Space>
              );
            }) : <Typography.Text type="secondary">先选择适用平台</Typography.Text>}
            <Paragraph type="secondary" style={{ marginBottom: 0 }}>
              候选含<b>未发布</b>与<b>测试版</b>（下拉里会标注），可直接勾选：适用于「音源包先上、应用版本稍后发」的流程。
              标了其他平台名的版本号在该平台没有真实客户端，仅用于预置。将写入：{JSON.stringify(admissionPreview)}（键缺省或空数组 = 该平台不限制）
            </Paragraph>
          </Form.Item>
          <Paragraph type="secondary" style={{ marginBottom: 8 }}>
            产物是「当前生效全集」。<b>编辑</b>时以本列表为准：删掉的行保存后即从该版本移除（未列出的 path 不再继承）；
            <b>新建</b>时按 path 增量合并——只填本次变更的文件，其余自动继承上一版，这就是「只发 chain」。
            地址可手填永久直链，也可行内上传（上传成功会覆盖手填值）；版本号由后端按 url 是否变化维护。
            标为「已停用」的 path（字典 qt_source_artifact_path 里 status=0，如单包时代的 chain.json /
            source-bundle.js）不会再被继承，也不会再提交——保存后即从该版本移除，无需手工删除。
          </Paragraph>
          <Form.List name="artifacts">
            {(fields, { add, remove }) => (
              <>
                {fields.map((field) => {
                  const current = watchedArtifacts[field.name]?.path;
                  const deprecated = isDeprecatedPath(current);
                  // 已停用的 path 不在字典下拉里，补一条只读选项，否则 Select 只显示裸值、看不出状态
                  const rowOpts = deprecated
                    ? [...pathOpts, { value: current!, label: `${current}（已停用）` }]
                    : pathOpts;
                  return (
                  <Space key={field.key} align="baseline" style={{ display: 'flex', marginBottom: 4 }}>
                    <Form.Item name={[field.name, 'path']} noStyle>
                      <Select
                        showSearch
                        placeholder="选择产物 path"
                        style={{ width: 200 }}
                        options={(rowOpts.length ? rowOpts : [
                          { value: 'meta-bundle.js', label: 'meta-bundle.js' },
                          { value: 'play-bundle.js', label: 'play-bundle.js' },
                        ])}
                      />
                    </Form.Item>
                    {deprecated && <Tag color="warning" style={{ marginRight: 0 }}>已停用，保存后移除</Tag>}
                    <Form.Item
                      name={[field.name, 'url']}
                      noStyle
                      // 手填与上传共用同一格：上传成功会覆盖当前值（customUpload 里
                      // setFieldValue 直接写回）。客户端只认 https 直链（明文 http
                      // 两端都拒），所以这里做前缀校验而不是放任任意字符串进 artifacts。
                      rules={[{
                        validator: (_, v: string | undefined) => {
                          const s = (v ?? '').trim();
                          if (s.length === 0) return Promise.resolve();
                          if (!/^https:\/\//.test(s)) {
                            return Promise.reject(new Error('产物地址必须是 https:// 直链（客户端拒绝明文 http）'));
                          }
                          return Promise.resolve();
                        },
                      }]}
                    >
                      <Input placeholder="永久直链：可手填，上传后自动覆盖" style={{ width: 280 }} allowClear />
                    </Form.Item>
                    <Upload
                      showUploadList={false}
                      disabled={!canEdit}
                      customRequest={(options) => customUpload(options, field.name)}
                    >
                      <Button icon={<UploadOutlined />} loading={uploadingIdx === field.name} size="middle" disabled={!canEdit}>上传</Button>
                    </Upload>
                    <Button type="text" danger disabled={!canEdit} onClick={() => remove(field.name)}>删除</Button>
                  </Space>
                  );
                })}
                <Button type="dashed" disabled={!canEdit} onClick={() => add()} icon={<PlusOutlined />} block>添加产物条目</Button>
              </>
            )}
          </Form.List>
        </Form>
      </Modal>

      {/* 装机分布弹窗 */}
      <Modal title="音源包装机分布（版本 × 平台 × 结果）" open={statsModal} onCancel={() => setStatsModal(false)} footer={null} width={760}>
        <ResizableTable
          rowKey={(r: QtSourceStatRow) => `${r.source_version_code}-${r.platform}-${r.result}`}
          columns={statsColumns}
          dataSource={stats}
          pagination={false}
          size="small"
        />
      </Modal>
    </div>
  );
}
