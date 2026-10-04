import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { InsufficientCreditsPrompt } from "./credits-prompt";

describe("InsufficientCreditsPrompt", () => {
  it("says the credits are gone and links to billing", () => {
    render(<InsufficientCreditsPrompt />);
    expect(screen.getByRole("alert")).toHaveTextContent("out of AI credits");
    expect(screen.getByRole("link", { name: "Upgrade or add credits" })).toHaveAttribute("href", "/billing");
  });
});
