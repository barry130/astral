'use client';

import { useEffect, useState } from 'react';
import { Card, Row, Col, Statistic, Spin, Progress } from 'antd';
import { 
  ApiOutlined, 
  DesktopOutlined, 
  ClusterOutlined, 
  DashboardOutlined,
  RocketOutlined,
  ThunderboltOutlined,
  DatabaseOutlined
} from '@ant-design/icons';
import { monitorApi, SystemMonitorDTO, JvmMonitorDTO, BusinessMonitorDTO } from '@/api/monitor';

/** 将字节数转换为可读格式（B/KB/MB/GB/TB） */
function formatBytes(bytes: number): string {
  if (bytes === 0) return '0 B';
  const k = 1024;
  const sizes = ['B', 'KB', 'MB', 'GB', 'TB'];
  const i = Math.floor(Math.log(bytes) / Math.log(k));
  return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
}

/** 将秒数转换为可读的运行时间格式（X天 X时 X分） */
function formatUptime(seconds: number): string {
  const days = Math.floor(seconds / 86400);
  const hours = Math.floor((seconds % 86400) / 3600);
  const mins = Math.floor((seconds % 3600) / 60);
  return `${days}天 ${hours}时 ${mins}分`;
}

/** 根据CPU使用率返回对应的颜色（红/黄/蓝） */
function getCpuColor(usage: number): string {
  if (usage > 80) return '#ff4d4f';
  if (usage > 60) return '#faad14';
  return '#4a6fa5';
}

/** 根据内存使用率返回对应的颜色（红/黄/绿） */
function getMemoryColor(usage: number): string {
  if (usage > 85) return '#ff4d4f';
  if (usage > 70) return '#faad14';
  return '#52c41a';
}

/**
 * 仪表盘页面组件
 * 实时监控系统运行状态，包括CPU、内存、JVM、业务指标等
 */
