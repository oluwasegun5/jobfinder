package com.jobfinder.core.activation;

import java.time.Instant;
import java.util.UUID;

/**
 * Milestone M3: the user's profile and preferences are complete (the preferences row was created). Published by profile
 * once, when the row is first saved.
 */
public record OnboardingCompleted(UUID userId, Instant at) {
}
