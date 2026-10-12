import type { Metadata } from "next";

import { Board } from "@/features/applications/board";
import { RequireOnboarding } from "@/features/onboarding/require-onboarding";

export const metadata: Metadata = { title: "Applications" };

export default function Page() {
  return (
    <RequireOnboarding>
      <div className="mx-auto flex max-w-[110rem] flex-col gap-4">
        <h1 className="text-title font-semibold">Applications</h1>
        <Board />
      </div>
    </RequireOnboarding>
  );
}
