import type { Metadata } from "next";

import { BillingPage } from "@/features/billing/billing-page";

export const metadata: Metadata = { title: "Billing" };

export default function Page() {
  return (
    <div className="mx-auto flex max-w-3xl flex-col gap-6">
      <h1 className="text-2xl font-semibold tracking-tight">Billing</h1>
      <BillingPage />
    </div>
  );
}
