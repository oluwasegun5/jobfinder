package com.jobfinder.core.applications.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;

import com.jayway.jsonpath.JsonPath;

/**
 * Creating, reading, listing, changing and deleting applications (docs/adr/0032-application-tracker.md): from a job and
 * by hand, idempotent per job, every request scoped to its owner, the links to a pack and to approved documents checked.
 */
class ApplicationCrudTests extends ApplicationsTestSupport {

    @Test
    void everyEndpointNeedsASignedInUser() throws Exception {
        UUID id = UUID.randomUUID();
        mvc.perform(post("/applications")).andExpect(status().isUnauthorized());
        for (String path : List.of("/applications", "/applications/" + id, "/applications/" + id + "/reminders")) {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/applications/" + id + "/status")).andExpect(status().isUnauthorized());
        mvc.perform(post("/applications/" + id + "/follow-up-draft")).andExpect(status().isUnauthorized());
    }

    // --- create ---

    @Test
    void anApplicationEnteredByHandIsCreatedAsAppliedWithItsFirstEvent() throws Exception {
        Session me = newSession();

        create(me, "{\"title\":\"  Platform Engineer \",\"company\":\"Globex\",\"url\":\"https://globex.example/jobs/1\","
                + "\"notes\":\"Found on a friend's feed\"}").andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isString()).andExpect(jsonPath("$.jobId").doesNotExist())
                .andExpect(jsonPath("$.title").value("Platform Engineer")).andExpect(jsonPath("$.company").value("Globex"))
                .andExpect(jsonPath("$.url").value("https://globex.example/jobs/1"))
                .andExpect(jsonPath("$.status").value("APPLIED")).andExpect(jsonPath("$.appliedAt").isString())
                .andExpect(jsonPath("$.notes").value("Found on a friend's feed"))
                .andExpect(jsonPath("$.events.length()").value(1)).andExpect(jsonPath("$.events[0].from").doesNotExist())
                .andExpect(jsonPath("$.events[0].to").value("APPLIED")).andExpect(jsonPath("$.reminders.length()").value(0));
    }

    @Test
    void anApplicationFromAJobCopiesItsTitleAndCompanyAndIgnoresWhatTheRequestSays() throws Exception {
        Session me = newSession();
        UUID job = newJob();

        create(me, "{\"jobId\":\"" + job + "\",\"title\":\"Something else\",\"company\":\"Nobody\","
                + "\"url\":\"https://acme.example/apply\"}").andExpect(status().isCreated())
                .andExpect(jsonPath("$.jobId").value(job.toString()))
                .andExpect(jsonPath("$.title").value("Staff Backend Engineer"))
                .andExpect(jsonPath("$.company").value("Acme Test Co"))
                .andExpect(jsonPath("$.url").value("https://acme.example/apply"));
    }

    @Test
    void creatingTheSameJobAgainReturnsTheExistingApplicationUnchanged() throws Exception {
        Session me = newSession();
        UUID job = newJob();
        String id = idOf(create(me, "{\"jobId\":\"" + job + "\",\"status\":\"SAVED\"}"));
        moveTo(me, id, "INTERVIEW").andExpect(status().isOk());

        create(me, "{\"jobId\":\"" + job + "\",\"status\":\"APPLIED\",\"notes\":\"again\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id)).andExpect(jsonPath("$.status").value("INTERVIEW"))
                .andExpect(jsonPath("$.notes").doesNotExist()).andExpect(jsonPath("$.events.length()").value(2));

        assertThat(rows("applications", userIdOf(me))).isEqualTo(1);
    }

    @Test
    void aDoubleClickMakesOneApplication() throws Exception {
        Session me = newSession();
        UUID job = newJob();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> click = () -> create(me, "{\"jobId\":\"" + job + "\"}").andReturn().getResponse()
                    .getStatus();
            Future<Integer> a = pool.submit(click);
            Future<Integer> b = pool.submit(click);
            assertThat(List.of(a.get(), b.get())).containsExactlyInAnyOrder(201, 200);
        } finally {
            pool.shutdownNow();
        }

        assertThat(rows("applications", userIdOf(me))).isEqualTo(1);
        assertThat(rows("application_events", userIdOf(me))).isEqualTo(1);
    }

    @Test
    void twoUsersCanTrackTheSameJob() throws Exception {
        UUID job = newJob();
        Session a = newSession();
        Session b = newSession();

        create(a, "{\"jobId\":\"" + job + "\"}").andExpect(status().isCreated());
        create(b, "{\"jobId\":\"" + job + "\"}").andExpect(status().isCreated());
    }

