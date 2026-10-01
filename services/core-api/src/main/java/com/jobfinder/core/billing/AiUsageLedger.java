package com.jobfinder.core.billing;

/**
 * Records AI usage: one {@code ai_calls} row per call and, for a call with an owner and a cost, one debit in that
 * user's credit ledger, atomically. Idempotent on {@link AiUsage#requestKey()}. Joins the caller's transaction when
 * there is one, so a module can record usage in the same commit as the result the call produced.
 */
public interface AiUsageLedger {

    RecordOutcome record(AiUsage usage);
}
