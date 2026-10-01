package com.jobfinder.core.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;

/**
 * Cursor pagination is keyset: it must visit every row exactly once, in the order of the sort, however many rows
 * share a sort key, and it must survive rows appearing and disappearing between pages.
 */
class JobPaginationTests extends JobsTestSupport {

    private static final Instant SAME_MOMENT = Instant.parse("2026-09-01T10:00:00Z");

    @Test
    void rowsThatShareASortKeyAreNeitherSkippedNorRepeated() throws Exception {
        List<UUID> inserted = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            inserted.add(insert(spec().title("Tied " + i).postedAt(SAME_MOMENT)));
        }
        Session me = newSession();

        for (int limit : new int[] { 1, 2, 3, 5, 10, 11, 50 }) {
            List<String> seen = allIds(me, "/jobs?limit=" + limit, 20);
            assertThat(seen).as("limit %d", limit).doesNotHaveDuplicates().hasSize(11)
                    .containsExactlyElementsOf(orderedByNewestThenId());
        }
        assertThat(inserted).hasSize(11);
    }

    @Test
    void keywordResultsWithEqualScoresPageCleanlyInIdOrder() throws Exception {
        for (int i = 0; i < 9; i++) {
            insert(spec().title("Platform Engineer").description("identical text").postedAt(SAME_MOMENT));
        }
        Session me = newSession();

        List<String> seen = allIds(me, "/jobs?q=platform+engineer&limit=4", 10);

        assertThat(seen).doesNotHaveDuplicates().hasSize(9);
        // Equal scores: descending id is the tie-break the cursor encodes.
        assertThat(seen).isSortedAccordingTo(java.util.Comparator.<String>reverseOrder());
    }

    @Test
    void aKeywordSearchPagesInTheSameOrderAsOneBigPage() throws Exception {
        for (int i = 0; i < 30; i++) {
            // More mentions of the word, and a varied age, give many distinct scores and some ties.
            insert(spec().title(i % 3 == 0 ? "Rust Engineer" : "Engineer").description("rust ".repeat(i % 7))
                    .postedAt(Instant.now().minus(Duration.ofDays(i % 5))));
        }
        Session me = newSession();

        List<String> whole = ids(getAs(me, "/jobs?q=rust&limit=50"));
        List<String> paged = allIds(me, "/jobs?q=rust&limit=4", 20);

        assertThat(whole).hasSize(paged.size()).doesNotHaveDuplicates();
        assertThat(paged).containsExactlyElementsOf(whole);
    }

    @Test
    void filtersPageTooAndTheLastPageHasNoCursor() throws Exception {
        for (int i = 0; i < 7; i++) {
            insert(spec().title("Remote " + i).workMode("REMOTE").postedAt(SAME_MOMENT.minusSeconds(i)));
            insert(spec().title("Onsite " + i).workMode("ONSITE").postedAt(SAME_MOMENT.minusSeconds(i)));
        }
        Session me = newSession();

        ResultActions last = getAs(me, "/jobs?workMode=REMOTE&limit=7");
        last.andExpect(jsonPath("$.items.length()").value(7)).andExpect(jsonPath("$.nextCursor").doesNotExist());
        assertThat(allIds(me, "/jobs?workMode=REMOTE&limit=3", 10)).hasSize(7).doesNotHaveDuplicates();
    }

    @Test
    void rowsAddedOrRemovedBetweenPagesNeitherRepeatNorDropTheRest() throws Exception {
        List<UUID> jobs = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            jobs.add(insert(spec().title("Job " + i).postedAt(SAME_MOMENT.minusSeconds(i))));
        }
        Session me = newSession();

        ResultActions first = getAs(me, "/jobs?limit=3");
        List<String> page1 = ids(first);
        // A newer job arrives (it sorts before the cursor) and one not yet seen is removed.
        insert(spec().title("Newcomer").postedAt(SAME_MOMENT.plusSeconds(60)));
        jdbc.update("delete from jobs where id = ?", jobs.get(4));
        ResultActions second = getAs(me, "/jobs?limit=3&cursor=" + nextCursor(first));
        List<String> page2 = ids(second);
        List<String> page3 = ids(getAs(me, "/jobs?limit=3&cursor=" + nextCursor(second)));

        assertThat(page1).containsExactly(jobs.get(0).toString(), jobs.get(1).toString(), jobs.get(2).toString());
        assertThat(page2).containsExactly(jobs.get(3).toString(), jobs.get(5).toString(), jobs.get(6).toString());
        assertThat(page3).containsExactly(jobs.get(7).toString(), jobs.get(8).toString());
    }

    @Test
    void theCursorFixesTheInstantTheFirstPageWasComputedAt() throws Exception {
        for (int i = 0; i < 6; i++) {
            insert(spec().title("Engineer " + i).postedAt(Instant.now().minus(Duration.ofDays(i))));
        }
        Session me = newSession();

        ResultActions first = getAs(me, "/jobs?q=engineer&limit=2");
        ResultActions second = getAs(me, "/jobs?q=engineer&limit=2&cursor=" + nextCursor(first));

        assertThat(asOf(nextCursor(second))).isEqualTo(asOf(nextCursor(first)));
    }

    // --- cursors are tied to the query they came from ---

    @Test
    void aCursorIsRejectedWithOtherParametersOrOtherListings() throws Exception {
        for (int i = 0; i < 6; i++) {
            insert(spec().title("Engineer " + i).workMode("REMOTE").postedAt(SAME_MOMENT.minusSeconds(i)));
        }
        Session me = newSession();
        String cursor = nextCursor(getAs(me, "/jobs?q=engineer&limit=2"));

        getAs(me, "/jobs?q=engineer&limit=2&cursor=" + cursor).andExpect(status().isOk());
        for (String other : List.of("/jobs?q=designer&limit=2", "/jobs?q=engineer&workMode=REMOTE&limit=2",
                "/jobs?limit=2", "/saved-jobs?limit=2")) {
            getAs(me, other + "&cursor=" + cursor).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("invalid_cursor"));
        }
    }

    @Test
    void garbageForgedAndWronglyVersionedCursorsAreRejected() throws Exception {
        insert("Engineer");
        Session me = newSession();
        String forged = Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{\"v\":99,\"mode\":\"R\",\"key\":\"x\",\"id\":\"%s\",\"asOf\":1,\"query\":\"x\"}"
                        .formatted(UUID.randomUUID()).getBytes(StandardCharsets.UTF_8));

        for (String cursor : List.of("garbage", "e30", forged, "%00%00", "A".repeat(500))) {
            getAs(me, "/jobs?cursor=" + cursor).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("invalid_cursor"));
        }
    }

    @Test
    void aKeywordListingPagesAcrossTheTierBoundaryRankedMatchesFirstThenDescriptionOnlyOnes() throws Exception {
        List<UUID> ranked = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            ranked.add(insert(spec().title("Kestrel Analyst " + i).postedAt(SAME_MOMENT)));
        }
        List<UUID> descriptionOnly = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            descriptionOnly.add(insert(spec().title("Analyst " + i).description("we use kestrel daily")
                    .postedAt(SAME_MOMENT.minusSeconds(i % 3))));
        }
        Session me = newSession();

        for (int limit : new int[] { 1, 2, 4, 5, 9 }) {
            List<String> seen = allIds(me, "/jobs?q=kestrel&limit=" + limit, 20);
            assertThat(seen).as("limit %d", limit).doesNotHaveDuplicates().hasSize(9);
            assertThat(seen.subList(0, 3)).containsExactlyInAnyOrderElementsOf(ranked.stream().map(UUID::toString).toList());
            // Description-only matches follow, newest first (ties by id, descending).
            assertThat(seen.subList(3, 9)).containsExactlyElementsOf(jdbc.queryForList(
                    "select id::text from jobs where title like 'Analyst %' order by sort_at desc, id desc", String.class));
        }
        assertThat(descriptionOnly).hasSize(6);
    }

    @Test
    void aForgedKeyInsideAWellFormedCursorIsA400NotA500() throws Exception {
        insert("Engineer A");
        insert("Engineer B");
        Session me = newSession();
        String real = nextCursor(getAs(me, "/jobs?limit=1"));
        String json = new String(Base64.getUrlDecoder().decode(real), StandardCharsets.UTF_8);
        String key = JsonPath.read(json, "$.key");

        getAs(me, "/jobs?limit=1&cursor=" + real).andExpect(status().isOk());
        for (String bad : List.of("x", "NaN", "2026-13-45")) {
            String forged = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    json.replace("\"key\":\"" + key + "\"", "\"key\":\"" + bad + "\"")
                            .getBytes(StandardCharsets.UTF_8));
            getAs(me, "/jobs?limit=1&cursor=" + forged).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("invalid_cursor"));
        }
    }

    // --- helpers ---

    private List<String> orderedByNewestThenId() {
        return jdbc.queryForList("select id::text from jobs order by sort_at desc, id desc", String.class);
    }

    private static long asOf(String cursor) {
        String json = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
        return ((Number) JsonPath.read(json, "$.asOf")).longValue();
    }
}
