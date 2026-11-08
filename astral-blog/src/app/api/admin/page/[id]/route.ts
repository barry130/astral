import { readAdminContent, saveAdminContent, type ContentRouteContext } from '@/lib/admin-route';

export const dynamic = 'force-dynamic';
export const GET = (request: Request, context: ContentRouteContext) =>
  readAdminContent('page', request, context);
export const PUT = (request: Request, context: ContentRouteContext) =>
  saveAdminContent('page', request, context);
