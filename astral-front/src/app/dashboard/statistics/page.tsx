'use client';

import { useState, useEffect } from 'react';
import { Card, Row, Col, Statistic, Table, DatePicker, Spin } from 'antd';
import ReactECharts from 'echarts-for-react';
import { request } from '@/api/client';
import dayjs from 'dayjs';

const { RangePicker } = DatePicker;

export default function StatisticsPage() {
  const [loading, setLoading] = useState(false);
  const [topApiData, setTopApiData] = useState<any[]>([]);
  const [trendData, setTrendData] = useState<any[]>([]);
  const [dateRange, setDateRange] = useState([dayjs().subtract(7, 'day'), dayjs()]);

  useEffect(() => {
    fetchTopApiStatistics();
  }, []);

  const fetchTopApiStatistics = async () => {
    setLoading(true);
    try {
      const res = await request.get('/api/v1/statistics/api/top', {
        params: { limit: 10 }
      });
      setTopApiData(res.data);
    } catch (error) {
      console.error('获取API统计失败');
    } finally {
      setLoading(false);
    }
  };

  const totalCalls = topApiData.reduce((sum, item) => sum + (item.callCount || 0), 0);
  const totalSuccess = topApiData.reduce((sum, item) => sum + (item.successCount || 0), 0);
  const totalFailure = topApiData.reduce((sum, item) => sum + (item.failureCount || 0), 0);
  const successRate = totalCalls > 0 ? ((totalSuccess / totalCalls) * 100).toFixed(2) : 0;

  const topApiColumns = [
    { title: 'API路径', dataIndex: 'apiPath', ellipsis: true },
    { title: '方法', dataIndex: 'apiMethod', width: 80 },
    { title: '调用次数', dataIndex: 'callCount', width: 100, sorter: (a: any, b: any) => a.callCount - b.callCount },
    { title: '成功次数', dataIndex: 'successCount', width: 100 },
    { title: '失败次数', dataIndex: 'failureCount', width: 100 },
    { title: '平均耗时(ms)', dataIndex: 'avgTime', width: 120, sorter: (a: any, b: any) => a.avgTime - b.avgTime },
    { title: '最大耗时(ms)', dataIndex: 'maxTime', width: 120 },
  ];

  const getTrendOption = () => ({
    title: { text: 'API调用趋势', left: 'center' },
    tooltip: { trigger: 'axis' },
    xAxis: {
      type: 'category',
      data: topApiData.slice(0, 5).map(item => item.apiPath.split('/').pop() || item.apiPath)
    },
    yAxis: { type: 'value', name: '调用次数' },
    series: [{
      data: topApiData.slice(0, 5).map(item => item.callCount || 0),
      type: 'bar',
      itemStyle: { color: '#5470c6' }
    }]
  });

  const getSuccessRateOption = () => ({
    title: { text: 'API成功率分布', left: 'center' },
    tooltip: { trigger: 'item' },
    series: [{
      type: 'pie',
      radius: ['40%', '70%'],
      data: [
        { value: totalSuccess, name: '成功', itemStyle: { color: '#52c41a' } },
        { value: totalFailure, name: '失败', itemStyle: { color: '#ff4d4f' } }
      ]
    }]
  });

  return (
    <Spin spinning={loading}>
      <div>
        <h2 style={{ marginBottom: 24 }}>API调用统计</h2>
        
        <Row gutter={[16, 16]} style={{ marginBottom: 24 }}>
          <Col span={6}>
            <Card>
              <Statistic title="总调用次数" value={totalCalls} suffix="次" />
            </Card>
          </Col>
          <Col span={6}>
            <Card>
              <Statistic title="成功次数" value={totalSuccess} suffix="次" valueStyle={{ color: '#52c41a' }} />
            </Card>
          </Col>
          <Col span={6}>
            <Card>
              <Statistic title="失败次数" value={totalFailure} suffix="次" valueStyle={{ color: '#ff4d4f' }} />
            </Card>
          </Col>
          <Col span={6}>
            <Card>
              <Statistic title="成功率" value={successRate} suffix="%" />
            </Card>
          </Col>
        </Row>

        <Row gutter={[16, 16]} style={{ marginBottom: 24 }}>
          <Col span={12}>
            <Card title="Top API调用排行">
              <ReactECharts option={getTrendOption()} style={{ height: 300 }} />
            </Card>
          </Col>
          <Col span={12}>
            <Card title="成功率分布">
              <ReactECharts option={getSuccessRateOption()} style={{ height: 300 }} />
            </Card>
          </Col>
        </Row>

        <Card title="API调用明细">
          <Table
            columns={topApiColumns}
            dataSource={topApiData}
            rowKey={(record) => `${record.apiPath}-${record.apiMethod}`}
            pagination={false}
            size="small"
          />
        </Card>
      </div>
    </Spin>
  );
}
