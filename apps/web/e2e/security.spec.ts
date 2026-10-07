import { expect, test } from "@playwright/test";

test("the proxy never forwards service-to-service paths to core-api", async ({ request }) => {
  for (const path of ["/api/core/internal/embeddings", "/api/core/%69nternal/x", "/api/core/actuator/env"]) {
    const response = await request.get(path);
    expect(response.status(), path).toBe(404);
  }
});

test("the status badge can still read core-api health, and only the status comes back", async ({ request }) => {
  const response = await request.get("/api/core/actuator/health");
  expect(response.status()).toBe(200);
  const body = await response.json();
  expect(Object.keys(body).sort()).toEqual(["groups", "status"]);
  expect(body.status).toBe("UP");
});

test("pages carry a nonce-based CSP and run without a single violation", async ({ page }) => {
  const problems: string[] = [];
  page.on("console", (message) => {
    if (message.text().includes("Content Security Policy")) problems.push(message.text());
  });
  const response = await page.goto("/login");
  const csp = response?.headers()["content-security-policy"] ?? "";
  expect(csp).toMatch(/script-src [^;]*'nonce-[A-Za-z0-9+/=]+'/);
  expect(csp).not.toMatch(/script-src [^;]*unsafe-(inline|eval)/);
  expect(response?.headers()["x-content-type-options"]).toBe("nosniff");
  expect(response?.headers()["x-frame-options"]).toBe("DENY");
  await expect(page.getByRole("button", { name: /log in|sign in/i }).first()).toBeVisible();
  expect(problems).toEqual([]);
});
