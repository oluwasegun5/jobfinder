import type { Metadata } from "next";
import { Suspense } from "react";

import { RequireAdmin } from "@/features/admin/require-admin";
import { RunHistory } from "@/features/admin/run-history";

export const metadata: Metadata = { title: "Run history" };

export default function Page() {
  return (
    <RequireAdmin>
      <div className="mx-auto flex max-w-6xl flex-col gap-4">
        <Suspense fallback={<p role="status">Loading runs</p>}>
          <RunHistory />
        </Suspense>
      </div>
    </RequireAdmin>
  );
}
