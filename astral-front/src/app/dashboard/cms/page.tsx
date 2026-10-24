import { redirect } from 'next/navigation';

/** /dashboard/cms 默认落地到文章列表 */
export default function CmsIndexPage() {
  redirect('/dashboard/cms/post');
}
