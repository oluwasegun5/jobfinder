package com.jobfinder.core.embeddings.internal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.jobfinder.core.embeddings.internal.EmbeddingDtos.InputItem;
import com.jobfinder.core.embeddings.internal.EmbeddingDtos.InputsResponse;
import com.jobfinder.core.embeddings.internal.EmbeddingDtos.ResultItem;
import com.jobfinder.core.embeddings.internal.EmbeddingDtos.ResultsRequest;
import com.jobfinder.core.embeddings.internal.EmbeddingDtos.ResultsResponse;
import com.jobfinder.core.embeddings.internal.EmbeddingDtos.Skipped;
import com.jobfinder.core.embeddings.internal.EmbeddingDtos.UsageRecord;
import com.jobfinder.core.embeddings.internal.EmbeddingStore.JobRow;
import com.jobfinder.core.embeddings.internal.EmbeddingStore.ResumeVersionRow;
import com.jobfinder.core.shared.ApiException;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * The two halves of the exchange with ai-service: what to embed ({@link #inputs}) and the vectors back
 * ({@link #store}). Both are safe to repeat.
 *
 * <p><b>Idempotency.</b> {@code inputs} leaves out rows whose stored embedding is already current (same pinned
 * model, same text hash), so a redelivered message costs no provider call. {@code store} re-reads each row under a
 * lock, rebuilds its text, and writes only if the hash of that text is the one the vector was made from: a vector
 * computed from older text is dropped (counted {@code stale}) because the change that made it old has already
 * queued a fresh message. Writing the same vector twice is a no-op in effect.
 */
@Service
class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

    private static final String INPUT_TYPE_JOB = "document";
    private static final String INPUT_TYPE_RESUME = "query";

    private final EmbeddingStore store;
    private final EmbeddingTextBuilder texts;
    private final EmbeddingProperties properties;
    private final TransactionTemplate tx;
    private final JsonMapper json;

    EmbeddingService(EmbeddingStore store, EmbeddingTextBuilder texts, EmbeddingProperties properties,
            TransactionTemplate tx, JsonMapper json) {
        this.store = store;
        this.texts = texts;
        this.properties = properties;
        this.tx = tx;
        this.json = json;
    }

    InputsResponse inputs(EmbeddingKind kind, Collection<UUID> requested) {
        Set<UUID> ids = new LinkedHashSet<>(requested);
        List<InputItem> items = new ArrayList<>();
        List<Skipped> skipped = new ArrayList<>();
        if (kind == EmbeddingKind.JOB) {
            Map<UUID, JobRow> rows = by(store.jobs(ids, false), JobRow::id);
            for (UUID id : ids) {
                JobRow row = rows.get(id);
                if (row == null) {
                    skipped.add(new Skipped(id, "NOT_FOUND"));
                } else if (!"ACTIVE".equals(row.status())) {
                    skipped.add(new Skipped(id, "EXPIRED"));
                } else {
                    String text = jobText(row);
                    String hash = EmbeddingTextBuilder.hash(text);
                    if (current(row.model(), row.inputHash(), hash)) {
                        skipped.add(new Skipped(id, "UP_TO_DATE"));
                    } else {
                        items.add(new InputItem(id, null, text, hash));
                    }
                }
            }
        } else {
            Map<UUID, ResumeVersionRow> rows = by(store.resumeVersions(ids, false), ResumeVersionRow::id);
            for (UUID id : ids) {
                ResumeVersionRow row = rows.get(id);
                if (row == null) {
                    skipped.add(new Skipped(id, "NOT_FOUND"));
                } else if (row.structuredJson() == null) {
                    skipped.add(new Skipped(id, "NO_CONTENT"));
                } else {
                    String text = resumeText(row);
                    String hash = EmbeddingTextBuilder.hash(text);
                    if (current(row.model(), row.inputHash(), hash)) {
                        skipped.add(new Skipped(id, "UP_TO_DATE"));
                    } else {
                        items.add(new InputItem(id, row.userId(), text, hash));
                    }
                }
            }
        }
        return new InputsResponse(properties.model(), properties.dimension(),
                kind == EmbeddingKind.JOB ? INPUT_TYPE_JOB : INPUT_TYPE_RESUME, items, skipped);
    }

    ResultsResponse store(ResultsRequest request) {
        if (!properties.model().equals(request.model()) || properties.dimension() != request.dimension()) {
            throw new ApiException(HttpStatus.CONFLICT, "embedding_space_mismatch",
                    "Vectors must come from model " + properties.model() + " at dimension " + properties.dimension());
        }
        Map<UUID, ResultItem> results = new java.util.LinkedHashMap<>();
        for (ResultItem item : request.items()) {
            validate(item);
            results.put(item.id(), item);
        }
        ResultsResponse response = tx.execute(status -> request.kind() == EmbeddingKind.JOB ? storeJobs(results)
                : storeResumeVersions(results));
        if (request.usage() != null) {
            request.usage().forEach(EmbeddingService::logUsage);
        }
        return response;
    }

    /** True if a row needs a (new) embedding now, given what is stored. */
    boolean jobIsStale(JobRow row) {
        return "ACTIVE".equals(row.status()) && !current(row.model(), row.inputHash(),
                EmbeddingTextBuilder.hash(jobText(row)));
    }

    boolean resumeVersionIsStale(ResumeVersionRow row) {
        return row.structuredJson() != null && !current(row.model(), row.inputHash(),
                EmbeddingTextBuilder.hash(resumeText(row)));
    }

    private ResultsResponse storeJobs(Map<UUID, ResultItem> results) {
        Map<UUID, JobRow> rows = by(store.jobs(results.keySet(), true), JobRow::id);
        int applied = 0;
        int stale = 0;
        int missing = 0;
        for (ResultItem item : results.values()) {
            JobRow row = rows.get(item.id());
            if (row == null) {
                missing++;
            } else if (!"ACTIVE".equals(row.status())
                    || !EmbeddingTextBuilder.hash(jobText(row)).equals(item.inputHash())) {
                stale++;
            } else {
                store.writeJob(row.id(), properties.model(), item.inputHash(), literal(item));
                applied++;
            }
        }
        return new ResultsResponse(applied, stale, missing);
    }

    private ResultsResponse storeResumeVersions(Map<UUID, ResultItem> results) {
        Map<UUID, ResumeVersionRow> rows = by(store.resumeVersions(results.keySet(), true), ResumeVersionRow::id);
        int applied = 0;
        int stale = 0;
        int missing = 0;
        for (ResultItem item : results.values()) {
            ResumeVersionRow row = rows.get(item.id());
            if (row == null) {
                missing++;
            } else if (row.structuredJson() == null
                    || !EmbeddingTextBuilder.hash(resumeText(row)).equals(item.inputHash())) {
                stale++;
            } else {
                store.writeResumeVersion(row.id(), properties.model(), item.inputHash(), literal(item));
                applied++;
            }
        }
        return new ResultsResponse(applied, stale, missing);
    }

    private boolean current(String storedModel, String storedHash, String hash) {
        return properties.model().equals(storedModel) && hash.equals(storedHash);
    }

    String jobText(JobRow row) {
        return texts.job(row.title(), row.company(), row.descriptionText());
    }

    String resumeText(ResumeVersionRow row) {
        try {
            Object parsed = json.readValue(row.structuredJson(), Object.class);
            return texts.resume(parsed instanceof Map<?, ?> map ? map : Map.of());
        } catch (JacksonException e) {
            throw new IllegalStateException("Stored resume content is unreadable", e);
        }
    }

    private void validate(ResultItem item) {
        if (item.embedding().size() != properties.dimension()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_embedding",
                    "Expected " + properties.dimension() + " values, got " + item.embedding().size());
        }
        for (Float value : item.embedding()) {
            if (!Float.isFinite(value)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_embedding", "Embeddings must be finite numbers");
            }
        }
    }

    private static String literal(ResultItem item) {
        return item.embedding().stream().map(f -> Float.toString(f)).collect(Collectors.joining(",", "[", "]"));
    }

    private static <T> Map<UUID, T> by(List<T> rows, Function<T, UUID> id) {
        return rows.stream().collect(Collectors.toMap(id, Function.identity()));
    }

    /** Usage goes to the ledger in Phase 3 (ADR 0008); until then it is logged, without any content. */
    private static void logUsage(UsageRecord u) {
        log.info("ai usage user={} feature={} provider={} model={} inputTokens={} outputTokens=0 costUsd={} "
                + "latencyMs={} promptVersion={}", u.userId(), u.feature(), u.provider(), u.model(), u.inputTokens(),
                u.costUsd(), u.latencyMs(), EmbeddingTextBuilder.TEMPLATE_VERSION);
    }
}
