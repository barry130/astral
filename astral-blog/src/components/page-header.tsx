interface PageHeaderProps {
  /** 小标签（分类 / 标签 / 知识库），用字距拉开的大写风格 */
  eyebrow?: string;
  title: string;
  description?: string;
}

/** 内页统一标题区：小标签 + 标题 + 一句说明，底部发丝线与内容分隔 */
export function PageHeader({ eyebrow, title, description }: PageHeaderProps) {
  return (
    <header className="mb-8 border-b border-line pb-6">
      {eyebrow ? <p className="eyebrow">{eyebrow}</p> : null}
      <h1 className="mt-2 text-[25px] font-semibold tracking-[-0.02em] text-ink sm:text-[clamp(28px,0.55vw+21px,34px)]">
        {title}
      </h1>
      {description ? (
        <p className="mt-3 max-w-xl text-[13px] leading-relaxed text-ink-soft">{description}</p>
      ) : null}
    </header>
  );
}
