export type FieldError = { field: string; message: string };

type ProblemLike = { detail?: string; code?: string; errors?: unknown } | undefined;

export function problemCode(error: unknown): string | undefined {
  return (error as ProblemLike)?.code;
}

/** User-facing text for a failed core-api call; falls back when the server sent no detail. */
export function problemMessage(error: unknown, fallback = "Something went wrong. Please try again.") {
  const detail = (error as ProblemLike)?.detail;
  return typeof detail === "string" && detail.length > 0 ? detail : fallback;
}

/** "experience[0].company" -> "Experience 1 → company", for showing a server validation failure to a person. */
function describeField(path: string) {
  const spaced = path
    .replace(/\[(\d+)\]/g, (_, index: string) => ` ${Number(index) + 1}`)
    .replaceAll(".", " → ")
    .replaceAll("_", " ");
  return spaced.charAt(0).toUpperCase() + spaced.slice(1);
}

/** The per-field failures core-api sends with a 400 `validation_failed`, already worded for display. */
export function problemFieldErrors(error: unknown): string[] {
  const errors = (error as ProblemLike)?.errors;
  if (!Array.isArray(errors)) return [];
  return errors
    .filter((e): e is FieldError => typeof e?.field === "string" && typeof e?.message === "string")
    .map((e) => `${describeField(e.field)}: ${e.message}`);
}
