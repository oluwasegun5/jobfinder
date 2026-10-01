package com.jobfinder.core.notifications.internal;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.jobfinder.core.notifications.internal.NotificationDtos.DigestFrequency;
import com.jobfinder.core.notifications.internal.NotificationDtos.NotificationPreferencesRequest;
import com.jobfinder.core.notifications.internal.NotificationDtos.NotificationPreferencesView;
import com.jobfinder.core.shared.ApiException;

/** The caller's notification settings ({@code notification_preferences}, V24). Every method takes the user's id. */
@Service
class NotificationPreferencesService {

    private final JdbcClient jdbc;
    private final Clock clock;

    NotificationPreferencesService(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** The user's settings, or the defaults when they never saved any. */
    @Transactional(readOnly = true)
    Prefs get(UUID userId) {
        return find(userId).orElseGet(() -> Prefs.defaults(userId));
    }

    @Transactional(readOnly = true)
    NotificationPreferencesView view(UUID userId) {
        return view(get(userId));
    }

    /**
     * Replaces the settings. Turning digests on clears an earlier digest or all-mail unsubscribe, turning instant alerts
     * on clears an all-mail unsubscribe: the user is asking, signed in, for what they once switched off.
     */
    @Transactional
    NotificationPreferencesView put(UUID userId, NotificationPreferencesRequest request) {
        String timezone = timezone(request.timezone());
        Instant now = Instant.ofEpochMilli(clock.millis());
        jdbc.sql("""
                insert into notification_preferences (user_id, email_enabled, digest_enabled, digest_frequency,
                       digest_hour, digest_weekday, timezone, instant_enabled, instant_threshold, created_at,
                       updated_at)
                values (:user, :email, :digest, :frequency, :hour, :weekday, :timezone, :instant, :threshold, :now,
                        :now)
                on conflict (user_id) do update set
                       email_enabled = excluded.email_enabled, digest_enabled = excluded.digest_enabled,
                       digest_frequency = excluded.digest_frequency, digest_hour = excluded.digest_hour,
                       digest_weekday = excluded.digest_weekday, timezone = excluded.timezone,
                       instant_enabled = excluded.instant_enabled, instant_threshold = excluded.instant_threshold,
                       digests_unsubscribed_at = case when excluded.digest_enabled then null
                                                      else notification_preferences.digests_unsubscribed_at end,
                       marketing_unsubscribed_at = case when excluded.digest_enabled or excluded.instant_enabled
                                                        then null
                                                        else notification_preferences.marketing_unsubscribed_at end,
                       updated_at = excluded.updated_at
                """)
                .param("user", userId).param("email", request.emailEnabled()).param("digest", request.digestEnabled())
                .param("frequency", request.digestFrequency().name()).param("hour", request.digestHour())
                .param("weekday", request.digestWeekday()).param("timezone", timezone)
                .param("instant", request.instantEnabled()).param("threshold", request.instantThreshold())
                .param("now", utc(now)).update();
        return view(userId);
    }

    /** Switches off a kind of mail for a user who followed an unsubscribe link; creates the row when there is none. */
    void suppress(UUID userId, boolean digests, boolean instant, boolean everything) {
        Instant now = Instant.ofEpochMilli(clock.millis());
        jdbc.sql("""
                insert into notification_preferences (user_id, digest_enabled, instant_enabled,
                       digests_unsubscribed_at, marketing_unsubscribed_at, created_at, updated_at)
                values (:user, false, false, :digests, :all, :now, :now)
                on conflict (user_id) do update set
                       digest_enabled = case when :turnOffDigests then false else notification_preferences.digest_enabled end,
                       instant_enabled = case when :turnOffInstant then false else notification_preferences.instant_enabled end,
                       digests_unsubscribed_at = coalesce(notification_preferences.digests_unsubscribed_at, :digests),
                       marketing_unsubscribed_at = coalesce(notification_preferences.marketing_unsubscribed_at, :all),
                       updated_at = :now
                """)
                .param("user", userId).param("now", utc(now))
                .param("digests", digests || everything ? utc(now) : null)
                .param("all", everything ? utc(now) : null)
                .param("turnOffDigests", digests || everything)
                .param("turnOffInstant", instant || everything)
                .update();
    }

    private Optional<Prefs> find(UUID userId) {
        return jdbc.sql("""
                select user_id, email_enabled, digest_enabled, digest_frequency, digest_hour, digest_weekday, timezone,
                       instant_enabled, instant_threshold, digests_unsubscribed_at, marketing_unsubscribed_at
                  from notification_preferences where user_id = :user
                """)
                .param("user", userId)
                .query((rs, row) -> new Prefs(rs.getObject("user_id", UUID.class), rs.getBoolean("email_enabled"),
                        rs.getBoolean("digest_enabled"), DigestFrequency.valueOf(rs.getString("digest_frequency")),
                        rs.getInt("digest_hour"), rs.getInt("digest_weekday"), rs.getString("timezone"),
                        rs.getBoolean("instant_enabled"), rs.getInt("instant_threshold"),
                        instant(rs.getObject("digests_unsubscribed_at", OffsetDateTime.class)),
                        instant(rs.getObject("marketing_unsubscribed_at", OffsetDateTime.class))))
                .optional();
    }

    /** Every user id with notification settings or a saved search that sends on a schedule, after {@code afterId}. */
    List<UUID> digestCandidates(UUID afterId, int limit) {
        return jdbc.sql("""
                select user_id from (
                    select user_id from notification_preferences where digest_enabled
                    union
                    select user_id from saved_searches where frequency in ('DAILY', 'WEEKLY')
                ) t where user_id > :after order by user_id limit :limit
                """)
                .param("after", afterId).param("limit", limit).query(UUID.class).list();
    }

    private static NotificationPreferencesView view(Prefs p) {
        return new NotificationPreferencesView(p.emailEnabled(), p.digestEnabled(), p.digestFrequency(), p.digestHour(),
                p.digestWeekday(), p.timezone(), p.instantEnabled(), p.instantThreshold(),
                p.digestsUnsubscribedAt() != null || p.marketingUnsubscribedAt() != null,
                p.marketingUnsubscribedAt() != null);
    }

    /** An IANA region id (Africa/Lagos), or null for UTC. Offsets and abbreviations are refused: they have no DST. */
    private static String timezone(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String zone = value.strip();
        if (!ZoneId.getAvailableZoneIds().contains(zone) || !zone.contains("/") && !zone.equals("UTC")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_timezone",
                    "The time zone must be an IANA region such as Africa/Lagos or Europe/London.");
        }
        return zone;
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
