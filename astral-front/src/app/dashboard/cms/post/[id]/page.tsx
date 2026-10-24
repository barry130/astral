'use client';

import { useParams } from 'next/navigation';

import PostForm from '../PostForm';

/** 编辑文章：/dashboard/cms/post/[id] */
export default function CmsPostEditPage() {
  const params = useParams<{ id: string }>();
  return <PostForm postId={Number(params.id)} />;
}
