'use client';

import { Suspense } from 'react';
import dynamic from 'next/dynamic';
import { usePathname, useRouter, useSearchParams } from 'next/navigation';
import { Tabs, TabsList, TabsTrigger } from '@/components/ui/tabs';
import {
  MailOutlined,
  FileTextOutlined,
  MobileOutlined,
  NotificationOutlined,
  ApiOutlined,
} from '@/components/antd-compat/icons';
import { AdminLoadingNotice } from '@/components/admin/AdminLoadNotice';
import { mailCenterHref, mailCenterTab } from '@/components/admin/mail-center-tabs';
import { PageHeader } from '@/components/page-header';

const loading = () => <AdminLoadingNotice label="正在加载消息中心页签…" />;
const MailAccountPage = dynamic(() => import('./account/page'), { loading });
const MessageTemplatePage = dynamic(() => import('./message-template/page'), { loading });
const SmsProviderPage = dynamic(() => import('./sms-provider/page'), { loading });
const NoticeManagement = dynamic(() => import('@/components/admin/NoticeManagement'), { loading });
const NotifyRulePage = dynamic(() => import('./notify-rule/page'), { loading });

/** Controlled URL state preserves deep links and follows browser back/forward changes. */
function MailCenter() {
  const searchParams = useSearchParams();
  const router = useRouter();
  const pathname = usePathname();
  const activeTab = mailCenterTab(searchParams.get('tab'));
  const changeTab = (tab: string) =>
    router.push(
      mailCenterHref(pathname || '/dashboard/system/mail', searchParams.toString(), tab),
      { scroll: false },
    );

  return (
    <div className="bg-background min-h-full">
      <div className="px-4 pt-6 sm:px-6">
        <PageHeader
          title="消息中心"
          description="邮箱、短信与站内信的发送通道、模板与订阅规则配置"
        />
      </div>
      <div className="bg-background sticky top-14 z-20 overflow-x-auto px-4 sm:px-6">
        <Tabs value={activeTab} onValueChange={changeTab}>
          <TabsList className="bg-muted/60 h-9 min-w-max justify-start">
            <TabsTrigger value="account" className="px-3 text-xs">
              <MailOutlined /> 邮箱帐户
            </TabsTrigger>
            <TabsTrigger value="messageTemplate" className="px-3 text-xs">
              <FileTextOutlined /> 消息模板
            </TabsTrigger>
            <TabsTrigger value="smsProvider" className="px-3 text-xs">
              <MobileOutlined /> 短信供应商
            </TabsTrigger>
            <TabsTrigger value="notice" className="px-3 text-xs">
              <NotificationOutlined /> 通知管理
            </TabsTrigger>
            <TabsTrigger value="rule" className="px-3 text-xs">
              <ApiOutlined /> 订阅规则
            </TabsTrigger>
          </TabsList>
        </Tabs>
      </div>
      <div className="px-4 pt-4 pb-6 sm:px-6">
        {activeTab === 'account' && <MailAccountPage />}
        {activeTab === 'messageTemplate' && <MessageTemplatePage />}
        {activeTab === 'smsProvider' && <SmsProviderPage />}
        {activeTab === 'notice' && <NoticeManagement />}
        {activeTab === 'rule' && <NotifyRulePage />}
      </div>
    </div>
  );
}

export default function MailPage() {
  return (
    <Suspense fallback={<AdminLoadingNotice label="正在加载消息中心…" />}>
      <MailCenter />
    </Suspense>
  );
}
