import type { Metadata } from "next";

import { InterviewSession } from "@/features/interview/session-view";
import { RequireOnboarding } from "@/features/onboarding/require-onboarding";

export const metadata: Metadata = { title: "Mock interview" };

export default async function Page({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  return (
    <RequireOnboarding>
      <div className="mx-auto max-w-3xl">
        <InterviewSession id={id} />
      </div>
    </RequireOnboarding>
  );
}
