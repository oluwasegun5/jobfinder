package com.jobfinder.core.interview.internal;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.jobfinder.core.applications.ApplicationLookup;
import com.jobfinder.core.applications.ApplicationLookup.ApplicationRef;
import com.jobfinder.core.billing.AiUsageGate;
import com.jobfinder.core.interview.MockInterviews;
import com.jobfinder.core.interview.internal.AiMockInterviewClient.AnswerIn;
import com.jobfinder.core.interview.internal.AiMockInterviewClient.MockUnavailableException;
import com.jobfinder.core.interview.internal.AiMockInterviewClient.NextQuestion;
import com.jobfinder.core.interview.internal.AiMockInterviewClient.PrepQuestion;
import com.jobfinder.core.interview.internal.AiMockInterviewClient.Summary;
import com.jobfinder.core.interview.internal.AiMockInterviewClient.SummaryTurnIn;
import com.jobfinder.core.interview.internal.AiMockInterviewClient.Turn;
import com.jobfinder.core.interview.internal.InterviewStore.Row;
import com.jobfinder.core.interview.internal.MockInterviewStore.SessionRow;
import com.jobfinder.core.interview.internal.MockInterviewStore.TurnRow;
import com.jobfinder.core.jobs.JobBriefSource;
import com.jobfinder.core.jobs.JobForBrief;
import com.jobfinder.core.shared.ApiException;

/**
 * Text mock interviews (docs/adr/0034-mock-interview.md).
 *
 * <p><b>State machine, enforced here and by the schema.</b> A session is ACTIVE until it is COMPLETED (by the last answer
 * or by {@code complete}) or ABANDONED (no activity for {@code abandon-after}, noticed lazily on the next access). Both
 * end states are final: an answer to a COMPLETED session is a 409, so is anything to an ABANDONED one, and completing a
 * completed session returns it unchanged.
 *
 * <p><b>One request at a time.</b> An answer, and a completion, first take the session's single in-flight slot with one
 * conditional UPDATE; a second concurrent request finds it taken and gets 409 {@code answer_in_flight}. The slot is
 * given back when the result is stored, or on any failure, and a holder that died is replaced after
 * {@code in-flight-timeout}.
 *
 * <p><b>No transaction around a model call.</b> The order is: look up, take the slot, check the daily cap, call
 * ai-service (usage recorded in the ledger as the calls come back), then store the turn, the next question and the
 * session's counters in one short transaction.
 *
 * <p><b>Idempotent answers.</b> The client's key is stored with the candidate turn (unique per session); a repeat of the
 * key returns the stored turn, its feedback, the question that followed and the summary if there is one, calling no
 * model and recording no usage, so it cannot be charged twice.
 *
 * <p><b>Metering.</b> The daily cap is checked before every call; each call is recorded by {@link AiMockInterviewClient};
 * the credits of what the ledger recorded accumulate on the session (also for calls that were billed and discarded).
 */
@Service
class MockInterviewService implements MockInterviews {

    private static final Logger log = LoggerFactory.getLogger(MockInterviewService.class);
    private static final int MAX_PAGE_SIZE = 100;

    private final MockInterviewStore store;
    private final InterviewStore preps;
    private final AiMockInterviewClient ai;
    private final JobBriefSource jobs;
    private final ApplicationLookup applications;
    private final AiUsageGate gate;
    private final MockInterviewProperties properties;
    private final TransactionTemplate tx;
    private final Clock clock;

    MockInterviewService(MockInterviewStore store, InterviewStore preps, AiMockInterviewClient ai, JobBriefSource jobs,
            ApplicationLookup applications, AiUsageGate gate, MockInterviewProperties properties,
            TransactionTemplate tx, Clock clock) {
        this.store = store;
        this.preps = preps;
        this.ai = ai;
        this.jobs = jobs;
        this.applications = applications;
        this.gate = gate;
        this.properties = properties;
        this.tx = tx;
        this.clock = clock;
    }

    // --- start ---

