package com.jobfinder.core.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.jobfinder.core.storage.UploadScanner;
import com.jobfinder.core.storage.UploadScannerUnavailableException;

/**
 * Work item 5, the scanner port: it runs on every upload before anything is stored or queued, an infected file is
 * refused, and a scanner that is down refuses the upload too (fail-closed is the default). Nothing reaches object storage,
 * the database or the parse queue in either case.
 */
class UploadScannerIntegrationTests extends ResumeTestSupport {

    @MockitoBean
    UploadScanner scanner;

    @Test
    void aCleanFileIsScannedBeforeItIsStored() throws Exception {
        when(scanner.scan(any())).thenReturn(UploadScanner.Verdict.clean());
        Session session = newSession();

        upload(session, "cv.pdf", "application/pdf", pdf()).andExpect(status().isCreated());

        verify(scanner).scan(pdf());
    }

    @Test
    void anInfectedFileIsRefusedAndNothingIsStoredOrQueued() throws Exception {
        when(scanner.scan(any())).thenReturn(UploadScanner.Verdict.infected("Eicar-Test-Signature"));
        Session session = newSession();
        String prefix = "resumes/" + userIdOf(session.accessToken()) + "/";

        upload(session, "cv.pdf", "application/pdf", pdf()).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("upload_infected"))
                // The signature stays in the log; the user is told only that the file was rejected.
                .andExpect(r -> assertThat(r.getResponse().getContentAsString()).doesNotContain("Eicar"));

        assertThat(count("select count(*) from resumes where user_id = ?", userIdOf(session.accessToken()))).isZero();
        assertThat(objectsUnder(prefix)).isZero();
    }

    @Test
    void aScannerThatIsDownRefusesTheUploadByDefault() throws Exception {
        when(scanner.scan(any())).thenThrow(new UploadScannerUnavailableException("down", null));
        Session session = newSession();
        String prefix = "resumes/" + userIdOf(session.accessToken()) + "/";

        upload(session, "cv.pdf", "application/pdf", pdf()).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("upload_scan_unavailable"));

        assertThat(count("select count(*) from resumes where user_id = ?", userIdOf(session.accessToken()))).isZero();
        assertThat(objectsUnder(prefix)).isZero();
    }

    @Test
    void filesThatFailTheStructuralChecksNeverReachTheScanner() throws Exception {
        Session session = newSession();

        upload(session, "notes.txt", "text/plain", pdf()).andExpect(status().isUnsupportedMediaType());
        upload(session, "cv.pdf", "application/pdf", new byte[0]).andExpect(status().isBadRequest());

        verify(scanner, never()).scan(any());
    }
}
