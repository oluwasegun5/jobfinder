package com.jobfinder.core.identity.internal;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.jobfinder.core.identity.UserDeletionRequested;

/**
 * Identity's share of an account deletion: everything keyed by the user except the user row,
 * which {@link AuthService#deleteAccount} removes last. Runs in the deleting transaction.
 */
@Component
class IdentityDeletionHandler {

    private final RefreshTokenRepository refreshTokens;
    private final EmailTokenRepository emailTokens;
    private final OAuthAccountRepository oauthAccounts;

    IdentityDeletionHandler(RefreshTokenRepository refreshTokens, EmailTokenRepository emailTokens,
            OAuthAccountRepository oauthAccounts) {
        this.refreshTokens = refreshTokens;
        this.emailTokens = emailTokens;
        this.oauthAccounts = oauthAccounts;
    }

    @EventListener
    void on(UserDeletionRequested event) {
        refreshTokens.deleteAllForUser(event.userId());
        emailTokens.deleteAllForUser(event.userId());
        oauthAccounts.deleteAllForUser(event.userId());
    }
}
