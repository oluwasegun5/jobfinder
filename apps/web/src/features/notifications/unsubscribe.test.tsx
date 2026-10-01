import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { fakeApi, json } from "@/test/fake-api";
import { renderWithQueryClient } from "@/test/render";

import { Unsubscribe } from "./unsubscribe";

const hoisted = vi.hoisted(() => ({ client: undefined as unknown, search: "" }));
vi.mock("@/lib/api", () => ({
  get api() {
    return hoisted.client;
  },
}));
vi.mock("next/navigation", () => ({
  useSearchParams: () => new URLSearchParams(hoisted.search),
}));

function setup(routes: Parameters<typeof fakeApi>[0], search = "token=abc.def") {
  hoisted.search = search;
  const api = fakeApi(routes);
  hoisted.client = api.client;
  renderWithQueryClient(<Unsubscribe />);
  return api;
}

beforeEach(() => {
  hoisted.client = undefined;
});

describe("Unsubscribe", () => {
  it("says what it would do, and only a click does it", async () => {
    const user = userEvent.setup();
    const api = setup({
      "GET /notifications/unsubscribe/abc.def": () => json({ scope: "DIGESTS" }),
      "POST /notifications/unsubscribe/abc.def": () => json({ scope: "DIGESTS" }),
    });
    expect(await screen.findByText("Stop digest emails?")).toBeInTheDocument();
    expect(api.callsTo("POST", "/notifications/unsubscribe/abc.def")).toHaveLength(0);

    await user.click(screen.getByRole("button", { name: "Unsubscribe" }));

    expect(await screen.findByText("You're unsubscribed")).toBeInTheDocument();
    expect(screen.getByRole("status")).toHaveTextContent("You will no longer get digest emails.");
    expect(screen.getByText(/password resets, are not affected/)).toBeInTheDocument();
    expect(api.callsTo("POST", "/notifications/unsubscribe/abc.def")).toHaveLength(1);
  });

  it("names the saved search for a one-search link", async () => {
    setup({ "GET /notifications/unsubscribe/abc.def": () => json({ scope: "SAVED_SEARCH", savedSearchName: "Go in Lagos" }) });
    expect(await screen.findByText('Stop emails for your saved search "Go in Lagos"?')).toBeInTheDocument();
  });

  it("works without being signed in: no Authorization header is sent", async () => {
    let authorization: string | null = "unset";
    setup({
      "GET /notifications/unsubscribe/abc.def": (r) => {
        authorization = r.headers.get("Authorization");
        return json({ scope: "MARKETING" });
      },
    });
    expect(await screen.findByText("Stop all optional emails from JobFinder?")).toBeInTheDocument();
    expect(authorization).toBeNull();
  });

  it("explains a link that is not valid and points to the settings", async () => {
    setup({
      "GET /notifications/unsubscribe/abc.def": () =>
        json({ status: 404, code: "invalid_unsubscribe_link", detail: "This unsubscribe link is not valid or has expired." }, 404),
    });
    expect(await screen.findByText("This link doesn't work")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Open notification settings" })).toHaveAttribute("href", "/settings/notifications");
  });

  it("treats an address without a token the same way, without calling the API", async () => {
    const api = setup({}, "");
    expect(await screen.findByText("This link doesn't work")).toBeInTheDocument();
    expect(api.calls).toHaveLength(0);
  });

  it("lets the person try again when the unsubscribe itself fails", async () => {
    const user = userEvent.setup();
    setup({
      "GET /notifications/unsubscribe/abc.def": () => json({ scope: "INSTANT_ALERTS" }),
      "POST /notifications/unsubscribe/abc.def": () => json({ status: 500 }, 500),
    });
    await user.click(await screen.findByRole("button", { name: "Unsubscribe" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("That didn't work");
    expect(screen.getByRole("button", { name: "Unsubscribe" })).toBeEnabled();
  });
});
