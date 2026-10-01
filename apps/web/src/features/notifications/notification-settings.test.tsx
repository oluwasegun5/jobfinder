import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { fakeApi, json } from "@/test/fake-api";
import { renderWithQueryClient } from "@/test/render";

import { NotificationSettings } from "./notification-settings";

const hoisted = vi.hoisted(() => ({ client: undefined as unknown }));
vi.mock("@/lib/api", () => ({
  get api() {
    return hoisted.client;
  },
}));

const defaults = {
  emailEnabled: true,
  digestEnabled: false,
  digestFrequency: "DAILY",
  digestHour: 8,
  digestWeekday: 1,
  instantEnabled: false,
  instantThreshold: 85,
  digestsUnsubscribed: false,
  allUnsubscribed: false,
};

function setup(routes: Parameters<typeof fakeApi>[0]) {
  const api = fakeApi(routes);
  hoisted.client = api.client;
  renderWithQueryClient(<NotificationSettings />);
  return api;
}

beforeEach(() => {
  hoisted.client = undefined;
});

describe("NotificationSettings", () => {
  it("shows everything optional off by default and the visitor's own time zone", async () => {
    setup({ "GET /notifications/preferences": () => json(defaults) });
    expect(await screen.findByLabelText("Email me a digest")).not.toBeChecked();
    expect(screen.getByLabelText("Alert me as soon as a strong match appears")).not.toBeChecked();
    expect(screen.getByLabelText("Send me emails from JobFinder")).toBeChecked();
    expect(screen.getByLabelText("Your time zone")).toHaveValue(Intl.DateTimeFormat().resolvedOptions().timeZone);
    expect(screen.getByLabelText("Lowest match score that alerts")).toBeDisabled();
  });

  it("saves the whole settings object", async () => {
    const user = userEvent.setup();
    const api = setup({
      "GET /notifications/preferences": () => json({ ...defaults, timezone: "Africa/Lagos" }),
      "PUT /notifications/preferences": (r) => r.json().then((body) => json({ ...defaults, ...body })),
    });
    await user.click(await screen.findByLabelText("Email me a digest"));
    await user.selectOptions(screen.getByLabelText("How often"), "WEEKLY");
    await user.selectOptions(screen.getByLabelText("On"), "5");
    await user.selectOptions(screen.getByLabelText("At"), "18");
    await user.click(screen.getByLabelText("Alert me as soon as a strong match appears"));
    const threshold = screen.getByLabelText("Lowest match score that alerts");
    await user.clear(threshold);
    await user.type(threshold, "90");
    await user.click(screen.getByRole("button", { name: "Save settings" }));

    expect(await screen.findByText("Saved.")).toBeInTheDocument();
    expect(api.callsTo("PUT", "/notifications/preferences")[0].body).toEqual({
      emailEnabled: true,
      digestEnabled: true,
      digestFrequency: "WEEKLY",
      digestHour: 18,
      digestWeekday: 5,
      timezone: "Africa/Lagos",
      instantEnabled: true,
      instantThreshold: 90,
    });
  });

  it("shows the server's reason when the settings are refused", async () => {
    const user = userEvent.setup();
    setup({
      "GET /notifications/preferences": () => json(defaults),
      "PUT /notifications/preferences": () =>
        json({ status: 400, code: "invalid_timezone", detail: "Choose a region such as Africa/Lagos." }, 400),
    });
    await user.click(await screen.findByRole("button", { name: "Save settings" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Choose a region such as Africa/Lagos.");
    expect(screen.queryByText("Saved.")).not.toBeInTheDocument();
  });

  it("tells a user who unsubscribed by email that turning a switch on lets mail through again", async () => {
    setup({ "GET /notifications/preferences": () => json({ ...defaults, digestsUnsubscribed: true }) });
    expect(await screen.findByText(/You unsubscribed from digests/)).toBeInTheDocument();
  });

  it("offers a retry when the settings cannot be loaded", async () => {
    const user = userEvent.setup();
    let fail = true;
    setup({
      "GET /notifications/preferences": () => (fail ? json({ status: 500 }, 500) : json(defaults)),
    });
    expect(await screen.findByRole("alert")).toHaveTextContent("couldn't load your notification settings");
    fail = false;
    await user.click(screen.getByRole("button", { name: "Try again" }));
    expect(await screen.findByLabelText("Email me a digest")).toBeInTheDocument();
  });
});
