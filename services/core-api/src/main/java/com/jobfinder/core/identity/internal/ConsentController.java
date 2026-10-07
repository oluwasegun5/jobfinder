package com.jobfinder.core.identity.internal;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

import com.jobfinder.core.identity.CurrentUser;
import com.jobfinder.core.identity.internal.AuthDtos.ConsentResponse;

/**
 * The caller's own consent to AI processing: read it, give it (accounts created with Google, or before consent was
 * recorded, start without it) and withdraw it. Withdrawing stops every further AI call for the user at once; data
 * already stored stays until the user deletes it or the account (docs/adr/0040).
 */
@RestController
class ConsentController {

    private final AiConsentService consent;

    ConsentController(AiConsentService consent) {
        this.consent = consent;
    }

    @GetMapping("/me/consent")
    ConsentResponse get() {
        return respond(consent.status(CurrentUser.require().id()));
    }

    @PutMapping("/me/consent/ai")
    ConsentResponse grant() {
        return respond(consent.grant(CurrentUser.require().id()));
    }

    @DeleteMapping("/me/consent/ai")
    ConsentResponse withdraw() {
        return respond(consent.withdraw(CurrentUser.require().id()));
    }

    private static ConsentResponse respond(AiConsentService.Status status) {
        return new ConsentResponse(status.granted(), status.version(), status.grantedAt(),
                AiConsentService.CURRENT_VERSION);
    }
}