export default function DashboardPage() {
  /** 加载状态 */
  const [loading, setLoading] = useState(true);
  /** 系统监控数据 */
  const [system, setSystem] = useState<SystemMonitorDTO | null>(null);
  /** JVM监控数据 */
  const [jvm, setJvm] = useState<JvmMonitorDTO | null>(null);
  /** 业务监控数据 */
  const [business, setBusiness] = useState<BusinessMonitorDTO | null>(null);

  /** 并行加载所有监控数据 */
  const loadData = () => {
    Promise.all([
      monitorApi.getSystemInfo(),
      monitorApi.getJvmInfo(),
      monitorApi.getBusinessInfo(),
    ])
      .then(([sysRes, jvmRes, bizRes]) => {
        setSystem(sysRes.data);
        setJvm(jvmRes.data);
        setBusiness(bizRes.data);
      })
      .finally(() => setLoading(false));
  };

  /** 组件挂载时加载数据，并设置5秒定时刷新 */
  useEffect(() => {
    loadData();
    const interval = setInterval(loadData, 5000);
    return () => clearInterval(interval);
  }, []);

  if (loading) {
    return <Spin size="large" style={{ display: 'block', margin: '100px auto' }} />;
  }

  return (
    <div>
      <div style={{ marginBottom: 24 }}>
        <h2 className="page-title" style={{ marginBottom: 8 }}>仪表盘</h2>
        <p style={{ color: '#909399', margin: 0 }}>实时监控系统运行状态</p>
      </div>

      <Row gutter={[16, 16]}>
        <Col xs={24} sm={12} lg={6}>
          <Card className="stat-card fade-in-up stagger-1" hoverable>
            <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between' }}>
              <div>
                <Statistic
                  title="CPU 使用率"
                  value={system?.cpuUsage || 0}
                  precision={1}
                  suffix="%"
                  valueStyle={{ color: getCpuColor(system?.cpuUsage || 0), fontSize: 28 }}
                />
                <Progress 
                  percent={Math.min(100, system?.cpuUsage || 0)} 
                  strokeColor={getCpuColor(system?.cpuUsage || 0)}
                  showInfo={false}
                  style={{ marginTop: 12 }}
                />
              </div>
              <div style={{ 
                width: 48, 
                height: 48, 
                borderRadius: 12, 
                background: 'rgba(74, 144, 217, 0.1)',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center'
              }}>
                <DashboardOutlined style={{ fontSize: 22, color: '#4a6fa5' }} />
              </div>
            </div>
          </Card>
        </Col>
        <Col xs={24} sm={12} lg={6}>
          <Card className="stat-card fade-in-up stagger-2" hoverable>
            <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between' }}>
              <div>
                <Statistic
                  title="堆内存使用率"
                  value={system?.memoryUsage || 0}
                  precision={1}
                  suffix="%"
                  valueStyle={{ color: getMemoryColor(system?.memoryUsage || 0), fontSize: 28 }}
                />
                <Progress 
                  percent={Math.min(100, system?.memoryUsage || 0)}
                  strokeColor={getMemoryColor(system?.memoryUsage || 0)}
                  showInfo={false}
                  style={{ marginTop: 12 }}
                />
              </div>
              <div style={{ 
                width: 48, 
                height: 48, 
                borderRadius: 12, 
                background: 'rgba(82, 196, 26, 0.1)',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center'
              }}>
                <DesktopOutlined style={{ fontSize: 22, color: '#52c41a' }} />
              </div>
            </div>
          </Card>
        </Col>
        <Col xs={24} sm={12} lg={6}>
          <Card className="stat-card fade-in-up stagger-3" hoverable>
            <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between' }}>
              <div>
                <Statistic
                  title="总生成数"
                  value={business?.sequenceGenerationTotal || 0}
                  valueStyle={{ fontSize: 28 }}
                />
                <div style={{ color: '#909399', fontSize: 12, marginTop: 4 }}>
                  活跃配置: {business?.configCount || 0}
                </div>
              </div>
              <div style={{ 
                width: 48, 
                height: 48, 
                borderRadius: 12, 
                background: 'rgba(255, 148, 52, 0.1)',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center'
              }}>
                <RocketOutlined style={{ fontSize: 22, color: '#ff9434' }} />
              </div>
            </div>
          </Card>
        </Col>
        <Col xs={24} sm={12} lg={6}>
          <Card className="stat-card fade-in-up stagger-4" hoverable>
            <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between' }}>
              <div>
                <Statistic
                  title="QPS"
                  value={Math.round(business?.sequenceGenerationQps || 0)}
                  precision={0}
                  valueStyle={{ fontSize: 28 }}
                />
                <div style={{ color: '#909399', fontSize: 12, marginTop: 4 }}>
                  每秒序列生成数
                </div>
              </div>
              <div style={{ 
                width: 48, 
                height: 48, 
                borderRadius: 12, 
                background: 'rgba(114, 46, 209, 0.1)',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center'
              }}>
                <ThunderboltOutlined style={{ fontSize: 22, color: '#722ed1' }} />
              </div>
            </div>
          </Card>
        </Col>
      </Row>

      <Row gutter={[16, 16]} style={{ marginTop: 16 }}>
        <Col xs={24} lg={12}>
          <Card 
            title={
              <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                <DatabaseOutlined style={{ color: '#4a6fa5' }} />
                <span>JVM 监控</span>
              </div>
            }
            bordered={false}
            className="fade-in-up stagger-5"
          >
            <Row gutter={[16, 20]}>
              <Col xs={{ span: 24 }} sm={{ span: 12 }}>
                <div style={{ padding: 16, background: '#fafbfc', borderRadius: 8 }}>
                  <div style={{ color: '#909399', fontSize: 13, marginBottom: 4 }}>堆内存使用</div>
                  <div style={{ fontSize: 18, fontWeight: 600, color: '#303133' }}>
                    {formatBytes(jvm?.heapUsed || 0)}
                  </div>
                  <div style={{ color: '#c0c4cc', fontSize: 12 }}>/ {formatBytes(jvm?.heapMax || 0)}</div>
                </div>
              </Col>
              <Col xs={{ span: 24 }} sm={{ span: 12 }}>
                <div style={{ padding: 16, background: '#fafbfc', borderRadius: 8 }}>
                  <div style={{ color: '#909399', fontSize: 13, marginBottom: 4 }}>堆内存使用率</div>
                  <div style={{ fontSize: 18, fontWeight: 600, color: '#303133' }}>
                    {jvm?.heapUsage?.toFixed(1) || 0}%
                  </div>
                  <Progress 
                    percent={jvm?.heapUsage || 0} 
                    showInfo={false}
                    strokeColor="#4a6fa5"
                    style={{ marginTop: 8 }}
                  />
                </div>
              </Col>
              <Col xs={{ span: 24 }} sm={{ span: 12 }}>
                <div style={{ padding: 16, background: '#fafbfc', borderRadius: 8 }}>
                  <div style={{ color: '#909399', fontSize: 13, marginBottom: 4 }}>线程数</div>
                  <div style={{ fontSize: 18, fontWeight: 600, color: '#303133' }}>
                    {jvm?.threadCount || 0}
                  </div>
                </div>
              </Col>
              <Col xs={{ span: 24 }} sm={{ span: 12 }}>
                <div style={{ padding: 16, background: '#fafbfc', borderRadius: 8 }}>
                  <div style={{ color: '#909399', fontSize: 13, marginBottom: 4 }}>GC 次数</div>
                  <div style={{ fontSize: 18, fontWeight: 600, color: '#303133' }}>
                    {jvm?.gcCount || 0}
                  </div>
                </div>
              </Col>
            </Row>
          </Card>
        </Col>
        <Col xs={24} lg={12}>
          <Card 
            title={
              <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                <ClusterOutlined style={{ color: '#52c41a' }} />
                <span>系统信息</span>
              </div>
            }
            bordered={false}
            className="fade-in-up stagger-6"
          >
            <Row gutter={[16, 20]}>
              <Col xs={{ span: 24 }} sm={{ span: 12 }}>
                <div style={{ padding: 16, background: '#fafbfc', borderRadius: 8 }}>
                  <div style={{ color: '#909399', fontSize: 13, marginBottom: 4 }}>磁盘使用率</div>
                  <div style={{ 
                    fontSize: 24, 
                    fontWeight: 600, 
                    color: (system?.diskUsage || 0) > 80 ? '#ff4d4f' : '#303133' 
                  }}>
                    {system?.diskUsage?.toFixed(1) || 0}%
                  </div>
                  <Progress 
                    percent={system?.diskUsage || 0} 
                    showInfo={false}
                    strokeColor={(system?.diskUsage || 0) > 80 ? '#ff4d4f' : '#52c41a'}
                    style={{ marginTop: 8 }}
                  />
                </div>
              </Col>
              <Col xs={{ span: 24 }} sm={{ span: 12 }}>
                <div style={{ padding: 16, background: '#fafbfc', borderRadius: 8 }}>
                  <div style={{ color: '#909399', fontSize: 13, marginBottom: 4 }}>运行时间</div>
                  <div style={{ fontSize: 18, fontWeight: 600, color: '#303133' }}>
                    {formatUptime((system?.uptime || 0) / 1000)}
                  </div>
                </div>
              </Col>
            </Row>
          </Card>
        </Col>
      </Row>
    </div>
  );
}
