import type { Metadata } from "next";

import { Feed } from "@/features/feed/feed";
import { RequireOnboarding } from "@/features/onboarding/require-onboarding";

export const metadata: Metadata = { title: "For you" };

export default function Page() {
  return (
    <RequireOnboarding>
      <div className="mx-auto flex max-w-4xl flex-col gap-4">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight">For you</h1>
          <p className="text-sm text-muted-foreground">
            Jobs ranked against your resume and preferences. Save, hide or mark jobs as applied and the feed learns what you want.
          </p>
        </div>
        <Feed />
      </div>
    </RequireOnboarding>
  );
}
