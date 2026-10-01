/**
 * The AI usage ledger and cost controls (docs/adr/0025-ai-usage-ledger.md): every AI call is recorded in
 * {@code ai_calls}, user-attributed calls debit the user's {@code credit_ledger}, and a per-user daily cap blocks
 * further user-attributed calls. Other modules use {@link com.jobfinder.core.billing.AiUsageLedger} to record,
 * {@link com.jobfinder.core.billing.AiUsageGate} to check the cap before a call and
 * {@link com.jobfinder.core.billing.AiCostReports} for the admin cost dashboard; billing depends on no other
 * module.
 */
package com.jobfinder.core.billing;
