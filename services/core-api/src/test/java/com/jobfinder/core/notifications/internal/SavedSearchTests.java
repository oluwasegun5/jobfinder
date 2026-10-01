package com.jobfinder.core.notifications.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import com.jayway.jsonpath.JsonPath;

/** Saved-search CRUD: who can reach what, what is rejected, and when the watermark moves. */
class SavedSearchTests extends NotificationsTestSupport {

    private static final String GO = """
            {"name":"Go in Lagos","criteria":{"q":"golang","workMode":["REMOTE"],"country":["NG"]},"frequency":"DAILY"}""";

    private String create(Session session, String json) throws Exception {
        String body = postJsonAs(session, "/saved-searches", json).andExpect(status().isCreated()).andReturn()
                .getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    @Test
    void everyEndpointNeedsASignedInUser() throws Exception {
        UUID any = UUID.randomUUID();
        mvc.perform(get("/saved-searches")).andExpect(status().isUnauthorized());
        mvc.perform(post("/saved-searches").contentType(MediaType.APPLICATION_JSON).content(GO))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/saved-searches/" + any)).andExpect(status().isUnauthorized());
        mvc.perform(delete("/saved-searches/" + any)).andExpect(status().isUnauthorized());
        mvc.perform(get("/notifications/preferences")).andExpect(status().isUnauthorized());
    }

    @Test
    void createReadUpdateAndDelete() throws Exception {
        Session me = newSession();
        String id = create(me, GO);

        getAs(me, "/saved-searches/" + id).andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Go in Lagos"))
                .andExpect(jsonPath("$.frequency").value("DAILY"))
                .andExpect(jsonPath("$.criteria.q").value("golang"))
                .andExpect(jsonPath("$.criteria.workMode[0]").value("REMOTE"))
                .andExpect(jsonPath("$.criteria.country[0]").value("NG"))
                .andExpect(jsonPath("$.lastRunAt").isString());
        getAs(me, "/saved-searches").andExpect(jsonPath("$.items.length()").value(1));

        putJsonAs(me, "/saved-searches/" + id, """
                {"name":"Go, weekly","criteria":{"q":"golang","workMode":["REMOTE"],"country":["NG"]},"frequency":"WEEKLY"}""")
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Go, weekly"))
                .andExpect(jsonPath("$.frequency").value("WEEKLY"));

        deleteAs(me, "/saved-searches/" + id).andExpect(status().isNoContent());
        getAs(me, "/saved-searches/" + id).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("saved_search_not_found"));
        getAs(me, "/saved-searches").andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void anotherUsersSearchIsNeitherListedNorReachable() throws Exception {
        Session owner = newSession();
        Session other = newSession();
        String id = create(owner, GO);

        getAs(other, "/saved-searches").andExpect(jsonPath("$.items.length()").value(0));
        getAs(other, "/saved-searches/" + id).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("saved_search_not_found"));
        putJsonAs(other, "/saved-searches/" + id, GO).andExpect(status().isNotFound());
        deleteAs(other, "/saved-searches/" + id).andExpect(status().isNotFound());

        getAs(owner, "/saved-searches/" + id).andExpect(status().isOk());
        assertThat(count("select count(*) from saved_searches where id = ?::uuid", id)).isEqualTo(1);
    }

    @Test
    void badInputIsRejected() throws Exception {
        Session me = newSession();
        String[] bad = {
                "{\"name\":\"\",\"criteria\":{\"q\":\"go\"},\"frequency\":\"DAILY\"}",
                "{\"name\":\"x\",\"criteria\":{\"q\":\"go\"},\"frequency\":\"HOURLY\"}",
                "{\"name\":\"x\",\"criteria\":{\"q\":\"go\",\"workMode\":[\"MOON\"]},\"frequency\":\"DAILY\"}",
                "{\"name\":\"x\",\"criteria\":{\"q\":\"go\",\"country\":[\"NGA\"]},\"frequency\":\"DAILY\"}",
                "{\"name\":\"x\",\"frequency\":\"DAILY\"}",
                "{\"name\":\"" + "n".repeat(101) + "\",\"criteria\":{\"q\":\"go\"},\"frequency\":\"DAILY\"}" };
        for (String json : bad) {
            postJsonAs(me, "/saved-searches", json).andExpect(status().isBadRequest());
        }
        postJsonAs(me, "/saved-searches", "{\"name\":\"x\",\"criteria\":{},\"frequency\":\"DAILY\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("empty_search"));
        postJsonAs(me, "/saved-searches",
                "{\"name\":\"x\",\"criteria\":{\"minSalary\":1000},\"frequency\":\"DAILY\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("salary_currency_required"));
        getAs(me, "/saved-searches").andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void aUserCanKeepOnlyTwentySearches() throws Exception {
        Session me = newSession();
        for (int i = 0; i < 20; i++) {
            create(me, "{\"name\":\"s" + i + "\",\"criteria\":{\"q\":\"go" + i + "\"},\"frequency\":\"OFF\"}");
        }
        postJsonAs(me, "/saved-searches", GO).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("saved_search_limit"));
        // the limit is per user
        create(newSession(), GO);
    }

    @Test
    void theWatermarkMovesOnlyWhenTheSearchChangesMeaning() throws Exception {
        Session me = newSession();
        String id = create(me, GO);
        jdbc.update("update saved_searches set last_run_at = now() - interval '3 days' where id = ?::uuid", id);
        Object old = jdbc.queryForObject("select last_run_at from saved_searches where id = ?::uuid",
                java.sql.Timestamp.class, id);

        // a rename and a new frequency leave "what is new since" alone
        putJsonAs(me, "/saved-searches/" + id, """
                {"name":"Renamed","criteria":{"q":"golang","workMode":["REMOTE"],"country":["NG"]},"frequency":"WEEKLY"}""")
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select last_run_at from saved_searches where id = ?::uuid",
                java.sql.Timestamp.class, id)).isEqualTo(old);

        // other criteria: jobs from before are not new for this search
        putJsonAs(me, "/saved-searches/" + id, """
                {"name":"Renamed","criteria":{"q":"rust"},"frequency":"WEEKLY"}""").andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select last_run_at from saved_searches where id = ?::uuid",
                java.sql.Timestamp.class, id)).isAfter((java.sql.Timestamp) old);

        // OFF to on starts from now too
        jdbc.update("update saved_searches set last_run_at = now() - interval '3 days' where id = ?::uuid", id);
        putJsonAs(me, "/saved-searches/" + id, """
                {"name":"Renamed","criteria":{"q":"rust"},"frequency":"OFF"}""").andExpect(status().isOk());
        java.sql.Timestamp whileOff = jdbc.queryForObject("select last_run_at from saved_searches where id = ?::uuid",
                java.sql.Timestamp.class, id);
        assertThat(whileOff).isBefore(java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(3600)));
        putJsonAs(me, "/saved-searches/" + id, """
                {"name":"Renamed","criteria":{"q":"rust"},"frequency":"DAILY"}""").andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select last_run_at from saved_searches where id = ?::uuid",
                java.sql.Timestamp.class, id)).isAfter(whileOff);
    }
}
