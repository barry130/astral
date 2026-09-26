'use client';

/**
 * 音源包热更新管理 Tab（SOURCE_UPDATE_DESIGN §五/§七）
 *
 * 发布渠道复用版本更新字典 qt_update_channel（stable=正式版 / beta=测试版）：
 * 正式版所有用户都能收到；测试版仅对拥有 qt_admin / qt_tester 权限（含超管）的用户投放，
 * 正式版版本号更高时所有用户都收到正式版。
 *
 * 发布流程对应「先拿号 → 上传文件 → 回填 artifacts → 发布」四步：
 * 1. 新建：只填平台/渠道/准入/说明，后端生成版本号（如 2026091801）随响应返回；
 * 2. 把变更文件上传到 source/<版本号>/ 目录（图床/存储页），拿到永久地址；
 * 3. 编辑该 release，按 path 填 {path, url}（version 留空自动 +1），未填的 path 继承上一版；
 * 4. 点「发布」。
 */
import { useEffect, useState } from 'react';
import {
  Button, Checkbox, Form, Input, InputNumber, Modal, Popconfirm, Select, Space, Tag, Typography, Upload, message,
} from 'antd';
import { BarChartOutlined, PlusOutlined, UploadOutlined } from '@ant-design/icons';
import { sourceReleaseApi, qtAdminApi, QtSourceRelease, QtSourceArtifact, QtSourceStatRow, enumLabel } from '@/api/qt';
import { fetchDictOptions, DictOption } from '@/api/dict';
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
];

const FALLBACK_CHANNEL_OPTS = [
  { value: 'stable', label: '正式版' },
  { value: 'beta', label: '测试版' },
];

const FALLBACK_STATE_LABEL: Record<string, string> = {
  unpublished: '未发布', published: '已发布', bad: '坏包',
};

/** artifacts 编辑行：path 从数据字典下拉选择，url 由行内上传生成（永久地址） */
type ArtifactRow = { path?: string; url?: string };

