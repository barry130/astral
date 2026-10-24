import { NextResponse } from 'next/server';
import { canEditContent, getSessionUser } from '@/lib/auth-server';

/**
 * 当前登录态（浏览器查询用）。
 * 未登录返回 { loggedIn: false }，而不是 401 —— 前台是公开站点，
 * 「没登录」是正常状态，不该被当成错误在控制台刷红。
 */
export async function GET() {
  const user = await getSessionUser();
  if (!user) {
    return NextResponse.json({ loggedIn: false });
  }
  return NextResponse.json({
    loggedIn: true,
    user: {
      id: user.id,
      username: user.username,
      nickname: user.nickname,
      userType: user.userType,
    },
    // 前端据此决定是否显示编辑入口（后端仍会二次校验）
    canEdit: canEditContent(user),
  });
}
