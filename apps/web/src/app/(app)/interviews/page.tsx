import type { Metadata } from "next";

import { InterviewHistory } from "@/features/interview/history";
import { RequireOnboarding } from "@/features/onboarding/require-onboarding";

export const metadata: Metadata = { title: "Mock interviews" };

export default function Page() {
  return (
    <RequireOnboarding>
      <div className="mx-auto flex max-w-3xl flex-col gap-4">
        <h1 className="text-title font-semibold">Mock interviews</h1>
        <InterviewHistory />
      </div>
    </RequireOnboarding>
  );
}
