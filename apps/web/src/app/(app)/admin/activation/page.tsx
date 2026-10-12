import type { Metadata } from "next";
import { Suspense } from "react";

import { ActivationDashboard } from "@/features/admin/activation-dashboard";
import { RequireAdmin } from "@/features/admin/require-admin";

export const metadata: Metadata = { title: "Activation" };

export default function Page() {
  return (
    <RequireAdmin>
      <div className="mx-auto flex max-w-5xl flex-col gap-4">
        <Suspense fallback={<p role="status">Loading activation</p>}>
          <ActivationDashboard />
        </Suspense>
      </div>
    </RequireAdmin>
  );
}
