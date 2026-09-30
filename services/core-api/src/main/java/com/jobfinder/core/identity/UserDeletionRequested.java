package com.jobfinder.core.identity;

import java.time.Instant;
import java.util.UUID;

/**
 * Published inside the deletion transaction when a user asks to delete their account.
 * Every module that stores data about a user must listen (synchronously, with a plain
 * {@code @EventListener}) and purge it: a failing handler rolls the whole deletion back, so
 * the account is either erased everywhere or nowhere. Carries no personal data.
 */
public record UserDeletionRequested(UUID userId, Instant requestedAt) {
}
