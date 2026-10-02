package com.jobfinder.core.notifications;

import java.util.List;

/**
 * The content of a {@link UserMail}: plain facts, rendered (and escaped) by the notifications module, so a caller can
 * pass text that came from a user or a job posting without making HTML of it.
 *
 * @param subject      one line
 * @param heading      the title at the top of the message
 * @param lines        paragraphs of plain text, in order
 * @param actionLabel  what the button or link says, or null for none
 * @param actionPath   a path of the web app (for example {@code /applications}) the action opens; null for none
 */
public record RequestedMail(String subject, String heading, List<String> lines, String actionLabel,
        String actionPath) {
}
