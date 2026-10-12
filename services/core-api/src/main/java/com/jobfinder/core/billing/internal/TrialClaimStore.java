package com.jobfinder.core.billing.internal;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.TreeSet;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.jobfinder.core.activation.IdentityDigest;
import com.jobfinder.core.activation.IdentityNormaliser;

/**
 * {@code trial_identity_claims}: keyed digests that say "this email / Google account / CV file / CV email / CV phone
 * already took part in a trial". Rows outlive the account (unlinked, never deleted with it) and are purged
 * {@code app.billing.trial.claim-retention} after they were last seen. Only {@link TrialGate},
 * {@link TrialSeedJob}, the deletion handler and the retention task write here.
 */
@Component
class TrialClaimStore {

    /** What a claim is about; the value type (EMAIL, PHONE, ...) is in the digest, the kind says where it came from. */
    enum Kind {
        EMAIL, GOOGLE_SUB, CV_FILE, CV_EMAIL, CV_PHONE;

        boolean fromCv() {
            return this == CV_FILE || this == CV_EMAIL || this == CV_PHONE;
        }
    }

    record Claim(UUID id, Kind kind, byte[] digest, short keyVersion, UUID userId, int claimedMilestones,
            int claimantCount) {

        String hex() {
            return IdentityNormaliser.hex(digest);
        }

        boolean ownedBy(UUID user) {
            return user.equals(userId);
        }
    }

    private final JdbcClient jdbc;

    TrialClaimStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static OffsetDateTime at(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    /**
     * Takes a transaction-scoped advisory lock per digest, in sorted order, so two claims of one identity (under any
     * account, kind or key version) run one after the other and never deadlock.
     */
    void lock(Collection<IdentityDigest> digests) {
        TreeSet<String> sorted = new TreeSet<>();
        digests.forEach(d -> sorted.add(d.hex()));
        for (String hex : sorted) {
            jdbc.sql("select count(*) from (select pg_advisory_xact_lock(hashtextextended(:key, 0))) l")
                    .param("key", "trial:" + hex).query(Long.class).single();
        }
    }

    /** Every claim on any of these digests (all kinds, all key versions), locked for the rest of the transaction. */
    List<Claim> findForUpdate(Collection<IdentityDigest> digests) {
        if (digests.isEmpty()) {
            return List.of();
        }
        List<byte[]> values = digests.stream().map(IdentityDigest::value).toList();
        return jdbc.sql("""
                select id, kind, digest, key_version, user_id, claimed_milestones, claimant_count
                  from trial_identity_claims where digest in (:digests) order by id for update
                """).param("digests", values)
                .query((rs, n) -> new Claim(rs.getObject("id", UUID.class), Kind.valueOf(rs.getString("kind")),
                        rs.getBytes("digest"), rs.getShort("key_version"), rs.getObject("user_id", UUID.class),
                        rs.getInt("claimed_milestones"), rs.getInt("claimant_count")))
                .list();
    }

    /**
     * Marks claims of other accounts as seen again: renews their retention and, for a CV value, counts one more
     * claimant (so a widely shared CV template is recognised as one).
     */
    void seen(Collection<Claim> claims, Instant now, Instant expiresAt) {
        for (Claim c : claims) {
            jdbc.sql("""
                    update trial_identity_claims
                       set last_seen_at = greatest(last_seen_at, :now), expires_at = greatest(expires_at, :expires),
                           claimant_count = claimant_count + :more
                     where id = :id
                    """).param("now", at(now)).param("expires", at(expiresAt))
                    .param("more", c.kind().fromCv() ? 1 : 0).param("id", c.id()).update();
        }
    }

    /**
     * Writes (or adds the milestone to) the claim of {@code digest} under its key version for {@code userId}. A claim
     * that already exists keeps its first owner and its claimant count.
     */
    void upsert(Kind kind, IdentityDigest digest, UUID userId, int milestones, Instant now, Instant expiresAt) {
        jdbc.sql("""
                insert into trial_identity_claims (kind, digest, key_version, user_id, claimed_milestones,
                                                   first_claimed_at, last_seen_at, expires_at)
                values (:kind, :digest, :version, :user, :milestones, :now, :now, :expires)
                on conflict (kind, digest, key_version) do update
                   set claimed_milestones = trial_identity_claims.claimed_milestones | excluded.claimed_milestones,
                       last_seen_at = greatest(trial_identity_claims.last_seen_at, excluded.last_seen_at),
                       expires_at = greatest(trial_identity_claims.expires_at, excluded.expires_at)
                """)
                .param("kind", kind.name()).param("digest", digest.value()).param("version", digest.keyVersion())
                .param("user", userId).param("milestones", milestones).param("now", at(now))
                .param("expires", at(expiresAt)).update();
    }

    /** Adds {@code bit} to every claim the user owns and renews them. */
    void markOwn(UUID userId, int bit, Instant now, Instant expiresAt) {
        jdbc.sql("""
                update trial_identity_claims
                   set claimed_milestones = claimed_milestones | :bit, last_seen_at = greatest(last_seen_at, :now),
                       expires_at = greatest(expires_at, :expires)
                 where user_id = :user
                """).param("bit", bit).param("now", at(now)).param("expires", at(expiresAt)).param("user", userId)
                .update();
    }

    /** Whether the user owns any claim. */
    boolean owns(UUID userId) {
        return jdbc.sql("select exists (select 1 from trial_identity_claims where user_id = :user)")
                .param("user", userId).query(Boolean.class).single();
    }

    /** Account deletion: the claims stay, without the link to the person (docs/adr/0042, section 3). */
    int unlink(UUID userId) {
        return jdbc.sql("update trial_identity_claims set user_id = null where user_id = :user").param("user", userId)
                .update();
    }

    /** Deletes the claims past their retention. */
    int purgeExpired(Instant now) {
        return jdbc.sql("delete from trial_identity_claims where expires_at < :now").param("now", at(now)).update();
    }
}
