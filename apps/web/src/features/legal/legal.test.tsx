import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import { CookieNotice } from "./cookie-notice";
import { LegalLinks } from "./legal-page";
import { PLACEHOLDERS, RETENTION_DAYS } from "./placeholders";
import { PrivacyPolicy } from "./privacy-policy";
import { Subprocessors } from "./subprocessors";
import { TermsOfService } from "./terms-of-service";

vi.mock("next/link", () => ({
  default: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => (
    <a href={href} {...rest}>
      {children}
    </a>
  ),
}));

const pages = [
  ["privacy policy", PrivacyPolicy],
  ["terms", TermsOfService],
  ["cookie notice", CookieNotice],
  ["subprocessors", Subprocessors],
] as const;

describe("legal templates", () => {
  it.each(pages)("%s is marked as a template that needs lawyer review", (_name, Page) => {
    render(<Page />);

    const note = screen.getByRole("note");
    expect(note).toHaveTextContent(/not legal advice/i);
    expect(note).toHaveTextContent(/lawyer/i);
    expect(screen.getByRole("heading", { level: 1 })).toBeInTheDocument();
  });

  it.each(pages)("%s only uses declared placeholders", (_name, Page) => {
    const { container } = render(<Page />);

    const used = [...container.querySelectorAll("[data-placeholder]")].map((el) => el.getAttribute("data-placeholder")!);
    expect(used.length).toBeGreaterThan(0);
    for (const key of used) expect(Object.keys(PLACEHOLDERS)).toContain(key);
  });

  it("every declared placeholder appears somewhere, so none can be dropped silently", () => {
    const used = new Set<string>();
    for (const [, Page] of pages) {
      const { container, unmount } = render(<Page />);
      container.querySelectorAll("[data-placeholder]").forEach((el) => used.add(el.getAttribute("data-placeholder")!));
      unmount();
    }

    expect([...used].sort()).toEqual(Object.keys(PLACEHOLDERS).sort());
  });

  it("claims no certification or compliance status", () => {
    for (const [, Page] of pages) {
      const { container, unmount } = render(<Page />);
      expect(container.textContent).not.toMatch(/soc ?2|iso ?27001|certified|fully compliant|hipaa/i);
      unmount();
    }
  });

  it("states the retention periods the code enforces", () => {
    const { container } = render(<PrivacyPolicy />);
    const text = container.textContent ?? "";

    expect(text).toContain(`deleted after ${RETENTION_DAYS.unverifiedAccounts} days`);
    expect(text).toContain(`${RETENTION_DAYS.expiredTokens} days after they expire`);
    expect(text).toContain(`${RETENTION_DAYS.emailLog} days`);
    expect(text).toContain(`${RETENTION_DAYS.renderedFiles} days`);
  });

  it("states the backup retention period as a fact, not a placeholder", () => {
    const { container } = render(<PrivacyPolicy />);
    const text = container.textContent ?? "";

    expect(RETENTION_DAYS.backups).toBeGreaterThan(0);
    expect(text).toContain(`backups are kept for ${RETENTION_DAYS.backups} days`);
    expect(text).toContain(`Database backups: ${RETENTION_DAYS.backups} days`);
    expect(text).not.toMatch(/BACKUP RETENTION/i);
    expect(Object.keys(PLACEHOLDERS)).not.toContain("backupRetention");
    expect(container.querySelector('[data-placeholder="backupRetention"]')).toBeNull();
  });

  it("names the subprocessors and the user rights", () => {
    const sub = render(<Subprocessors />);
    for (const name of ["Anthropic", "Google", "Stripe", "Paystack", "Sentry"]) expect(sub.container.textContent).toContain(name);
    sub.unmount();

    const policy = render(<PrivacyPolicy />);
    expect(policy.container.textContent).toMatch(/Nigeria Data Protection Act/);
    expect(policy.container.textContent).toMatch(/GDPR/);
  });

  it("links to every legal page from the footer navigation", () => {
    render(<LegalLinks />);

    const nav = screen.getByRole("navigation", { name: "Legal" });
    const hrefs = [...nav.querySelectorAll("a")].map((a) => a.getAttribute("href"));
    expect(hrefs).toEqual(["/privacy", "/terms", "/cookies", "/subprocessors"]);
  });
});
