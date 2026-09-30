import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { fakeApi, json } from "@/test/fake-api";
import { renderWithQueryClient } from "@/test/render";

import { PreferencesForm } from "./preferences-form";

const hoisted = vi.hoisted(() => ({ client: undefined as unknown }));
vi.mock("@/lib/api", () => ({
  get api() {
    return hoisted.client;
  },
}));

const empty = { targetTitles: [], locations: [], workModes: [], needsSponsorship: false, excludedCompanies: [], excludedIndustries: [] };

function setup(routes: Parameters<typeof fakeApi>[0]) {
  const api = fakeApi({ "GET /preferences": () => json(empty), ...routes });
  hoisted.client = api.client;
  const onSaved = vi.fn();
  renderWithQueryClient(<PreferencesForm submitLabel="Finish" onSaved={onSaved} />);
  return { ...api, onSaved };
}

beforeEach(() => {
  hoisted.client = undefined;
});

describe("PreferencesForm", () => {
  it("sends what was entered as a clean request and continues", async () => {
    const user = userEvent.setup();
    const api = setup({ "PUT /preferences": () => json({ ...empty, currency: "GBP" }) });

    await user.type(await screen.findByLabelText("Job titles"), "Backend Engineer{Enter}Platform Engineer");
    await user.type(screen.getByLabelText("Locations"), "London, Remote - EU");
    await user.click(screen.getByLabelText("Remote"));
    await user.click(screen.getByLabelText("Hybrid"));
    await user.type(screen.getByLabelText("Minimum yearly salary"), "90000");
    await user.type(screen.getByLabelText("Currency"), "gbp");
    await user.click(screen.getByLabelText("I need visa sponsorship"));
    await user.type(screen.getByLabelText("Companies to exclude"), "Acme");
    await user.click(screen.getByRole("button", { name: "Finish" }));

    await waitFor(() => expect(api.onSaved).toHaveBeenCalledOnce());
    expect(api.callsTo("PUT", "/preferences")[0].body).toEqual({
      targetTitles: ["Backend Engineer", "Platform Engineer"],
      locations: ["London", "Remote - EU"],
      workModes: ["REMOTE", "HYBRID"],
      minSalary: 90000,
      currency: "GBP",
      needsSponsorship: true,
      excludedCompanies: ["Acme"],
      excludedIndustries: [],
    });
  }, 20_000);

  it("can be saved with nothing filled in (how a user skips the step)", async () => {
    const user = userEvent.setup();
    const api = setup({ "PUT /preferences": () => json(empty) });

    await user.click(await screen.findByRole("button", { name: "Finish" }));

    await waitFor(() => expect(api.onSaved).toHaveBeenCalledOnce());
    expect(api.callsTo("PUT", "/preferences")[0].body).toMatchObject({ targetTitles: [], workModes: [], needsSponsorship: false });
  });

  it("starts from the saved preferences", async () => {
    setup({
      "GET /preferences": () => json({ ...empty, targetTitles: ["Data Engineer"], workModes: ["REMOTE"], minSalary: 70000, currency: "EUR" }),
    });

    expect(await screen.findByLabelText("Job titles")).toHaveValue("Data Engineer");
    expect(screen.getByLabelText("Remote")).toBeChecked();
    expect(screen.getByLabelText("Hybrid")).not.toBeChecked();
    expect(screen.getByLabelText("Minimum yearly salary")).toHaveValue(70000);
    expect(screen.getByLabelText("Currency")).toHaveValue("EUR");
  });

  it("shows the server's validation message and stays put", async () => {
    const user = userEvent.setup();
    const api = setup({
      "PUT /preferences": () => json({ status: 400, code: "invalid_currency", detail: "Currency must be a valid ISO 4217 code, for example USD." }, 400),
    });

    await user.type(await screen.findByLabelText("Minimum yearly salary"), "50000");
    await user.type(screen.getByLabelText("Currency"), "ZZZ");
    await user.click(screen.getByRole("button", { name: "Finish" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("Currency must be a valid ISO 4217 code");
    expect(api.onSaved).not.toHaveBeenCalled();
  });
});
