"use client";

import Link from "next/link";
import { useState } from "react";

import { buttonVariants } from "@/components/ui/button";
import { useAuth } from "@/features/auth/auth-provider";
import { FailureNotice } from "@/features/tailoring/shared";
import { toFailure } from "@/features/tailoring/errors";
import { cn } from "@/lib/utils";

import { CurrencyPicker, PackList, PlanList, TRIAL_RULE } from "./catalogue-view";
import { browserSignals, currenciesOf, defaultCurrency, prefersNaira } from "./pricing";
import { usePlans } from "./queries";
import { LoadingState } from "@/components/state";

/**
 * The public pricing page body: every plan and pack the API offers, in the visitor's currency (naira through Paystack
 * for Nigerian browsers, dollars through Stripe otherwise, switchable). Signed-out visitors are sent to sign up,
 * members to the billing page where checkout starts.
 */
export function PricingView({ naira, signedIn }: { naira: boolean; signedIn: boolean }) {
  const plans = usePlans();
  const [chosen, setChosen] = useState<string>();

  if (plans.isPending) return <LoadingState>Loading prices</LoadingState>;
  if (plans.isError) {
    return <FailureNotice failure={toFailure(plans.error, "Could not load the prices. Try again.")} onRetry={() => void plans.refetch()} retrying={plans.isFetching} />;
  }

  const catalogue = plans.data;
  const currencies = currenciesOf(catalogue, naira);
  const currency = chosen && currencies.includes(chosen) ? chosen : defaultCurrency(currencies, naira);
  const next = signedIn ? "/billing" : "/signup";

  return (
    <div className="flex flex-col gap-8">
      <CurrencyPicker catalogue={catalogue} currencies={currencies} value={currency} onChange={setChosen} />
      <PlanList
        plans={catalogue.plans ?? []}
        currency={currency}
        action={(plan) => (
          <Link href={next} className={cn(buttonVariants(), "w-full")}>
            {`Choose ${plan.name}`}
          </Link>
        )}
        trialAction={
          signedIn ? undefined : (
            <Link href="/signup" className={cn(buttonVariants({ variant: "outline" }), "w-full")}>
              Start your free trial
            </Link>
          )
        }
      />
      <section aria-labelledby="packs-heading" className="flex flex-col gap-3">
        <h2 id="packs-heading" className="text-title font-semibold">
          Need more? Add credits
        </h2>
        <PackList
          packs={catalogue.packs ?? []}
          currency={currency}
          action={(pack) => (
            <Link href={next} className={buttonVariants({ variant: "outline" })}>
              {`Buy ${pack.name}`}
            </Link>
          )}
        />
      </section>
      <p className="text-sm text-muted-foreground">
        {TRIAL_RULE} Subscriptions renew monthly and can be cancelled at any time; they stay active until the end of the
        paid period. See the <Link href="/terms" className="underline underline-offset-4">terms of service</Link>.
      </p>
    </div>
  );
}

/** The route's client entry: reads the sign-in state and the browser's locale signals. */
export function PricingPage() {
  const { status } = useAuth();
  // Read once on the client; on the server the list is still loading, so the first render is the same either way.
  const [naira] = useState(() => prefersNaira(browserSignals()));
  return <PricingView naira={naira} signedIn={status === "authenticated"} />;
}
