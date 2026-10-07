'use client';

import { useEffect, useState } from 'react';
import { Button, Card, Space, Table, Tag, Tabs, message } from '@/components/antd-compat';
import { DownloadOutlined, FileTextOutlined, SecurityScanOutlined, CheckCircleOutlined, CloseCircleOutlined } from '@/components/antd-compat/icons';
import { logApi, OperateLog, LoginLog } from '@/api/log';
import { clientHeaders } from '@/lib/client-info';
import { readCsrfToken } from '@/api/client';

/**
 * CSV 导出（fetch 直连）：
 * axios 响应拦截器面向 Result 包装体，二进制流不兼容（会走 result.code 分支被拒），
 * 与「对象存储预签名直传」一样属于约定的 fetch 例外，需手动带客户端系统头与 CSRF 头。
 *
 * 认证凭据不再手动携带：它是 HttpOnly Cookie，浏览器同源请求自动带上
 * （这也是为什么这里不能加 `credentials: 'omit'`）。但正因为凭据是自动携带的，
 * 写语义的请求必须回填 CSRF 头，否则会被后端的双提交校验拦下。
 */
const exportCsv = async (url: string, filename: string) => {
  const headers: Record<string, string> = { ...clientHeaders() };
  const csrf = readCsrfToken();
  if (csrf) headers['X-CSRF-Token'] = csrf;
  const resp = await fetch(url, { headers, credentials: 'same-origin' });
  if (!resp.ok) throw new Error(`导出失败（${resp.status}）`);
  const blob = await resp.blob();
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = filename;
  a.click();
  URL.revokeObjectURL(a.href);
};

/**
 * 日志管理页面组件
 * 提供操作日志和登录日志的分页查看功能
 */
