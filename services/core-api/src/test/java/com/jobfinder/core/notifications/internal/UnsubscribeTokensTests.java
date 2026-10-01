package com.jobfinder.core.notifications.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.jobfinder.core.notifications.internal.NotificationDtos.UnsubscribeScope;

/** Signing, verifying and expiring unsubscribe tokens (no Spring, no database). */
class UnsubscribeTokensTests {

    private static final String SECRET = "unit-test-unsubscribe-secret-0123456789";
    private static final Instant NOW = Instant.parse("2026-10-01T10:00:00Z");

    static NotificationProperties props(String secret, Duration ttl) {
        return new NotificationProperties("http://localhost:3000", "", "no-reply@example.test", secret, 20, ttl,
                Duration.ofMinutes(2), new NotificationProperties.Digest(true, "0 5 * * * *", "UTC", 10, 70,
                        Duration.ofDays(14), Duration.ofHours(6), 200, Duration.ofMinutes(30)),
                new NotificationProperties.Alerts(true, Duration.ofMinutes(10), 3, 5, Duration.ofHours(36),
                        Duration.ofMinutes(10)),
                new NotificationProperties.Delivery(3, Duration.ofMinutes(15), Duration.ofMinutes(30),
                        Duration.ofDays(180)));
    }

    private static UnsubscribeTokens tokens(Instant at) {
        return new UnsubscribeTokens(props(SECRET, Duration.ofDays(730)), Clock.fixed(at, ZoneOffset.UTC), "jwt");
    }

    @Test
    void aTokenReturnsWhatWasSigned() {
        UUID user = UUID.randomUUID();
        UUID search = UUID.randomUUID();
        UnsubscribeTokens t = tokens(NOW);

        assertThat(t.verify(t.issue(user, UnsubscribeScope.DIGESTS, null))).get().satisfies(c -> {
            assertThat(c.userId()).isEqualTo(user);
            assertThat(c.scope()).isEqualTo(UnsubscribeScope.DIGESTS);
            assertThat(c.savedSearchId()).isNull();
            assertThat(c.expires()).isEqualTo(NOW.plus(Duration.ofDays(730)));
        });
        assertThat(t.verify(t.issue(user, UnsubscribeScope.SAVED_SEARCH, search))).get()
                .satisfies(c -> assertThat(c.savedSearchId()).isEqualTo(search));
    }

    @Test
    void tokensAreUrlSafe() {
        String token = tokens(NOW).issue(UUID.randomUUID(), UnsubscribeScope.MARKETING, null);
        assertThat(token).matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+");
    }

    @Test
    void anExpiredTokenIsRefusedAndOneJustBeforeExpiryIsNot() {
        UUID user = UUID.randomUUID();
        String token = tokens(NOW).issue(user, UnsubscribeScope.DIGESTS, null);
        Instant expiry = NOW.plus(Duration.ofDays(730));

        assertThat(tokens(expiry.minusSeconds(1)).verify(token)).isPresent();
        assertThat(tokens(expiry).verify(token)).isEmpty();
        assertThat(tokens(expiry.plus(Duration.ofDays(1))).verify(token)).isEmpty();
    }

    @Test
    void aChangedTokenIsRefused() {
        UnsubscribeTokens t = tokens(NOW);
        UUID user = UUID.randomUUID();
        String token = t.issue(user, UnsubscribeScope.DIGESTS, null);
        String marketing = t.issue(user, UnsubscribeScope.MARKETING, null);
        String mac = token.substring(token.indexOf('.'));

        // another payload under this token's signature, and the signature of another token
        assertThat(t.verify(marketing.substring(0, marketing.indexOf('.')) + mac)).isEmpty();
        assertThat(t.verify(token.substring(0, token.indexOf('.')) + marketing.substring(marketing.indexOf('.'))))
                .isEmpty();
        // a flipped character anywhere
        for (int i = 0; i < token.length(); i += 7) {
            char c = token.charAt(i);
            if (c == '.') {
                continue;
            }
            String flipped = token.substring(0, i) + (c == 'A' ? 'B' : 'A') + token.substring(i + 1);
            assertThat(t.verify(flipped)).as("flipped at " + i).isEmpty();
        }
    }

    @Test
    void aTokenFromAnotherKeyIsRefused() {
        UnsubscribeTokens other = new UnsubscribeTokens(props("another-unsubscribe-secret-0123456789ab",
                Duration.ofDays(730)), Clock.fixed(NOW, ZoneOffset.UTC), "jwt");
        String foreign = other.issue(UUID.randomUUID(), UnsubscribeScope.MARKETING, null);

        assertThat(tokens(NOW).verify(foreign)).isEmpty();
    }

    @Test
    void junkIsRefusedWithoutAnError() {
        UnsubscribeTokens t = tokens(NOW);
        for (String junk : new String[] { null, "", ".", "a", "a.", ".b", "a.b.c", "%%%.%%%", "x".repeat(401) }) {
            assertThat(t.verify(junk)).as(String.valueOf(junk)).isEmpty();
        }
    }

    @Test
    void theKeyComesFromTheJwtSecretWhenNoneIsSetAndSomethingMustBeSet() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        UnsubscribeTokens derived = new UnsubscribeTokens(props("", Duration.ofDays(730)), clock, "a-jwt-secret");
        UUID user = UUID.randomUUID();

        String token = derived.issue(user, UnsubscribeScope.DIGESTS, null);
        assertThat(new UnsubscribeTokens(props("", Duration.ofDays(730)), clock, "a-jwt-secret").verify(token))
                .isPresent();
        assertThat(new UnsubscribeTokens(props("", Duration.ofDays(730)), clock, "a-different-jwt-secret").verify(token))
                .isEmpty();
        assertThatThrownBy(() -> new UnsubscribeTokens(props("", Duration.ofDays(730)), clock, ""))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new UnsubscribeTokens(props("short", Duration.ofDays(730)), clock, "jwt"))
                .isInstanceOf(IllegalStateException.class);
    }
}
