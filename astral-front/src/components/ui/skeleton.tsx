import * as React from 'react';

import { cn } from '@/lib/utils';

function Skeleton({ className, ...props }: React.ComponentProps<'div'>) {
  return (
    <div
      data-slot="skeleton"
      className={cn('animate-pulse rounded-lg bg-[color:color-mix(in_srgb,var(--color-text)_8%,transparent)]', className)}
      {...props}
    />
  );
}

export { Skeleton };
