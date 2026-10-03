package com.jobfinder.core.applications.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** URL normalisation for the extension's apply context (docs/adr/0035-chrome-extension.md): the four ATS and the rest. */
class ApplyUrlsTests {

    private static String n(String url) {
        return ApplyUrls.normalize(url).orElseThrow();
    }

    // --- Greenhouse ---

    @Test
    void greenhouseHostedPagesShareOneFormWhateverTheHostOrTrackingOrCase() {
        String canonical = "https://boards.greenhouse.io/acme/jobs/4012345";

        assertThat(n("https://boards.greenhouse.io/acme/jobs/4012345")).isEqualTo(canonical);
        assertThat(n("https://job-boards.greenhouse.io/Acme/jobs/4012345")).isEqualTo(canonical);
        assertThat(n("https://job-boards.greenhouse.io/acme/jobs/4012345/?gh_src=abc&utm_source=x#app")).isEqualTo(canonical);
        assertThat(n("HTTPS://BOARDS.GREENHOUSE.IO/acme/jobs/4012345/")).isEqualTo(canonical);
    }

    @Test
    void greenhouseEmbeddedFormMapsToTheHostedPage() {
        assertThat(n("https://boards.greenhouse.io/embed/job_app?for=acme&token=4012345&utm_medium=x"))
                .isEqualTo("https://boards.greenhouse.io/acme/jobs/4012345");
    }

    @Test
    void greenhouseOnACompanyDomainKeepsTheJobIdAndDropsTracking() {
        assertThat(n("https://careers.acme.example/open-roles?gh_src=li&gh_jid=4012345&utm_campaign=q3"))
                .isEqualTo("https://careers.acme.example/open-roles?gh_jid=4012345");
    }

    @Test
    void theEuGreenhouseHostIsItsOwnHost() {
        assertThat(n("https://job-boards.eu.greenhouse.io/acme/jobs/9"))
                .isEqualTo("https://boards.eu.greenhouse.io/acme/jobs/9");
    }

    // --- Lever ---

    @Test
    void leverPostingAndItsApplyPageAreTheSameJob() {
        String canonical = "https://jobs.lever.co/acme/5f2c0a7e-1d3b-4c58-9a7e-0b1c2d3e4f50";

        assertThat(n(canonical)).isEqualTo(canonical);
        assertThat(n(canonical + "/apply")).isEqualTo(canonical);
        assertThat(n(canonical + "/apply?lever-source=LinkedIn&lever-origin=applied#x")).isEqualTo(canonical);
        assertThat(n("https://jobs.lever.co/ACME/5F2C0A7E-1D3B-4C58-9A7E-0B1C2D3E4F50/")).isEqualTo(canonical);
        assertThat(n("https://jobs.eu.lever.co/acme/5f2c0a7e-1d3b-4c58-9a7e-0b1c2d3e4f50/apply"))
                .isEqualTo("https://jobs.eu.lever.co/acme/5f2c0a7e-1d3b-4c58-9a7e-0b1c2d3e4f50");
    }

    // --- Ashby ---

    @Test
    void ashbyPostingAndItsApplicationTabAreTheSameJob() {
        String canonical = "https://jobs.ashbyhq.com/acme/0d6b3f5e-7a41-4c1b-8e2d-3a9f1c5b7d20";

        assertThat(n(canonical + "/application")).isEqualTo(canonical);
        assertThat(n(canonical + "/application?utm_source=Twitter")).isEqualTo(canonical);
        assertThat(n(canonical)).isEqualTo(canonical);
    }

    // --- Workday ---

    @Test
    void workdayDropsTheLocaleAndTheApplySteps() {
        String canonical = "https://acme.wd5.myworkdayjobs.com/Careers/job/Lagos/Backend-Engineer_R-1042";

        assertThat(n("https://acme.wd5.myworkdayjobs.com/en-US/Careers/job/Lagos/Backend-Engineer_R-1042"))
                .isEqualTo(canonical);
        assertThat(n("https://acme.wd5.myworkdayjobs.com/en-US/Careers/job/Lagos/Backend-Engineer_R-1042/apply"))
                .isEqualTo(canonical);
        assertThat(n("https://acme.wd5.myworkdayjobs.com/Careers/job/Lagos/Backend-Engineer_R-1042/apply/applyManually?source=x"))
                .isEqualTo(canonical);
        assertThat(n(canonical)).isEqualTo(canonical);
    }

    @Test
    void workdayJobsOfTheSameSiteStayDistinct() {
        assertThat(n("https://acme.wd5.myworkdayjobs.com/en-US/Careers/job/Lagos/Backend-Engineer_R-1042"))
                .isNotEqualTo(n("https://acme.wd5.myworkdayjobs.com/en-US/Careers/job/Lagos/Backend-Engineer_R-1043"));
    }

    // --- anything else ---

    @Test
    void aGenericLinkLosesFragmentUserInfoAndTrackingAndSortsTheRest() {
        assertThat(n("https://user:pw@Jobs.Example.Test:443/apply/?utm_source=a&id=7&gclid=z&b=2&a=1#top"))
                .isEqualTo("https://jobs.example.test/apply?a=1&b=2&id=7");
    }

    @Test
    void aNonDefaultPortIsKept() {
        assertThat(n("http://localhost:8081/jobs/1")).isEqualTo("http://localhost:8081/jobs/1");
    }

    @Test
    void differentJobIdsOnOneGenericSiteDoNotCollapse() {
        assertThat(n("https://x.example/jobs?id=1")).isNotEqualTo(n("https://x.example/jobs?id=2"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = { "   ", "ftp://jobs.example.test/1", "javascript:alert(1)", "not a url", "https:///nohost",
            "//jobs.example.test/1", "https://exa mple.test/1" })
    void thingsThatAreNotHttpUrlsAreRejected(String raw) {
        assertThat(ApplyUrls.normalize(raw)).isEmpty();
    }

    @Test
    void anOverlongUrlIsRejected() {
        assertThat(ApplyUrls.normalize("https://x.example/" + "a".repeat(ApplyUrls.MAX_LENGTH))).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({
            "https://boards.greenhouse.io/acme/jobs/4012345, 4012345",
            "https://careers.acme.example/open-roles?gh_jid=77, 77",
            "https://jobs.lever.co/acme/5f2c0a7e-1d3b-4c58-9a7e-0b1c2d3e4f50, 5f2c0a7e-1d3b-4c58-9a7e-0b1c2d3e4f50",
            "https://acme.wd5.myworkdayjobs.com/Careers/job/Lagos/Backend-Engineer_R-1042, Backend-Engineer_R-1042",
            "https://jobs.example.test/, jobs.example.test" })
    void theSearchFragmentIsTheMostSpecificIdentifier(String canonical, String fragment) {
        assertThat(ApplyUrls.searchFragment(canonical)).isEqualTo(fragment);
    }
}