    @Test
    void anUnknownJobIsNotFound() throws Exception {
        Session me = newSession();

        create(me, "{\"jobId\":\"" + UUID.randomUUID() + "\"}").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("job_not_found"));
    }

    @Test
    void aSavedApplicationHasNoAppliedAtAndCannotBeGivenOne() throws Exception {
        Session me = newSession();

        create(me, "{\"title\":\"Dev\",\"status\":\"SAVED\"}").andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SAVED")).andExpect(jsonPath("$.appliedAt").doesNotExist());
        create(me, "{\"title\":\"Dev\",\"status\":\"SAVED\",\"appliedAt\":\"2026-01-01T00:00:00Z\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_applied_at"));
    }

    @Test
    void anAppliedAtGivenIsKeptButNeverInTheFuture() throws Exception {
        Session me = newSession();

        create(me, "{\"title\":\"Dev\",\"appliedAt\":\"2026-01-02T10:00:00Z\"}").andExpect(status().isCreated())
                .andExpect(jsonPath("$.appliedAt").value("2026-01-02T10:00:00Z"));
        create(me, "{\"title\":\"Dev\",\"appliedAt\":\"2999-01-01T00:00:00Z\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_applied_at"));
    }

    @Test
    void invalidInputIsRefusedWithATypedError() throws Exception {
        Session me = newSession();

        create(me, "{}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("title_required"));
        create(me, "{\"title\":\"  \"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("title_required"));
        create(me, "{\"title\":\"Dev\",\"url\":\"javascript:alert(1)\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_url"));
        create(me, "{\"title\":\"Dev\",\"url\":\"ftp://example.test/x\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_url"));
        create(me, "{\"title\":\"Dev\",\"status\":\"GHOSTED\"}").andExpect(status().isBadRequest());
        create(me, "{\"title\":\"" + "x".repeat(401) + "\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation_failed"));
    }

    // --- links to a pack and to approved documents ---

    @Test
    void thePackAndTheApprovedDocumentsUsedAreLinked() throws Exception {
        Session me = newSession();
        Candidate c = seed(me);
        UUID job = newJob();
        UUID pack = packFor(c, job);
        UUID resume = approvedDocument(c, "TAILORED_RESUME", job);
        UUID letter = approvedDocument(c, "COVER_LETTER", job);
        UUID answers = approvedDocument(c, "SCREENING_ANSWERS", job);

        create(me, "{\"jobId\":\"" + job + "\",\"packId\":\"" + pack + "\",\"resumeDocumentId\":\"" + resume
                + "\",\"coverLetterDocumentId\":\"" + letter + "\",\"screeningAnswersDocumentId\":\"" + answers + "\"}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.packId").value(pack.toString()))
                .andExpect(jsonPath("$.resumeDocumentId").value(resume.toString()))
                .andExpect(jsonPath("$.coverLetterDocumentId").value(letter.toString()))
                .andExpect(jsonPath("$.screeningAnswersDocumentId").value(answers.toString()));
    }

    @Test
    void aDocumentThatIsNotTheCallersApprovedDocumentOfThatTypeIsRefused() throws Exception {
        Session me = newSession();
        Candidate mine = seed(me);
        Session other = newSession();
        Candidate theirs = seed(other);
        UUID job = newJob();
        UUID theirResume = approvedDocument(theirs, "TAILORED_RESUME", job);
        UUID myDraft = draftDocument(mine, "TAILORED_RESUME", job);
        UUID myLetter = approvedDocument(mine, "COVER_LETTER", job);

        // someone else's, a draft, a document of another type, one that does not exist
        for (UUID bad : List.of(theirResume, myDraft, myLetter, UUID.randomUUID())) {
            create(me, "{\"jobId\":\"" + job + "\",\"resumeDocumentId\":\"" + bad + "\"}")
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_document"));
        }
        assertThat(rows("applications", mine.userId())).isZero();
    }

    @Test
    void aPackOfSomeoneElseIsRefusedAndSoIsADocumentMadeForAnotherJob() throws Exception {
        Session me = newSession();
        Candidate mine = seed(me);
        Session other = newSession();
        Candidate theirs = seed(other);
        UUID job = newJob();
        UUID otherJob = newJob();

        create(me, "{\"jobId\":\"" + job + "\",\"packId\":\"" + packFor(theirs, job) + "\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_pack"));
        create(me, "{\"jobId\":\"" + job + "\",\"packId\":\"" + packFor(mine, otherJob) + "\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("document_job_mismatch"));
        create(me, "{\"jobId\":\"" + job + "\",\"coverLetterDocumentId\":\""
                + approvedDocument(mine, "COVER_LETTER", otherJob) + "\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("document_job_mismatch"));
    }

    // --- read and list ---

    @Test
    void getReturnsTheApplicationWithItsEventsAndReminders() throws Exception {
        Session me = newSession();
        String id = manual(me, "Dev");
        moveTo(me, id, "SCREENING").andExpect(status().isOk());
        addReminder(me, id, java.time.Instant.now().plus(java.time.Duration.ofDays(3)), null)
                .andExpect(status().isCreated());

        getAs(me, "/applications/" + id).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.status").value("SCREENING")).andExpect(jsonPath("$.events.length()").value(2))
                .andExpect(jsonPath("$.reminders.length()").value(1))
                .andExpect(jsonPath("$.reminders[0].state").value("PENDING"))
                .andExpect(jsonPath("$.nextReminderAt").isString());
    }

    @Test
    void theListFiltersByStatusAndTheBoardGroupsEveryColumn() throws Exception {
        Session me = newSession();
        String saved = idOf(create(me, "{\"title\":\"A\",\"status\":\"SAVED\"}"));
        String applied = manual(me, "B");
        String interview = manual(me, "C");
        moveTo(me, interview, "INTERVIEW").andExpect(status().isOk());

        // flat, newest status change first, with counts of everything
        String all = body(getAs(me, "/applications"));
        assertThat(JsonPath.<List<String>>read(all, "$.items[*].id")).containsExactly(interview, applied, saved);
        assertThat(JsonPath.<Integer>read(all, "$.counts.SAVED")).isEqualTo(1);
        assertThat(JsonPath.<Integer>read(all, "$.counts.APPLIED")).isEqualTo(1);
        assertThat(JsonPath.<Integer>read(all, "$.counts.INTERVIEW")).isEqualTo(1);
        assertThat(JsonPath.<Integer>read(all, "$.counts.OFFER")).isZero();
        assertThat(JsonPath.<Boolean>read(all, "$.truncated")).isFalse();

        // filter: one or several statuses; counts still cover everything
        String filtered = body(getAs(me, "/applications?status=SAVED&status=INTERVIEW"));
        assertThat(JsonPath.<List<String>>read(filtered, "$.items[*].id")).containsExactly(interview, saved);
        assertThat(JsonPath.<Integer>read(filtered, "$.counts.APPLIED")).isEqualTo(1);

        // the board has every column, in order, empty ones too
        String board = body(getAs(me, "/applications?grouped=true"));
        assertThat(JsonPath.<java.util.Map<String, Object>>read(board, "$.board").keySet()).containsExactly("SAVED",
                "APPLIED", "SCREENING", "INTERVIEW", "OFFER", "REJECTED", "WITHDRAWN");
        assertThat(JsonPath.<List<String>>read(board, "$.board.INTERVIEW[*].id")).containsExactly(interview);
        assertThat(JsonPath.<List<String>>read(board, "$.board.OFFER[*].id")).isEmpty();
        assertThat(board).doesNotContain("\"items\"");

        String oneColumn = body(getAs(me, "/applications?grouped=true&status=APPLIED"));
        assertThat(JsonPath.<java.util.Map<String, Object>>read(oneColumn, "$.board").keySet())
                .containsExactly("APPLIED");
    }

    @Test
    void theLimitBoundsTheListAndSaysSo() throws Exception {
        Session me = newSession();
        manual(me, "A");
        manual(me, "B");

        String page = body(getAs(me, "/applications?limit=1"));
        assertThat(JsonPath.<List<?>>read(page, "$.items")).hasSize(1);
        assertThat(JsonPath.<Boolean>read(page, "$.truncated")).isTrue();
        getAs(me, "/applications?limit=0").andExpect(status().isBadRequest());
        getAs(me, "/applications?status=NOPE").andExpect(status().isBadRequest());
    }

    // --- ownership ---

    @Test
    void someoneElsesApplicationIsNotFoundForEveryOperation() throws Exception {
        Session mine = newSession();
        Session other = newSession();
        String id = manual(mine, "Mine");
        String reminder = idOf(addReminder(mine, id, java.time.Instant.now().plus(java.time.Duration.ofDays(2)), null));

        getAs(other, "/applications/" + id).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("application_not_found"));
        patchApp(other, id, "{\"notes\":\"hijack\"}").andExpect(status().isNotFound());
        moveTo(other, id, "OFFER").andExpect(status().isNotFound());
        deleteAs(other, "/applications/" + id).andExpect(status().isNotFound());
        getAs(other, "/applications/" + id + "/reminders").andExpect(status().isNotFound());
        addReminder(other, id, java.time.Instant.now().plus(java.time.Duration.ofDays(2)), null)
                .andExpect(status().isNotFound());
        deleteAs(other, "/applications/" + id + "/reminders/" + reminder).andExpect(status().isNotFound());
        draft(other, id, null).andExpect(status().isNotFound());

        // and none of it changed anything of mine
        getAs(mine, "/applications/" + id).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPLIED"))
                .andExpect(jsonPath("$.notes").doesNotExist()).andExpect(jsonPath("$.reminders[0].state").value("PENDING"));
        assertThat(JsonPath.<List<?>>read(body(getAs(other, "/applications")), "$.items")).isEmpty();
        assertThat(JsonPath.<Integer>read(body(getAs(other, "/applications")), "$.counts.APPLIED")).isZero();
    }

    @Test
    void aReminderOfAnotherApplicationIsNotFoundThroughThisOne() throws Exception {
        Session me = newSession();
        String one = manual(me, "One");
        String two = manual(me, "Two");
        String reminder = idOf(addReminder(me, two, java.time.Instant.now().plus(java.time.Duration.ofDays(2)), null));

        deleteAs(me, "/applications/" + one + "/reminders/" + reminder).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("reminder_not_found"));
        assertThat(reminderState(reminder)).isEqualTo("PENDING");
    }

    // --- change and delete ---

    @Test
    void patchChangesTheFieldsGivenAndAnEmptyStringClearsOne() throws Exception {
        Session me = newSession();
        String id = idOf(create(me, "{\"title\":\"Dev\",\"company\":\"Acme\",\"url\":\"https://acme.example/1\","
                + "\"notes\":\"first\"}"));

        patchApp(me, id, "{\"notes\":\"second\"}").andExpect(status().isOk()).andExpect(jsonPath("$.notes").value("second"))
                .andExpect(jsonPath("$.title").value("Dev")).andExpect(jsonPath("$.company").value("Acme"))
                .andExpect(jsonPath("$.url").value("https://acme.example/1"));
        patchApp(me, id, "{\"title\":\"Senior Dev\",\"company\":\"\",\"url\":\"\",\"notes\":\"\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.title").value("Senior Dev"))
                .andExpect(jsonPath("$.company").doesNotExist()).andExpect(jsonPath("$.url").doesNotExist())
                .andExpect(jsonPath("$.notes").doesNotExist());
        patchApp(me, id, "{\"title\":\" \"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("title_required"));
        patchApp(me, id, "{\"url\":\"nope\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_url"));
        patchApp(me, id, "{\"appliedAt\":\"2026-02-03T04:05:06Z\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.appliedAt").value("2026-02-03T04:05:06Z"));
        patchApp(me, id, "{\"appliedAt\":\"2999-01-01T00:00:00Z\"}").andExpect(status().isBadRequest());
    }

    @Test
    void theTitleAndCompanyOfAnApplicationFromAJobAreFixedButItsNotesAndUrlAreNot() throws Exception {
        Session me = newSession();
        String id = fromJob(me, newJob());

        patchApp(me, id, "{\"title\":\"Renamed\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("job_fields_fixed"));
        patchApp(me, id, "{\"company\":\"Renamed Inc\"}").andExpect(status().isConflict());
        // sending them back unchanged is not a change
        patchApp(me, id, "{\"title\":\"Staff Backend Engineer\",\"company\":\"Acme Test Co\",\"notes\":\"n\","
                + "\"url\":\"https://acme.example/apply\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.notes").value("n")).andExpect(jsonPath("$.url").value("https://acme.example/apply"));
    }

    @Test
    void appliedAtCannotBeSetOnAnApplicationThatIsOnlySaved() throws Exception {
        Session me = newSession();
        String id = idOf(create(me, "{\"title\":\"Dev\",\"status\":\"SAVED\"}"));

        patchApp(me, id, "{\"appliedAt\":\"2026-02-03T04:05:06Z\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("not_applied_yet"));
    }

    @Test
    void deleteRemovesTheApplicationWithItsHistoryAndReminders() throws Exception {
        Session me = newSession();
        UUID userId = userIdOf(me);
        String id = manual(me, "Dev");
        moveTo(me, id, "INTERVIEW").andExpect(status().isOk());
        addReminder(me, id, java.time.Instant.now().plus(java.time.Duration.ofDays(2)), null)
                .andExpect(status().isCreated());

        deleteAs(me, "/applications/" + id).andExpect(status().isNoContent());

        getAs(me, "/applications/" + id).andExpect(status().isNotFound());
        deleteAs(me, "/applications/" + id).andExpect(status().isNotFound());
        assertThat(rows("applications", userId)).isZero();
        assertThat(rows("application_events", userId)).isZero();
        assertThat(rows("reminders", userId)).isZero();
    }
}
