'use client';

import { createContext, useContext } from 'react';

/** The nearest actual Dialog DOM node, not a guessed document-wide modal. */
export const DialogPortalContext = createContext<HTMLElement | null>(null);
export function useDialogPortalContainer(): HTMLElement | null {
  return useContext(DialogPortalContext);
}
