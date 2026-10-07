/**
 * Masks what is recognisable by its shape (emails, tokens, keys) in anything that leaves the server in a log line or an
 * error report. The rule is to never log such values; this catches the slip. Mirrors core-api's PiiRedactor and
 * ai-service's redaction module (docs/adr/0039-observability.md). It cannot recognise free text such as a CV, so it
 * also caps the length.
 */
export const MAX_LENGTH = 4000;

const EMAIL = /[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}/g;
const JWT = /eyJ[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]*/g;
const BEARER = /\b(bearer|basic)\s+[A-Za-z0-9._~+/-]+=*/gi;
const PROVIDER_KEY =
  /\b(?:sk-ant-[A-Za-z0-9_-]{8,}|(?:sk|pk|rk)_(?:live|test)_[A-Za-z0-9]{6,}|whsec_[A-Za-z0-9]{6,}|pa-[A-Za-z0-9_-]{20,}|AKIA[0-9A-Z]{16}|AIza[0-9A-Za-z_-]{20,})/g;
const NAMED_SECRET =
  /(["']?(?:password|passwd|secret|api[_-]?key|access[_-]?token|refresh[_-]?token|id[_-]?token|token|authorization|x-service-token|cookie|set-cookie|signature|stripe-signature|x-paystack-signature)["']?\s*[=:]\s*)(?:"[^"]*"|'[^']*'|[^\s,;&"'}]+)/gi;
const PHONE = /(?<![\w+])\+\d[\d\s().-]{7,}\d/g;

export function redact(text: string): string {
  if (!text) return text;
  const result = text
    .replace(JWT, "[jwt]")
    .replace(BEARER, (_m, scheme: string) => `${scheme} [redacted]`)
    .replace(PROVIDER_KEY, "[key]")
    .replace(NAMED_SECRET, (_m, name: string) => `${name}[redacted]`)
    .replace(EMAIL, "[email]")
    .replace(PHONE, "[phone]");
  return result.length > MAX_LENGTH ? `${result.slice(0, MAX_LENGTH)}...[truncated]` : result;
}
