package com.jobfinder.core.billing.internal;

import java.time.Instant;

import org.springframework.stereotype.Component;

import com.jobfinder.core.compliance.RetentionTask;

/**
 * Trial-eligibility claims are kept 12 months after they were last used to check eligibility
 * ({@code app.billing.trial.claim-retention}, written into {@code expires_at} on every match), then purged, whether or not
 * the account still exists (docs/adr/0042, section 3; docs/compliance/data-inventory.md).
 */
@Component
class TrialClaimRetention implements RetentionTask {

    private final TrialClaimStore claims;

    TrialClaimRetention(TrialClaimStore claims) {
        this.claims = claims;
    }

    @Override
    public String name() {
        return "trial-claims";
    }

    @Override
    public int purge(Instant now) {
        return claims.purgeExpired(now);
    }
}
