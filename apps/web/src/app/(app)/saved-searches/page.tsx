import type { Metadata } from "next";

import { SavedSearches } from "@/features/notifications/saved-searches";

export const metadata: Metadata = { title: "Saved searches" };

export default function Page() {
  return (
    <div className="mx-auto flex max-w-3xl flex-col gap-4">
      <h1 className="text-2xl font-semibold tracking-tight">Saved searches</h1>
      <SavedSearches />
    </div>
  );
}
