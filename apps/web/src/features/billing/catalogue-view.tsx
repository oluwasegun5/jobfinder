"use client";

import type { ReactNode } from "react";

import { Card, CardContent, CardHeader } from "@/components/ui/card";

import { formatCredits, formatPrice, priceIn, providerFor, type Price } from "./pricing";
import type { Catalogue } from "./queries";

export type CataloguePlan = NonNullable<Catalogue["plans"]>[number];
export type CataloguePack = NonNullable<Catalogue["packs"]>[number];

/** The plan code of the free trial. A code, not a name: the name shown is whatever the API says. */
export const FREE_PLAN = "free";

export const TRIAL_RULE = "One free trial per person.";

/** Currency choice as native radios (arrow keys move between them), shown only when there is a choice to make. */
export function CurrencyPicker({
  catalogue,
  currencies,
  value,
  onChange,
}: {
  catalogue: Catalogue;
  currencies: string[];
  value: string | undefined;
  onChange: (currency: string) => void;
}) {
  const provider = providerFor(catalogue, value);
  return (
    <div className="flex flex-wrap items-center gap-3">
      {currencies.length > 1 && (
        <fieldset className="inline-flex rounded-lg border p-1">
          <legend className="sr-only">Currency</legend>
          {currencies.map((currency) => (
            <label
              key={currency}
              className="cursor-pointer rounded-md px-3 py-1.5 text-sm font-medium text-muted-foreground has-checked:bg-primary has-checked:text-primary-foreground has-focus-visible:ring-2 has-focus-visible:ring-ring"
            >
              <input
                type="radio"
                name="currency"
                value={currency}
                checked={value === currency}
                onChange={() => onChange(currency)}
                className="sr-only"
              />
              {currency}
            </label>
          ))}
        </fieldset>
      )}
      {value && provider && (
        <p className="text-sm text-muted-foreground">
          Prices in {value}. Paid through {provider}.
        </p>
      )}
    </div>
  );
}

function Amount({ price, suffix }: { price: Price; suffix?: string }) {
  return (
    <p className="flex items-baseline gap-1">
      <span className="text-headline font-semibold">{formatPrice(price)}</span>
      {suffix && <span className="text-sm text-muted-foreground">{suffix}</span>}
    </p>
  );
}

/**
 * The plans in the catalogue's order, priced in {@code currency}. The free plan is shown as the one-time trial; a paid
 * plan without a price in this currency says so. {@code action} renders the call to action of a paid plan.
 */
export function PlanList({
  plans,
  currency,
  action,
  trialAction,
}: {
  plans: CataloguePlan[];
  currency: string | undefined;
  action: (plan: CataloguePlan, price: Price) => ReactNode;
  trialAction?: ReactNode;
}) {
  return (
    <ul aria-label="Plans" className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
      {plans.map((plan) => {
        const free = plan.code === FREE_PLAN;
        const price = free ? undefined : priceIn(plan, currency);
        return (
          <li key={plan.code}>
            <Card className="h-full">
              <CardHeader>
                <h3 className="text-lead font-semibold">{plan.name}</h3>
              </CardHeader>
              <CardContent className="flex flex-1 flex-col gap-3">
                {free ? (
                  <>
                    <p className="text-headline font-semibold">Free</p>
                    <p className="text-muted-foreground">A one-time trial with credits to try every feature.</p>
                    <p className="font-medium">{TRIAL_RULE}</p>
                    {trialAction && <div className="mt-auto">{trialAction}</div>}
                  </>
                ) : (
                  <>
                    {price ? <Amount price={price} suffix="a month" /> : <p className="text-muted-foreground">Not available in {currency}</p>}
                    <p className="text-muted-foreground">{formatCredits(plan.monthlyCredits)} credits a month</p>
                    {price && <div className="mt-auto">{action(plan, price)}</div>}
                  </>
                )}
              </CardContent>
            </Card>
          </li>
        );
      })}
    </ul>
  );
}

/** The credit packs priced in {@code currency}; a pack without a price in it is left out. */
export function PackList({
  packs,
  currency,
  action,
}: {
  packs: CataloguePack[];
  currency: string | undefined;
  action: (pack: CataloguePack, price: Price) => ReactNode;
}) {
  const priced = packs.flatMap((pack) => {
    const price = priceIn(pack, currency);
    return price ? [{ pack, price }] : [];
  });
  if (priced.length === 0) return <p className="text-sm text-muted-foreground">No credit packs are available in {currency ?? "this currency"} right now.</p>;
  return (
    <ul aria-label="Credit packs" className="grid gap-4 sm:grid-cols-2">
      {priced.map(({ pack, price }) => (
        <li key={pack.id}>
          <Card className="h-full">
            <CardContent className="flex flex-col gap-2">
              <h3 className="font-semibold">{pack.name}</h3>
              <Amount price={price} />
              <p className="text-muted-foreground">{formatCredits(pack.credits)} credits that never expire</p>
              <div>{action(pack, price)}</div>
            </CardContent>
          </Card>
        </li>
      ))}
    </ul>
  );
}
