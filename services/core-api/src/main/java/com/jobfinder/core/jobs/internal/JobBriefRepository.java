package com.jobfinder.core.jobs.internal;

import java.sql.Array;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.jobfinder.core.jobs.JobBriefSource;
import com.jobfinder.core.jobs.JobForBrief;

/** {@link JobBriefSource}: one job joined to its company row. */
@Repository
class JobBriefRepository implements JobBriefSource {

    private final JdbcClient jdbc;

    JobBriefRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<JobForBrief> job(UUID id) {
        return jdbc.sql("""
                select j.id, j.title, c.name as company, c.domain, c.size, c.industry, j.location_raw, j.city,
                       j.country, j.work_mode, j.employment_type, j.seniority, j.salary_min, j.salary_max,
                       j.salary_currency, j.salary_period, j.skills, j.description_text
                  from jobs j join companies c on c.id = j.company_id
                 where j.id = :id
                """).param("id", id).query((rs, row) -> new JobForBrief(rs.getObject("id", UUID.class),
                rs.getString("title"), rs.getString("company"), rs.getString("domain"), rs.getString("size"),
                rs.getString("industry"), rs.getString("location_raw"), rs.getString("city"),
                rs.getString("country"), rs.getString("work_mode"), rs.getString("employment_type"),
                rs.getString("seniority"), rs.getBigDecimal("salary_min"), rs.getBigDecimal("salary_max"),
                rs.getString("salary_currency"), rs.getString("salary_period"), texts(rs.getArray("skills")),
                rs.getString("description_text"))).optional();
    }

    private static List<String> texts(Array array) throws SQLException {
        return array == null ? List.of() : Arrays.asList((String[]) array.getArray());
    }
}
