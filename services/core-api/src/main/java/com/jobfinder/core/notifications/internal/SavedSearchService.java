package com.jobfinder.core.notifications.internal;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.jobfinder.core.jobs.JobSearchCriteria;
import com.jobfinder.core.notifications.internal.NotificationDtos.SavedSearchCriteria;
import com.jobfinder.core.notifications.internal.NotificationDtos.SavedSearchFrequency;
import com.jobfinder.core.notifications.internal.NotificationDtos.SavedSearchList;
import com.jobfinder.core.notifications.internal.NotificationDtos.SavedSearchRequest;
import com.jobfinder.core.notifications.internal.NotificationDtos.SavedSearchView;
import com.jobfinder.core.notifications.internal.NotificationDtos.SearchEmploymentType;
import com.jobfinder.core.notifications.internal.NotificationDtos.SearchSeniority;
import com.jobfinder.core.notifications.internal.NotificationDtos.SearchWorkMode;
import com.jobfinder.core.shared.ApiException;

/**
 * The caller's saved searches ({@code saved_searches}, V24). Every method that serves a request takes the user's id and
 * scopes by it: someone else's search is {@code 404 saved_search_not_found}, the same as one that does not exist.
 */
@Service
class SavedSearchService {

    /** A saved search as the senders read it. */
    record Row(UUID id, UUID userId, String name, JobSearchCriteria criteria, SavedSearchFrequency frequency,
            Instant lastRunAt, Instant createdAt) {
    }

    private static final String COLUMNS = """
            id, user_id, name, q, work_modes, employment_types, seniorities, countries, city, min_salary,
            salary_currency, frequency, last_run_at, created_at""";

    private final JdbcClient jdbc;
    private final NotificationProperties properties;
    private final Clock clock;