    @Override
    public SessionView start(UUID userId, StartCommand command) {
        JobForBrief job = jobs.job(command.jobId()).orElseThrow(
                () -> new ApiException(HttpStatus.NOT_FOUND, "job_not_found", "Job not found."));
        if (command.applicationId() != null) {
            ApplicationRef application = applications.owned(userId, command.applicationId()).orElseThrow(
                    () -> new ApiException(HttpStatus.NOT_FOUND, "application_not_found", "Application not found."));
            if (application.jobId() != null && !application.jobId().equals(job.id())) {
                throw new ApiException(HttpStatus.CONFLICT, "application_job_mismatch",
                        "That application is for a different job.");
            }
        }
        List<PrepQuestion> prepQuestions = List.of();
        if (command.prepId() != null) {
            Row prep = preps.find(userId, command.prepId()).filter(Row::ready)
                    .orElseThrow(InterviewService::notFound);
            if (!prep.jobId().equals(job.id())) {
                throw new ApiException(HttpStatus.CONFLICT, "prep_job_mismatch",
                        "That interview prep is for a different job.");
            }
            prepQuestions = preps.questions(prep.id()).stream()
                    .map(q -> new PrepQuestion(q.category(), q.question())).toList();
        }
        int maxTurns = command.maxTurns() == null ? properties.maxTurns() : command.maxTurns();
        if (maxTurns < 1 || maxTurns > properties.maxTurns()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_max_turns",
                    "A mock interview has between 1 and " + properties.maxTurns() + " questions.");
        }
        PersonaView persona = PersonaPicker.pick(job.title(), job.seniority());

