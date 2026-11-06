import { Orbit } from 'lucide-react';

import { cn } from '@/lib/utils';

const MARK_SIZE = {
  sm: 'size-8 rounded-lg [&_svg]:size-4',
  md: 'size-10 rounded-[10px] [&_svg]:size-5',
  lg: 'size-12 rounded-xl [&_svg]:size-6',
} as const;

/** 统一品牌标记：只承载识别，不使用渐变或装饰性阴影。 */
export function BrandMark({
  size = 'md',
  className,
}: {
  size?: keyof typeof MARK_SIZE;
  className?: string;
}) {
  return (
    <span
      aria-hidden="true"
      className={cn(
        'bg-primary text-primary-foreground inline-flex shrink-0 items-center justify-center',
        MARK_SIZE[size],
        className,
      )}
    >
      <Orbit strokeWidth={1.8} />
    </span>
  );
}

/** 首页、登录页与控制台共用的品牌文字组合。 */
export function BrandLockup({
  inverted = false,
  subtitle = '运维控制台',
  size = 'md',
  className,
}: {
  inverted?: boolean;
  subtitle?: string | null;
  size?: keyof typeof MARK_SIZE;
  className?: string;
}) {
  return (
    <span className={cn('inline-flex min-w-0 items-center gap-3', className)}>
      <BrandMark size={size} />
      <span className="min-w-0 leading-none">
        <span
          className={cn(
            'block text-[18px] font-semibold tracking-[-0.025em]',
            inverted ? 'text-[color:var(--color-nav-text)]' : 'text-foreground',
          )}
        >
          Astral
        </span>
        {subtitle ? (
          <span
            className={cn(
              'mt-1.5 block text-[11px] leading-none',
              inverted ? 'text-[color:var(--color-nav-muted)]' : 'text-muted-foreground',
            )}
          >
            {subtitle}
          </span>
        ) : null}
      </span>
    </span>
  );
}
