import { Loader2 } from "lucide-react";
import type { ReactNode } from "react";

import { cn } from "@/lib/utils";

/**
 * The shared loading, empty and error states of the in-app screens (docs/adr/0043-design-system.md). Calm and short:
 * say what is happening or what is missing, and offer one clear next action.
 */

/** A quiet, announced "working on it" line. The spinner stops under reduced motion (a global rule). */
export function LoadingState({ children, className }: { children: ReactNode; className?: string }) {
  return (
    <p role="status" className={cn("flex items-center gap-2 py-4 text-sm text-muted-foreground", className)}>
      <Loader2 className="size-4 shrink-0 animate-spin" aria-hidden />
      {children}
    </p>
  );
}

/**
 * Nothing here yet. An optional title names the situation, the body says what to do, and {@code action} is the one
 * next step (a link or button, shown below the text).
 */
export function EmptyState({
  title,
  children,
  action,
  className,
}: {
  title?: ReactNode;
  children: ReactNode;
  action?: ReactNode;
  className?: string;
}) {
  return (
    <div className={cn("flex max-w-prose flex-col items-start gap-3 rounded-2xl border border-dashed bg-card p-5", className)}>
      <div className="flex flex-col gap-1">
        {title && <h2 className="text-base font-semibold">{title}</h2>}
        <div className="text-sm text-muted-foreground">{children}</div>
      </div>
      {action}
    </div>
  );
}

/** Inline link inside running text: primary, underlined, with a visible focus ring from the global rule. */
export const inlineLinkClass = "rounded-sm font-medium text-primary underline underline-offset-4 hover:decoration-2";