    SavedSearchService(JdbcClient jdbc, NotificationProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    SavedSearchList list(UUID userId) {
        return new SavedSearchList(rows("where user_id = :user order by created_at, id", userId).stream()
                .map(SavedSearchService::view).toList());
    }

    @Transactional(readOnly = true)
    SavedSearchView get(UUID userId, UUID id) {
        return view(find(userId, id).orElseThrow(SavedSearchService::notFound));
    }

    @Transactional
    SavedSearchView create(UUID userId, SavedSearchRequest request) {
        JobSearchCriteria criteria = criteria(request.criteria());
        // Serialise concurrent creates of one user, so the limit cannot be passed by two requests at once.
        jdbc.sql("select pg_advisory_xact_lock(hashtextextended(:key, 0))").param("key", "saved-searches:" + userId)
                .query().listOfRows();
        int count = jdbc.sql("select count(*) from saved_searches where user_id = :user").param("user", userId)
                .query(Integer.class).single();
        if (count >= properties.maxSavedSearches()) {
            throw new ApiException(HttpStatus.CONFLICT, "saved_search_limit",
                    "You can keep up to " + properties.maxSavedSearches() + " saved searches. Delete one first.");
        }
        UUID id = UUID.randomUUID();
        Instant now = now();
        jdbc.sql("""
                insert into saved_searches (id, user_id, name, q, work_modes, employment_types, seniorities,
                       countries, city, min_salary, salary_currency, frequency, last_run_at, created_at, updated_at)
                values (:id, :user, :name, :q, cast(:workModes as text[]), cast(:employmentTypes as text[]),
                        cast(:seniorities as text[]), cast(:countries as text[]), :city, :minSalary, :currency,
                        :frequency, :now, :now, :now)
                """)
                .param("id", id).param("user", userId).param("name", name(request.name()))
                .params(criteriaParams(criteria)).param("frequency", request.frequency().name()).param("now", utc(now))
                .update();
        return view(find(userId, id).orElseThrow());
    }

    /**
     * Replaces a saved search. When its criteria change, or it is switched on after being OFF, the watermark moves to
     * now: jobs that appeared while the search was something else are not "new" for it.
     */
    @Transactional
    SavedSearchView replace(UUID userId, UUID id, SavedSearchRequest request) {
        Row current = find(userId, id).orElseThrow(SavedSearchService::notFound);
        JobSearchCriteria criteria = criteria(request.criteria());
        boolean reset = !sameCriteria(current.criteria(), criteria)
                || current.frequency() == SavedSearchFrequency.OFF && request.frequency() != SavedSearchFrequency.OFF;
        Instant now = now();
        jdbc.sql("""
                update saved_searches set name = :name, q = :q, work_modes = cast(:workModes as text[]),
                       employment_types = cast(:employmentTypes as text[]), seniorities = cast(:seniorities as text[]),
                       countries = cast(:countries as text[]), city = :city, min_salary = :minSalary,
                       salary_currency = :currency, frequency = :frequency,
                       last_run_at = case when :reset then :now else last_run_at end, updated_at = :now
                 where id = :id and user_id = :user
                """)
                .param("id", id).param("user", userId).param("name", name(request.name()))
                .params(criteriaParams(criteria)).param("frequency", request.frequency().name())
                .param("reset", reset).param("now", utc(now)).update();
        return view(find(userId, id).orElseThrow());
    }

    @Transactional
    void delete(UUID userId, UUID id) {
        int deleted = jdbc.sql("delete from saved_searches where id = :id and user_id = :user").param("id", id)
                .param("user", userId).update();
        if (deleted == 0) {
            throw notFound();
        }
    }

    // --- for the senders (no request, no ownership check beyond the user id they were given) ---

    /** The user's searches that send on a schedule ({@code frequency}), oldest first. */
    @Transactional(readOnly = true)
    List<Row> scheduled(UUID userId, SavedSearchFrequency frequency) {
        return rows("where user_id = :user and frequency = '" + frequency.name() + "' order by created_at, id", userId);
    }

    /** Searches set to INSTANT, in id order after {@code afterId}. */
    @Transactional(readOnly = true)
    List<Row> instantAfter(UUID afterId, int limit) {
        return jdbc.sql("select " + COLUMNS + " from saved_searches where frequency = 'INSTANT' and id > :after "
                + "order by id limit :limit").param("after", afterId).param("limit", limit)
                .query((rs, row) -> map(rs)).list();
    }

    /** Moves the watermark forward (never back): jobs stored up to {@code until} have been handled. */
    void advance(UUID id, Instant until) {
        jdbc.sql("update saved_searches set last_run_at = greatest(last_run_at, :until) where id = :id")
                .param("id", id).param("until", utc(until)).update();
    }

    /** Switches one search off; false when it does not exist (or is not the user's). */
    boolean switchOff(UUID userId, UUID id) {
        return jdbc.sql("update saved_searches set frequency = 'OFF', updated_at = :now where id = :id and user_id = :user")
                .param("id", id).param("user", userId).param("now", utc(now())).update() > 0;
    }

    /** Switches off every INSTANT search of the user. */
    void switchOffInstant(UUID userId) {
        jdbc.sql("update saved_searches set frequency = 'OFF', updated_at = :now "
                + "where user_id = :user and frequency = 'INSTANT'").param("user", userId).param("now", utc(now()))
                .update();
    }

    Optional<String> nameOf(UUID userId, UUID id) {
        return jdbc.sql("select name from saved_searches where id = :id and user_id = :user").param("id", id)
                .param("user", userId).query(String.class).optional();
    }

    Optional<Row> find(UUID userId, UUID id) {
        return jdbc.sql("select " + COLUMNS + " from saved_searches where id = :id and user_id = :user")
                .param("id", id).param("user", userId).query((rs, row) -> map(rs)).optional();
    }

    // --- mapping and validation ---

    private List<Row> rows(String where, UUID userId) {
        return jdbc.sql("select " + COLUMNS + " from saved_searches " + where).param("user", userId)
                .query((rs, row) -> map(rs)).list();
    }

    private static Row map(java.sql.ResultSet rs) throws SQLException {
        JobSearchCriteria criteria = new JobSearchCriteria(rs.getString("q"), texts(rs.getArray("work_modes")),
                texts(rs.getArray("employment_types")), texts(rs.getArray("seniorities")),
                texts(rs.getArray("countries")), rs.getString("city"), rs.getBigDecimal("min_salary"),
                rs.getString("salary_currency") == null ? null : rs.getString("salary_currency").strip());
        return new Row(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class), rs.getString("name"),
                criteria, SavedSearchFrequency.valueOf(rs.getString("frequency")),
                rs.getObject("last_run_at", OffsetDateTime.class).toInstant(),
                rs.getObject("created_at", OffsetDateTime.class).toInstant());
    }

