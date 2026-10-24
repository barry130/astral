import { NextResponse } from 'next/server';
import { backendFetch, getSessionUser, canEditContent } from '@/lib/auth-server';

/**
 * 编辑区需要的后台「基础数据」：分类树、标签列表、页面树。
 *
 * 为什么合成一个接口：三份数据都是打开编辑器时一次性要用的，
 * 分三个请求在同一个渲染过程里并发度一样但要过三次鉴权与三次网络往返。
 * 这里在服务端并发取回，任一失败给空数组 —— 编辑器要能打开，缺选项不该阻塞编辑。
 */

const ADMIN_CATEGORY_TREE = '/api/v1/admin/cms/category/tree';
const ADMIN_TAG_LIST = '/api/v1/admin/cms/tag/list';
const ADMIN_PAGE_TREE = '/api/v1/admin/cms/page/tree';

interface Envelope<T> {
  code?: number;
  data?: T | null;
}

async function safeGet<T>(path: string): Promise<T | null> {
  try {
    const res = await backendFetch(path);
    if (!res.ok) return null;
    const body = (await res.json()) as Envelope<T>;
    return body.code === 200 && body.data != null ? body.data : null;
  } catch {
    return null;
  }
}

export async function GET() {
  const user = await getSessionUser();
  if (!user) {
    return NextResponse.json({ code: 401, message: '请先登录。' }, { status: 401 });
  }
  if (!canEditContent(user)) {
    return NextResponse.json({ code: 403, message: '当前账号没有内容编辑权限。' }, { status: 403 });
  }

  const [categories, tags, pages] = await Promise.all([
    safeGet<unknown[]>(ADMIN_CATEGORY_TREE),
    safeGet<unknown[]>(ADMIN_TAG_LIST),
    safeGet<unknown[]>(ADMIN_PAGE_TREE),
  ]);

  return NextResponse.json({
    code: 200,
    data: {
      categories: categories ?? [],
      tags: tags ?? [],
      pages: pages ?? [],
    },
  });
}
