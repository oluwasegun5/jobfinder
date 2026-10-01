package com.jobfinder.core.profile.internal;

import java.sql.Array;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.jobfinder.core.profile.Candidate;
import com.jobfinder.core.profile.CandidatePreferences;
import com.jobfinder.core.profile.CandidateProfiles;

/** Reads the matching view of a candidate with its own queries; it writes nothing. */
@Component
class CandidateReader implements CandidateProfiles {

    private final JdbcClient jdbc;

    CandidateReader(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Candidate> candidate(UUID userId) {
        record Version(UUID id, String structured, String seniority, Integer years) {
        }
        Optional<Version> version = jdbc.sql("""
                select v.id, v.structured::text as structured, p.seniority, p.years_experience
                  from resumes r
                  join resume_versions v on v.resume_id = r.id
                  left join profiles p on p.user_id = r.user_id
                 where r.user_id = :userId and r.is_primary and v.structured is not null
                 order by v.version_number desc
                 limit 1
                """).param("userId", userId)
                .query((rs, row) -> new Version(rs.getObject("id", UUID.class), rs.getString("structured"),
                        rs.getString("seniority"), (Integer) rs.getObject("years_experience")))
                .optional();
        if (version.isEmpty()) {
            return Optional.empty();
        }
        Optional<CandidatePreferences> preferences = jdbc.sql("""
                select target_titles, locations, work_modes, min_salary, currency, needs_sponsorship,
                       excluded_companies, excluded_industries
                  from preferences where user_id = :userId
                """).param("userId", userId)
                .query((rs, row) -> new CandidatePreferences(texts(rs.getArray("target_titles")),
                        texts(rs.getArray("locations")), texts(rs.getArray("work_modes")),
                        (Integer) rs.getObject("min_salary"),
                        rs.getString("currency") == null ? null : rs.getString("currency").strip(),
                        rs.getBoolean("needs_sponsorship"), texts(rs.getArray("excluded_companies")),
                        texts(rs.getArray("excluded_industries"))))
                .optional();
        Version v = version.get();
        return Optional.of(new Candidate(userId, v.id(), v.structured(), v.seniority(), v.years(),
                preferences.orElse(CandidatePreferences.EMPTY), preferences.isPresent()));
    }

    private static List<String> texts(Array array) throws SQLException {
        return array == null ? List.of() : Arrays.asList((String[]) array.getArray());
    }
}
