'use client';
import { Button } from '@/components/ui/button';

/** Retrying an independent read never replays an unknown write outcome. */
export function RetryButton() {
  return (
    <Button
      type="button"
      variant="outline"
      className="mt-4"
      onClick={() => window.location.reload()}
    >
      重新加载
    </Button>
  );
}
