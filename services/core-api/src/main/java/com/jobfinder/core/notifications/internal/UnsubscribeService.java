package com.jobfinder.core.notifications.internal;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.jobfinder.core.notifications.internal.NotificationDtos.UnsubscribeInfo;
import com.jobfinder.core.notifications.internal.UnsubscribeTokens.Claims;
import com.jobfinder.core.shared.ApiException;

/**
 * What a signed unsubscribe link does (docs/adr/0028-notifications.md). Public: the token is the only credential.
 *
 * <ul>
 * <li>A token that is forged, changed or expired is {@code 404 invalid_unsubscribe_link}: one answer for every kind of
 * bad token, so the endpoint reveals nothing.</li>
 * <li>A genuine token always succeeds, whether or not the account or saved search still exists, and repeats are
 * harmless: the response never says whether anything changed, so it cannot be used to find out who has an account.</li>
 * <li>It touches only optional mail (digests, alerts). Verification and password-reset mail do not read these tables.</li>
 * </ul>
 */
@Service
class UnsubscribeService {

    private final UnsubscribeTokens tokens;
    private final NotificationPreferencesService preferences;
    private final SavedSearchService searches;

    UnsubscribeService(UnsubscribeTokens tokens, NotificationPreferencesService preferences, SavedSearchService searches) {
        this.tokens = tokens;
        this.preferences = preferences;
        this.searches = searches;
    }

    /** What the link would do, without doing it (a mail scanner or a curious click on the link changes nothing). */
    UnsubscribeInfo describe(String token) {
        Claims claims = claims(token);
        return info(claims);
    }

    /** Does it. */
    UnsubscribeInfo apply(String token) {
        Claims claims = claims(token);
        try {
            switch (claims.scope()) {
                case SAVED_SEARCH -> searches.switchOff(claims.userId(), claims.savedSearchId());
                case DIGESTS -> preferences.suppress(claims.userId(), true, false, false);
                case INSTANT_ALERTS -> {
                    preferences.suppress(claims.userId(), false, true, false);
                    searches.switchOffInstant(claims.userId());
                }
                case MARKETING -> {
                    preferences.suppress(claims.userId(), false, false, true);
                    searches.switchOffInstant(claims.userId());
                }
            }
        } catch (DataIntegrityViolationException e) {
            // The account is gone (a foreign key refused the row): nothing is left to send to, which is the goal.
        }
        return info(claims);
    }

    private Claims claims(String token) {
        return tokens.verify(token).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                "invalid_unsubscribe_link", "This unsubscribe link is not valid or has expired."));
    }

    private UnsubscribeInfo info(Claims claims) {
        String name = claims.savedSearchId() == null ? null
                : searches.nameOf(claims.userId(), claims.savedSearchId()).orElse(null);
        return new UnsubscribeInfo(claims.scope(), name);
    }
}
