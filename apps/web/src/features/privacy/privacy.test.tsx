import { configure, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { fakeApi, json } from "@/test/fake-api";
import { renderWithQueryClient } from "@/test/render";

import { ConsentGate } from "./consent-gate";
import { PrivacySettings } from "./privacy-settings";

// A CI runner can be slow to resolve the stubbed fetch; the default 1 s is too tight there.
configure({ asyncUtilTimeout: 5000 });

const hoisted = vi.hoisted(() => ({
  client: undefined as unknown,
  user: { aiConsent: false } as { aiConsent: boolean } | null,
  pathname: "/dashboard",
  reloadUser: vi.fn(async () => {}),
  signOut: vi.fn(async () => {}),
  replace: vi.fn(),
  clearSession: vi.fn(),
  saveBlob: vi.fn(),
}));

vi.mock("@/lib/api", () => ({
  get api() {
    return hoisted.client;
  },
}));
vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: hoisted.replace }),
  usePathname: () => hoisted.pathname,
}));
vi.mock("next/link", () => ({
  default: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => (
    <a href={href} {...rest}>
      {children}
    </a>
  ),
}));
vi.mock("@/features/auth/auth-provider", () => ({
  useAuth: () => ({ user: hoisted.user, reloadUser: hoisted.reloadUser, signOut: hoisted.signOut }),
}));
vi.mock("@/lib/auth/session", () => ({ clearSession: hoisted.clearSession }));
vi.mock("./queries", async (importOriginal) => ({ ...(await importOriginal<typeof import("./queries")>()), saveBlob: hoisted.saveBlob }));

const off = { aiProcessing: false, currentVersion: "2026-10" };
const on = { aiProcessing: true, version: "2026-10", grantedAt: "2026-10-01T10:00:00Z", currentVersion: "2026-10" };
const problem = (status: number, detail: string) =>
  json({ type: "about:blank", title: "Error", status, detail }, status);

beforeEach(() => {
  vi.clearAllMocks();
  hoisted.user = { aiConsent: false };
  hoisted.pathname = "/dashboard";
});

describe("ConsentGate", () => {
  it("replaces the page with the consent prompt while consent is missing", () => {
    hoisted.client = fakeApi({}).client;
    renderWithQueryClient(<ConsentGate>app content</ConsentGate>);

    expect(screen.queryByText("app content")).not.toBeInTheDocument();
    expect(screen.getByRole("heading", { name: /allow ai processing/i })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /download or delete your data/i })).toHaveAttribute("href", "/settings/privacy");
  });

  it("passes through once consent exists, when nobody is signed in, and on the privacy settings page", () => {
    hoisted.client = fakeApi({}).client;
    hoisted.user = { aiConsent: true };
    const granted = renderWithQueryClient(<ConsentGate>app content</ConsentGate>);
    expect(screen.getByText("app content")).toBeInTheDocument();
    granted.unmount();

    hoisted.user = null;
    const anonymous = renderWithQueryClient(<ConsentGate>app content</ConsentGate>);
    expect(screen.getByText("app content")).toBeInTheDocument();
    anonymous.unmount();

    hoisted.user = { aiConsent: false };
    hoisted.pathname = "/settings/privacy";
    renderWithQueryClient(<ConsentGate>app content</ConsentGate>);
    expect(screen.getByText("app content")).toBeInTheDocument();
  });

  it("records the agreement and reloads the user", async () => {
    const api = fakeApi({ "PUT /me/consent/ai": () => json(on) });
    hoisted.client = api.client;
    const user = userEvent.setup();
    renderWithQueryClient(<ConsentGate>app content</ConsentGate>);

    await user.click(screen.getByRole("button", { name: /i agree to ai processing/i }));

    await waitFor(() => expect(hoisted.reloadUser).toHaveBeenCalledTimes(1));
    expect(api.callsTo("PUT", "/me/consent/ai")).toHaveLength(1);
  });

  it("shows an error and does not reload when saving fails", async () => {
    hoisted.client = fakeApi({ "PUT /me/consent/ai": () => problem(500, "boom") }).client;
    const user = userEvent.setup();
    renderWithQueryClient(<ConsentGate>app content</ConsentGate>);

    await user.click(screen.getByRole("button", { name: /i agree to ai processing/i }));

    expect(await screen.findByRole("alert")).toBeInTheDocument();
    expect(hoisted.reloadUser).not.toHaveBeenCalled();
  });

  it("lets the person sign out instead", async () => {
    hoisted.client = fakeApi({}).client;
    const user = userEvent.setup();
    renderWithQueryClient(<ConsentGate>app content</ConsentGate>);

    await user.click(screen.getByRole("button", { name: "Sign out" }));

    expect(hoisted.signOut).toHaveBeenCalled();
  });
});

