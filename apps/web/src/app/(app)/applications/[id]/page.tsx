import type { Metadata } from "next";

import { ApplicationDetailView } from "@/features/applications/application-detail";
import { RequireOnboarding } from "@/features/onboarding/require-onboarding";

export const metadata: Metadata = { title: "Application" };

export default async function Page({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  return (
    <RequireOnboarding>
      <div className="mx-auto max-w-3xl">
        <ApplicationDetailView id={id} />
      </div>
    </RequireOnboarding>
  );
}
