import { describe, expect, it } from "vitest";

import { currenciesOf, defaultCurrency, formatPrice, prefersNaira, priceIn, providerFor } from "./pricing";

const catalogue = {
  plans: [
    { code: "free", name: "Free", monthlyCredits: 300, prices: [] },
    {
      code: "starter",
      name: "Starter",
      monthlyCredits: 2500,
      prices: [
        { provider: "STRIPE", currency: "USD", amountMinor: 500 },
        { provider: "PAYSTACK", currency: "NGN", amountMinor: 450000 },
      ],
    },
  ],
  packs: [{ id: "small", name: "Small pack", credits: 2000, prices: [{ provider: "STRIPE", currency: "EUR", amountMinor: 400 }] }],
};

describe("prefersNaira", () => {
  it("is true for a Nigerian time zone or a Nigerian language tag, false otherwise", () => {
    expect(prefersNaira({ timeZone: "Africa/Lagos", languages: ["en-US"] })).toBe(true);
    expect(prefersNaira({ timeZone: "Europe/London", languages: ["en-GB", "en-NG"] })).toBe(true);
    expect(prefersNaira({ timeZone: "Europe/London", languages: ["en-GB"] })).toBe(false);
    expect(prefersNaira({})).toBe(false);
  });
});

describe("currencies", () => {
  it("lists only the currencies the catalogue has prices in, the preferred one first", () => {
    expect(currenciesOf(catalogue, true)).toEqual(["NGN", "EUR", "USD"]);
    expect(currenciesOf(catalogue, false)).toEqual(["USD", "EUR", "NGN"]);
    expect(currenciesOf({ plans: [], packs: [] }, true)).toEqual([]);
  });

  it("defaults to naira for Nigerian visitors and dollars otherwise, when those exist", () => {
    expect(defaultCurrency(["USD", "NGN"], true)).toBe("NGN");
    expect(defaultCurrency(["USD", "NGN"], false)).toBe("USD");
    expect(defaultCurrency(["USD"], true)).toBe("USD");
    expect(defaultCurrency(["EUR"], false)).toBe("EUR");
    expect(defaultCurrency([], false)).toBeUndefined();
  });

  it("finds an item's price and provider in a currency", () => {
    const starter = catalogue.plans[1];
    expect(priceIn(starter, "NGN")?.amountMinor).toBe(450000);
    expect(priceIn(starter, "GBP")).toBeUndefined();
    expect(providerFor(catalogue, "NGN")).toBe("Paystack");
    expect(providerFor(catalogue, "USD")).toBe("Stripe");
  });
});

describe("formatPrice", () => {
  it("shows whole amounts without decimals, with the local currency sign", () => {
    expect(formatPrice({ currency: "NGN", amountMinor: 450000 })).toBe("₦4,500");
    expect(formatPrice({ currency: "USD", amountMinor: 500 })).toBe("$5");
    expect(formatPrice({ currency: "USD", amountMinor: 499 })).toBe("$4.99");
  });
});