/** 按平台准入编辑行：unlimited=不限制（提交时省略该平台键）；touched=已显式设置过，晚到的版本列表不再自动全选 */
type AdmissionRow = { unlimited: boolean; codes: number[]; touched?: boolean };

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
  /** 现存 App 版本号（平台码 → versionCode 降序），用于准入下拉自动反显 */
  const [appVersions, setAppVersions] = useState<Record<number, number[]>>({});
  /** 按平台准入选择状态（key=平台码），随「适用平台」联动增删 */
  const [admission, setAdmission] = useState<Record<number, AdmissionRow>>({});

  // 由数据字典派生出的下拉/标签选项（数值类将 value 转为 number 以便比较）
  const platformOpts = (dict.qt_update_platform?.length ? dict.qt_update_platform : FALLBACK_PLATFORM_OPTS)
    .map((o) => ({ value: Number(o.value), label: o.label }));
  const channelOpts = dict.qt_update_channel?.length ? dict.qt_update_channel : FALLBACK_CHANNEL_OPTS;
  const pathOpts = dict.qt_source_artifact_path || [];
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

  // 现存应用版本号（取版本更新管理的数据），按平台分组供准入下拉反显
  useEffect(() => {
    qtAdminApi.updates(1, 200).then((res) => {
      if (res.code !== 200) return;
      const grouped: Record<number, number[]> = {};
      for (const u of res.data.records || []) {
        if (u.type == null || u.versionCode == null) continue;
        (grouped[u.type] = grouped[u.type] || []).push(u.versionCode);
      }
      for (const k of Object.keys(grouped)) {
        grouped[Number(k)] = Array.from(new Set(grouped[Number(k)])).sort((a, b) => b - a);
      }
      setAppVersions(grouped);
    }).catch(() => {});
  }, []);

  // 平台增删时同步准入行：新增平台默认全选现存版本，删除平台连带移除；
  // 版本列表晚到时为未显式设置过的空行补默认全选
  const platformKey = watchedPlatforms.join(',');
  useEffect(() => {
    setAdmission((prev) => {
      const next: Record<number, AdmissionRow> = {};
      for (const p of watchedPlatforms) {
        const old = prev[p];
        const codes = appVersions[p] || [];
        if (old) {
          next[p] = !old.touched && !old.codes.length && codes.length
            ? { ...old, unlimited: false, codes: [...codes] }
            : old;
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

  const openModal = (record?: QtSourceRelease) => {
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
      form.setFieldsValue({ platforms: [1103], channel: 'stable', hostApiVersion: 1, artifacts: [] });
      // 新建默认：已选平台各自全选现存版本（用户自行删除不需要的）
      setAdmission(() => {
        const adm: Record<number, AdmissionRow> = {};
        for (const p of [1103]) {
          const codes = appVersions[p] || [];
          adm[p] = { unlimited: !codes.length, codes: [...codes] };
        }
        return adm;
      });
    }
    setModal(true);
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
    };
    try {
      if (values.id != null) {
        const res = await sourceReleaseApi.update(values.id, body);
        if (res.code === 200) refreshAfter('已保存（artifacts 按 path 合并，未提交项继承上一版）');
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
          <Button type="link" size="small" onClick={() => openModal(r)}>编辑</Button>
          {r.published ? (
            <Popconfirm title="撤回后客户端停止投递该版本，确认？" onConfirm={async () => {
              const res = await sourceReleaseApi.unpublish(r.id!);
              if (res.code === 200) refreshAfter('已撤回');
            }}><Button type="link" size="small">撤回</Button></Popconfirm>
          ) : (
            <Popconfirm title="发布后客户端立即可拉到该版本，确认？" onConfirm={async () => {
              const res = await sourceReleaseApi.publish(r.id!);
              if (res.code === 200) refreshAfter(`已发布 ${r.sourceVersionCode}`);
            }}><Button type="link" size="small">发布</Button></Popconfirm>
          )}
          {!r.bad && (
            <Popconfirm title="标坏后客户端会回退并拉黑该版本，确认？" onConfirm={async () => {
              const res = await sourceReleaseApi.markBad(r.id!);
              if (res.code === 200) refreshAfter('已标记坏包');
            }}><Button type="link" size="small" danger>标坏</Button></Popconfirm>
          )}
          {!r.published && (
            <Popconfirm title="确认删除该 release？" onConfirm={async () => {
              const res = await sourceReleaseApi.remove(r.id!);
              if (res.code === 200) refreshAfter('已删除');
              else message.error(res.message || '删除失败');
            }}><Button type="link" size="small" danger>删除</Button></Popconfirm>
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
          <Button type="primary" icon={<PlusOutlined />} onClick={() => openModal()}>新建 release</Button>
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
        width={680}
        destroyOnClose
      >
        <Form form={form} layout="vertical">
          <Form.Item name="id" hidden><Input /></Form.Item>
          <Space size="large" style={{ display: 'flex' }}>
            <Form.Item name="platforms" label="适用平台" rules={[{ required: true, message: '至少选一个平台' }]}>
              <Select mode="multiple" options={platformOpts} style={{ minWidth: 220 }} placeholder="可多选，一个包服务多平台" />
            </Form.Item>
            <Form.Item
              name="channel" label="发布渠道" rules={[{ required: true }]}
              tooltip="正式版（stable）所有用户都能收到；测试版（beta）仅对拥有 qt_admin / qt_tester 权限（含超管）的用户投放，与版本更新渠道同源"
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
            tooltip="默认每个平台全选现存版本，可删除不需要的（灰度放量）；勾「不限制」或一个不选=该平台不限制（含未来新版本）"
          >
            {watchedPlatforms.length ? watchedPlatforms.map((p) => {
              const row = admission[p] || { unlimited: true, codes: [] as number[] };
              const versions = appVersions[p] || [];
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
                    mode="multiple" allowClear disabled={row.unlimited}
                    value={row.unlimited ? [] : row.codes}
                    placeholder={versions.length ? '默认全选，可删除不需要的版本' : '该平台暂无已发布版本'}
                    style={{ minWidth: 240 }}
                    options={versions.map((c) => ({ value: c, label: String(c) }))}
                    onChange={(vals: number[]) => setAdmission((prev) => ({
                      ...prev, [p]: { ...row, codes: vals, touched: true },
                    }))}
                  />
                </Space>
              );
            }) : <Typography.Text type="secondary">先选择适用平台</Typography.Text>}
            <Paragraph type="secondary" style={{ marginBottom: 0 }}>
              将写入：{JSON.stringify(admissionPreview)}（键缺省或空数组 = 该平台不限制）
            </Paragraph>
          </Form.Item>
          <Paragraph type="secondary" style={{ marginBottom: 8 }}>
            产物按 path 合并：只填本次变更的文件（上传后自动填入永久地址，版本号由后端按 url 是否变化维护），
            其余文件自动继承上一版——这就是「只发 chain」。path 选项来自数据字典 qt_source_artifact_path，可在字典管理里扩展。
          </Paragraph>
          <Form.List name="artifacts">
            {(fields, { add, remove }) => (
              <>
                {fields.map((field) => (
                  <Space key={field.key} align="baseline" style={{ display: 'flex', marginBottom: 4 }}>
                    <Form.Item name={[field.name, 'path']} noStyle>
                      <Select
                        showSearch
                        placeholder="选择产物 path"
                        style={{ width: 200 }}
                        options={(pathOpts.length ? pathOpts : [
                          { value: 'chain.json', label: 'chain.json' },
                          { value: 'source-bundle.js', label: 'source-bundle.js' },
                        ])}
                      />
                    </Form.Item>
                    <Form.Item name={[field.name, 'url']} noStyle>
                      <Input placeholder="上传后自动填入永久地址" style={{ width: 280 }} readOnly />
                    </Form.Item>
                    <Upload
                      showUploadList={false}
                      customRequest={(options) => customUpload(options, field.name)}
                    >
                      <Button icon={<UploadOutlined />} loading={uploadingIdx === field.name} size="middle">上传</Button>
                    </Upload>
                    <Button type="text" danger onClick={() => remove(field.name)}>删除</Button>
                  </Space>
                ))}
                <Button type="dashed" onClick={() => add()} icon={<PlusOutlined />} block>添加产物条目</Button>
              </>
            )}
          </Form.List>
        </Form>
      </Modal>

      {/* 装机分布弹窗 */}
      <Modal title="音源包装机分布（版本 × 平台 × 结果）" open={statsModal} onCancel={() => setStatsModal(false)} footer={null} width={560}>
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
