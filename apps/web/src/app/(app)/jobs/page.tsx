import type { Metadata } from "next";
import { Suspense } from "react";

import { JobSearch } from "@/features/jobs/job-search";
import { RequireOnboarding } from "@/features/onboarding/require-onboarding";

export const metadata: Metadata = { title: "Jobs" };

export default function Page() {
  return (
    <RequireOnboarding>
      <div className="mx-auto flex max-w-4xl flex-col gap-4">
        <h1 className="text-2xl font-semibold tracking-tight">Jobs</h1>
        <Suspense fallback={<p role="status">Loading jobs</p>}>
          <JobSearch />
        </Suspense>
      </div>
    </RequireOnboarding>
  );
}
