import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import { RequireAdmin } from "./require-admin";

let user: { role?: string } | null = null;
vi.mock("@/features/auth/auth-provider", () => ({ useAuth: () => ({ user }) }));

describe("RequireAdmin", () => {
  it("shows administrators the page", () => {
    user = { role: "ADMIN" };
    render(<RequireAdmin>secret</RequireAdmin>);

    expect(screen.getByText("secret")).toBeInTheDocument();
  });

  it("tells everyone else they are not allowed, with a way back", () => {
    user = { role: "USER" };
    render(<RequireAdmin>secret</RequireAdmin>);

    expect(screen.queryByText("secret")).not.toBeInTheDocument();
    expect(screen.getByRole("alert")).toHaveTextContent("Not allowed");
    expect(screen.getByRole("link", { name: "Back to the dashboard" })).toHaveAttribute("href", "/dashboard");
  });

  it("shows nothing protected before there is a user", () => {
    user = null;
    render(<RequireAdmin>secret</RequireAdmin>);

    expect(screen.queryByText("secret")).not.toBeInTheDocument();
  });
});
