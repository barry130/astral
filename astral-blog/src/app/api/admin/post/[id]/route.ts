import { readAdminContent, saveAdminContent, type ContentRouteContext } from '@/lib/admin-route';

export const dynamic = 'force-dynamic';
export const GET = (request: Request, context: ContentRouteContext) =>
  readAdminContent('post', request, context);
export const PUT = (request: Request, context: ContentRouteContext) =>
  saveAdminContent('post', request, context);
