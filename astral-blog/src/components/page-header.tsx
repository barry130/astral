interface PageHeaderProps {
  eyebrow?: string;
  title: string;
  description?: string;
}

/** 内页刊头：标题与说明分栏，底部粗刊线明确正文起点。 */
export function PageHeader({ eyebrow, title, description }: PageHeaderProps) {
  return (
    <header className="border-ink mb-9 grid gap-5 border-b pb-8 sm:grid-cols-[minmax(0,1fr)_minmax(220px,360px)] sm:items-end sm:pb-9">
      <div>
        {eyebrow ? <p className="eyebrow">{eyebrow}</p> : null}
        <h1 className="font-display text-ink mt-3 text-[36px] leading-[1.2] font-semibold tracking-[-0.015em] sm:text-[clamp(40px,3.4vw,50px)]">
          {title}
        </h1>
      </div>
      {description ? (
        <p className="text-ink-soft max-w-xl text-[14px] leading-7 sm:justify-self-end">
          {description}
        </p>
      ) : null}
    </header>
  );
}
