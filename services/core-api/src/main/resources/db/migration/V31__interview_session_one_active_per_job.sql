-- P5.x follow-up to P5.2 (docs/adr/0034-mock-interview.md, addendum): starting a mock interview is idempotent per job.
-- At most one ACTIVE session per (user, job). COMPLETED and ABANDONED sessions are not constrained, so a user can run
-- the same interview again once the last one is over. Concurrent starts race on this index; the loser catches the
-- duplicate and returns the winner's session.
--
-- Backward compatible: an index only. V30 is unreleased, but in case any database already holds two ACTIVE sessions for
-- one (user, job), the older ones are marked ABANDONED first (the most recently active one is kept), so the index can
-- be built. ABANDONED is an existing, final status; nothing is deleted.
UPDATE interview_sessions s
   SET status = 'ABANDONED'
 WHERE s.status = 'ACTIVE'
   AND EXISTS (SELECT 1
                 FROM interview_sessions newer
                WHERE newer.user_id = s.user_id
                  AND newer.job_id = s.job_id
                  AND newer.status = 'ACTIVE'
                  AND (newer.last_activity_at, newer.id) > (s.last_activity_at, s.id));

CREATE UNIQUE INDEX uq_interview_sessions_active_job
    ON interview_sessions (user_id, job_id)
    WHERE status = 'ACTIVE';
