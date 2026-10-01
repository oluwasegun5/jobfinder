/**
 * Job search and the caller's own job state (saved, hidden): the read side of the job tables. Ingestion owns
 * every write to {@code jobs}, {@code companies} and {@code job_sources}; this module only reads them (with its
 * own queries, as ADR 0019 foresaw) and owns {@code user_job_actions}. See docs/adr/0023-job-search.md.
 */
package com.jobfinder.core.jobs;
