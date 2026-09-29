package com.jobfinder.core.identity;

import java.util.UUID;

/** The caller behind a request, as resolved from a verified access token. */
public record AuthenticatedUser(UUID id, Role role) {
}
