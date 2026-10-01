package com.jobfinder.core.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * An approved document is immutable in the database itself, not only in the API: a direct UPDATE or DELETE is refused
 * by the trigger, a document with a blocking flag cannot be stored as approved, drafts stay deletable, and account
 * deletion (the one purge path) still erases approved documents.
 */
class ApprovedDocumentImmutabilityTests extends DocumentsTestSupport {

    @Autowired
    private TransactionTemplate tx;

    private record Approved(Session session, Candidate candidate, String id) {
    }

    private Approved approvedDocument() throws Exception {
        Session me = newSession();
        Candidate candidate = seed(me);
        stubFactCheck(candidate);
        stubTailor(candidate.userId(), tailorOk(UUID.randomUUID(), "0.003"));
        String id = idOf(tailor(me, newJob()).andExpect(status().isCreated()));
        approve(me, id).andExpect(status().isOk());
        return new Approved(me, candidate, id);
    }

    @Test
    void aDirectUpdateOfAnApprovedRowIsRefused() throws Exception {
        Approved a = approvedDocument();
        String content = jdbc.queryForObject("select content::text from generated_documents where id = ?::uuid",
                String.class, a.id());

        assertThatThrownBy(() -> jdbc.update(
                "update generated_documents set content = '{\"tampered\":true}'::jsonb where id = ?::uuid", a.id()))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("immutable");
        assertThatThrownBy(() -> jdbc.update("update generated_documents set status = 'DRAFT', approved_at = null "
                + "where id = ?::uuid", a.id())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update generated_documents set job_title = 'x' where id = ?::uuid",
                a.id())).isInstanceOf(DataIntegrityViolationException.class);

        assertThat(jdbc.queryForObject("select content::text from generated_documents where id = ?::uuid",
                String.class, a.id())).isEqualTo(content);
        assertThat(statusOf(a.id())).isEqualTo("APPROVED");
    }

    @Test
    void aDirectDeleteOfAnApprovedRowIsRefused() throws Exception {
        Approved a = approvedDocument();

        assertThatThrownBy(() -> jdbc.update("delete from generated_documents where id = ?::uuid", a.id()))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("immutable");

        assertThat(documents(a.candidate().userId())).isEqualTo(1);
    }

    @Test
    void aBlockingFlagCannotBeStoredOnAnApprovedRow() throws Exception {
        Session me = newSession();
        Candidate candidate = seed(me);
        UUID job = newJob();

        assertThatThrownBy(() -> jdbc.update("""
                insert into generated_documents (id, user_id, type, status, job_id, job_title, base_resume_version_id,
                        prompt_version, source_content, content, changes, fact_check, version, created_at, updated_at,
                        approved_at)
                values (?, ?, 'TAILORED_RESUME', 'APPROVED', ?, 't', ?, 'tailor_resume/v1', '{}'::jsonb, '{}'::jsonb,
                        '[]'::jsonb, '{"passed":false,"blocking":1,"warnings":0,"flags":[]}'::jsonb, 1, now(), now(),
                        now())
                """, UUID.randomUUID(), candidate.userId(), job, candidate.versionId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(documents(candidate.userId())).isZero();
    }

    @Test
    void aDraftCanStillBeUpdatedAndDeletedInTheDatabase() throws Exception {
        Session me = newSession();
        Candidate candidate = seed(me);
        stubFactCheck(candidate);
        stubTailor(candidate.userId(), tailorOk(UUID.randomUUID(), "0.003"));
        String id = idOf(tailor(me, newJob()).andExpect(status().isCreated()));

        assertThat(jdbc.update("update generated_documents set job_title = 'renamed' where id = ?::uuid", id))
                .isEqualTo(1);
        assertThat(jdbc.update("delete from generated_documents where id = ?::uuid", id)).isEqualTo(1);
    }

    @Test
    void theApiAndTheDatabaseAgreeThatApprovingTwiceChangesNothing() throws Exception {
        Approved a = approvedDocument();
        String approvedAt = jdbc.queryForObject("select approved_at::text from generated_documents where id = ?::uuid",
                String.class, a.id());

        approve(a.session(), a.id()).andExpect(status().isConflict());

        assertThat(jdbc.queryForObject("select approved_at::text from generated_documents where id = ?::uuid",
                String.class, a.id())).isEqualTo(approvedAt);
    }

    @Test
    void theDeletionPurgeSettingIsLocalToTheTransactionThatSetsIt() throws Exception {
        Approved a = approvedDocument();

        tx.executeWithoutResult(s -> {
            jdbc.queryForObject("select set_config('app.purge_documents', 'on', true)", String.class);
            assertThat(jdbc.update("delete from generated_documents where id = ?::uuid", a.id())).isEqualTo(1);
            s.setRollbackOnly();
        });

        // Rolled back, and the setting did not leak to the next statement: the row is protected again.
        assertThat(documents(a.candidate().userId())).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update("delete from generated_documents where id = ?::uuid", a.id()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deletingTheAccountErasesItsApprovedDocuments() throws Exception {
        Approved a = approvedDocument();

        mvc.perform(delete("/me").header("Authorization", bearer(a.session()))).andExpect(status().isNoContent());

        assertThat(documents(a.candidate().userId())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from users where id = ?", Integer.class,
                a.candidate().userId())).isZero();
    }
}
