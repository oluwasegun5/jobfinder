package com.jobfinder.core.notifications.internal;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.jobfinder.core.identity.CurrentUser;
import com.jobfinder.core.notifications.internal.NotificationDtos.NotificationPreferencesRequest;
import com.jobfinder.core.notifications.internal.NotificationDtos.NotificationPreferencesView;
import com.jobfinder.core.notifications.internal.NotificationDtos.SavedSearchList;
import com.jobfinder.core.notifications.internal.NotificationDtos.SavedSearchRequest;
import com.jobfinder.core.notifications.internal.NotificationDtos.SavedSearchView;
import com.jobfinder.core.notifications.internal.NotificationDtos.UnsubscribeInfo;

import jakarta.validation.Valid;

/**
 * Notification settings and saved searches of the signed-in user (the user comes from the access token, never from the
 * request), and the public one-click unsubscribe endpoints (the signed token is the credential; see
 * {@link UnsubscribeService}).
 */
@RestController
class NotificationController {

    private final NotificationPreferencesService preferences;
    private final SavedSearchService searches;
    private final UnsubscribeService unsubscribe;

    NotificationController(NotificationPreferencesService preferences, SavedSearchService searches,
            UnsubscribeService unsubscribe) {
        this.preferences = preferences;
        this.searches = searches;
        this.unsubscribe = unsubscribe;
    }

    /** The caller's notification settings, or the defaults (digests and alerts off) when they never saved any. */
    @GetMapping("/notifications/preferences")
    NotificationPreferencesView preferences() {
        return preferences.view(CurrentUser.require().id());
    }

    @PutMapping("/notifications/preferences")
    NotificationPreferencesView savePreferences(@Valid @RequestBody NotificationPreferencesRequest request) {
        return preferences.put(CurrentUser.require().id(), request);
    }

    @GetMapping("/saved-searches")
    SavedSearchList savedSearches() {
        return searches.list(CurrentUser.require().id());
    }

    @PostMapping("/saved-searches")
    @ResponseStatus(HttpStatus.CREATED)
    SavedSearchView createSavedSearch(@Valid @RequestBody SavedSearchRequest request) {
        return searches.create(CurrentUser.require().id(), request);
    }

    @GetMapping("/saved-searches/{id}")
    SavedSearchView savedSearch(@PathVariable UUID id) {
        return searches.get(CurrentUser.require().id(), id);
    }

    @PutMapping("/saved-searches/{id}")
    SavedSearchView replaceSavedSearch(@PathVariable UUID id, @Valid @RequestBody SavedSearchRequest request) {
        return searches.replace(CurrentUser.require().id(), id, request);
    }

    @DeleteMapping("/saved-searches/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteSavedSearch(@PathVariable UUID id) {
        searches.delete(CurrentUser.require().id(), id);
    }

    /** Public. Says what the unsubscribe link in an email would switch off; changes nothing. */
    @GetMapping("/notifications/unsubscribe/{token}")
    UnsubscribeInfo describeUnsubscribe(@PathVariable String token) {
        return unsubscribe.describe(token);
    }

    /**
     * Public. Switches it off. This is the RFC 8058 one-click endpoint the mail client posts to
     * ({@code List-Unsubscribe=One-Click}, which is not read) and the call behind the confirmation page. Idempotent.
     */
    @PostMapping("/notifications/unsubscribe/{token}")
    UnsubscribeInfo performUnsubscribe(@PathVariable String token) {
        return unsubscribe.apply(token);
    }
}
