interface PageHeaderProps {
  eyebrow?: string;
  title: string;
  description?: string;
}

/** 内页刊头：标题与说明分栏，底部粗刊线明确正文起点。 */
export function PageHeader({ eyebrow, title, description }: PageHeaderProps) {
  return (
    <header className="mb-9 grid gap-5 border-b border-ink pb-8 sm:grid-cols-[minmax(0,1fr)_minmax(220px,360px)] sm:items-end sm:pb-9">
      <div>
        {eyebrow ? <p className="eyebrow">{eyebrow}</p> : null}
        <h1 className="mt-3 font-display text-[36px] font-semibold leading-[1.2] tracking-[-0.015em] text-ink sm:text-[clamp(40px,3.4vw,50px)]">
          {title}
        </h1>
      </div>
      {description ? <p className="max-w-xl text-[14px] leading-7 text-ink-soft sm:justify-self-end">{description}</p> : null}
    </header>
  );
}