describe("PrivacySettings", () => {
  it("turns AI processing on and off through the consent endpoints", async () => {
    let current: unknown = off;
    const api = fakeApi({
      "GET /me/consent": () => json(current),
      "PUT /me/consent/ai": () => json((current = on)),
      "DELETE /me/consent/ai": () => json((current = off)),
    });
    hoisted.client = api.client;
    const user = userEvent.setup();
    renderWithQueryClient(<PrivacySettings />);

    await user.click(await screen.findByRole("button", { name: "Turn on AI processing" }));
    expect(await screen.findByRole("button", { name: "Turn off AI processing" })).toBeInTheDocument();
    expect(api.callsTo("PUT", "/me/consent/ai")).toHaveLength(1);
    expect(hoisted.reloadUser).toHaveBeenCalledTimes(1);

    await user.click(screen.getByRole("button", { name: "Turn off AI processing" }));
    expect(await screen.findByRole("button", { name: "Turn on AI processing" })).toBeInTheDocument();
    expect(api.callsTo("DELETE", "/me/consent/ai")).toHaveLength(1);
    expect(hoisted.reloadUser).toHaveBeenCalledTimes(2);
  });

  it("downloads the export as a zip", async () => {
    const api = fakeApi({
      "GET /me/consent": () => json(on),
      "GET /me/export": () => new Response(new Uint8Array([0x50, 0x4b]), { status: 200, headers: { "Content-Type": "application/zip" } }),
    });
    hoisted.client = api.client;
    const user = userEvent.setup();
    renderWithQueryClient(<PrivacySettings />);

    await user.click(screen.getByRole("button", { name: "Download my data" }));

    await waitFor(() => expect(hoisted.saveBlob).toHaveBeenCalledTimes(1));
    expect(hoisted.saveBlob.mock.calls[0][1]).toBe("jobfinder-data-export.zip");
    expect(await screen.findByText("Your export was downloaded.")).toBeInTheDocument();
  });

  it("explains a rate-limited export", async () => {
    hoisted.client = fakeApi({
      "GET /me/consent": () => json(on),
      "GET /me/export": () => problem(429, "slow down"),
    }).client;
    const user = userEvent.setup();
    renderWithQueryClient(<PrivacySettings />);

    await user.click(screen.getByRole("button", { name: "Download my data" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(/too often/i);
    expect(hoisted.saveBlob).not.toHaveBeenCalled();
  });

  it("deletes the account only after DELETE is typed, then clears the session", async () => {
    const api = fakeApi({ "GET /me/consent": () => json(on), "DELETE /me": () => new Response(null, { status: 204 }) });
    hoisted.client = api.client;
    const user = userEvent.setup();
    renderWithQueryClient(<PrivacySettings />);

    await user.click(screen.getByRole("button", { name: /delete my account…/i }));
    const confirm = screen.getByRole("button", { name: "Permanently delete my account" });
    expect(confirm).toBeDisabled();

    await user.type(screen.getByLabelText(/type delete to confirm/i), "delete");
    expect(confirm).toBeDisabled();
    await user.clear(screen.getByLabelText(/type delete to confirm/i));
    await user.type(screen.getByLabelText(/type delete to confirm/i), "DELETE");
    expect(confirm).toBeEnabled();
    expect(api.callsTo("DELETE", "/me")).toHaveLength(0);

    await user.click(confirm);

    await waitFor(() => expect(hoisted.clearSession).toHaveBeenCalled());
    expect(api.callsTo("DELETE", "/me")).toHaveLength(1);
    expect(hoisted.replace).toHaveBeenCalledWith("/");
  });

  it("keeps the session and says nothing was deleted when deletion fails", async () => {
    hoisted.client = fakeApi({ "GET /me/consent": () => json(on), "DELETE /me": () => problem(500, "boom") }).client;
    const user = userEvent.setup();
    renderWithQueryClient(<PrivacySettings />);

    await user.click(screen.getByRole("button", { name: /delete my account…/i }));
    await user.type(screen.getByLabelText(/type delete to confirm/i), "DELETE");
    await user.click(screen.getByRole("button", { name: "Permanently delete my account" }));

    expect(await screen.findByRole("alert")).toBeInTheDocument();
    expect(hoisted.clearSession).not.toHaveBeenCalled();
    expect(hoisted.replace).not.toHaveBeenCalled();
  });
});
