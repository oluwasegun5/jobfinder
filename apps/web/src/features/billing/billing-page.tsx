"use client";

import { useState } from "react";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { formatDateTime } from "@/features/admin/format";
import { FailureNotice } from "@/features/tailoring/shared";
import { toFailure } from "@/features/tailoring/errors";

import {
  useBillingMe,
  useCancelSubscription,
  useCheckout,
  useLedger,
  usePlans,
  type CheckoutRequest,
  type LedgerLine,
} from "./queries";

// The generated types mark every field optional, so the page reads them defensively.
type Price = { provider?: string; currency?: string; amountMinor?: number };

const REASONS: Record<string, string> = {
  AI_USAGE: "AI usage",
  PLAN_GRANT: "Plan credits",
  PLAN_EXPIRY: "Unused plan credits expired",
  TOPUP: "Credit pack",
  REFUND_ADJUSTMENT: "Adjustment",
};

export function formatCredits(value: number | string | undefined | null): string {
  const n = Number(value ?? 0);
  if (!Number.isFinite(n)) return "0";
  return n.toLocaleString("en", { maximumFractionDigits: 2 });
}

export function formatPrice(price: Price): string {
  const amount = (price.amountMinor ?? 0) / 100;
  try {
    return new Intl.NumberFormat("en", { style: "currency", currency: price.currency ?? "USD" }).format(amount);
  } catch {
    return `${amount.toFixed(2)} ${price.currency ?? ""}`;
  }
}

const providerName = (provider?: string) => (provider === "PAYSTACK" ? "Paystack" : "Stripe");

export function BillingPage() {
  const me = useBillingMe();
  const plans = usePlans();
  const checkout = useCheckout();
  const cancel = useCancelSubscription();
  const [confirmingCancel, setConfirmingCancel] = useState(false);

  if (me.isPending || plans.isPending) return <p role="status">Loading</p>;
  if (me.isError || plans.isError) {
    return <FailureNotice failure={toFailure(me.error ?? plans.error, "Could not load billing. Try again.")} onRetry={() => { void me.refetch(); void plans.refetch(); }} retrying={me.isFetching} />;
  }

  const account = me.data;
  const catalogue = plans.data;
  const subscription = account.subscription ?? undefined;
  const paid = account.plan?.code !== "free";

  function start(request: CheckoutRequest) {
    checkout.mutate(request, { onSuccess: (result) => result.url && window.location.assign(result.url) });
  }

  return (
    <div className="flex flex-col gap-6">
      <Card>
        <CardHeader>
          <CardTitle className="flex flex-wrap items-center gap-2">
            Your plan: {account.plan?.name}
            {subscription?.status === "PAST_DUE" && <Badge variant="destructive">Payment failed</Badge>}
            {subscription?.cancelAtPeriodEnd && <Badge variant="secondary">Ends {formatDateTime(subscription.currentPeriodEnd ?? "")}</Badge>}
          </CardTitle>
        </CardHeader>
        <CardContent className="flex flex-col gap-3 text-sm">
          <p>
            Credits left: <strong data-testid="balance">{formatCredits(account.balance)}</strong>
          </p>
          <p className="text-muted-foreground">
            This period: {formatCredits(account.grantedThisPeriod)} granted, {formatCredits(account.usedThisPeriod)} used.
            {account.allowance?.remaining != null && <> Today you can still use {formatCredits(account.allowance.remaining)}.</>}
          </p>
          {subscription?.status === "PAST_DUE" && (
            <p className="text-destructive">
              The last payment failed. Your plan stays until {formatDateTime(subscription.graceEndsAt ?? "")}; after that you are on Free.
            </p>
          )}
          {paid && subscription && !subscription.cancelAtPeriodEnd && subscription.status === "ACTIVE" && (
            <div className="flex flex-col gap-2">
              {!confirmingCancel ? (
                <div>
                  <Button variant="outline" size="sm" onClick={() => setConfirmingCancel(true)}>
                    Cancel subscription
                  </Button>
                </div>
              ) : (
                <div className="flex flex-wrap items-center gap-2">
                  <span>Your plan stays until {formatDateTime(subscription.currentPeriodEnd ?? "")}, then you are on Free.</span>
                  <Button size="sm" disabled={cancel.isPending} onClick={() => cancel.mutate(undefined, { onSettled: () => setConfirmingCancel(false) })}>
                    {cancel.isPending ? "Cancelling…" : "Confirm cancel"}
                  </Button>
                  <Button variant="ghost" size="sm" onClick={() => setConfirmingCancel(false)}>
                    Keep my plan
                  </Button>
                </div>
              )}
            </div>
          )}
          {cancel.isError && <FailureNotice failure={toFailure(cancel.error, "Could not cancel. Try again.")} />}
        </CardContent>
      </Card>

      {checkout.isError && <FailureNotice failure={toFailure(checkout.error, "Could not start the checkout. Try again.")} />}

      {!paid && (
        <section aria-label="Upgrade" className="flex flex-col gap-3">
          <h2 className="text-lg font-semibold">Upgrade</h2>
          {(catalogue.plans ?? [])
            .filter((plan) => plan.code !== "free")
            .map((plan) => (
              <Card key={plan.code} size="sm">
                <CardContent className="flex flex-col gap-2">
                  <p className="font-medium">
                    {plan.name}: {formatCredits(plan.monthlyCredits)} credits a month
                  </p>
                  <div className="flex flex-wrap gap-2">
                    {(plan.prices ?? []).length === 0 && <span className="text-sm text-muted-foreground">Not available right now.</span>}
                    {(plan.prices ?? []).map((price) => (
                      <Button key={price.provider} disabled={checkout.isPending} onClick={() => start({ plan: plan.code, provider: price.provider ?? "" })}>
                        {`Upgrade for ${formatPrice(price)} a month with ${providerName(price.provider)}`}
                      </Button>
                    ))}
                  </div>
                </CardContent>
              </Card>
            ))}
        </section>
      )}

      <section aria-label="Credit packs" className="flex flex-col gap-3">
        <h2 className="text-lg font-semibold">Add credits</h2>
        <p className="text-sm text-muted-foreground">Credit packs never expire.</p>
        {(catalogue.packs ?? []).length === 0 && <p className="text-sm text-muted-foreground">No credit packs are available right now.</p>}
        {(catalogue.packs ?? []).map((pack) => (
          <Card key={pack.id} size="sm">
            <CardContent className="flex flex-col gap-2">
              <p className="font-medium">
                {pack.name}: {formatCredits(pack.credits)} credits
              </p>
              <div className="flex flex-wrap gap-2">
                {(pack.prices ?? []).map((price) => (
                  <Button key={price.provider} variant="outline" disabled={checkout.isPending} onClick={() => start({ pack: pack.id, provider: price.provider ?? "" })}>
                    {`Buy for ${formatPrice(price)} with ${providerName(price.provider)}`}
                  </Button>
                ))}
              </div>
            </CardContent>
          </Card>
        ))}
      </section>

      <LedgerList />
    </div>
  );
}

