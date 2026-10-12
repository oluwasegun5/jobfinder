import type { Metadata } from "next";

import { SiteShell } from "@/components/layout/site-shell";
import { PricingPage } from "@/features/billing/pricing-page";

export const metadata: Metadata = {
  title: "Pricing",
  description: "Start with a free trial, then choose a monthly plan or add credits when you need them.",
};

// Public: no sign-in. Plans, credits and prices all come from the API (GET /billing/plans).
export default function Page() {
  return (
    <SiteShell>
      <div className="mx-auto flex w-full max-w-6xl flex-1 flex-col gap-8 px-4 pt-8 pb-16 sm:px-6">
        <div className="flex flex-col gap-3">
          <h1 className="text-headline font-semibold sm:text-display">Pricing</h1>
          <p className="max-w-2xl text-lead text-pretty text-muted-foreground">
            Start with a free trial. When you need more, choose a monthly plan or add credits that never expire.
          </p>
        </div>
        <PricingPage />
      </div>
    </SiteShell>
  );
}
