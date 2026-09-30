package com.jobfinder.core.profile.internal;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.jobfinder.core.identity.CurrentUser;
import com.jobfinder.core.profile.internal.ProfileDtos.PreferencesRequest;
import com.jobfinder.core.profile.internal.ProfileDtos.PreferencesResponse;
import com.jobfinder.core.profile.internal.ProfileDtos.ProfileRequest;
import com.jobfinder.core.profile.internal.ProfileDtos.ProfileResponse;

import jakarta.validation.Valid;

/** The caller's own profile and job-search preferences. The user comes from the access token, never the request. */
@RestController
class ProfileController {

    private final ProfileService profiles;
    private final PreferencesService preferences;

    ProfileController(ProfileService profiles, PreferencesService preferences) {
        this.profiles = profiles;
        this.preferences = preferences;
    }

    @GetMapping("/profile")
    ProfileResponse profile() {
        return profiles.get(CurrentUser.require().id());
    }

    /** Replaces the profile (fields left out are cleared). */
    @PutMapping("/profile")
    ProfileResponse saveProfile(@Valid @RequestBody ProfileRequest request) {
        return profiles.save(CurrentUser.require().id(), request);
    }

    @GetMapping("/preferences")
    PreferencesResponse preferences() {
        return preferences.get(CurrentUser.require().id());
    }

    /** Replaces the preferences. The first save also completes onboarding. */
    @PutMapping("/preferences")
    PreferencesResponse savePreferences(@Valid @RequestBody PreferencesRequest request) {
        return preferences.save(CurrentUser.require().id(), request);
    }
}
