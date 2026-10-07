"use client";

import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { useRef } from "react";

import { buttonVariants } from "@/components/ui/button";

import { formatCredits } from "./billing-page";
import { useBillingMe } from "./queries";

/** How often, and how many times, to look again while the payment provider's confirmation is on its way. */
const POLL_MS = 3_000;
const MAX_POLLS = 10;

/**
 * Where the hosted checkout sends people back. The page itself proves nothing about payment: the provider's signed
 * webhook is what changes the account, so this only shows the account as it now stands (and waits a little for it).
 */
export function CheckoutReturn() {
  const canceled = useSearchParams().get("canceled") === "1";
  return canceled ? <Canceled /> : <Returned />;
}

function Canceled() {
  return (
    <div className="flex flex-col gap-3">
      <p role="status">The checkout was cancelled and you have not been charged.</p>
      <div>
        <Link href="/billing" className={buttonVariants({ variant: "outline" })}>
          Back to billing
        </Link>
      </div>
    </div>
  );
}

function Returned() {
  const polls = useRef(0);
  const me = useBillingMe({
    refetchInterval: (query) => {
      polls.current += 1;
      const data = query.state.data;
      return data && (data.plan?.code !== "free" || polls.current >= MAX_POLLS) ? false : POLL_MS;
    },
  });
  const upgraded = me.data?.plan?.code !== undefined && me.data.plan.code !== "free";
  return (
    <div className="flex flex-col gap-3">
      <p role="status">
        {me.isPending
          ? "Checking your payment"
          : upgraded
            ? `Thank you. You are on ${me.data?.plan?.name}, with ${formatCredits(me.data?.balance)} credits.`
            : "Thank you. We are waiting for the payment to be confirmed: your credits appear here as soon as it is. This can take a minute."}
      </p>
      <div>
        <Link href="/billing" className={buttonVariants()}>
          Go to billing
        </Link>
      </div>
    </div>
  );
}
