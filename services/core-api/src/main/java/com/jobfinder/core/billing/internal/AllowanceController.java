package com.jobfinder.core.billing.internal;

import java.math.BigDecimal;
import java.time.Instant;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.jobfinder.core.billing.Allowance;
import com.jobfinder.core.identity.CurrentUser;

/** What the signed-in user can still spend on AI today. Always about the caller; there is no way to ask about anyone else. */
@RestController
@RequestMapping("/billing")
class AllowanceController {

    /**
     * Credits for the current UTC day. {@code dailyCap} and {@code remaining} are null when no cap is configured;
     * {@code resetsAt} is when the day ends and the allowance starts afresh.
     */
    record AllowanceResponse(BigDecimal dailyCap, BigDecimal used, BigDecimal remaining, Instant resetsAt,
            boolean exhausted) {

        static AllowanceResponse of(Allowance allowance) {
            return new AllowanceResponse(allowance.dailyCap(), allowance.used(), allowance.remaining(),
                    allowance.resetsAt(), allowance.exhausted());
        }
    }

    private final DailyCapService caps;

    AllowanceController(DailyCapService caps) {
        this.caps = caps;
    }

    @GetMapping("/allowance")
    AllowanceResponse allowance() {
        return AllowanceResponse.of(caps.allowance(CurrentUser.require().id()));
    }
}
