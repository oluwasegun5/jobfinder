import type { Metadata } from "next";

import { RequireOnboarding } from "@/features/onboarding/require-onboarding";
import { TailorWorkspace } from "@/features/tailoring/tailor-workspace";

export const metadata: Metadata = { title: "Tailor for this job" };

export default async function Page({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  return (
    <RequireOnboarding>
      <div className="mx-auto max-w-4xl">
        <TailorWorkspace jobId={id} />
      </div>
    </RequireOnboarding>
  );
}
