/**
 * The access token's only home: chrome.storage.session, restricted to trusted contexts (the service worker and our own
 * pages), so no content script and no page can read it. It is never copied to storage.local, never sent in a message,
 * never logged (docs/adr/0035-chrome-extension.md).
 */

export interface StoredAuth {
  accessToken: string;
  /** Epoch milliseconds, already shortened by a safety margin. */
  expiresAt: number;
  /** For display in the popup only. */
  email?: string;
}

const KEY = "auth";

/** Idempotent; called whenever the service worker starts. */
export async function protectSession(): Promise<void> {
  await chrome.storage.session.setAccessLevel({ accessLevel: "TRUSTED_CONTEXTS" });
}

function isStoredAuth(value: unknown): value is StoredAuth {
  if (typeof value !== "object" || value === null) return false;
  const v = value as Record<string, unknown>;
  return (
    typeof v.accessToken === "string" &&
    v.accessToken.length > 0 &&
    typeof v.expiresAt === "number" &&
    (v.email === undefined || typeof v.email === "string")
  );
}

export async function loadAuth(): Promise<StoredAuth | null> {
  const stored = await chrome.storage.session.get(KEY);
  const value: unknown = stored[KEY];
  return isStoredAuth(value) ? value : null;
}

export async function saveAuth(auth: StoredAuth): Promise<void> {
  await chrome.storage.session.set({ [KEY]: auth });
}

export async function clearAuth(): Promise<void> {
  await chrome.storage.session.remove(KEY);
}
