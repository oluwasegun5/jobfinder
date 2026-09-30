import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { fakeApi, json } from "@/test/fake-api";
import { renderWithQueryClient } from "@/test/render";

import { RequireOnboarding } from "./require-onboarding";

const replace = vi.fn();
vi.mock("next/navigation", () => ({ useRouter: () => ({ replace }) }));

const hoisted = vi.hoisted(() => ({ client: undefined as unknown }));
vi.mock("@/lib/api", () => ({
  get api() {
    return hoisted.client;
  },
}));

function setup(profile: () => Response) {
  hoisted.client = fakeApi({ "GET /profile": profile }).client;
  renderWithQueryClient(<RequireOnboarding>the dashboard</RequireOnboarding>);
}

beforeEach(() => {
  replace.mockClear();
});

describe("RequireOnboarding", () => {
  it("sends a user who has not finished onboarding to it, without flashing the page", async () => {
    setup(() => json({ links: [], onboardingCompleted: false }));

    expect(screen.getByRole("status")).toBeInTheDocument();
    await vi.waitFor(() => expect(replace).toHaveBeenCalledWith("/onboarding"));
    expect(screen.queryByText("the dashboard")).not.toBeInTheDocument();
  });

  it("shows the page once onboarding is complete", async () => {
    setup(() => json({ links: [], onboardingCompleted: true }));

    expect(await screen.findByText("the dashboard")).toBeInTheDocument();
    expect(replace).not.toHaveBeenCalled();
  });

  it("waits for a refetch instead of trusting a stale cached profile", async () => {
    const api = fakeApi({ "GET /profile": () => json({ links: [], onboardingCompleted: true }) });
    hoisted.client = api.client;
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    // What the onboarding steps left in the cache before preferences were saved.
    queryClient.setQueryData(["profile"], { links: [], onboardingCompleted: false });
    await queryClient.invalidateQueries({ queryKey: ["profile"], refetchType: "none" });

    render(
      <QueryClientProvider client={queryClient}>
        <RequireOnboarding>the dashboard</RequireOnboarding>
      </QueryClientProvider>,
    );

    expect(await screen.findByText("the dashboard")).toBeInTheDocument();
    expect(replace).not.toHaveBeenCalled();
  });

  it("does not lock the user out when the profile cannot be loaded", async () => {
    setup(() => json({ status: 500 }, 500));

    expect(await screen.findByText("the dashboard")).toBeInTheDocument();
    expect(replace).not.toHaveBeenCalled();
  });
});
