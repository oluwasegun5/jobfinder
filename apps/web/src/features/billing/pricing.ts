/**
 * Pure helpers for showing the plan catalogue (GET /billing/plans). Nothing here knows a tier name or a price: those
 * come from the API. What is decided here is only presentation: which currency to show first and how to format money
 * (docs/adr/0036, G3 addendum).
 */

// The generated types mark every field optional, so these read them defensively.
export type Price = { provider?: string; currency?: string; amountMinor?: number };
type Priced = { prices?: Price[] };
type CatalogueLike = { plans?: Priced[]; packs?: Priced[] };

export type BrowserSignals = { timeZone?: string; languages?: readonly string[] };

const NAIRA = "NGN";
const DOLLAR = "USD";

/** The account has no country, so the browser decides: a Nigerian time zone or a Nigerian language tag. */
export function prefersNaira(signals: BrowserSignals): boolean {
  if (signals.timeZone === "Africa/Lagos") return true;
  return (signals.languages ?? []).some((tag) => /-NG$/i.test(tag));
}

/** What the browser says about where the visitor is. Empty on the server. */
export function browserSignals(): BrowserSignals {
  if (typeof window === "undefined") return {};
  let timeZone: string | undefined;
  try {
    timeZone = Intl.DateTimeFormat().resolvedOptions().timeZone;
  } catch {
    timeZone = undefined;
  }
  const languages = typeof navigator === "undefined" ? [] : (navigator.languages ?? [navigator.language]);
  return { timeZone, languages };
}

/** The currencies the catalogue has at least one price in: the preferred one first, then the others alphabetically. */
export function currenciesOf(catalogue: CatalogueLike, naira: boolean): string[] {
  const found = new Set<string>();
  for (const item of [...(catalogue.plans ?? []), ...(catalogue.packs ?? [])]) {
    for (const price of item.prices ?? []) if (price.currency) found.add(price.currency.toUpperCase());
  }
  const preferred = naira ? NAIRA : DOLLAR;
  return [...found].sort((a, b) => Number(b === preferred) - Number(a === preferred) || a.localeCompare(b));
}

/** Naira for Nigerian visitors, dollars otherwise, when the catalogue has them; else the first currency there is. */
export function defaultCurrency(currencies: string[], naira: boolean): string | undefined {
  const preferred = naira ? NAIRA : DOLLAR;
  if (currencies.includes(preferred)) return preferred;
  if (currencies.includes(DOLLAR)) return DOLLAR;
  return currencies[0];
}

export function priceIn(item: Priced, currency: string | undefined): Price | undefined {
  if (!currency) return undefined;
  return (item.prices ?? []).find((price) => price.currency?.toUpperCase() === currency);
}

export function providerName(provider?: string): string {
  if (provider === "PAYSTACK") return "Paystack";
  if (provider === "STRIPE") return "Stripe";
  return provider ?? "";
}

/** Who charges in a currency (one provider per currency in the catalogue), as a display name. */
export function providerFor(catalogue: CatalogueLike, currency: string | undefined): string | undefined {
  for (const item of [...(catalogue.plans ?? []), ...(catalogue.packs ?? [])]) {
    const price = priceIn(item, currency);
    if (price?.provider) return providerName(price.provider);
  }
  return undefined;
}

export function formatCredits(value: number | string | undefined | null): string {
  const n = Number(value ?? 0);
  if (!Number.isFinite(n)) return "0";
  return n.toLocaleString("en", { maximumFractionDigits: 2 });
}

/** Money in its own currency with the local sign (₦4,500, $5, $4.99): no decimals for whole amounts. */
export function formatPrice(price: Price): string {
  const amount = (price.amountMinor ?? 0) / 100;
  const whole = Number.isInteger(amount);
  try {
    return new Intl.NumberFormat("en", {
      style: "currency",
      currency: price.currency ?? DOLLAR,
      currencyDisplay: "narrowSymbol",
      minimumFractionDigits: whole ? 0 : 2,
      maximumFractionDigits: 2,
    }).format(amount);
  } catch {
    return `${amount.toFixed(whole ? 0 : 2)} ${price.currency ?? ""}`.trim();
  }
}
