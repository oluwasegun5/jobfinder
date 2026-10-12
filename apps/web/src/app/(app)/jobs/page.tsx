import type { Metadata } from "next";
import { Suspense } from "react";

import { JobSearch } from "@/features/jobs/job-search";
import { RequireOnboarding } from "@/features/onboarding/require-onboarding";
import { LoadingState } from "@/components/state";

export const metadata: Metadata = { title: "Jobs" };

export default function Page() {
  return (
    <RequireOnboarding>
      <div className="mx-auto flex max-w-4xl flex-col gap-4">
        <h1 className="text-title font-semibold">Jobs</h1>
        <Suspense fallback={<LoadingState>Loading jobs</LoadingState>}>
          <JobSearch />
        </Suspense>
      </div>
    </RequireOnboarding>
  );
}