function LedgerList() {
  const ledger = useLedger();
  if (ledger.isPending) return <p role="status">Loading history</p>;
  if (ledger.isError) return <FailureNotice failure={toFailure(ledger.error, "Could not load your history.")} onRetry={() => void ledger.refetch()} retrying={ledger.isFetching} />;
  const lines = ledger.data.pages.flatMap((page) => page.items ?? []).filter((line): line is LedgerLine => line !== undefined);
  return (
    <section aria-label="Credit history" className="flex flex-col gap-3">
      <h2 className="text-lg font-semibold">History</h2>
      {lines.length === 0 && <p className="text-sm text-muted-foreground">Nothing yet.</p>}
      <ul className="flex flex-col divide-y rounded-lg border text-sm">
        {lines.map((line) => (
          <li key={line.id} className="flex items-center justify-between gap-3 px-3 py-2">
            <span className="flex flex-col">
              <span>{REASONS[line.reason ?? ""] ?? line.reason}</span>
              <time className="text-xs text-muted-foreground" dateTime={line.createdAt}>
                {formatDateTime(line.createdAt)}
              </time>
            </span>
            <span className="flex flex-col items-end">
              <span className={Number(line.delta) < 0 ? "" : "font-medium"}>{`${Number(line.delta) > 0 ? "+" : ""}${formatCredits(line.delta)}`}</span>
              <span className="text-xs text-muted-foreground">balance {formatCredits(line.balanceAfter)}</span>
            </span>
          </li>
        ))}
      </ul>
      {ledger.hasNextPage && (
        <div>
          <Button variant="outline" size="sm" disabled={ledger.isFetchingNextPage} onClick={() => void ledger.fetchNextPage()}>
            {ledger.isFetchingNextPage ? "Loading…" : "Show older"}
          </Button>
        </div>
      )}
    </section>
  );
}
