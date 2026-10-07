/**
 * Every fact about the operating company that the legal templates need but the product cannot know. They are shown
 * highlighted in the pages and must ALL be replaced (by editing this file) after review by a qualified lawyer, before
 * launch. The test in legal.test.tsx lists them, so a placeholder cannot be dropped silently.
 */
export const PLACEHOLDERS = {
  company: "[COMPANY LEGAL NAME]",
  address: "[REGISTERED ADDRESS]",
  contactEmail: "[CONTACT EMAIL]",
  privacyEmail: "[DATA PROTECTION CONTACT EMAIL]",
  effectiveDate: "[EFFECTIVE DATE]",
  governingLaw: "[GOVERNING LAW AND COURTS]",
  minimumAge: "[MINIMUM AGE, e.g. 18]",
  hostingProvider: "[HOSTING PROVIDER]",
  emailProvider: "[EMAIL DELIVERY PROVIDER]",
  backupRetention: "[BACKUP RETENTION PERIOD]",
  transferMechanism: "[TRANSFER MECHANISM, e.g. standard contractual clauses]",
  refundPolicy: "[REFUND POLICY]",
} as const;

export type PlaceholderKey = keyof typeof PLACEHOLDERS;

/** The retention periods the code enforces (app.retention.* in core-api, docs/compliance/data-inventory.md). */
export const RETENTION_DAYS = {
  unverifiedAccounts: 30,
  expiredTokens: 7,
  emailLog: 365,
  renderedFiles: 90,
} as const;
