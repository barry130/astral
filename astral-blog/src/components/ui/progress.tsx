'use client';

import * as React from 'react';
import * as ProgressPrimitive from '@radix-ui/react-progress';

import { cn } from '@/lib/utils';

function Progress({
  className,
  value,
  max = 100,
  indicatorClassName,
  ...props
}: React.ComponentProps<typeof ProgressPrimitive.Root> & { indicatorClassName?: string }) {
  const normalizedMax = Number.isFinite(max) && max > 0 ? max : 100;
  const normalized =
    typeof value === 'number' && Number.isFinite(value)
      ? Math.max(0, Math.min(normalizedMax, value))
      : undefined;
  return (
    <ProgressPrimitive.Root
      value={normalized}
      max={normalizedMax}
      data-slot="progress"
      className={cn('bg-secondary relative h-2 w-full overflow-hidden rounded-full', className)}
      {...props}
    >
      <ProgressPrimitive.Indicator
        data-slot="progress-indicator"
        className={cn(
          'bg-primary h-full w-full flex-1 transition-[width] duration-300 ease-out',
          indicatorClassName,
        )}
        style={{ transform: `translateX(-${100 - ((normalized ?? 0) / normalizedMax) * 100}%)` }}
      />
    </ProgressPrimitive.Root>
  );
}

export { Progress };
