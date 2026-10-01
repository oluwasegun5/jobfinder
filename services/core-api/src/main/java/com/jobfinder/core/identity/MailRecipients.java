package com.jobfinder.core.identity;

import java.util.Optional;
import java.util.UUID;

/**
 * Who may be sent optional mail (digests, alerts) and where. Transactional mail (verification, password reset) does not
 * go through here: it is sent to an address someone just typed and is never affected by notification settings.
 */
public interface MailRecipients {

    /**
     * The address of a user who can receive optional mail: an account that exists, is active (not disabled), is not
     * deleted and has verified its email address. Empty for anyone else, so callers cannot tell the cases apart.
     */
    Optional<MailRecipient> forUser(UUID userId);
}
