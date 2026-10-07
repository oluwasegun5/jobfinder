import type { Metadata } from "next";
import { Suspense } from "react";

import { CheckoutReturn } from "@/features/billing/checkout-return";

export const metadata: Metadata = { title: "Checkout" };

export default function Page() {
  return (
    <div className="mx-auto flex max-w-3xl flex-col gap-6">
      <h1 className="text-2xl font-semibold tracking-tight">Checkout</h1>
      <Suspense fallback={<p role="status">Loading</p>}>
        <CheckoutReturn />
      </Suspense>
    </div>
  );
}
