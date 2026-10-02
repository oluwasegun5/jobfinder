package com.jobfinder.core.notifications;

import java.util.UUID;

/**
 * Sends a one-off email the user asked for themselves, such as the reminder of an application they set, through the same
 * mail server, sender address and settings as every other email of the platform. It is not for digests or alerts (those
 * stay in this module) and not for transactional mail (verification, password reset): it is for mail another module
 * schedules on the user's behalf.
 *
 * <p>It respects the user's notification settings: nothing is sent to someone who switched email notifications off,
 * and, as for all optional mail, only to an active account with a verified address.
 */
public interface UserMail {

    /** What became of a request to send. */
    enum Result {
        /** The mail server accepted the message. */
        SENT,
        /** The user switched email notifications off in their settings. */
        EMAIL_DISABLED,
        /** The account cannot receive optional mail: gone, disabled or its address not verified. */
        NO_RECIPIENT
    }

    /**
     * @return {@link Result#SENT} when the message was handed to the mail server
     * @throws MailDeliveryException when the user can receive mail but the message could not be built or sent
     */
    Result send(UUID userId, RequestedMail mail);
}
