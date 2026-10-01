import type { Metadata } from "next";

import { JobDetailView } from "@/features/jobs/job-detail";
import { RequireOnboarding } from "@/features/onboarding/require-onboarding";

export const metadata: Metadata = { title: "Job" };

export default async function Page({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  return (
    <RequireOnboarding>
      <div className="mx-auto max-w-4xl">
        <JobDetailView id={id} />
      </div>
    </RequireOnboarding>
  );
}
