package com.jobfinder.core.ingestion.internal;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.jobfinder.core.ingestion.InvalidSourceTargetException;
import com.jobfinder.core.ingestion.SourceKind;
import com.jobfinder.core.ingestion.SourceTargetService;
import com.jobfinder.core.ingestion.SourceTargetView;
import com.jobfinder.core.ingestion.UnknownSourceException;

/**
 * Adds source targets. The company is found or created by normalized name (as the normalizer does, so a
 * board token's jobs and the same employer's jobs from another source land on one company) in the same
 * transaction as the target.
 */
@Service
class SourceTargetManager implements SourceTargetService {

    static final int MAX_IDENTIFIER = 255;
    private static final int MAX_COMPANY = 300;

    private final IngestionRunner runner;
    private final SourceStore sources;
    private final CompanyStore companies;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;

    SourceTargetManager(IngestionRunner runner, SourceStore sources, CompanyStore companies, JdbcClient jdbc,
            PlatformTransactionManager transactionManager, Clock clock) {
        this.runner = runner;
        this.sources = sources;
        this.companies = companies;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    @Override
    public SourceTargetView addTarget(String sourceCode, String identifier, String companyName) {
        if (sourceCode == null || !runner.adapters().containsKey(sourceCode)) {
            throw new UnknownSourceException(String.valueOf(sourceCode));
        }
        String id = identifier == null ? "" : identifier.strip();
        if (id.isEmpty() || id.length() > MAX_IDENTIFIER || id.codePoints().anyMatch(Character::isISOControl)) {
            throw new InvalidSourceTargetException(
                    "The identifier must be 1 to " + MAX_IDENTIFIER + " characters without control characters.");
        }
        if (runner.adapters().get(sourceCode).kind() == SourceKind.AGGREGATOR) {
            // An aggregator target is a search; its postings name their own employers (ADR 0021).
            if (companyName != null && !companyName.isBlank()) {
                throw new InvalidSourceTargetException("An aggregator target is a search and has no company.");
            }
            sources.register(sourceCode, SourceKind.AGGREGATOR);
            UUID sourceId = sources.findByCode(sourceCode).orElseThrow(() -> new UnknownSourceException(sourceCode)).id();
            return tx.execute(status -> saveSearch(sourceCode, sourceId, id));
        }
        String company = TextCleaner.truncate(TextCleaner.line(companyName), MAX_COMPANY);
        String normalized = company == null ? "" : Names.company(company);
        if (normalized.isEmpty()) {
            throw new InvalidSourceTargetException("The company name is required.");
        }
        // The source row exists once the application is up; make sure here too, so a call that races startup works.
        sources.register(sourceCode, runner.adapters().get(sourceCode).kind());
        UUID sourceId = sources.findByCode(sourceCode).orElseThrow(() -> new UnknownSourceException(sourceCode)).id();
        return tx.execute(status -> save(sourceCode, sourceId, id, company, normalized));
    }

    private SourceTargetView saveSearch(String sourceCode, UUID sourceId, String identifier) {
        OffsetDateTime now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
        boolean inserted = jdbc.sql("""
                insert into source_targets (id, source_id, identifier, created_at, updated_at)
                values (:id, :sourceId, :identifier, :now, :now)
                on conflict (source_id, identifier) do nothing
                """)
                .param("id", UUID.randomUUID())
                .param("sourceId", sourceId)
                .param("identifier", identifier)
                .param("now", now)
                .update() == 1;
        return jdbc.sql("select id, enabled from source_targets where source_id = :sourceId and identifier = :identifier")
                .param("sourceId", sourceId)
                .param("identifier", identifier)
                .query((rs, row) -> new SourceTargetView(rs.getObject("id", UUID.class), sourceCode, identifier, null,
                        rs.getBoolean("enabled"), inserted))
                .single();
    }

    private SourceTargetView save(String sourceCode, UUID sourceId, String identifier, String company,
            String normalized) {
        OffsetDateTime now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
        UUID companyId = companies.findOrCreate(company, normalized, now.toInstant());
        Optional<UUID> inserted = jdbc.sql("""
                insert into source_targets (id, source_id, identifier, company_id, created_at, updated_at)
                values (:id, :sourceId, :identifier, :companyId, :now, :now)
                on conflict (source_id, identifier) do nothing
                returning id
                """)
                .param("id", UUID.randomUUID())
                .param("sourceId", sourceId)
                .param("identifier", identifier)
                .param("companyId", companyId)
                .param("now", now)
                .query(UUID.class)
                .optional();
        if (inserted.isPresent()) {
            return new SourceTargetView(inserted.get(), sourceCode, identifier, company, true, true);
        }
        // Already there: keep it as it is, only filling in a company that was never set.
        jdbc.sql("update source_targets set company_id = :companyId, updated_at = :now "
                + "where source_id = :sourceId and identifier = :identifier and company_id is null")
                .param("companyId", companyId)
                .param("now", now)
                .param("sourceId", sourceId)
                .param("identifier", identifier)
                .update();
        return jdbc.sql("""
                select t.id, t.enabled, c.name from source_targets t left join companies c on c.id = t.company_id
                where t.source_id = :sourceId and t.identifier = :identifier
                """)
                .param("sourceId", sourceId)
                .param("identifier", identifier)
                .query((rs, row) -> new SourceTargetView(rs.getObject("id", UUID.class), sourceCode, identifier,
                        rs.getString("name"), rs.getBoolean("enabled"), false))
                .single();
    }
}
