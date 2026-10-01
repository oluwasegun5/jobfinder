package com.jobfinder.core.matching;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.jobfinder.core.jobs.JobMatchSource;
import com.jobfinder.core.jobs.JobSelection;
import com.jobfinder.core.jobs.RecalledJob;

/**
 * Stage 1, the SQL filters, against real Postgres: each preference on its own, how a job with no value for the field
 * is treated (it passes), and what never passes whatever the preferences (hidden, expired, not embedded). The recall
 * order and limit are checked here too, since the filters run inside the vector scan.
 */
class Stage1FilterTests extends MatchingTestSupport {

    @Autowired
    private JobMatchSource source;

    private UUID user;

    private static float[] query() {
        double[] d = unit(0);
        float[] f = new float[d.length];
        for (int i = 0; i < d.length; i++) {
            f[i] = (float) d[i];
        }
        return f;
    }

    private static JobSelection none(UUID user) {
        return new JobSelection(user, List.of(), List.of(), List.of(), null, null, List.of(), List.of(), List.of());
    }

    private List<UUID> recalled(JobSelection selection) {
        return source.recall("voyage-4", query(), selection, 100).stream().map(RecalledJob::jobId).toList();
    }

    private JobSpecs jobs() {
        return new JobSpecs();
    }

    /** Collects ids by a label so assertions read as names. */
    private final class JobSpecs {
        private final List<UUID> ids = new ArrayList<>();
        private final List<String> names = new ArrayList<>();

        UUID add(String name, JobSpec spec) {
            UUID id = insert(spec.title(name).embedding(unit(ids.size())));
            ids.add(id);
            names.add(name);
            return id;
        }

        List<String> names(List<UUID> found) {
            return found.stream().map(id -> names.get(ids.indexOf(id))).toList();
        }
    }

    private UUID user() {
        if (user == null) {
            user = newUser();
        }
        return user;
    }

    @Test
    void withNoFiltersEveryActiveEmbeddedJobPassesNearestFirst() {
        JobSpecs j = jobs();
        j.add("a", spec());
        j.add("b", spec());
        j.add("c", spec());

        assertThat(j.names(recalled(none(user())))).containsExactly("a", "b", "c");
    }

    @Test
    void theRecallIsLimitedAndNearestFirstWithItsSimilarity() {
        JobSpecs j = jobs();
        j.add("near", spec());
        j.add("mid", spec());
        j.add("far", spec());

        List<RecalledJob> found = source.recall("voyage-4", query(), none(user()), 2);

        assertThat(found).hasSize(2);
        assertThat(j.names(found.stream().map(RecalledJob::jobId).toList())).containsExactly("near", "mid");
        assertThat(found.get(0).similarity()).isCloseTo(1.0, within(1e-6));
        assertThat(found.get(1).similarity()).isCloseTo(Math.cos(Math.toRadians(1)), within(1e-6));
    }

    @Test
    void workModesKeepTheChosenModesAndJobsThatDoNotSay() {
        JobSpecs j = jobs();
        j.add("remote", spec().workMode("REMOTE"));
        j.add("hybrid", spec().workMode("HYBRID"));
        j.add("onsite", spec().workMode("ONSITE"));
        j.add("unstated", spec());
        JobSelection s = new JobSelection(user(), List.of("REMOTE", "HYBRID"), List.of(), List.of(), null, null,
                List.of(), List.of(), List.of());

        assertThat(j.names(recalled(s))).containsExactly("remote", "hybrid", "unstated");
    }

    @Test
    void locationsMatchACityACountryOrAPhraseInTheRawLocationAndRemoteJobsAlwaysPass() {
        JobSpecs j = jobs();
        j.add("lagos", spec().city("Lagos").country("NG").workMode("ONSITE"));
        j.add("abuja-raw", spec().workMode("ONSITE").description("x"));
        jdbc.update("update jobs set location_raw = 'Abuja, Nigeria', city = null, country = null where id = ?",
                j.ids.get(1));
        j.add("berlin", spec().city("Berlin").country("DE").workMode("ONSITE"));
        j.add("remote-berlin", spec().city("Berlin").country("DE").workMode("REMOTE"));
        j.add("nowhere", spec());
        JobSelection byCity = new JobSelection(user(), List.of(), List.of("lagos"), List.of(), null, null, List.of(),
                List.of(), List.of());
        JobSelection byCountry = new JobSelection(user(), List.of(), List.of("nigeria"), List.of("NG"), null, null,
                List.of(), List.of(), List.of());
        JobSelection byPhrase = new JobSelection(user(), List.of(), List.of("abuja"), List.of(), null, null,
                List.of(), List.of(), List.of());

        assertThat(j.names(recalled(byCity))).containsExactly("lagos", "remote-berlin", "nowhere");
        assertThat(j.names(recalled(byCountry))).containsExactly("lagos", "abuja-raw", "remote-berlin", "nowhere");
        assertThat(j.names(recalled(byPhrase))).containsExactly("abuja-raw", "remote-berlin", "nowhere");
    }

