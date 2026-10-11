package com.jobfinder.core.billing.internal;

import java.util.ArrayList;
import java.util.List;

/** The activation milestones that each release one trial tranche (docs/adr/0042, section 1). */
enum TrialMilestone {
    /** M1: email verified, or a Google sign-in. */
    EMAIL_VERIFIED("M1", 1),
    /** M2: a CV parsed. */
    CV_PARSED("M2", 2),
    /** M3: profile and preferences complete. */
    ONBOARDING_COMPLETED("M3", 4),
    /** M4: matches viewed, on a later UTC day than M1. */
    MATCHES_VIEWED("M4", 8);

    /** Every milestone's bit: what a claim of an account that existed before the trial carries. */
    static final int ALL = 15;

    private final String code;
    private final int bit;

    TrialMilestone(String code, int bit) {
        this.code = code;
        this.bit = bit;
    }

    String code() {
        return code;
    }

    int bit() {
        return bit;
    }

    boolean in(int milestones) {
        return (milestones & bit) != 0;
    }

    static List<String> names(int milestones) {
        List<String> names = new ArrayList<>();
        for (TrialMilestone m : values()) {
            if (m.in(milestones)) {
                names.add(m.name());
            }
        }
        return names;
    }
}