        NextQuestion opening;
        BigDecimal credits = BigDecimal.ZERO;
        if (!prepQuestions.isEmpty()) {
            PrepQuestion first = prepQuestions.get(0);
            opening = new NextQuestion(first.category(), first.question(), "PREP");
        } else {
            gate.requireAllowance(userId, AiMockInterviewClient.TURN_FEATURE);
            try {
                Turn turn = ai.turn(userId, persona, job, null, true, List.of(), List.of());
                opening = turn.next();
                credits = turn.credits();
            } catch (MockUnavailableException e) {
                log.warn("Mock interview could not start: {}", e.getMessage());
                throw unavailable();
            }
        }
        UUID id = UUID.randomUUID();
        Instant now = Instant.now(clock);
        BigDecimal spent = credits;
        NextQuestion first = opening;
        tx.executeWithoutResult(status -> {
            store.insertSession(id, userId, job.id(), clip(job.title(), 400), clip(job.company(), 400),
                    command.applicationId(), command.prepId(), persona, maxTurns, properties.promptVersion(), spent,
                    now);
            store.insertInterviewerTurn(id, 0, first.question(), first.category(), first.source(), now);
        });
        return view(find(userId, id), store.turns(id));
    }

    // --- read ---

    @Override
    public SessionView get(UUID userId, UUID sessionId) {
        sweep(userId);
        SessionRow row = find(userId, sessionId);
        return view(row, store.turns(sessionId));
    }

    @Override
    public SessionPage list(UUID userId, int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_page",
                    "page must be 0 or more and size between 1 and " + MAX_PAGE_SIZE + ".");
        }
        sweep(userId);
        long total = store.count(userId);
        List<SessionSummary> items = store.page(userId, size, (long) page * size).stream().map(r -> new SessionSummary(
                r.id(), r.jobId(), r.jobTitle(), r.jobCompany(), r.applicationId(), r.prepId(), r.status(),
                r.maxTurns(), r.turnsAnswered(), r.creditsConsumed(),
                r.summary() == null ? null : r.summary().averages().overall(), r.createdAt(), r.completedAt()))
                .toList();
        return new SessionPage(items, page, size, total, (int) ((total + size - 1) / size));
    }

    // --- answer ---

    @Override
    public AnswerResult answer(UUID userId, UUID sessionId, String answer, String idempotencyKey) {
        sweep(userId);
        SessionRow row = find(userId, sessionId);

        Optional<TurnRow> stored = store.turnByKey(sessionId, idempotencyKey);
        if (stored.isPresent()) {
            if (!stored.get().content().equals(answer)) {
                throw new ApiException(HttpStatus.CONFLICT, "idempotency_key_reused",
                        "That idempotency key was used for a different answer.");
            }
            return replay(row, stored.get());
        }
        if (answer.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "answer_empty", "Write an answer first.");
        }
        if (answer.length() > properties.maxAnswerChars()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "answer_too_long",
                    "An answer can be at most " + properties.maxAnswerChars() + " characters.");
        }
        requireActive(row);
        if (!acquire(userId, sessionId, idempotencyKey)) {
            requireActive(find(userId, sessionId));
            throw inFlight();
        }
        try {
            SessionRow current = find(userId, sessionId);
            requireActive(current);
            List<TurnRow> turns = store.turns(sessionId);
            if (current.turnsAnswered() >= current.maxTurns()) {
                throw new ApiException(HttpStatus.CONFLICT, "turn_limit_reached",
                        "Every question has been answered. Finish the session to get the summary.");
            }
            TurnRow open = turns.get(turns.size() - 1);
            if (!open.interviewer() || open.position() != 2 * current.turnsAnswered()) {
                throw new ApiException(HttpStatus.CONFLICT, "answer_in_flight",
                        "This session has no open question right now. Try again in a moment.");
            }
            boolean last = current.turnsAnswered() + 1 >= current.maxTurns();
            List<String> asked = turns.stream().filter(TurnRow::interviewer).map(TurnRow::content).toList();
            List<PrepQuestion> remaining = remainingPrep(userId, current, asked);
            JobForBrief job = job(current);

            // Checked before the call, for a call that will really be made.
            gate.requireAllowance(userId, AiMockInterviewClient.TURN_FEATURE);
            Turn turn;
            try {
                turn = ai.turn(userId, current.persona(), job,
                        new AnswerIn(open.content(), open.category(), answer), !last, asked, remaining);
            } catch (MockUnavailableException e) {
                store.addCredits(sessionId, e.credits());
                log.warn("Mock interview turn failed: {}", e.getMessage());
                throw unavailable();
            }

            int position = open.position() + 1;
            Instant now = Instant.now(clock);
            boolean stayLocked = last;
            Boolean saved = tx.execute(status -> {
                store.insertCandidateTurn(sessionId, position, answer, turn.feedback(), idempotencyKey, now);
                if (turn.next() != null) {
                    store.insertInterviewerTurn(sessionId, position + 1, turn.next().question(),
                            turn.next().category(), turn.next().source(), now);
                }
                if (!store.recordAnswered(sessionId, idempotencyKey, turn.credits(), now, !stayLocked)) {
                    status.setRollbackOnly();
                    return false;
                }
                return true;
            });
            if (!Boolean.TRUE.equals(saved)) {
                // The slot was taken over as stale while the model worked; the call's credits are still the session's.
                store.addCredits(sessionId, turn.credits());
                throw inFlight();
            }
            if (last) {
                // The slot is still ours: make the summary in the same request.
                complete(userId, sessionId, idempotencyKey, true);
            }
            SessionRow after = find(userId, sessionId);
            List<TurnRow> all = store.turns(sessionId);
            TurnRow candidate = all.get(position);
            return new AnswerResult(turnView(candidate), turn.next() == null ? null : turnView(all.get(position + 1)),
                    summaryOf(after), view(after, all));
        } finally {
            // A no-op when the slot was already given back by storing the result (it matches this key only).
            store.release(sessionId, idempotencyKey);
        }
    }

    /** What was stored for a key already used: no model call, no usage, nothing to charge. */
    private AnswerResult replay(SessionRow row, TurnRow candidate) {
        List<TurnRow> turns = store.turns(row.id());
        TurnRow next = turns.stream().filter(t -> t.position() == candidate.position() + 1).findFirst().orElse(null);
        return new AnswerResult(turnView(candidate), next == null ? null : turnView(next), summaryOf(row),
                view(row, turns));
    }

    // --- complete ---

    @Override
    public SessionView complete(UUID userId, UUID sessionId) {
        sweep(userId);
        SessionRow row = find(userId, sessionId);
        if ("COMPLETED".equals(row.status())) {
            return view(row, store.turns(sessionId));
        }
        requireActive(row);
        if (row.turnsAnswered() == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "nothing_to_summarise",
                    "Answer at least one question before finishing.");
        }
        String key = "complete-" + UUID.randomUUID();
        if (!acquire(userId, sessionId, key)) {
            SessionRow again = find(userId, sessionId);
            if ("COMPLETED".equals(again.status())) {
                return view(again, store.turns(sessionId));
            }
            requireActive(again);
            throw inFlight();
        }
        try {
            SessionRow current = find(userId, sessionId);
            if ("COMPLETED".equals(current.status())) {
                return view(current, store.turns(sessionId));
            }
            requireActive(current);
            complete(userId, sessionId, key, false);
        } finally {
            store.release(sessionId, key);
        }
        return view(find(userId, sessionId), store.turns(sessionId));
    }

    /**
     * Makes the summary of the stored turns and completes the session; the caller holds the in-flight slot under
     * {@code key}. When {@code auto} (reached after the last answer) a failure leaves the session ACTIVE for
     * {@link #complete(UUID, UUID)} to retry and is not an error of the answer; otherwise it is a 503, or the cap's 429.
     */
    private void complete(UUID userId, UUID sessionId, String key, boolean auto) {
        SessionRow current = find(userId, sessionId);
        try {
            gate.requireAllowance(userId, AiMockInterviewClient.SUMMARY_FEATURE);
        } catch (ApiException cap) {
            if (auto) {
                store.release(sessionId, key);
                log.info("Mock interview summary postponed: {}", cap.code());
                return;
            }
            throw cap;
        }
        List<TurnRow> turns = store.turns(sessionId);
        List<SummaryTurnIn> answered = new ArrayList<>();
        for (TurnRow t : turns) {
            if (!t.interviewer() && t.position() > 0) {
                TurnRow question = turns.get(t.position() - 1);
                answered.add(new SummaryTurnIn(question.content(), question.category(), t.feedback()));
            }
        }
        Summary summary;
        try {
            summary = ai.summarize(userId, current.persona(), job(current), answered,
                    current.turnsAnswered() < current.maxTurns());
        } catch (MockUnavailableException e) {
            store.addCredits(sessionId, e.credits());
            log.warn("Mock interview summary failed: {}", e.getMessage());
            if (auto) {
                store.release(sessionId, key);
                return;
            }
            throw unavailable();
        }
        Instant now = Instant.now(clock);
        Boolean done = tx.execute(status -> store.complete(sessionId, key, summary.summary(), summary.credits(), now));
        if (!Boolean.TRUE.equals(done)) {
            store.addCredits(sessionId, summary.credits());
            throw inFlight();
        }
    }

    // --- pieces ---

    private boolean acquire(UUID userId, UUID sessionId, String key) {
        Instant now = Instant.now(clock);
        return store.acquire(userId, sessionId, key, now, now.minus(properties.inFlightTimeout()));
    }

    /** Turns this user's sessions with no activity for the configured period into ABANDONED. */
    private void sweep(UUID userId) {
        Instant now = Instant.now(clock);
        int n = store.abandonStale(userId, now.minus(properties.abandonAfter()),
                now.minus(properties.inFlightTimeout()));
        if (n > 0) {
            log.info("Abandoned {} idle mock interview session(s)", n);
        }
    }

    private SessionRow find(UUID userId, UUID sessionId) {
        return store.find(userId, sessionId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                "interview_session_not_found", "Interview session not found."));
    }

    private static void requireActive(SessionRow row) {
        if ("COMPLETED".equals(row.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "interview_session_completed",
                    "This interview session is finished.");
        }
        if ("ABANDONED".equals(row.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "interview_session_abandoned",
                    "This interview session was left idle too long and cannot be resumed. Start a new one.");
        }
    }

    private static ApiException inFlight() {
        return new ApiException(HttpStatus.CONFLICT, "answer_in_flight",
                "Another answer for this session is still being processed. Try again in a moment.");
    }

    private static ApiException unavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "mock_interview_unavailable",
                "The mock interview is unavailable right now. Try again in a moment.");
    }

    /** The prep's questions the session has not asked, for ai-service to take the next one from. */
    private List<PrepQuestion> remainingPrep(UUID userId, SessionRow session, List<String> asked) {
        if (session.prepId() == null) {
            return List.of();
        }
        List<String> folded = asked.stream().map(AiMockInterviewClient::fold).toList();
        return preps.find(userId, session.prepId()).filter(Row::ready).map(prep -> preps.questions(prep.id()).stream()
                .filter(q -> !folded.contains(AiMockInterviewClient.fold(q.question())))
                .map(q -> new PrepQuestion(q.category(), q.question())).toList()).orElse(List.of());
    }

    /** The job as it is now, or what the session copied of it if it has been removed since. */
    private JobForBrief job(SessionRow session) {
        return jobs.job(session.jobId()).orElseGet(() -> new JobForBrief(session.jobId(), session.jobTitle(),
                session.jobCompany(), null, null, null, null, null, null, null, null, null, null, null, null, null,
                List.of(), null));
    }

    // --- views ---

    private SessionView view(SessionRow row, List<TurnRow> turns) {
        List<TurnView> views = turns.stream().map(MockInterviewService::turnView).toList();
        TurnView open = null;
        if (row.active() && !turns.isEmpty() && turns.get(turns.size() - 1).interviewer()) {
            open = views.get(views.size() - 1);
        }
        return new SessionView(row.id(), row.jobId(), row.jobTitle(), row.jobCompany(), row.applicationId(),
                row.prepId(), "MOCK", row.persona(), row.status(), row.maxTurns(), row.turnsAnswered(),
                row.creditsConsumed(), row.promptVersion(), row.createdAt(), row.completedAt(), open, views,
                summaryOf(row));
    }

    private static SummaryView summaryOf(SessionRow row) {
        SummaryView s = row.summary();
        return s == null ? null : new SummaryView(s.turnsAnswered(), s.averages(), s.topStrengths(),
                s.topImprovements(), s.narrative(), s.nextSteps(), s.model(), row.creditsConsumed());
    }

    private static TurnView turnView(TurnRow t) {
        return new TurnView(t.position(), t.role(), t.content(), t.category(), t.source(), t.feedback(),
                t.createdAt());
    }

    private static String clip(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
