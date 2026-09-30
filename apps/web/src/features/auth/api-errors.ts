type ProblemLike = { detail?: string; code?: string } | undefined;

export function problemCode(error: unknown): string | undefined {
  return (error as ProblemLike)?.code;
}

/** User-facing text for a failed core-api call; falls back when the server sent no detail. */
export function problemMessage(error: unknown, fallback = "Something went wrong. Please try again.") {
  const detail = (error as ProblemLike)?.detail;
  return typeof detail === "string" && detail.length > 0 ? detail : fallback;
}
