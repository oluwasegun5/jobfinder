import { render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { GuestOnly, RequireAuth } from "./route-guards";

const replace = vi.fn();
let status: "loading" | "authenticated" | "unauthenticated" = "loading";
let signedOutByUser = false;

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace }),
  usePathname: () => "/dashboard",
}));
vi.mock("./auth-provider", () => ({ useAuth: () => ({ status, signedOutByUser }) }));

describe("route guards", () => {
  beforeEach(() => {
    replace.mockClear();
    signedOutByUser = false;
  });

  it("RequireAuth shows nothing protected while loading", () => {
    status = "loading";
    render(<RequireAuth>secret</RequireAuth>);

    expect(screen.queryByText("secret")).not.toBeInTheDocument();
    expect(replace).not.toHaveBeenCalled();
  });

  it("RequireAuth redirects visitors to login and remembers the page", () => {
    status = "unauthenticated";
    render(<RequireAuth>secret</RequireAuth>);

    expect(screen.queryByText("secret")).not.toBeInTheDocument();
    expect(replace).toHaveBeenCalledWith("/login?next=%2Fdashboard");
  });

  it("RequireAuth sends a deliberate sign-out to plain /login", () => {
    status = "unauthenticated";
    signedOutByUser = true;
    render(<RequireAuth>secret</RequireAuth>);

    expect(replace).toHaveBeenCalledWith("/login");
  });

  it("RequireAuth renders children once authenticated", () => {
    status = "authenticated";
    render(<RequireAuth>secret</RequireAuth>);

    expect(screen.getByText("secret")).toBeInTheDocument();
  });

  it("GuestOnly sends signed-in users to the dashboard", () => {
    status = "authenticated";
    render(<GuestOnly>form</GuestOnly>);

    expect(screen.queryByText("form")).not.toBeInTheDocument();
    expect(replace).toHaveBeenCalledWith("/dashboard");
  });
});
