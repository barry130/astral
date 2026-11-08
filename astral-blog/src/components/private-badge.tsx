import { Badge } from '@/components/ui/badge';
import { cn } from '@/lib/utils';
import { getContentStatuses } from '@/lib/cms-status';

/**
 * 「私密」标识。
 *
 * 后端只会把 status=private 的内容返回给「已登录且具备 admin:cms:view」的请求，
 * 所以能渲染出这个徽标，说明当前访客就是有权看的人 —— 它只起提醒作用：
 * 这篇内容对外部访客不可见，别误以为已经公开。
 *
 * 文案来自 cms_content_status，字典失败回退原始值；配色只承担前端访问提示语义。
 */
export async function PrivateBadge({ className }: { className?: string }) {
  const options = await getContentStatuses();
  const label = options.find((option) => option.value === 'private')?.label ?? 'private';
  return (
    <Badge
      variant="outline"
      className={cn('border-brand-line bg-brand-wash text-brand', className)}
    >
      {label}
    </Badge>
  );
}
