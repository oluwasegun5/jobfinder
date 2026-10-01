-- ShedLock's lock table: one row per lock name, so a scheduled unit of work (here: one ingestion
-- run per source) executes on a single instance at a time. Standard ShedLock schema.
CREATE TABLE shedlock (
    name       VARCHAR(64)  NOT NULL PRIMARY KEY,
    lock_until TIMESTAMP(3) NOT NULL,
    locked_at  TIMESTAMP(3) NOT NULL,
    locked_by  VARCHAR(255) NOT NULL
);
