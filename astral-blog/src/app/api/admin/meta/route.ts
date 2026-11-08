import { backendFetch, getSessionState, canViewContent } from '@/lib/auth-server';
import { privateJson, upstreamFailure } from '@/lib/bff';
import { parseResult } from '@/lib/result';
import { parseCategoryTree, parseTagList, parseWikiTree } from '@/lib/cms-validation';
import { parseStatusDictionary } from '@/lib/editor';

export const dynamic = 'force-dynamic';

/** Failures are explicit, never silently replaced by destructive empty options. */
export async function GET() {
  const session = await getSessionState();
  if (!session.available)
    return privateJson({ code: 502, msg: '会话暂时无法确认，请稍后重试。', data: null }, 502);
  const user = session.user;
  if (!user) return privateJson({ code: 401, msg: '请先登录。', data: null });
  if (!canViewContent(user))
    return privateJson({ code: 403, msg: '当前账号没有内容查看权限。', data: null });
  try {
    const paths = [
      '/api/v1/admin/cms/category/tree',
      '/api/v1/admin/cms/tag/list',
      '/api/v1/admin/cms/page/tree',
      '/api/v1/admin/system/dict/data/byCode?code=cms_content_status',
    ];
    const responses = await Promise.all(paths.map((path) => backendFetch(path)));
    const bodies = await Promise.all(
      responses.map(async (response) => parseResult(await response.json().catch(() => null))),
    );
    const failed = bodies.findIndex(
      (body, index) => responses[index].status !== 200 || body?.code !== 200,
    );
    if (failed >= 0)
      return upstreamFailure(
        bodies[failed],
        '编辑基础数据暂时不可用，请重试。',
        responses[failed].status,
      );
    const categories = parseCategoryTree(bodies[0]?.data);
    const tags = parseTagList(bodies[1]?.data);
    const pages = parseWikiTree(bodies[2]?.data);
    const statuses = parseStatusDictionary(bodies[3]?.data);
    if (!categories || !tags || !pages || !statuses)
      return privateJson({ code: 502, msg: '编辑基础数据格式异常。', data: null }, 502);
    return privateJson({ code: 200, data: { categories, tags, pages, statuses } });
  } catch {
    return privateJson({ code: 502, msg: '编辑基础数据暂时不可用，请重试。', data: null }, 502);
  }
}
