'use client';

import { Suspense } from 'react';
import { useSearchParams } from 'next/navigation';

import WikiForm from '../WikiForm';

function NewWikiInner() {
  const searchParams = useSearchParams();
  const parentId = searchParams.get('parentId') || undefined;
  return <WikiForm defaultParentId={parentId} />;
}

/** 新建 Wiki 页面：/dashboard/cms/page/new（可带 ?parentId= 预选父页面） */
export default function CmsWikiNewPage() {
  // useSearchParams 需在 Suspense 内使用（Next 静态预渲染约束）
  return (
    <Suspense fallback={null}>
      <NewWikiInner />
    </Suspense>
  );
}
