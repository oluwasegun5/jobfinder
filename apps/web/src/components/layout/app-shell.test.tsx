import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";

import { renderWithQueryClient } from "@/test/render";

import { AppShell } from "./app-shell";

let role: string | undefined;

vi.mock("next/navigation", () => ({ usePathname: () => "/dashboard" }));
vi.mock("@/features/auth/auth-provider", () => ({ useAuth: () => ({ user: { id: "u", email: "u@example.test", role } }) }));
vi.mock("@/features/auth/user-menu", () => ({ UserMenu: () => null }));
vi.mock("@/features/health/api-status", () => ({ ApiStatus: () => null }));

describe("AppShell", () => {
  it("marks the current page and links every item, the application board included", () => {
    renderWithQueryClient(<AppShell>content</AppShell>);

    expect(screen.getByRole("link", { name: "Dashboard" })).toHaveAttribute("aria-current", "page");
    expect(screen.getByRole("link", { name: "For you" })).toHaveAttribute("href", "/feed");
    expect(screen.getByRole("link", { name: "Jobs" })).toHaveAttribute("href", "/jobs");
    expect(screen.getByRole("link", { name: "Saved jobs" })).toHaveAttribute("href", "/saved-jobs");
    expect(screen.getByRole("link", { name: "Applications" })).toHaveAttribute("href", "/applications");
    expect(screen.queryByText("Soon")).not.toBeInTheDocument();
    expect(screen.getByText("content")).toBeInTheDocument();
  });

  it("shows the job sources and AI cost links to administrators only", () => {
    role = "USER";
    const { unmount } = renderWithQueryClient(<AppShell>content</AppShell>);
    expect(screen.queryByRole("link", { name: "Job sources" })).not.toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "AI cost" })).not.toBeInTheDocument();
    unmount();

    role = "ADMIN";
    renderWithQueryClient(<AppShell>content</AppShell>);
    expect(screen.getByRole("link", { name: "Job sources" })).toHaveAttribute("href", "/admin/ingestion");
    expect(screen.getByRole("link", { name: "AI cost" })).toHaveAttribute("href", "/admin/billing");
    role = undefined;
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
