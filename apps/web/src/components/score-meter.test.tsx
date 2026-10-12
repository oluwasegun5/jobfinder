import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { normalizeScore, scoreBand } from "@/lib/score-scale";

import { ScoreMeter } from "./score-meter";

describe("scoreBand", () => {
  it.each([
    [0, "low"],
    [49, "low"],
    [50, "fair"],
    [74, "fair"],
    [75, "strong"],
    [89, "strong"],
    [90, "top"],
    [100, "top"],
  ] as const)("puts %i in the %s band", (score, band) => {
    expect(scoreBand(score)).toBe(band);
  });

  it("clamps and rounds out-of-range scores", () => {
    expect(normalizeScore(-5)).toBe(0);
    expect(normalizeScore(140)).toBe(100);
    expect(normalizeScore(74.6)).toBe(75);
    expect(normalizeScore(Number.NaN)).toBe(0);
    expect(scoreBand(74.6)).toBe("strong");
  });
});

describe("ScoreMeter", () => {
  it("shows the score as text and exposes it as a meter", () => {
    render(<ScoreMeter score={82} />);

    expect(screen.getByText("82")).toBeInTheDocument();
    expect(screen.getByText("Strong match")).toBeInTheDocument();
    const meter = screen.getByRole("meter", { name: "Match score" });
    expect(meter).toHaveAttribute("aria-valuenow", "82");
    expect(meter).toHaveAttribute("aria-valuetext", "82 out of 100, strong match");
  });
});
