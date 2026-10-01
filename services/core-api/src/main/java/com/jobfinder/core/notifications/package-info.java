/**
 * Saved searches, notification settings, and the emails built from them: the daily and weekly digests, instant
 * alerts for strong matches and for saved searches, and the signed one-click unsubscribe links in every one of them.
 * It owns {@code saved_searches}, {@code notification_preferences} and {@code notification_log}, reads other modules
 * through their public APIs only (jobs, feed, ingestion, identity, matching's {@code MatchesRefreshed} event), and has no
 * public API of its own. See docs/adr/0028-notifications.md.
 */
package com.jobfinder.core.notifications;
