package com.jobfinder.core.identity.internal;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.jobfinder.core.identity.UserDeletionRequested;

/**
 * Identity's share of an account deletion: everything keyed by the user except the user row,
 * which {@link AuthService#deleteAccount} removes last, and the user's rate-limit keys in Redis. Runs in the deleting transaction.
 */
@Component
class IdentityDeletionHandler {

    private final RefreshTokenRepository refreshTokens;
    private final EmailTokenRepository emailTokens;
    private final OAuthAccountRepository oauthAccounts;
    private final RateLimiter rateLimiter;

    IdentityDeletionHandler(RefreshTokenRepository refreshTokens, EmailTokenRepository emailTokens,
            OAuthAccountRepository oauthAccounts, RateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
        this.refreshTokens = refreshTokens;
        this.emailTokens = emailTokens;
        this.oauthAccounts = oauthAccounts;
    }

    @EventListener
    void on(UserDeletionRequested event) {
        refreshTokens.deleteAllForUser(event.userId());
        emailTokens.deleteAllForUser(event.userId());
        oauthAccounts.deleteAllForUser(event.userId());
        rateLimiter.forgetSubject(event.userId());
    }
}
