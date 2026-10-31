import * as React from 'react';
import { Slot } from '@radix-ui/react-slot';
import { cva, type VariantProps } from 'class-variance-authority';

import { cn } from '@/lib/utils';

const buttonVariants = cva(
  "inline-flex items-center justify-center gap-2 whitespace-nowrap rounded-xl text-[13.5px] font-medium transition-all disabled:pointer-events-none disabled:opacity-45 [&_svg]:pointer-events-none [&_svg:not([class*='size-'])]:size-4 [&_svg]:shrink-0 outline-none focus-visible:border-ring focus-visible:ring-ring/25 focus-visible:ring-[3px] cursor-pointer",
  {
    variants: {
      variant: {
        default:
          'bg-primary text-primary-foreground shadow-[0_8px_18px_-10px_rgba(11,143,98,0.85)] hover:bg-[var(--color-brand-hover)] hover:shadow-[0_10px_22px_-10px_rgba(11,143,98,0.9)] active:translate-y-px disabled:bg-[color:color-mix(in_srgb,var(--color-brand)_16%,transparent)] disabled:text-[color:color-mix(in_srgb,var(--color-brand)_70%,transparent)] disabled:shadow-none',
        destructive:
          'bg-destructive text-destructive-foreground shadow-[0_8px_18px_-10px_rgba(220,50,50,0.7)] hover:bg-destructive/90',
        outline:
          'border border-[color:var(--color-border)] bg-card text-[color:var(--color-text-secondary)] shadow-[0_1px_2px_rgba(16,32,26,0.04)] hover:border-[color:color-mix(in_srgb,var(--color-brand)_35%,transparent)] hover:bg-[var(--color-brand-soft)] hover:text-[color:var(--color-brand-text)]',
        secondary:
          'bg-[var(--color-bg-tint)] text-[color:var(--color-text)] border border-transparent hover:bg-[var(--color-brand-soft)] hover:text-[color:var(--color-brand-text)]',
        ghost:
          'text-[color:var(--color-text-secondary)] hover:bg-[var(--color-bg-tint)] hover:text-[color:var(--color-text)]',
        link: 'text-[color:var(--color-brand-text)] underline-offset-4 hover:underline',
      },
      size: {
        default: 'h-10 px-4 has-[>svg]:px-3.5',
        sm: 'h-9 rounded-[10px] gap-1.5 px-3.5 text-[13px] has-[>svg]:px-3',
        lg: 'h-11 rounded-[13px] px-6 text-[14px] has-[>svg]:px-5',
        icon: 'size-10 rounded-xl',
      },
    },
    defaultVariants: {
      variant: 'default',
      size: 'default',
    },
  },
);

function Button({
  className,
  variant,
  size,
  asChild = false,
  ...props
}: React.ComponentProps<'button'> &
  VariantProps<typeof buttonVariants> & {
    asChild?: boolean;
  }) {
  const Comp = asChild ? Slot : 'button';

  return (
    <Comp
      data-slot="button"
      className={cn(buttonVariants({ variant, size, className }))}
      {...props}
    />
  );
}

export { Button, buttonVariants };
