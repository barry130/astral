import * as React from 'react';

import { cn } from '@/lib/utils';

function Input({ className, type, ...props }: React.ComponentProps<'input'>) {
  return (
    <input
      type={type}
      data-slot="input"
      className={cn(
        'flex h-10 w-full min-w-0 rounded-xl border border-[color:var(--color-border)] bg-card px-3.5 py-1 text-[13.5px] shadow-[0_1px_2px_rgba(16,32,26,0.03)] transition-[color,box-shadow,border-color,background-color] outline-none',
        'placeholder:text-[color:var(--color-text-tertiary)] selection:bg-primary selection:text-primary-foreground',
        'hover:border-[color:color-mix(in_srgb,var(--color-brand)_28%,var(--color-border))]',
        'focus-visible:border-[color:var(--color-brand)] focus-visible:ring-[3px] focus-visible:ring-[color:color-mix(in_srgb,var(--color-brand)_18%,transparent)]',
        'disabled:pointer-events-none disabled:cursor-not-allowed disabled:bg-[var(--color-bg-tint)] disabled:opacity-60',
        'file:inline-flex file:h-7 file:border-0 file:bg-transparent file:text-sm file:font-medium',
        'aria-invalid:border-destructive aria-invalid:ring-destructive/20',
        className,
      )}
      {...props}
    />
  );
}

export { Input };
