package com.jobfinder.core.identity;

import java.util.UUID;

/** A user optional mail may be sent to. Personal data: never log it. */
public record MailRecipient(UUID userId, String email) {
}
