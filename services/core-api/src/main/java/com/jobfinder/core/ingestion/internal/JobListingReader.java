package com.jobfinder.core.ingestion.internal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.jobfinder.core.ingestion.JobListing;
import com.jobfinder.core.ingestion.JobListingService;
import com.jobfinder.core.ingestion.SourceAttribution;
import com.jobfinder.core.ingestion.SourceKind;

/** Reads {@code job_sources} joined to {@code sources}: where each job is listed and what must be credited. */
@Service
class JobListingReader implements JobListingService {

    private final JdbcClient jdbc;

    JobListingReader(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<UUID, List<JobListing>> listingsOf(Collection<UUID> jobIds) {
        Map<UUID, List<JobListing>> result = new LinkedHashMap<>();
        if (jobIds == null || jobIds.isEmpty()) {
            return result;
        }
        jdbc.sql("""
                select js.job_id, s.code, s.kind, js.url, s.attribution_name, s.attribution_text,
                       s.attribution_url, s.attribution_notes
                  from job_sources js join sources s on s.id = js.source_id
                 where js.job_id in (:jobIds)
                 order by js.created_at, js.id
                """)
                .param("jobIds", List.copyOf(jobIds))
                .query((rs, row) -> {
                    String name = rs.getString("attribution_name");
                    SourceAttribution attribution = name == null ? null
                            : new SourceAttribution(name, rs.getString("attribution_text"),
                                    rs.getString("attribution_url"), rs.getString("attribution_notes"));
                    result.computeIfAbsent(rs.getObject("job_id", UUID.class), id -> new ArrayList<>())
                            .add(new JobListing(rs.getString("code"), SourceKind.valueOf(rs.getString("kind")),
                                    rs.getString("url"), attribution));
                    return null;
                })
                .list();
        return result;
    }
}
