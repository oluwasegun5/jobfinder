import type { Metadata } from "next";

import { StartInterview } from "@/features/interview/start-interview";
import { RequireOnboarding } from "@/features/onboarding/require-onboarding";

export const metadata: Metadata = { title: "Practise the interview" };

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export default async function Page({
  params,
  searchParams,
}: {
  params: Promise<{ id: string }>;
  searchParams: Promise<{ prepId?: string | string[] }>;
}) {
  const { id } = await params;
  const { prepId } = await searchParams;
  // Only a well-formed id is passed on; anything else is ignored and the interview starts without a prep.
  const prep = typeof prepId === "string" && UUID.test(prepId) ? prepId : undefined;
  return (
    <RequireOnboarding>
      <div className="mx-auto max-w-3xl">
        <StartInterview jobId={id} prepId={prep} />
      </div>
    </RequireOnboarding>
  );
}
