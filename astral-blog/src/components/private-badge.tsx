import { Badge } from '@/components/ui/badge';
import { cn } from '@/lib/utils';

/**
 * 「私密」标识。
 *
 * 后端只会把 status=private 的内容返回给「已登录且具备 admin:cms:view」的请求，
 * 所以能渲染出这个徽标，说明当前访客就是有权看的人 —— 它只起提醒作用：
 * 这篇内容对外部访客不可见，别误以为已经公开。
 *
 * 配色刻意区别于站内 sky 主色（用 Tailwind 内置的 amber 系，
 * 与 Badge 的 warning 变体同色阶），一眼能看出是「非公开」状态。
 */
export function PrivateBadge({ className }: { className?: string }) {
  return (
    <Badge
      variant="outline"
      className={cn('border-amber-200 bg-amber-50 text-amber-700', className)}
    >
      私密
    </Badge>
  );
}
