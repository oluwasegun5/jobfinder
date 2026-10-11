package com.jobfinder.core.billing.internal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.jobfinder.core.activation.IdentityDigester;
import com.jobfinder.core.billing.internal.TrialAccountStore.State;
import com.jobfinder.core.billing.internal.TrialAccountStore.TrialAccount;
import com.jobfinder.core.billing.internal.TrialClaimStore.Kind;

import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;

/**
 * Seeds the claims of the accounts that existed before the trial (verified before the cutoff V36 recorded), so that
 * deleting one and signing up again cannot obtain a trial either (docs/adr/0042, section 1). Their email and Google
 * account are claimed with every milestone bit and they get a LEGACY row (no trial; they keep their balance).
 *
 * <p>Flyway cannot see the peppers, so this runs in the application: once at startup, under a ShedLock lock, in
 * batches, each user in its own transaction. It is idempotent (users who already have a trial row or a claim are skipped)
 * and resumable; it marks {@code trial_seed.completed_at} when nothing is left. Reads the account email and Google
 * subject from identity's tables by SQL, as billing's other account queries do; nothing raw is stored.
 */
@Component
class TrialSeedJob {

    static final String LOCK = "billing:trial-seed";
    private static final Logger log = LoggerFactory.getLogger(TrialSeedJob.class);

    private record Account(UUID id, String email, String googleSubject) {
    }

    private final JdbcClient jdbc;
    private final IdentityDigester digester;
    private final TrialClaimStore claims;
    private final TrialAccountStore accounts;
    private final TrialProperties properties;
    private final LockingTaskExecutor locks;
    private final TransactionTemplate tx;
    private final Clock clock;

    TrialSeedJob(JdbcClient jdbc, IdentityDigester digester, TrialClaimStore claims, TrialAccountStore accounts,
            TrialProperties properties, LockingTaskExecutor locks, PlatformTransactionManager transactions,
            Clock clock) {
        this.jdbc = jdbc;
        this.digester = digester;
        this.claims = claims;
        this.accounts = accounts;
        this.properties = properties;
        this.locks = locks;
        this.tx = new TransactionTemplate(transactions);
        this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    void onStartup() {
        if (!properties.seed().enabled()) {
            return;
        }
        try {
            locks.executeWithLock((LockingTaskExecutor.TaskWithResult<Integer>) this::run,
                    new LockConfiguration(Instant.now(), LOCK, Duration.ofMinutes(30), Duration.ZERO));
        } catch (Throwable e) {
            log.error("Seeding the trial claims of existing accounts failed; it is retried on the next start", e);
        }
    }

    /** Seeds every account still missing; @return how many were seeded by this call */
    int run() {
        boolean done = jdbc.sql("select completed_at is not null from trial_seed where id = 1").query(Boolean.class)
                .optional().orElse(true);
        if (done) {
            return 0;
        }
        int seeded = 0;
        UUID after = null;
        while (true) {
            List<Account> batch = batch(after);
            if (batch.isEmpty()) {
                break;
            }
            for (Account account : batch) {
                Boolean wrote = tx.execute(status -> seed(account));
                if (Boolean.TRUE.equals(wrote)) {
                    seeded++;
                }
            }
            after = batch.get(batch.size() - 1).id();
        }
        jdbc.sql("update trial_seed set completed_at = :now where id = 1")
                .param("now", java.time.OffsetDateTime.ofInstant(clock.instant(), java.time.ZoneOffset.UTC)).update();
        log.info("Trial seed: {} existing accounts claimed", seeded);
        return seeded;
    }

    private List<Account> batch(UUID after) {
        return jdbc.sql("""
                select u.id, u.email,
                       (select o.provider_user_id from oauth_accounts o
                         where o.user_id = u.id and o.provider = 'GOOGLE' limit 1) as google_subject
                  from users u, trial_seed s
                 where s.id = 1 and u.email_verified_at is not null and u.email_verified_at <= s.cutoff
                   and u.deleted_at is null
                   and (cast(:after as uuid) is null or u.id > cast(:after as uuid))
                   and not exists (select 1 from trial_accounts t where t.user_id = u.id)
                   and not exists (select 1 from trial_identity_claims c where c.user_id = u.id)
                 order by u.id limit :limit
                """).param("after", after == null ? null : after.toString(), java.sql.Types.VARCHAR)
                .param("limit", properties.seed().batchSize())
                .query((rs, n) -> new Account(rs.getObject("id", UUID.class), rs.getString("email"),
                        rs.getString("google_subject")))
                .list();
    }

    private boolean seed(Account account) {
        Instant now = clock.instant();
        Instant expires = now.plus(properties.claimRetention());
        var email = digester.email(account.email());
        email.ifPresent(d -> {
            claims.lock(d.digests());
            claims.upsert(Kind.EMAIL, d.current(), account.id(), TrialMilestone.ALL, now, expires);
        });
        if (account.googleSubject() != null) {
            var sub = digester.googleSubject(account.googleSubject());
            claims.lock(sub.digests());
            claims.upsert(Kind.GOOGLE_SUB, sub.current(), account.id(), TrialMilestone.ALL, now, expires);
        }
        accounts.save(new TrialAccount(account.id(), State.LEGACY, TrialMilestone.ALL, 0, null, null, null), now);
        return true;
    }
}
