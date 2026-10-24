import { IconSpark } from '@/components/icons';

/** 空态 / 失败态：虚线纸面 + 极简星标，不做插画 */
export function EmptyState({ title, description }: { title: string; description?: string }) {
  return (
    <div className="rounded-card border border-dashed border-line-strong/80 bg-surface/50 px-6 py-14 text-center">
      <span className="mx-auto flex h-10 w-10 items-center justify-center rounded-full border border-line bg-surface text-ink-faint">
        <IconSpark className="h-3.5 w-3.5" />
      </span>
      <p className="mt-4 text-[13.5px] font-medium text-ink">{title}</p>
      {description ? (
        <p className="mx-auto mt-1.5 max-w-xs text-[12.5px] leading-relaxed text-ink-soft">
          {description}
        </p>
      ) : null}
    </div>
  );
}
