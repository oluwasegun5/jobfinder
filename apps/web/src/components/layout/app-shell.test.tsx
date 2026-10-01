import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";

import { renderWithQueryClient } from "@/test/render";

import { AppShell } from "./app-shell";

vi.mock("next/navigation", () => ({ usePathname: () => "/dashboard" }));
vi.mock("@/features/auth/user-menu", () => ({ UserMenu: () => null }));
vi.mock("@/features/health/api-status", () => ({ ApiStatus: () => null }));

describe("AppShell", () => {
  it("marks the current page and renders later-phase items as disabled", () => {
    renderWithQueryClient(<AppShell>content</AppShell>);

    expect(screen.getByRole("link", { name: "Dashboard" })).toHaveAttribute("aria-current", "page");
    expect(screen.getByRole("link", { name: "Jobs" })).toHaveAttribute("href", "/jobs");
    expect(screen.getByRole("link", { name: "Saved jobs" })).toHaveAttribute("href", "/saved-jobs");
    expect(screen.queryByRole("link", { name: /Applications/ })).not.toBeInTheDocument();
    expect(screen.getByText("content")).toBeInTheDocument();
  });

  it("toggles the mobile menu from the keyboard", async () => {
    const user = userEvent.setup();
    renderWithQueryClient(<AppShell>content</AppShell>);

    const toggle = screen.getByRole("button", { name: "Open menu" });
    expect(toggle).toHaveAttribute("aria-expanded", "false");

    toggle.focus();
    await user.keyboard("{Enter}");

    expect(screen.getByRole("button", { name: "Close menu" })).toHaveAttribute("aria-expanded", "true");
    expect(document.getElementById("mobile-nav")).toBeInTheDocument();
  });
});