export default function LogPage() {
  /** 加载状态 */
  const [loading, setLoading] = useState(true);
  /** 当前激活的标签页：operate（操作日志）或login（登录日志） */
  const [activeTab, setActiveTab] = useState('operate');
  /** 操作日志列表 */
  const [operateLogs, setOperateLogs] = useState<OperateLog[]>([]);
  /** 登录日志列表 */
  const [loginLogs, setLoginLogs] = useState<LoginLog[]>([]);
  /** 操作日志总数 */
  const [operateTotal, setOperateTotal] = useState(0);
  /** 登录日志总数 */
  const [loginTotal, setLoginTotal] = useState(0);
  /** 当前页码 */
  const [pageNum, setPageNum] = useState(1);
  /** 每页条数 */
  const [pageSize, setPageSize] = useState(20);

  /** 加载操作日志 */
  const loadOperateLogs = () => {
    setLoading(true);
    logApi.getOperateLogPage(pageNum, pageSize)
      .then((res) => {
        if (res.code === 200) {
          setOperateLogs(res.data.records);
          setOperateTotal(res.data.total);
        }
      })
      .catch(() => message.error('操作日志加载失败'))
      .finally(() => setLoading(false));
  };

  /** 加载登录日志 */
  const loadLoginLogs = () => {
    setLoading(true);
    logApi.getLoginLogPage(pageNum, pageSize)
      .then((res) => {
        if (res.code === 200) {
          setLoginLogs(res.data.records);
          setLoginTotal(res.data.total);
        }
      })
      .catch(() => message.error('登录日志加载失败'))
      .finally(() => setLoading(false));
  };

  /** 标签页切换：重置页码为1 */
  const handleTabChange = (key: string) => {
    setActiveTab(key);
    setPageNum(1);
  };

  /** 监听标签页、页码、页大小变化，自动加载对应数据 */
  useEffect(() => {
    if (activeTab === 'operate') {
      loadOperateLogs();
    } else {
      loadLoginLogs();
    }
  }, [activeTab, pageNum, pageSize]);

  /** 操作日志表格列定义 */
  const operateColumns = [
    { title: 'ID', dataIndex: 'id', key: 'id', width: 60 },
    { title: '用户名', dataIndex: 'username', key: 'username', width: 100, render: (v: string) => v || '-' },
    { title: '模块', dataIndex: 'module', key: 'module', width: 100, render: (v: string) => <Tag color="blue">{v}</Tag> },
    { title: '操作类型', dataIndex: 'operateType', key: 'operateType', width: 80 },
    { title: '请求方法', dataIndex: 'requestMethod', key: 'requestMethod', width: 70, render: (v: string) => <Tag>{v}</Tag> },
    { title: '请求URL', dataIndex: 'requestUrl', key: 'requestUrl', ellipsis: true, width: 180, render: (v: string) => <code style={{ fontSize: 12 }}>{v}</code> },
    { title: 'IP', dataIndex: 'ip', key: 'ip', width: 110, render: (v: string) => v || '-' },
    { title: '耗时', dataIndex: 'executeTime', key: 'executeTime', width: 70, render: (v: number) => v ? <span style={{ color: '#52c41a' }}>{v}ms</span> : '-' },
    { title: '状态', dataIndex: 'status', key: 'status', width: 60, render: (v: number) => (
      v === 1 
        ? <Tag icon={<CheckCircleOutlined />} color="success">成功</Tag> 
        : <Tag icon={<CloseCircleOutlined />} color="error">失败</Tag>
    )},
    { title: '时间', dataIndex: 'createTime', key: 'createTime', width: 150 },
  ];

  /** 登录日志表格列定义 */
  const loginColumns = [
    { title: 'ID', dataIndex: 'id', key: 'id', width: 60 },
    { title: '用户名', dataIndex: 'username', key: 'username', width: 110, render: (v: string) => v || '-' },
    { title: '登录类型', dataIndex: 'loginType', key: 'loginType', width: 90, render: (v: string) => <Tag>{v}</Tag> },
    { title: 'IP', dataIndex: 'ip', key: 'ip', width: 110, render: (v: string) => v || '-' },
    { title: '位置', dataIndex: 'location', key: 'location', width: 140, render: (v: string) => v || '-' },
    { title: '状态', dataIndex: 'status', key: 'status', width: 70, render: (v: number) => (
      v === 1 
        ? <Tag icon={<CheckCircleOutlined />} color="success">成功</Tag> 
        : <Tag icon={<CloseCircleOutlined />} color="error">失败</Tag>
    )},
    { title: '消息', dataIndex: 'msg', key: 'msg', ellipsis: true, render: (v: string) => v || '-' },
    { title: '时间', dataIndex: 'loginTime', key: 'loginTime', width: 150 },
  ];

  return (
    <div>
      <div style={{ marginBottom: 24 }}>
        <h2 className="page-title" style={{ marginBottom: 8 }}>日志管理</h2>
        <p style={{ color: '#909399', margin: 0 }}>查看系统操作日志与登录日志</p>
      </div>

      <Card className="fade-in-up">
        <Tabs 
          activeKey={activeTab} 
          onChange={handleTabChange}
          items={[
            {
              key: 'operate',
              label: (
                <span style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                  <FileTextOutlined />
                  <span>操作日志</span>
                </span>
              ),
              children: (
                <div>
                <div style={{ marginBottom: 8, textAlign: 'right' }}>
                  <Button icon={<DownloadOutlined />} onClick={() => {
                    exportCsv('/api/v1/admin/log/operate_log/export',
                      `operate-logs-${new Date().toISOString().slice(0, 10)}.csv`)
                      .then(() => message.success('已导出'))
                      .catch((e) => message.error(e.message || '导出失败'));
                  }}>导出 CSV</Button>
                </div>
                <Table
                  dataSource={operateLogs}
                  columns={operateColumns}
                  rowKey="id"
                  loading={loading}
                  pagination={{
                    current: pageNum,
                    pageSize: pageSize,
                    total: operateTotal,
                    onChange: (page) => setPageNum(page),
                    onShowSizeChange: (current, size) => setPageSize(size),
                    showSizeChanger: true,
                    showTotal: (total) => `共 ${total} 条`,
                  }}
                  scroll={{ x: 'max-content' }}
                />
                </div>
              ),
            },
            {
              key: 'login',
              label: (
                <span style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                  <SecurityScanOutlined />
                  <span>登录日志</span>
                </span>
              ),
              children: (
                <div>
                <div style={{ marginBottom: 8, textAlign: 'right' }}>
                  <Button icon={<DownloadOutlined />} onClick={() => {
                    exportCsv('/api/v1/admin/log/login_log/export',
                      `login-logs-${new Date().toISOString().slice(0, 10)}.csv`)
                      .then(() => message.success('已导出'))
                      .catch((e) => message.error(e.message || '导出失败'));
                  }}>导出 CSV</Button>
                </div>
                <Table
                  dataSource={loginLogs}
                  columns={loginColumns}
                  rowKey="id"
                  loading={loading}
                  pagination={{
                    current: pageNum,
                    pageSize: pageSize,
                    total: loginTotal,
                    onChange: (page) => setPageNum(page),
                    onShowSizeChange: (current, size) => setPageSize(size),
                    showSizeChanger: true,
                    showTotal: (total) => `共 ${total} 条`,
                  }}
                  scroll={{ x: 'max-content' }}
                />
                </div>
              ),
            },
          ]}
        />
      </Card>
    </div>
  );
}