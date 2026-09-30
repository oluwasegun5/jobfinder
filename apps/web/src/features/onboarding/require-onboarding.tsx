"use client";

import { useRouter } from "next/navigation";
import { useEffect, type ReactNode } from "react";

import { useProfile } from "@/features/profile/queries";

/**
 * Sends people who have not finished onboarding to it, from pages that assume a filled-in profile. Client-side,
 * like the auth guards (docs/adr/0014); core-api does not depend on it. If the profile cannot be loaded the page is
 * shown anyway: an API hiccup must not lock a signed-in user out of the app.
 */
export function RequireOnboarding({ children }: { children: ReactNode }) {
  const profile = useProfile();
  const router = useRouter();
  const notOnboarded = profile.data !== undefined && !profile.data.onboardingCompleted;
  // Cached data may be stale while a refetch is running, so only redirect on a settled answer.
  const incomplete = notOnboarded && !profile.isFetching;

  useEffect(() => {
    if (incomplete) router.replace("/onboarding");
  }, [incomplete, router]);

  if (profile.isPending || notOnboarded) {
    return (
      <div role="status" className="flex flex-1 items-center justify-center p-8 text-sm text-muted-foreground">
        Loading…
      </div>
    );
  }
  return children;
}
