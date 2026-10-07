package com.jobfinder.core.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import com.jobfinder.core.CoversEndpoints;
import com.jobfinder.core.billing.AiUsageGate;
import com.jobfinder.core.profile.ResumeTestSupport;

/**
 * The data export (docs/adr/0040): a zip with a folder per module, the caller's CV files, and nothing of anybody else's
 * or of the secrets that sit next to the data (hashes, tokens, vectors).
 */
class DataExportTests extends ResumeTestSupport {

    private static final String ROOT = "jobfinder-export/";

    @Autowired
    private AiUsageGate gate;

    private Map<String, byte[]> export(Session session) throws Exception {
        MvcResult started = mvc.perform(get("/me/export").header("Authorization", "Bearer " + session.accessToken()))
                .andExpect(request().asyncStarted()).andReturn();
        MvcResult done = mvc.perform(asyncDispatch(started)).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/zip"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("attachment")))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andReturn();
        Map<String, byte[]> entries = new TreeMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(done.getResponse().getContentAsByteArray()))) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                entries.put(entry.getName(), zip.readAllBytes());
            }
        }
        return entries;
    }

    private static String text(Map<String, byte[]> zip, String name) {
        return new String(zip.get(ROOT + name), StandardCharsets.UTF_8);
    }

    private static String everything(Map<String, byte[]> zip) {
        StringBuilder all = new StringBuilder();
        zip.forEach((name, bytes) -> all.append(name).append('\n').append(new String(bytes, StandardCharsets.ISO_8859_1))
                .append('\n'));
        return all.toString();
    }

    private void seedProfile(UUID userId, String fullName) {
        jdbc.update("insert into profiles (id, user_id, full_name, created_at, updated_at) "
                + "values (?, ?, ?, now(), now())", UUID.randomUUID(), userId, fullName);
        jdbc.update("insert into preferences (id, user_id, target_titles, created_at, updated_at) "
                + "values (?, ?, array['Engineer'], now(), now())", UUID.randomUUID(), userId);
        jdbc.update("insert into notification_preferences (user_id, created_at, updated_at) values (?, now(), now())",
                userId);
    }

    @CoversEndpoints({ "GET /me/export" })
    @Test
    void theExportHoldsTheCallersDataAndFilesAndNothingOfAnotherUser() throws Exception {
        Session bystander = newSession();
        UUID bystanderId = userIdOf(bystander.accessToken());
        seedProfile(bystanderId, "Bystander Marker");
        UUID bystanderCv = uploadPdf(bystander);

        Session caller = newSession();
        UUID callerId = userIdOf(caller.accessToken());
        seedProfile(callerId, "Caller Marker");
        UUID callerCv = uploadPdf(caller);
        gate.requireAllowance(callerId, "parse_resume");
        String callerEmail = jdbc.queryForObject("select email from users where id = ?", String.class, callerId);
        String bystanderEmail = jdbc.queryForObject("select email from users where id = ?", String.class, bystanderId);

        Map<String, byte[]> zip = export(caller);

        assertThat(zip.keySet()).contains(ROOT + "README.txt", ROOT + "manifest.json", ROOT + "account/account.json",
                ROOT + "profile/profile.json", ROOT + "profile/preferences.json", ROOT + "profile/resumes.json",
                ROOT + "profile/resume-versions.json", ROOT + "billing/credit-ledger.json",
                ROOT + "notifications/email-settings.json", ROOT + "profile/cv-files/" + callerCv + ".pdf");
        assertThat(zip.get(ROOT + "profile/cv-files/" + callerCv + ".pdf")).isEqualTo(pdf());
        assertThat(text(zip, "account/account.json")).contains(callerEmail);
        assertThat(text(zip, "profile/profile.json")).contains("Caller Marker");
        assertThat(text(zip, "billing/credit-ledger.json")).contains("PLAN_GRANT");
        assertThat(text(zip, "manifest.json")).contains("profile/cv-files/" + callerCv + ".pdf");

        String all = everything(zip);
        assertThat(all).doesNotContain("Bystander Marker").doesNotContain(bystanderEmail)
                .doesNotContain(bystanderId.toString()).doesNotContain(bystanderCv.toString());
        // Secrets and internal columns never leave: hashes, token material, search vectors and storage paths.
        assertThat(all).doesNotContain("password_hash").doesNotContain("token_hash").doesNotContain("\"embedding\"")
                .doesNotContain("file_key").doesNotContain("storage_key");
        assertThat(text(zip, "account/account.json")).contains("\"has_password\": true");
    }

    @Test
    void everyModuleThatStoresUserDataContributesAFolder() throws Exception {
        Map<String, byte[]> zip = export(newSession());

        for (String folder : new String[] { "account", "profile", "documents", "generated-files", "applications",
                "interview", "jobs", "matching", "notifications", "billing" }) {
            assertThat(zip.keySet()).as(folder).anyMatch(name -> name.startsWith(ROOT + folder + "/"));
        }
    }

    @Test
    void theExportOfAUserWithNoDataIsStillAValidArchive() throws Exception {
        Map<String, byte[]> zip = export(newSession());

        assertThat(text(zip, "profile/resumes.json")).isEqualToIgnoringWhitespace("[]");
        assertThat(text(zip, "manifest.json")).contains("\"notIncluded\": [\n  ]");
    }

    @Test
    void aFileThatIsMissingFromStorageIsReportedInTheManifestNotHidden() throws Exception {
        Session session = newSession();
        UUID cv = uploadPdf(session);
        s3.deleteObject(b -> b.bucket(bucket).key(fileKey(cv)));

        Map<String, byte[]> zip = export(session);

        assertThat(zip.keySet()).doesNotContain(ROOT + "profile/cv-files/" + cv + ".pdf");
        assertThat(text(zip, "manifest.json")).contains("profile/cv-files/" + cv + ".pdf")
                .contains("no longer exists");
        assertThat(text(zip, "profile/resumes.json")).contains(cv.toString());
    }

    @Test
    void theExportIsAlwaysTheTokenOwnersWhateverTheRequestSays() throws Exception {
        Session other = newSession();
        UUID otherId = userIdOf(other.accessToken());
        seedProfile(otherId, "Other Marker");
        Session caller = newSession();

        MvcResult started = mvc.perform(get("/me/export").param("userId", otherId.toString())
                .header("Authorization", "Bearer " + caller.accessToken()))
                .andExpect(request().asyncStarted()).andReturn();
        String body = new String(mvc.perform(asyncDispatch(started)).andReturn().getResponse().getContentAsByteArray(),
                StandardCharsets.ISO_8859_1);

        assertThat(body).doesNotContain(otherId.toString());
        mvc.perform(get("/me/export")).andExpect(status().isUnauthorized());
    }
}
