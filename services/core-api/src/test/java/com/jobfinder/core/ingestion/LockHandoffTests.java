package com.jobfinder.core.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * A lock that was released can be taken again at once. With millisecond lock columns an unlock time rounded up to the
 * next millisecond kept the lock "held" for a moment, so back-to-back runNow calls sometimes returned empty on a fast
 * machine (about 1 in 2500 hand-offs).
 */
class LockHandoffTests extends IngestionTestSupport {

    @Autowired
    private LockProvider lockProvider;

    @Test
    void theLockColumnsAreFinerThanTheTimeBetweenTwoDatabaseCalls() {
        for (String column : new String[] { "lock_until", "locked_at" }) {
            assertThat(jdbc.queryForObject("select datetime_precision from information_schema.columns "
                    + "where table_name = 'shedlock' and column_name = ?", Integer.class, column))
                    .as(column).isEqualTo(6);
        }
    }

    @Test
    void aReleasedLockCanBeTakenAgainImmediately() {
        int refused = 0;
        for (int i = 0; i < 3000; i++) {
            Optional<SimpleLock> lock = lockProvider.lock(new LockConfiguration(Instant.now(),
                    "handoff-test", Duration.ofMinutes(5), Duration.ZERO));
            if (lock.isEmpty()) {
                refused++;
            } else {
                lock.get().unlock();
            }
        }
        assertThat(refused).as("hand-offs refused although nothing held the lock").isZero();
    }
}
