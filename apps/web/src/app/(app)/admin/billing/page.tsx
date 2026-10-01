import type { Metadata } from "next";
import { Suspense } from "react";

import { CostDashboard } from "@/features/admin/cost-dashboard";
import { RequireAdmin } from "@/features/admin/require-admin";

export const metadata: Metadata = { title: "AI cost" };

export default function Page() {
  return (
    <RequireAdmin>
      <div className="mx-auto flex max-w-5xl flex-col gap-4">
        <Suspense fallback={<p role="status">Loading AI cost</p>}>
          <CostDashboard />
        </Suspense>
      </div>
    </RequireAdmin>
  );
}
