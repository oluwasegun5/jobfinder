package com.jobfinder.core.ingestion.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import com.jobfinder.core.ingestion.IngestionRunStatus;
import com.jobfinder.core.ingestion.SourceKind;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * The alert rules and their failure modes without a database or a mail server: no recipients (log only), a mail
 * server that refuses (retry on the next run, never an exception), and a recipient list with junk in it. The
 * end-to-end behaviour is in {@code SourceAlertTests}.
 */
class SourceAlertsTests {

    private final UUID sourceId = UUID.randomUUID();
    private final SourceAlertStore store = mock(SourceAlertStore.class);
    private final IngestionRunStore runs = mock(IngestionRunStore.class);
    private final JavaMailSender mail = mock(JavaMailSender.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    @BeforeEach
    void anEmptyRunOverTenTargetsAfterABusyOne() {
        when(runs.previousCleanFetched(eq(sourceId), any())).thenReturn(Optional.of(40));
        when(store.find(eq(sourceId), any())).thenReturn(Optional.empty());
    }

    private SourceAlerts alerts(List<String> recipients) {
        var defaults = new IngestionProperties.Defaults(360, 900, 2, 3, Duration.ofSeconds(1), Duration.ofSeconds(30),
                50, 5, Duration.ofMinutes(5));
        var config = new IngestionProperties.Alerts(recipients, "no-reply@jobfinder.local", "https://jobs.example.test/",
                0.2, 5, 5, Duration.ofHours(24));
        var properties = new IngestionProperties(new IngestionProperties.Scheduler(true), Duration.ofMinutes(30),
                defaults, new IngestionProperties.Expiry(2, Duration.ofDays(45)),
                new IngestionProperties.Seed(false, "classpath:none"), config);
        return new SourceAlerts(store, runs, mail, meters, properties);
    }

    private SourceAlerts.RunOutcome emptyRun() {
        var source = new SourceStore.SourceRow(sourceId, "GREENHOUSE", SourceKind.ATS, true, null, SourceHealth.HEALTHY,
                null);
        Instant now = Instant.now();
        return new SourceAlerts.RunOutcome(source, true, UUID.randomUUID(), now.minusSeconds(5),
                IngestionRunStatus.SUCCEEDED, 10, new IngestionRunStore.Counts(0, 0, 0, 0, 0), now);
    }

    private void openedAlertIsReadBack() {
        var row = new SourceAlertStore.AlertRow(sourceId, AlertRule.ZERO_JOBS, true, Instant.now(), null, null, 1, 0,
                "detail");
        when(store.find(eq(sourceId), eq(AlertRule.ZERO_JOBS))).thenReturn(Optional.empty(), Optional.of(row));
    }

    @Test
    void withoutRecipientsAnAlertIsOnlyLoggedAndStillThrottled() {
        openedAlertIsReadBack();

        assertThatCode(() -> alerts(List.of()).evaluate(emptyRun())).doesNotThrowAnyException();

        verify(mail, never()).send(any(SimpleMailMessage.class));
        verify(store).markNotified(eq(sourceId), eq(AlertRule.ZERO_JOBS), any());
        assertThat(meters.get("ingestion.alerts.fired").tags("source", "GREENHOUSE", "rule", "ZERO_JOBS",
                "delivery", "log").counter().count()).isEqualTo(1);
    }

    @Test
    void aMailServerThatRefusesNeverFailsTheRunAndTheAlertIsRetriedNextTime() {
        openedAlertIsReadBack();
        org.mockito.Mockito.doThrow(new MailSendException("connection refused")).when(mail)
                .send(any(SimpleMailMessage.class));

        assertThatCode(() -> alerts(List.of("ops@example.test")).evaluate(emptyRun())).doesNotThrowAnyException();

        verify(store, never()).markNotified(any(), any(), any());
        assertThat(meters.get("ingestion.alerts.fired").tags("delivery", "failed").counter().count()).isEqualTo(1);
    }

    @Test
    void aFailingStoreNeverEscapesTheEvaluation() {
        when(store.find(eq(sourceId), any())).thenThrow(new IllegalStateException("database is down"));

        assertThatCode(() -> alerts(List.of("ops@example.test")).evaluate(emptyRun())).doesNotThrowAnyException();

        verify(mail, never()).send(any(SimpleMailMessage.class));
    }

    @Test
    void junkInTheRecipientListIsDroppedAndTheMailGoesToTheRest() {
        openedAlertIsReadBack();

        alerts(List.of(" ops@example.test ", "not an address", "a@b.test\nBcc: evil@example.test", "", "ops@example.test"))
                .evaluate(emptyRun());

        var sent = org.mockito.ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mail).send(sent.capture());
        assertThat(sent.getValue().getTo()).containsExactly("ops@example.test");
        assertThat(sent.getValue().getText()).contains("https://jobs.example.test/admin/ingestion");
    }
}
