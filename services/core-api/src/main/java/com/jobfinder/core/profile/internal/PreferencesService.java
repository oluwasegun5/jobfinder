package com.jobfinder.core.profile.internal;

import java.sql.Array;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Currency;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.jobfinder.core.profile.internal.ProfileDtos.PreferencesRequest;
import com.jobfinder.core.profile.internal.ProfileDtos.PreferencesResponse;
import com.jobfinder.core.profile.internal.ProfileDtos.WorkMode;
import com.jobfinder.core.shared.ApiException;

/**
 * The caller's single preferences row (PLAN.md section 5). Keyed by the user ID from the access token only. A user who
 * has never saved gets empty preferences back rather than a 404. Saving creates the row, which is also what marks
 * onboarding complete (see {@link ProfileService}).
 */
@Service
class PreferencesService {

    private final JdbcClient jdbc;

    PreferencesService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    static boolean exists(JdbcClient jdbc, UUID userId) {
        return jdbc.sql("select 1 from preferences where user_id = :userId").param("userId", userId)
                .query(Integer.class).optional().isPresent();
    }

    PreferencesResponse get(UUID userId) {
        return jdbc.sql("""
                select target_titles, locations, work_modes, min_salary, currency, needs_sponsorship,
                       excluded_companies, excluded_industries, updated_at
                from preferences where user_id = :userId
                """).param("userId", userId)
                .query((rs, row) -> new PreferencesResponse(texts(rs.getArray("target_titles")),
                        texts(rs.getArray("locations")),
                        texts(rs.getArray("work_modes")).stream().map(WorkMode::valueOf).toList(),
                        (Integer) rs.getObject("min_salary"), trimmed(rs.getString("currency")),
                        rs.getBoolean("needs_sponsorship"), texts(rs.getArray("excluded_companies")),
                        texts(rs.getArray("excluded_industries")), rs.getTimestamp("updated_at").toInstant()))
                .optional()
                .orElseGet(() -> new PreferencesResponse(List.of(), List.of(), List.of(), null, null, false,
                        List.of(), List.of(), null));
    }

    PreferencesResponse save(UUID userId, PreferencesRequest request) {
        check(request);
        jdbc.sql("""
                insert into preferences (id, user_id, target_titles, locations, work_modes, min_salary, currency,
                                         needs_sponsorship, excluded_companies, excluded_industries, created_at,
                                         updated_at)
                values (:id, :userId, :targetTitles, :locations, :workModes, :minSalary, :currency,
                        :needsSponsorship, :excludedCompanies, :excludedIndustries, now(), now())
                on conflict (user_id) do update set
                    target_titles = excluded.target_titles, locations = excluded.locations,
                    work_modes = excluded.work_modes, min_salary = excluded.min_salary,
                    currency = excluded.currency, needs_sponsorship = excluded.needs_sponsorship,
                    excluded_companies = excluded.excluded_companies,
                    excluded_industries = excluded.excluded_industries, updated_at = now()
                """)
                .param("id", UUID.randomUUID())
                .param("userId", userId)
                .param("targetTitles", array(request.targetTitles()))
                .param("locations", array(request.locations()))
                .param("workModes", array(request.workModes().stream().map(WorkMode::name).toList()))
                .param("minSalary", request.minSalary(), java.sql.Types.INTEGER)
                .param("currency", request.currency(), java.sql.Types.CHAR)
                .param("needsSponsorship", request.needsSponsorship())
                .param("excludedCompanies", array(request.excludedCompanies()))
                .param("excludedIndustries", array(request.excludedIndustries()))
                .update();
        return get(userId);
    }

    /** Rules that annotations cannot express: a real ISO 4217 code, and a salary floor needs one. */
    private static void check(PreferencesRequest request) {
        if (request.currency() != null) {
            try {
                Currency.getInstance(request.currency());
            } catch (IllegalArgumentException e) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_currency",
                        "Currency must be a valid ISO 4217 code, for example USD.");
            }
        }
        if (request.minSalary() != null && request.currency() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "currency_required",
                    "Choose a currency for your minimum salary.");
        }
    }

    private static String[] array(List<String> values) {
        return values.toArray(String[]::new);
    }

    private static List<String> texts(Array array) throws SQLException {
        return array == null ? List.of() : Arrays.asList((String[]) array.getArray());
    }

    /** The column is CHAR(3); pgjdbc pads nothing for a full code, but strip defensively. */
    private static String trimmed(String value) {
        return value == null ? null : value.strip();
    }
}