    @Test
    void theSalaryFloorOnlyRulesOutAStatedYearlySalaryInTheSameCurrency() {
        JobSpecs j = jobs();
        j.add("high", spec().salary("90000", "120000", "USD", "YEAR"));
        j.add("low", spec().salary("30000", "50000", "USD", "YEAR"));
        j.add("monthly-high", spec().salary("9000", "12000", "USD", "MONTH"));
        j.add("monthly-low", spec().salary("2000", "3000", "USD", "MONTH"));
        j.add("other-currency", spec().salary("100", "200", "EUR", "YEAR"));
        j.add("no-period", spec().salary("100", "200", "USD", null));
        j.add("no-salary", spec());
        JobSelection s = new JobSelection(user(), List.of(), List.of(), List.of(), 80000, "USD", List.of(), List.of(),
                List.of());

        assertThat(j.names(recalled(s))).containsExactly("high", "monthly-high", "other-currency", "no-period",
                "no-salary");
    }

    @Test
    void aSalaryFloorWithoutACurrencyFiltersNothing() {
        JobSpecs j = jobs();
        j.add("low", spec().salary("30000", "50000", "USD", "YEAR"));
        JobSelection s = new JobSelection(user(), List.of(), List.of(), List.of(), null, null, List.of(), List.of(),
                List.of());

        assertThat(recalled(s)).hasSize(1);
    }

    @Test
    void seniorityKeepsTheBandAndJobsThatDoNotSay() {
        JobSpecs j = jobs();
        j.add("junior", spec().seniority("JUNIOR"));
        j.add("mid", spec().seniority("MID"));
        j.add("senior", spec().seniority("SENIOR"));
        j.add("lead", spec().seniority("LEAD"));
        j.add("unstated", spec());
        JobSelection s = new JobSelection(user(), List.of(), List.of(), List.of(), null, null,
                List.of("MID", "SENIOR", "LEAD"), List.of(), List.of());

        assertThat(j.names(recalled(s))).containsExactly("mid", "senior", "lead", "unstated");
    }

    @Test
    void excludedCompaniesMatchTheNameCaseInsensitively() {
        JobSpecs j = jobs();
        j.add("keep", spec().company("Good Co"));
        j.add("drop", spec().company("Evil Corp"));
        JobSelection s = new JobSelection(user(), List.of(), List.of(), List.of(), null, null, List.of(),
                List.of("evil corp"), List.of());

        assertThat(j.names(recalled(s))).containsExactly("keep");
    }

    @Test
    void excludedIndustriesDropOnlyCompaniesKnownToBeInThem() {
        JobSpecs j = jobs();
        j.add("gambling", spec().company("Bet Co"));
        j.add("health", spec().company("Care Co"));
        j.add("unknown", spec().company("Mystery Co"));
        jdbc.update("update companies set industry = 'Gambling' where name = 'Bet Co'");
        jdbc.update("update companies set industry = 'Health' where name = 'Care Co'");
        JobSelection s = new JobSelection(user(), List.of(), List.of(), List.of(), null, null, List.of(), List.of(),
                List.of("gambling"));

        assertThat(j.names(recalled(s))).containsExactly("health", "unknown");
    }

    @Test
    void filtersCombine() {
        JobSpecs j = jobs();
        j.add("match", spec().workMode("REMOTE").seniority("SENIOR").salary("100000", "150000", "USD", "YEAR"));
        j.add("wrong-mode", spec().workMode("ONSITE").seniority("SENIOR"));
        j.add("wrong-seniority", spec().workMode("REMOTE").seniority("INTERN"));
        j.add("too-low", spec().workMode("REMOTE").seniority("SENIOR").salary("1", "2", "USD", "YEAR"));
        JobSelection s = new JobSelection(user(), List.of("REMOTE"), List.of(), List.of(), 90000, "USD",
                List.of("SENIOR"), List.of(), List.of());

        assertThat(j.names(recalled(s))).containsExactly("match");
    }

    @Test
    void hiddenExpiredAndUnembeddedJobsNeverPassWhateverThePreferences() {
        JobSpecs j = jobs();
        UUID hidden = j.add("hidden", spec());
        j.add("expired-status", spec().status("EXPIRED"));
        UUID pastExpiry = j.add("past-expires-at", spec());
        j.add("visible", spec());
        UUID unembedded = insert(spec().title("unembedded"));
        jdbc.update("insert into user_job_actions (user_id, job_id, action, created_at) values (?, ?, 'HIDDEN', now())",
                user(), hidden);
        jdbc.update("update jobs set expires_at = ? where id = ?", at(Instant.now().minus(1, ChronoUnit.DAYS)),
                pastExpiry);

        List<UUID> found = recalled(none(user()));

        assertThat(j.names(found)).containsExactly("visible");
        assertThat(found).doesNotContain(unembedded);
    }

    @Test
    void anotherUsersHiddenJobStillShowsForThisUser() {
        JobSpecs j = jobs();
        UUID id = j.add("shared", spec());
        jdbc.update("insert into user_job_actions (user_id, job_id, action, created_at) values (?, ?, 'HIDDEN', now())",
                newUser(), id);

        assertThat(recalled(none(user()))).containsExactly(id);
    }

    @Test
    void aJobEmbeddedByAnotherModelIsNotComparable() {
        JobSpecs j = jobs();
        UUID id = j.add("other-model", spec());
        jdbc.update("update jobs set embedding_model = 'some-other-model' where id = ?", id);

        assertThat(recalled(none(user()))).isEmpty();
    }
}
