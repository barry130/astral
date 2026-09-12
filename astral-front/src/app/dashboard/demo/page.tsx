'use client';

import { Card, Tag, Typography, Space } from 'antd';

const { Title, Paragraph, Text } = Typography;

export default function DemoPluginPage() {
  return (
    <div>
      <Card>
        <Title level={4} className="page-title">示例插件页面</Title>
        <Paragraph>
          本页面由 <Tag color="blue">astral-plugin-demo</Tag> 通过
          <Text code>PluginFrontendExtension</Text> 动态注册到侧边栏菜单。
          插件只需实现 <Text code>AstralPlugin</Text> 接口并注入 Spring 容器即可被自动发现。
        </Paragraph>
        <Space direction="vertical" size="small">
          <Text>后端接口：<Text code>GET /api/v1/admin/plugin/demo/hello</Text></Text>
          <Text>数据来自插件自己的 Controller，无需修改主应用代码。</Text>
        </Space>
      </Card>
    </div>
  );
}