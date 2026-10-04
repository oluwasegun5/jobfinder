package com.jobfinder.core.applications.internal;

import com.jobfinder.core.CoversEndpoints;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

/**
 * GET /extension/apply-context (docs/adr/0035-chrome-extension.md): the job behind the form the user has open, with their
 * own application and pack, and never anyone else's.
 */
class ApplyContextTests extends ApplicationsTestSupport {

    private static final String GREENHOUSE = "https://boards.greenhouse.io/acme/jobs/4012345";
    private static final String LEVER = "https://jobs.lever.co/acme/5f2c0a7e-1d3b-4c58-9a7e-0b1c2d3e4f50";

    private ResultActions context(Session session, String url) throws Exception {
        return mvc.perform(get("/extension/apply-context").param("url", url).header("Authorization", bearer(session)));
    }

    private UUID jobAt(String applyUrl) {
        return insert(spec().title("Staff Backend Engineer").company("Acme Test Co").applyUrl(applyUrl)
                .postedAt(Instant.now().minus(1, ChronoUnit.HOURS)));
    }

    @Test
    void itNeedsASignedInUser() throws Exception {
        mvc.perform(get("/extension/apply-context").param("url", GREENHOUSE)).andExpect(status().isUnauthorized());
    }

    @Test
    void aKnownJobIsFoundFromTheFormUrlWhateverTrackingItCarries() throws Exception {
        Session me = newSession();
        UUID job = jobAt(GREENHOUSE);

        for (String url : new String[] { GREENHOUSE, "https://job-boards.greenhouse.io/acme/jobs/4012345/?gh_src=li#app",
                "https://boards.greenhouse.io/embed/job_app?for=acme&token=4012345&utm_source=x" }) {
            context(me, url).andExpect(status().isOk()).andExpect(jsonPath("$.job.id").value(job.toString()))
                    .andExpect(jsonPath("$.job.title").value("Staff Backend Engineer"))
                    .andExpect(jsonPath("$.job.company").value("Acme Test Co"))
                    .andExpect(jsonPath("$.applicationId").doesNotExist())
                    .andExpect(jsonPath("$.packSummary").doesNotExist());
        }
    }

    @Test
    void theApplyPageOfALeverPostingFindsThePostingsJob() throws Exception {
        Session me = newSession();
        UUID job = jobAt(LEVER);

        context(me, LEVER + "/apply?lever-source=LinkedIn").andExpect(status().isOk())
                .andExpect(jsonPath("$.job.id").value(job.toString()));
    }

    @Test
    void aLikePatternInTheUrlIsLiteralAndMatchesNothingElse() throws Exception {
        Session me = newSession();
        jobAt(GREENHOUSE);

        context(me, "https://careers.example/jobs?gh_jid=%25").andExpect(status().isNotFound());
        context(me, "https://careers.example/jobs?gh_jid=_").andExpect(status().isNotFound());
    }

    @Test
    void anUnknownAddressIsNotFound() throws Exception {
        Session me = newSession();
        jobAt(GREENHOUSE);

        context(me, "https://boards.greenhouse.io/acme/jobs/999").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("job_not_found"));
        // The id is in the stored link but the page is another company's job.
        context(me, "https://boards.greenhouse.io/other/jobs/4012345").andExpect(status().isNotFound());
    }

    @Test
    void aBadUrlIsRejected() throws Exception {
        Session me = newSession();

        context(me, "javascript:alert(1)").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_url"));
        context(me, " ").andExpect(status().isBadRequest());
        context(me, "https://x.example/" + "a".repeat(2100)).andExpect(status().isBadRequest());
        mvc.perform(get("/extension/apply-context").header("Authorization", bearer(me)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void itReturnsTheCallersApplicationAndPackForTheJob() throws Exception {
        Session me = newSession();
        Candidate c = seed(me);
        UUID job = jobAt(GREENHOUSE);
        String application = fromJob(me, job);
        UUID pack = packFor(c, job);

        context(me, GREENHOUSE).andExpect(status().isOk()).andExpect(jsonPath("$.applicationId").value(application))
                .andExpect(jsonPath("$.applicationStatus").value("APPLIED"))
                .andExpect(jsonPath("$.packSummary.id").value(pack.toString()))
                .andExpect(jsonPath("$.packSummary.status").value("COMPLETE"))
                .andExpect(jsonPath("$.packSummary.version").value(1));
    }

    @CoversEndpoints({"GET /extension/apply-context"})
    @Test
    void anotherUsersApplicationAndPackAreNeverShown() throws Exception {
        Session a = newSession();
        Candidate ca = seed(a);
        Session b = newSession();
        UUID job = jobAt(GREENHOUSE);
        String applicationOfA = fromJob(a, job);
        UUID packOfA = packFor(ca, job);

        // B sees the job (jobs are public to signed-in users) and nothing of A's.
        context(b, GREENHOUSE).andExpect(status().isOk()).andExpect(jsonPath("$.job.id").value(job.toString()))
                .andExpect(jsonPath("$.applicationId").doesNotExist())
                .andExpect(jsonPath("$.applicationStatus").doesNotExist())
                .andExpect(jsonPath("$.packSummary").doesNotExist());
        // A still sees their own.
        context(a, GREENHOUSE).andExpect(jsonPath("$.applicationId").value(applicationOfA))
                .andExpect(jsonPath("$.packSummary.id").value(packOfA.toString()));
    }

    @Test
    void whenTwoJobsShareALinkTheOneTheCallerTracksWins() throws Exception {
        Session me = newSession();
        jobAt(GREENHOUSE);
        UUID second = jobAt(GREENHOUSE + "?gh_src=other");
        String application = fromJob(me, second);

        context(me, GREENHOUSE).andExpect(jsonPath("$.job.id").value(second.toString()))
                .andExpect(jsonPath("$.applicationId").value(application));
        // Someone who tracks neither gets one of them.
        context(newSession(), GREENHOUSE).andExpect(status().isOk()).andExpect(jsonPath("$.applicationId").doesNotExist());
    }

    @Test
    void itChangesNothing() throws Exception {
        Session me = newSession();
        jobAt(GREENHOUSE);

        context(me, GREENHOUSE).andExpect(status().isOk());

        assertThat(rows("applications", userIdOf(me))).isZero();
    }
}
