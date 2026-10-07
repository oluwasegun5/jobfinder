-- ShedLock writes the unlock time as the caller's clock reading, and TIMESTAMP(3) rounds it to the nearest millisecond,
-- which can round up into the future. A lock released and asked for again within that half millisecond was then refused
-- as still held, so a run that nothing was running beside (runNow) came back as "another run holds the lock". Microsecond
-- columns leave a window far smaller than one database round trip. Widening the precision rewrites no rows.
ALTER TABLE shedlock
    ALTER COLUMN lock_until TYPE TIMESTAMP(6),
    ALTER COLUMN locked_at TYPE TIMESTAMP(6);
