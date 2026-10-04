import { toFailure, type Failure } from "@/features/tailoring/errors";

/** Words for the codes core-api's mock interview endpoints put on their problem documents. */
const MESSAGES: Record<string, { message: string; retryable?: boolean }> = {
  answer_in_flight: {
    message: "Your last answer is still being scored. Wait a few seconds, then send again. Your text is still here.",
    retryable: true,
  },
  mock_interview_unavailable: {
    message: "The interviewer is unavailable right now, so nothing was scored. Your text is still here: try again in a moment.",
    retryable: true,
  },
  answer_too_long: { message: "That answer is longer than the limit. Shorten it and send again." },
  idempotency_key_reused: { message: "That answer could not be matched to the one already sent. Reload the interview and try again." },
  interview_session_not_found: { message: "This interview does not exist, or it is not yours." },
  interview_session_completed: { message: "This interview is finished. Start a new one to keep practising." },
  interview_session_abandoned: { message: "This interview was left idle for too long and was closed. Start a new one." },
  turn_limit_reached: { message: "You have answered every question. End the interview to get your summary." },
  nothing_to_summarise: { message: "Answer at least one question first, then end the interview." },
  job_not_found: { message: "This job is no longer available." },
  interview_prep_not_found: { message: "That interview prep was not found." },
  prep_job_mismatch: { message: "That interview prep was made for a different job." },
  application_not_found: { message: "That application was not found." },
  application_job_mismatch: { message: "That application is for a different job." },
  invalid_max_turns: { message: "Choose a number of questions the interviewer offers." },
};

/**
 * A failed mock interview call in words, never including what the person wrote. The daily cap (429) and the
 * unavailable service (503) keep the shapes the rest of the app uses; this adds the interview's own codes.
 */
export function interviewFailure(error: unknown): Failure {
  const failure = toFailure(error);
  if (failure.kind === "cap" || failure.kind === "credits") return failure;
  const known = failure.code ? MESSAGES[failure.code] : undefined;
  if (!known) {
    return failure.kind === "unavailable"
      ? { ...failure, message: MESSAGES.mock_interview_unavailable.message, retryable: true }
      : failure;
  }
  return { ...failure, message: known.message, retryable: known.retryable ?? failure.retryable };
}
