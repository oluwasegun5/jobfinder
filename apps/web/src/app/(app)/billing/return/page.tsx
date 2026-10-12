import type { Metadata } from "next";
import { Suspense } from "react";

import { CheckoutReturn } from "@/features/billing/checkout-return";
import { LoadingState } from "@/components/state";

export const metadata: Metadata = { title: "Checkout" };

export default function Page() {
  return (
    <div className="mx-auto flex max-w-3xl flex-col gap-6">
      <h1 className="text-title font-semibold">Checkout</h1>
      <Suspense fallback={<LoadingState>Loading</LoadingState>}>
        <CheckoutReturn />
      </Suspense>
    </div>
  );
}
