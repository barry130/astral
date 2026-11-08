import { IconSpark } from '@/components/icons';
import { RetryButton } from '@/components/retry-button';

/** 空态 / 失败态：虚线纸面 + 极简星标，不做插画 */
export function EmptyState({
  title,
  description,
  retry = false,
}: {
  title: string;
  description?: string;
  retry?: boolean;
}) {
  return (
    <div className="rounded-card border-line-strong/80 bg-surface/50 border border-dashed px-6 py-14 text-center">
      <span className="border-line bg-surface text-ink-faint mx-auto flex h-10 w-10 items-center justify-center rounded-full border">
        <IconSpark className="h-3.5 w-3.5" />
      </span>
      <p className="text-ink mt-4 text-[13.5px] font-medium">{title}</p>
      {description ? (
        <p className="text-ink-soft mx-auto mt-1.5 max-w-xs text-[12.5px] leading-relaxed">
          {description}
        </p>
      ) : null}
      {retry ? <RetryButton /> : null}
    </div>
  );
}
