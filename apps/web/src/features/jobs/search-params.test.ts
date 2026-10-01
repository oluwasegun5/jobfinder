import { describe, expect, it } from "vitest";

import {
  activeFilterCount,
  filtersFromSearchParams,
  filtersToApiQuery,
  filtersToQueryString,
  NO_FILTERS,
} from "./search-params";

const ID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
const parse = (qs: string) => filtersFromSearchParams(new URLSearchParams(qs));

describe("job search URL filters", () => {
  it("round-trips every filter through the URL", () => {
    const filters = {
      ...NO_FILTERS,
      q: "java engineer",
      workMode: ["REMOTE", "HYBRID"] as const,
      seniority: ["SENIOR"] as const,
      country: "NG",
      location: "Lagos",
      minSalary: "50000",
      salaryCurrency: "GBP",
      postedWithinDays: "7",
      companyId: ID,
    };
    const parsed = parse(filtersToQueryString({ ...filters, workMode: [...filters.workMode], seniority: [...filters.seniority] }));
    expect(parsed).toEqual({ ...filters, workMode: ["REMOTE", "HYBRID"], seniority: ["SENIOR"] });
  });

  it("drops unknown or malformed values from an edited URL", () => {
    const parsed = parse(`workMode=MARS&workMode=REMOTE&country=nigeria&minSalary=5a0b&postedWithinDays=9&companyId=x&similarTo=y&salaryCurrency=zz1`);
    expect(parsed.workMode).toEqual(["REMOTE"]);
    expect(parsed.country).toBe("");
    expect(parsed.minSalary).toBe("50");
    expect(parsed.postedWithinDays).toBe("");
    expect(parsed.companyId).toBe("");
    expect(parsed.similarTo).toBe("");
    expect(parsed.salaryCurrency).toBe("USD");
  });

  it("caps the keyword at the server's limit and ignores the keyword when searching by similarity", () => {
    expect(parse(`q=${"a".repeat(300)}`).q).toHaveLength(200);
    expect(filtersToQueryString({ ...NO_FILTERS, q: "java", similarTo: ID })).toBe(`similarTo=${ID}`);
  });

  it("sends the salary currency only together with a minimum", () => {
    expect(filtersToApiQuery(NO_FILTERS).salaryCurrency).toBeUndefined();
    expect(filtersToApiQuery({ ...NO_FILTERS, minSalary: "1000", salaryCurrency: "EUR" })).toMatchObject({
      minSalary: 1000,
      salaryCurrency: "EUR",
    });
    expect(filtersToQueryString({ ...NO_FILTERS, salaryCurrency: "EUR" })).toBe("");
  });

  it("counts the narrowing filters but not the keyword", () => {
    expect(activeFilterCount({ ...NO_FILTERS, q: "x" })).toBe(0);
    expect(activeFilterCount({ ...NO_FILTERS, workMode: ["REMOTE"], country: "NG", minSalary: "1" })).toBe(3);
  });
});
