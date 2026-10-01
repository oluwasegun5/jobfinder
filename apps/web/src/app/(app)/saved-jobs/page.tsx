import type { Metadata } from "next";

import { SavedJobs } from "@/features/jobs/saved-jobs";
import { RequireOnboarding } from "@/features/onboarding/require-onboarding";

export const metadata: Metadata = { title: "Saved jobs" };

export default function Page() {
  return (
    <RequireOnboarding>
      <div className="mx-auto flex max-w-4xl flex-col gap-4">
        <h1 className="text-2xl font-semibold tracking-tight">Saved jobs</h1>
        <SavedJobs />
      </div>
    </RequireOnboarding>
  );
}
