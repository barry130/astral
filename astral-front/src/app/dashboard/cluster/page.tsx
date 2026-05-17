'use client';

import { useEffect, useState } from 'react';
import { Table, Tag, Card, Row, Col, Statistic, Button, Popconfirm, message, Spin } from 'antd';
import { ClusterOutlined, ReloadOutlined, DisconnectOutlined, NodeIndexOutlined, CheckCircleOutlined, CloseCircleOutlined, WarningOutlined } from '@ant-design/icons';
import { clusterApi, ClusterNode, ClusterStatus } from '@/api/cluster';

/**
 * 集群管理页面组件
 * 展示集群节点状态统计和节点列表，支持节点下线操作
 */
export default function ClusterPage() {
  /** 加载状态 */
  const [loading, setLoading] = useState(true);
  /** 节点列表数据 */
  const [nodes, setNodes] = useState<ClusterNode[]>([]);
  /** 集群状态统计 */
  const [status, setStatus] = useState<ClusterStatus | null>(null);

  /** 加载集群节点列表和状态统计 */
  const loadData = () => {
    setLoading(true);
    Promise.all([
      clusterApi.getAllNodes(),
      clusterApi.getStatus(),
    ])
      .then(([nodesRes, statusRes]) => {
        if (nodesRes.code === 200) setNodes(nodesRes.data);
        if (statusRes.code === 200) setStatus(statusRes.data);
      })
      .finally(() => setLoading(false));
  };

  /** 组件挂载时加载数据 */
  useEffect(() => {
    loadData();
  }, []);

  /** 下线指定节点 */
  const handleOffline = async (nodeId: string) => {
    try {
      await clusterApi.offlineNode(nodeId);
      message.success('节点已下线');
      loadData();
    } catch (error: any) {
      message.error(error.message);
    }
  };

  /** 根据节点状态返回对应的Tag标签 */
  const getStatusTag = (status: string) => {
    switch (status) {
      case 'ONLINE':
        return <Tag icon={<CheckCircleOutlined />} color="success">在线</Tag>;
      case 'OFFLINE':
        return <Tag icon={<CloseCircleOutlined />} color="error">离线</Tag>;
      case 'EXPIRED':
        return <Tag icon={<WarningOutlined />} color="warning">过期</Tag>;
      default:
        return <Tag>{status}</Tag>;
    }
  };

  /** 表格列定义 */
  const columns = [
    { title: '节点ID', dataIndex: 'nodeId', key: 'nodeId', render: (v: string) => <span style={{ fontFamily: 'monospace', fontWeight: 500 }}>{v}</span> },
    { title: '节点名称', dataIndex: 'nodeName', key: 'nodeName', render: (v: string) => <span style={{ fontWeight: 500 }}>{v}</span> },
    { title: 'WorkerId', dataIndex: 'workerId', key: 'workerId', render: (v: number) => <Tag color="blue">{v}</Tag> },
    { title: '地址', key: 'address', render: (_: any, r: ClusterNode) => <code style={{ background: '#f0f2f5', padding: '2px 6px', borderRadius: 4 }}>{r.ipAddress}:{r.port}</code> },
    { title: '状态', dataIndex: 'status', key: 'status', render: (v: string) => getStatusTag(v) },
    { title: '心跳时间', dataIndex: 'lastHeartbeat', key: 'lastHeartbeat', render: (v: string) => new Date(v).toLocaleString() },
    { title: '当前节点', dataIndex: 'isCurrent', key: 'isCurrent', render: (v: boolean) => v ? <Tag color="purple">当前</Tag> : '-' },
    { title: '操作', key: 'action', width: 100, render: (_: any, r: ClusterNode) => (
      // 非当前节点且在线状态才显示下线按钮
      !r.isCurrent && r.status === 'ONLINE' && (
        <Popconfirm title="确认下线此节点?" onConfirm={() => handleOffline(r.nodeId)}>
          <Button type="link" danger icon={<DisconnectOutlined />}>下线</Button>
        </Popconfirm>
      )
    )},
  ];

  return (
    <div>
      <div style={{ marginBottom: 24 }}>
        <h2 className="page-title" style={{ marginBottom: 8 }}>集群管理</h2>
        <p style={{ color: '#909399', margin: 0 }}>管理集群节点状态与信息</p>
      </div>

      <Spin spinning={loading}>
        <Row gutter={[16, 16]} style={{ marginBottom: 20 }}>
          <Col xs={12} sm={6}>
            <Card className="stat-card fade-in-up stagger-1" hoverable>
              <div style={{ textAlign: 'center' }}>
                <div style={{ 
                  width: 48, 
                  height: 48, 
                  borderRadius: 12,
                  background: 'linear-gradient(135deg, rgba(74, 144, 217, 0.1) 0%, rgba(74, 144, 217, 0.2) 100%)',
                  display: 'inline-flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                  marginBottom: 12
                }}>
                  <NodeIndexOutlined style={{ fontSize: 22, color: '#4a90d9' }} />
                </div>
                <Statistic 
                  title="总节点数" 
                  value={status?.totalNodes || 0} 
                  valueStyle={{ fontSize: 28, fontWeight: 600 }}
                />
              </div>
            </Card>
          </Col>
          <Col xs={12} sm={6}>
            <Card className="stat-card fade-in-up stagger-2" hoverable>
              <div style={{ textAlign: 'center' }}>
                <div style={{ 
                  width: 48, 
                  height: 48, 
                  borderRadius: 12,
                  background: 'linear-gradient(135deg, rgba(82, 196, 26, 0.1) 0%, rgba(82, 196, 26, 0.2) 100%)',
                  display: 'inline-flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                  marginBottom: 12
                }}>
                  <CheckCircleOutlined style={{ fontSize: 22, color: '#52c41a' }} />
                </div>
                <Statistic 
                  title="在线节点" 
                  value={status?.onlineNodes || 0} 
                  valueStyle={{ fontSize: 28, fontWeight: 600, color: '#52c41a' }}
                />
              </div>
            </Card>
          </Col>
          <Col xs={12} sm={6}>
            <Card className="stat-card fade-in-up stagger-3" hoverable>
              <div style={{ textAlign: 'center' }}>
                <div style={{ 
                  width: 48, 
                  height: 48, 
                  borderRadius: 12,
                  background: 'linear-gradient(135deg, rgba(255, 77, 79, 0.1) 0%, rgba(255, 77, 79, 0.2) 100%)',
                  display: 'inline-flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                  marginBottom: 12
                }}>
                  <CloseCircleOutlined style={{ fontSize: 22, color: '#ff4d4f' }} />
                </div>
                <Statistic 
                  title="离线节点" 
                  value={status?.offlineNodes || 0} 
                  valueStyle={{ fontSize: 28, fontWeight: 600, color: '#ff4d4f' }}
                />
              </div>
            </Card>
          </Col>
          <Col xs={12} sm={6}>
            <Card className="stat-card fade-in-up stagger-4" hoverable>
              <div style={{ textAlign: 'center' }}>
                <div style={{ 
                  width: 48, 
                  height: 48, 
                  borderRadius: 12,
                  background: 'linear-gradient(135deg, rgba(250, 173, 20, 0.1) 0%, rgba(250, 173, 20, 0.2) 100%)',
                  display: 'inline-flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                  marginBottom: 12
                }}>
                  <WarningOutlined style={{ fontSize: 22, color: '#faad14' }} />
                </div>
                <Statistic 
                  title="过期节点" 
                  value={status?.expiredNodes || 0} 
                  valueStyle={{ fontSize: 28, fontWeight: 600, color: '#faad14' }}
                />
              </div>
            </Card>
          </Col>
        </Row>
        
        <Card 
          className="fade-in-up stagger-5"
          title={
            <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
              <ClusterOutlined style={{ color: '#4a90d9' }} />
              <span>节点列表</span>
            </div>
          }
          extra={
            <Button icon={<ReloadOutlined />} onClick={loadData}>刷新</Button>
          }
        >
          <Table 
            dataSource={nodes} 
            columns={columns} 
            rowKey="nodeId"
            pagination={{ 
              showSizeChanger: true,
              showTotal: (total) => `共 ${total} 个节点`
            }}
          />
        </Card>
      </Spin>
    </div>
  );
}