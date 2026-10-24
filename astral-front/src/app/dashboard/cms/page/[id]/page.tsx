'use client';

import { useParams } from 'next/navigation';

import WikiForm from '../WikiForm';

/** 编辑 Wiki 页面：/dashboard/cms/page/[id] */
export default function CmsWikiEditPage() {
  const params = useParams<{ id: string }>();
  return <WikiForm pageId={Number(params.id)} />;
}
