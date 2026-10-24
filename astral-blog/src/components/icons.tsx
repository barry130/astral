import type { SVGProps } from 'react';

// 站内线性图标：统一 24 网格、1.6 描边、圆头圆角，尺寸交给调用方（h-3.5 等）。
// 不引入图标库，避免新增依赖；纯展示，无需客户端能力。

type IconProps = SVGProps<SVGSVGElement>;

const strokeProps = {
  viewBox: '0 0 24 24',
  fill: 'none',
  stroke: 'currentColor',
  strokeWidth: 1.6,
  strokeLinecap: 'round',
  strokeLinejoin: 'round',
  'aria-hidden': true,
} as const;

/** 站点标记 / 空态装饰：四角星（Astral 意象） */
export function IconSpark({ className, ...props }: IconProps) {
  return (
    <svg viewBox="0 0 24 24" fill="currentColor" aria-hidden="true" className={className} {...props}>
      <path d="M12 2.6c.72 4.6 3.8 7.68 8.4 8.4-4.6.72-7.68 3.8-8.4 8.4-.72-4.6-3.8-7.68-8.4-8.4 4.6-.72 7.68-3.8 8.4-8.4Z" />
    </svg>
  );
}

export function IconCalendar({ className, ...props }: IconProps) {
  return (
    <svg {...strokeProps} className={className} {...props}>
      <rect x="3.25" y="5" width="17.5" height="15.75" rx="3" />
      <path d="M8 3.25V7M16 3.25V7M3.25 10.25h17.5" />
    </svg>
  );
}

export function IconEye({ className, ...props }: IconProps) {
  return (
    <svg {...strokeProps} className={className} {...props}>
      <path d="M2.75 12S6.4 5.75 12 5.75 21.25 12 21.25 12 17.6 18.25 12 18.25 2.75 12 2.75 12Z" />
      <circle cx="12" cy="12" r="2.6" />
    </svg>
  );
}

export function IconClock({ className, ...props }: IconProps) {
  return (
    <svg {...strokeProps} className={className} {...props}>
      <circle cx="12" cy="12" r="8.75" />
      <path d="M12 7.5V12l3 1.8" />
    </svg>
  );
}

export function IconPin({ className, ...props }: IconProps) {
  return (
    <svg {...strokeProps} className={className} {...props}>
      <path d="M9.5 3.5h5l-.6 5 3.35 3.25H14.5v4.1L12 20l-2.5-4.15V10.75H6.75L10.1 8.5l-.6-5Z" />
    </svg>
  );
}

export function IconArrowRight({ className, ...props }: IconProps) {
  return (
    <svg {...strokeProps} className={className} {...props}>
      <path d="M4.5 12h15M13.5 6l6 6-6 6" />
    </svg>
  );
}

export function IconArrowUp({ className, ...props }: IconProps) {
  return (
    <svg {...strokeProps} className={className} {...props}>
      <path d="M12 19.5v-15M6 10.5l6-6 6 6" />
    </svg>
  );
}

export function IconChevronLeft({ className, ...props }: IconProps) {
  return (
    <svg {...strokeProps} className={className} {...props}>
      <path d="M14.5 6 8.5 12l6 6" />
    </svg>
  );
}

export function IconChevronRight({ className, ...props }: IconProps) {
  return (
    <svg {...strokeProps} className={className} {...props}>
      <path d="M9.5 6l6 6-6 6" />
    </svg>
  );
}
