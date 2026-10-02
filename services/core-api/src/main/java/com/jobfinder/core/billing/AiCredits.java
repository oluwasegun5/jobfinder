package com.jobfinder.core.billing;

import java.math.BigDecimal;

/**
 * What a call costs in credits, by the same formula the ledger debits with (docs/adr/0025-ai-usage-ledger.md): the
 * provider cost over what one credit stands for, to six decimals. A module that shows a user what a feature consumed
 * (a mock interview session, docs/adr/0034-mock-interview.md) adds up this figure for the calls the ledger recorded,
 * so what it shows and what the ledger holds cannot disagree. Read only: it records and debits nothing.
 */
public interface AiCredits {

    /** @param costUsd the provider cost of one call in US dollars, as reported to {@link AiUsageLedger} */
    BigDecimal creditsFor(BigDecimal costUsd);
}