    static SavedSearchView view(Row r) {
        JobSearchCriteria c = r.criteria();
        SavedSearchCriteria criteria = new SavedSearchCriteria(c.q(), enums(c.workModes(), SearchWorkMode::valueOf),
                enums(c.employmentTypes(), SearchEmploymentType::valueOf),
                enums(c.seniorities(), SearchSeniority::valueOf), c.countries(), c.location(),
                c.minSalary() == null ? null : c.minSalary().stripTrailingZeros(), c.currency());
        return new SavedSearchView(r.id(), r.name(), criteria, r.frequency(), r.lastRunAt(), r.createdAt());
    }

    private static <E> List<E> enums(List<String> values, Function<String, E> parse) {
        return values.stream().map(parse).toList();
    }

    /** Normalised criteria: trimmed text, de-duplicated sorted lists, upper-case codes. Refuses a search with no filter. */
    private static JobSearchCriteria criteria(SavedSearchCriteria c) {
        String q = c.q() == null ? null : c.q().replaceAll("\\s+", " ").strip();
        q = q == null || q.isEmpty() ? null : q;
        String location = c.location() == null || c.location().isBlank() ? null : c.location().strip();
        List<String> workModes = names(c.workMode());
        List<String> types = names(c.employmentType());
        List<String> seniorities = names(c.seniority());
        List<String> countries = c.country() == null ? List.of()
                : c.country().stream().filter(v -> v != null && !v.isBlank()).map(v -> v.strip().toUpperCase(Locale.ROOT))
                        .distinct().sorted().toList();
        String currency = c.salaryCurrency() == null || c.salaryCurrency().isBlank() ? null
                : c.salaryCurrency().strip().toUpperCase(Locale.ROOT);
        BigDecimal minSalary = c.minSalary() == null ? null : c.minSalary().stripTrailingZeros();
        if (minSalary != null && currency == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "salary_currency_required",
                    "A minimum salary needs salaryCurrency: amounts in different currencies are never compared.");
        }
        if (q == null && location == null && workModes.isEmpty() && types.isEmpty() && seniorities.isEmpty()
                && countries.isEmpty() && minSalary == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "empty_search",
                    "A saved search needs a keyword or at least one filter.");
        }
        return new JobSearchCriteria(q, workModes, types, seniorities, countries, location, minSalary,
                minSalary == null ? null : currency);
    }

    private static List<String> names(List<? extends Enum<?>> values) {
        return values == null ? List.of() : values.stream().map(Enum::name).distinct().sorted().toList();
    }

    private static boolean sameCriteria(JobSearchCriteria a, JobSearchCriteria b) {
        return java.util.Objects.equals(a.q(), b.q()) && a.workModes().equals(b.workModes())
                && a.employmentTypes().equals(b.employmentTypes()) && a.seniorities().equals(b.seniorities())
                && a.countries().equals(b.countries()) && java.util.Objects.equals(a.location(), b.location())
                && (a.minSalary() == null ? b.minSalary() == null
                        : b.minSalary() != null && a.minSalary().compareTo(b.minSalary()) == 0)
                && java.util.Objects.equals(a.currency(), b.currency());
    }

    private static java.util.Map<String, Object> criteriaParams(JobSearchCriteria c) {
        java.util.Map<String, Object> params = new java.util.HashMap<>();
        params.put("q", c.q());
        params.put("workModes", array(c.workModes()));
        params.put("employmentTypes", array(c.employmentTypes()));
        params.put("seniorities", array(c.seniorities()));
        params.put("countries", array(c.countries()));
        params.put("city", c.location());
        params.put("minSalary", c.minSalary());
        params.put("currency", c.currency());
        return params;
    }

    /** A Postgres text[] literal, cast on the SQL side. */
    private static String array(List<String> values) {
        StringBuilder out = new StringBuilder("{");
        for (int i = 0; i < values.size(); i++) {
            out.append(i > 0 ? "," : "").append('"').append(values.get(i).replace("\\", "\\\\").replace("\"", "\\\""))
                    .append('"');
        }
        return out.append('}').toString();
    }

    private static List<String> texts(java.sql.Array array) throws SQLException {
        if (array == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object o : (Object[]) array.getArray()) {
            out.add((String) o);
        }
        return out;
    }

    private static String name(String value) {
        return value.replaceAll("\\s+", " ").strip();
    }

    private Instant now() {
        return Instant.ofEpochMilli(clock.millis());
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "saved_search_not_found", "No such saved search.");
    }
}
