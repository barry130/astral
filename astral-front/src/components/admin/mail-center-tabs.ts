const TABS = ['account', 'messageTemplate', 'smsProvider', 'notice', 'rule'] as const;
export type MailCenterTab = (typeof TABS)[number];

/** URL-derived state: legacy aliases resolve on every render, not only during initial mounting. */
export function mailCenterTab(value: string | null): MailCenterTab {
  if (value === 'template' || value === 'smsTemplate') return 'messageTemplate';
  return value && (TABS as readonly string[]).includes(value)
    ? (value as MailCenterTab)
    : 'account';
}

export function mailCenterHref(pathname: string, search: string, tab: string): string {
  const query = new URLSearchParams(search);
  query.set('tab', mailCenterTab(tab));
  return `${pathname}?${query.toString()}`;
}
