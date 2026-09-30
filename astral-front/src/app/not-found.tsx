import Link from 'next/link';

import { Button } from '@/components/ui/button';

/** 404 页面：路由不存在时展示，避免出现框架默认英文提示 */
export default function NotFound() {
  return (
    <div className="flex min-h-[70vh] flex-col items-center justify-center gap-4 bg-background px-6 text-center">
      <p className="text-sm font-medium text-muted-foreground">404</p>
      <h1 className="text-lg font-semibold text-foreground">页面不存在</h1>
      <p className="max-w-md text-sm text-muted-foreground">你访问的页面可能已被移除，或链接地址有误。</p>
      <Button asChild>
        <Link href="/">返回首页</Link>
      </Button>
    </div>
  );
}
