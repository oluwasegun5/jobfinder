import { Suspense } from "react";

import { SiteShell } from "@/components/layout/site-shell";
import { GuestOnly } from "@/features/auth/route-guards";
import { cn } from "@/lib/utils";

/**
 * Auth pages sit on the page background on phones (no card chrome to scroll past) and in a card from `sm` up.
 * The form components keep their own markup; their headings and inline links take the type scale and link colour here.
 */
export default function AuthLayout({ children }: LayoutProps<"/">) {
  return (
    <SiteShell className="items-center px-4 py-10 sm:justify-center sm:px-6 sm:py-16">
      <div
        className={cn(
          "w-full max-w-md text-sm",
          "sm:rounded-2xl sm:border sm:bg-card sm:p-8 sm:shadow-sm sm:dark:shadow-none",
          "[&_h1]:text-title [&_h1]:font-semibold [&_h1]:tracking-[-0.02em]",
          "[&_a]:font-medium [&_a]:text-primary [&_a]:decoration-primary/40 [&_a:hover]:decoration-primary",
          "[&_input[type=checkbox]]:size-5",
        )}
      >
        <GuestOnly>
          <Suspense>{children}</Suspense>
        </GuestOnly>
      </div>
    </SiteShell>
  );
}
