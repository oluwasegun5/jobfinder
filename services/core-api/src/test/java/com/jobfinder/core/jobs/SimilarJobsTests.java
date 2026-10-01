package com.jobfinder.core.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;

/** "Similar jobs": nearest neighbours by embedding (cosine), with the search filters and the user's hidden jobs applied. */
class SimilarJobsTests extends JobsTestSupport {

    @Test
    void neighboursComeNearestFirstWithTheirSimilarityAndNeverTheJobItself() throws Exception {
        UUID source = insert(spec().title("Source").embedding(unit(0)));
        insert(spec().title("Close").embedding(unit(10)));
        insert(spec().title("Closer").embedding(unit(5)));
        insert(spec().title("Mid").embedding(unit(45)));
        insert(spec().title("Far").embedding(unit(90)));
        insert(spec().title("Opposite").embedding(unit(180)));
        Session me = newSession();

        ResultActions result = getAs(me, "/jobs/" + source + "/similar");

        assertThat(titles(result)).containsExactly("Closer", "Close", "Mid", "Far", "Opposite");
        List<Double> similarity = JsonPath.read(result.andReturn().getResponse().getContentAsString(),
                "$.items[*].similarity");
        assertThat(similarity.get(0)).isCloseTo(Math.cos(Math.toRadians(5)), within(1e-6));
        assertThat(similarity.get(2)).isCloseTo(Math.cos(Math.toRadians(45)), within(1e-6));
        assertThat(similarity.get(4)).isCloseTo(-1.0, within(1e-6));
    }

    @Test
    void onlyActiveEmbeddedJobsOfTheSameModelCount() throws Exception {
        UUID source = insert(spec().title("Source").embedding(unit(0)));
        insert(spec().title("Active").embedding(unit(10)));
        insert(spec().title("Expired").embedding(unit(1)).status("EXPIRED"));
        insert(spec().title("Not embedded yet"));
        UUID otherModel = insert(spec().title("Other model").embedding(unit(2)));
        jdbc.update("update jobs set embedding_model = 'some-other-model' where id = ?", otherModel);
        Session me = newSession();

        assertThat(titles(getAs(me, "/jobs/" + source + "/similar"))).containsExactly("Active");
    }

    @Test
    void anExpiredJobCanStillBeTheStartingPoint() throws Exception {
        UUID source = insert(spec().title("Source").embedding(unit(0)).status("EXPIRED"));
        insert(spec().title("Active").embedding(unit(10)));
        Session me = newSession();

        assertThat(titles(getAs(me, "/jobs/" + source + "/similar"))).containsExactly("Active");
    }

    @Test
    void filtersAndMyHiddenJobsApplyToTheNeighbours() throws Exception {
        UUID source = insert(spec().title("Source").embedding(unit(0)).workMode("REMOTE"));
        insert(spec().title("Remote near").embedding(unit(5)).workMode("REMOTE").country("US"));
        UUID hidden = insert(spec().title("Remote hidden").embedding(unit(6)).workMode("REMOTE").country("US"));
        insert(spec().title("Onsite near").embedding(unit(4)).workMode("ONSITE").country("US"));
        insert(spec().title("Remote far").embedding(unit(80)).workMode("REMOTE").country("GB"));
        Session me = newSession();
        Session other = newSession();
        putAs(me, "/jobs/" + hidden + "/hide").andExpect(status().isNoContent());

        assertThat(titles(getAs(me, "/jobs/" + source + "/similar?workMode=REMOTE")))
                .containsExactly("Remote near", "Remote far");
        assertThat(titles(getAs(me, "/jobs/" + source + "/similar?workMode=REMOTE&country=US")))
                .containsExactly("Remote near");
        // Someone else still sees the job I hid.
        assertThat(titles(getAs(other, "/jobs/" + source + "/similar?workMode=REMOTE&country=US")))
                .containsExactly("Remote near", "Remote hidden");
    }

    @Test
    void neighboursPageByKeysetEvenWhenVectorsAreIdentical() throws Exception {
        UUID source = insert(spec().title("Source").embedding(unit(0)));
        for (int i = 0; i < 7; i++) {
            insert(spec().title("Twin " + i).embedding(unit(30))); // identical vectors: equal distances
        }
        insert(spec().title("Nearer").embedding(unit(10)));
        Session me = newSession();

        List<String> seen = allIds(me, "/jobs/" + source + "/similar?limit=3", 10);

        assertThat(seen).hasSize(8).doesNotHaveDuplicates();
        assertThat(titlesOf(seen).get(0)).isEqualTo("Nearer");
    }

    @Test
    void theListIsBoundedToTheNearestHundred() throws Exception {
        UUID source = insert(spec().title("Source").embedding(unit(0)));
        for (int i = 0; i < 110; i++) {
            insert(spec().title("Job " + i).embedding(unit(1 + i * 0.5)));
        }
        Session me = newSession();

        List<String> seen = allIds(me, "/jobs/" + source + "/similar?limit=50", 5);

        assertThat(seen).hasSize(100).doesNotHaveDuplicates();
        assertThat(titlesOf(seen).get(0)).isEqualTo("Job 0");
        assertThat(titlesOf(seen).get(99)).isEqualTo("Job 99");
    }

    @Test
    void aCursorFromOneJobsNeighboursDoesNotWorkForAnother() throws Exception {
        UUID first = insert(spec().title("First").embedding(unit(0)));
        UUID second = insert(spec().title("Second").embedding(unit(20)));
        for (int i = 0; i < 5; i++) {
            insert(spec().title("Other " + i).embedding(unit(40 + i)));
        }
        Session me = newSession();
        String cursor = nextCursor(getAs(me, "/jobs/" + first + "/similar?limit=2"));

        getAs(me, "/jobs/" + second + "/similar?limit=2&cursor=" + cursor).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_cursor"));
    }

    @Test
    void refusalsAreSpecific() throws Exception {
        UUID noVector = insert(spec().title("Not embedded"));
        UUID withVector = insert(spec().title("Embedded").embedding(unit(0)));
        Session me = newSession();

        getAs(me, "/jobs/" + UUID.randomUUID() + "/similar").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("job_not_found"));
        getAs(me, "/jobs/" + noVector + "/similar").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("embedding_unavailable"));
        getAs(me, "/jobs/" + withVector + "/similar?q=engineer").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("keyword_not_supported"));
        getAs(me, "/jobs/" + withVector + "/similar?limit=51").andExpect(status().isBadRequest());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/jobs/" + withVector + "/similar")).andExpect(status().isUnauthorized());
    }

    @Test
    void theJobPageSaysWhetherSimilarJobsAreAvailable() throws Exception {
        UUID noVector = insert(spec().title("Not embedded"));
        UUID withVector = insert(spec().title("Embedded").embedding(unit(0)));
        Session me = newSession();

        getAs(me, "/jobs/" + noVector).andExpect(jsonPath("$.similarAvailable").value(false));
        getAs(me, "/jobs/" + withVector).andExpect(jsonPath("$.similarAvailable").value(true));
    }

    private List<String> titlesOf(List<String> ids) {
        List<String> titles = new ArrayList<>();
        for (String id : ids) {
            titles.add(jdbc.queryForObject("select title from jobs where id = ?::uuid", String.class, id));
        }
        return titles;
    }
}
