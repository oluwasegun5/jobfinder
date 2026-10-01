import type { Metadata } from "next";

import { RequireAdmin } from "@/features/admin/require-admin";
import { SourceDashboard } from "@/features/admin/source-dashboard";

export const metadata: Metadata = { title: "Job sources" };

export default function Page() {
  return (
    <RequireAdmin>
      <div className="mx-auto flex max-w-4xl flex-col gap-4">
        <SourceDashboard />
      </div>
    </RequireAdmin>
  );
}
