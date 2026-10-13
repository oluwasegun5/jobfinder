import type { Metadata } from "next";
import { Suspense } from "react";

import { LoadingState } from "@/components/state";
import { RequireAdmin } from "@/features/admin/require-admin";
import { TrialReviewQueue } from "@/features/admin/trial-review";

export const metadata: Metadata = { title: "Trial review" };

export default function Page() {
  return (
    <RequireAdmin>
      <div className="mx-auto flex max-w-5xl flex-col gap-4">
        <Suspense fallback={<LoadingState>Loading trial review</LoadingState>}>
          <TrialReviewQueue />
        </Suspense>
      </div>
    </RequireAdmin>
  );
}
