package com.jobfinder.core.ingestion;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Helpers for tests that look at the jobs a run produced rather than at the raw postings. */
public abstract class JobPipelineTestSupport extends IngestionTestSupport {

    /** A posting document: title, company and location, plus any extra fields. */
    protected static RawPosting posting(String externalId, String title, String company, String location,
            Object... extraKeyValues) {
        Map<String, Object> fields = new HashMap<>();
        fields.put("title", title);
        fields.put("company", company);
        fields.put("location", location);
        for (int i = 0; i < extraKeyValues.length; i += 2) {
            fields.put((String) extraKeyValues[i], extraKeyValues[i + 1]);
        }
        return FakeJobSourceAdapter.raw(externalId, fields);
    }

    protected UUID jobIdOf(String sourceCode, String externalId) {
        return jdbc.queryForObject("select job_id from job_sources where source_id = ? and external_id = ?",
                UUID.class, sourceId(sourceCode), externalId);
    }

    protected String statusOf(String sourceCode, String externalId) {
        return jdbc.queryForObject("select status from jobs where id = ?", String.class,
                jobIdOf(sourceCode, externalId));
    }

    protected int missedRuns(String sourceCode, String externalId) {
        return jdbc.queryForObject("select missed_runs from job_sources where source_id = ? and external_id = ?",
                Integer.class, sourceId(sourceCode), externalId);
    }

    protected int linkCount(UUID jobId) {
        return jdbc.queryForObject("select count(*) from job_sources where job_id = ?", Integer.class, jobId);
    }

    protected List<String> jobTitles() {
        return jdbc.queryForList("select title from jobs where id in (select job_id from job_sources "
                + "where source_id in (select id from sources where code like 'FAKE%')) order by title", String.class);
    }

    protected int companyCount(String normalizedName) {
        return jdbc.queryForObject("select count(*) from companies where normalized_name = ?", Integer.class,
                normalizedName);
    }
}
