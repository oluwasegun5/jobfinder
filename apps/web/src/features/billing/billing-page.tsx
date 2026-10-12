"use client";

import { useState } from "react";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { formatDateTime } from "@/features/admin/format";
import { FailureNotice } from "@/features/tailoring/shared";
import { toFailure } from "@/features/tailoring/errors";

import { CurrencyPicker, FREE_PLAN, PackList, PlanList } from "./catalogue-view";
import { browserSignals, currenciesOf, defaultCurrency, formatCredits, formatPrice, prefersNaira, providerName } from "./pricing";
import {
  useBillingMe,
  useCancelSubscription,
  useCheckout,
  useLedger,
  usePlans,
  type CheckoutRequest,
  type LedgerLine,
} from "./queries";

const REASONS: Record<string, string> = {
  AI_USAGE: "AI usage",
  PLAN_GRANT: "Plan credits",
  PLAN_EXPIRY: "Unused plan credits expired",
  TOPUP: "Credit pack",
  REFUND_ADJUSTMENT: "Adjustment",
};

// Kept here too: other billing screens import them from this module.
export { formatCredits, formatPrice };

/**
 * The signed-in billing page: the caller's plan and balance, the paid plans and packs from the API in their currency
 * (naira through Paystack first for Nigerian browsers, dollars through Stripe otherwise; {@code naira} overrides the
 * browser for tests), cancellation and the credit history.
 */
export function BillingPage({ naira: nairaOverride }: { naira?: boolean } = {}) {
  const me = useBillingMe();
  const plans = usePlans();
  const checkout = useCheckout();
  const cancel = useCancelSubscription();
  const [confirmingCancel, setConfirmingCancel] = useState(false);
  const [chosenCurrency, setChosenCurrency] = useState<string>();
  const [naira] = useState(() => nairaOverride ?? prefersNaira(browserSignals()));

  if (me.isPending || plans.isPending) return <p role="status">Loading</p>;
  if (me.isError || plans.isError) {
    return <FailureNotice failure={toFailure(me.error ?? plans.error, "Could not load billing. Try again.")} onRetry={() => { void me.refetch(); void plans.refetch(); }} retrying={me.isFetching} />;
  }

  const account = me.data;
  const catalogue = plans.data;
  const subscription = account.subscription ?? undefined;
  const paid = account.plan?.code !== FREE_PLAN;
  const currencies = currenciesOf(catalogue, naira);
  const currency = chosenCurrency && currencies.includes(chosenCurrency) ? chosenCurrency : defaultCurrency(currencies, naira);
  const paidPlans = (catalogue.plans ?? []).filter((plan) => plan.code !== FREE_PLAN);

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

      {currencies.length === 0 ? (
        <p className="text-sm text-muted-foreground">Paid plans and credit packs are not available right now.</p>
      ) : (
        <>
          <CurrencyPicker catalogue={catalogue} currencies={currencies} value={currency} onChange={setChosenCurrency} />

          {!paid && paidPlans.length > 0 && (
            <section aria-labelledby="upgrade-heading" className="flex flex-col gap-3">
              <h2 id="upgrade-heading" className="text-lg font-semibold">
                Upgrade
              </h2>
              <PlanList
                plans={paidPlans}
                currency={currency}
                action={(plan, price) => (
                  <Button
                    className="w-full"
                    aria-label={`Upgrade to ${plan.name} for ${formatPrice(price)} a month with ${providerName(price.provider)}`}
                    disabled={checkout.isPending}
                    onClick={() => start({ plan: plan.code, provider: price.provider ?? "" })}
                  >
                    {`Upgrade to ${plan.name}`}
                  </Button>
                )}
              />
            </section>
          )}

          <section aria-labelledby="packs-heading" className="flex flex-col gap-3">
            <h2 id="packs-heading" className="text-lg font-semibold">
              Add credits
            </h2>
            <PackList
              packs={catalogue.packs ?? []}
              currency={currency}
              action={(pack, price) => (
                <Button
                  variant="outline"
                  aria-label={`Buy ${pack.name} for ${formatPrice(price)} with ${providerName(price.provider)}`}
                  disabled={checkout.isPending}
                  onClick={() => start({ pack: pack.id, provider: price.provider ?? "" })}
                >
                  {`Buy ${pack.name}`}
                </Button>
              )}
            />
          </section>
        </>
      )}

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
