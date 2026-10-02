package com.jobfinder.core.jobs.internal;

import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.jobfinder.core.jobs.JobApplyLinks;

/** {@link JobApplyLinks}: a literal, case-insensitive substring search on {@code jobs.apply_url}. */
@Repository
class JobApplyLinkRepository implements JobApplyLinks {

    private final JdbcClient jdbc;

    JobApplyLinkRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<ApplyLink> withApplyUrlContaining(String fragment, int limit) {
        if (fragment == null || fragment.isBlank()) {
            return List.of();
        }
        // LIKE metacharacters in the fragment are escaped: it is a literal, whatever the page's URL looked like.
        String pattern = "%" + fragment.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        return jdbc.sql("""
                select j.id, j.title, c.name as company, j.apply_url
                  from jobs j join companies c on c.id = j.company_id
                 where j.apply_url ilike :pattern escape '\\'
                 order by (j.status = 'ACTIVE') desc, j.created_at desc, j.id
                 limit :limit
                """).param("pattern", pattern).param("limit", limit)
                .query((rs, row) -> new ApplyLink(rs.getObject("id", java.util.UUID.class), rs.getString("title"),
                        rs.getString("company"), rs.getString("apply_url")))
                .list();
    }
}
