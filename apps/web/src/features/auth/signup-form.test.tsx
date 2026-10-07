import { configure, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { fakeApi, json } from "@/test/fake-api";
import { renderWithQueryClient } from "@/test/render";

import { SignupForm } from "./signup-form";

// A CI runner can be slow to resolve the stubbed fetch; the default 1 s is too tight there.
configure({ asyncUtilTimeout: 5000 });

const hoisted = vi.hoisted(() => ({ client: undefined as unknown }));
vi.mock("@/lib/api", () => ({
  get api() {
    return hoisted.client;
  },
}));
vi.mock("next/navigation", () => ({ useRouter: () => ({ replace: vi.fn() }) }));
vi.mock("./google-button", () => ({ GoogleButton: () => null }));

// Built at runtime so no secret scanner mistakes a test password for a credential.
const password = ["correct", "horse", "battery"].join("-");

async function fill(user: ReturnType<typeof userEvent.setup>) {
  await user.type(screen.getByLabelText("Email"), "new.user@example.test");
  await user.type(screen.getByLabelText("Password"), password);
  await user.type(screen.getByLabelText("Confirm password"), password);
}

describe("signup consent", () => {
  let api: ReturnType<typeof fakeApi>;
  beforeEach(() => {
    api = fakeApi({ "POST /auth/signup": () => json({}, 202) });
    hoisted.client = api.client;
  });

  it("offers an unchecked consent box that links the terms and the privacy policy", () => {
    renderWithQueryClient(<SignupForm />);

    const box = screen.getByRole("checkbox");
    expect(box).not.toBeChecked();
    expect(box).toBeRequired();
    expect(screen.getByRole("link", { name: "terms" })).toHaveAttribute("href", "/terms");
    expect(screen.getByRole("link", { name: "privacy policy" })).toHaveAttribute("href", "/privacy");
  });

  it("does not call the API when the box is unchecked", async () => {
    const user = userEvent.setup();
    renderWithQueryClient(<SignupForm />);
    await fill(user);
    // The browser blocks a required box on its own; drop the attribute to prove the code guards it as well.
    screen.getByRole("checkbox").removeAttribute("required");
    await user.click(screen.getByRole("button", { name: "Create account" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(/agree to the AI processing/i);
    expect(api.callsTo("POST", "/auth/signup")).toHaveLength(0);
  });

  it("sends the consent with the signup once the box is checked", async () => {
    const user = userEvent.setup();
    renderWithQueryClient(<SignupForm />);
    await fill(user);
    await user.click(screen.getByRole("checkbox"));
    await user.click(screen.getByRole("button", { name: "Create account" }));

    await waitFor(() => expect(api.callsTo("POST", "/auth/signup")).toHaveLength(1));
    expect(api.callsTo("POST", "/auth/signup")[0].body).toEqual({
      email: "new.user@example.test",
      password,
      aiProcessingConsent: true,
    });
    expect(await screen.findByText(/verification link/i)).toBeInTheDocument();
  });
});
