package com.jobfinder.core.billing.internal;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The disposable-email-domain list (docs/adr/0042, section 5): a hard rule for the trial only. The account still works.
 * The list starts as the pinned copy of {@code disposable-email-domains} (CC0) shipped in the jar, and is replaced by the
 * last good copy in {@code disposable_domain_list} once a refresh has stored one. A new copy is refused when it is empty
 * or differs from the current one by more than {@code max-change} (a truncated download or a bad upstream commit),
 * and the current one is kept. {@code allow} and {@code deny} in configuration override the list. A domain matches when
 * it, or any parent domain of it, is listed, so {@code x.mailinator.com} matches {@code mailinator.com}.
 */
@Component
class DisposableDomains {

    static final String SHIPPED = "trial/disposable-email-domains.txt";

    enum Outcome {
        UPDATED, UNCHANGED, REJECTED_EMPTY, REJECTED_TOO_DIFFERENT, FETCH_FAILED, DISABLED
    }

    private static final Logger log = LoggerFactory.getLogger(DisposableDomains.class);

    private final JdbcClient jdbc;
    private final TrialProperties.Disposable config;
    private final Clock clock;
    private volatile Set<String> domains;
    private volatile Instant loadedAt = Instant.EPOCH;

    DisposableDomains(JdbcClient jdbc, TrialProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.config = properties.disposable();
        this.clock = clock;
        this.domains = shipped();
    }

    private static Set<String> shipped() {
        try (InputStream in = new ClassPathResource(SHIPPED).getInputStream()) {
            Set<String> parsed = parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            if (parsed.isEmpty()) {
                throw new IllegalStateException("The shipped disposable-domain list is empty");
            }
            return parsed;
        } catch (IOException e) {
            throw new IllegalStateException("The shipped disposable-domain list cannot be read", e);
        }
    }

    static Set<String> parse(String body) {
        Set<String> parsed = new HashSet<>();
        if (body == null) {
            return parsed;
        }
        for (String line : body.split("\\R")) {
            String d = line.strip().toLowerCase(Locale.ROOT);
            if (!d.isEmpty() && !d.startsWith("#") && d.contains(".") && d.chars().noneMatch(Character::isWhitespace)) {
                parsed.add(d);
            }
        }
        return parsed;
    }

    /** Whether {@code domain} (lower-case ASCII) or a parent domain of it is disposable, after allow and deny. */
    boolean isDisposable(String domain) {
        if (domain == null || domain.isBlank()) {
            return false;
        }
        Set<String> list = current();
        String d = domain.toLowerCase(Locale.ROOT);
        while (d.contains(".")) {
            if (config.allow().contains(d)) {
                return false;
            }
            if (config.deny().contains(d) || list.contains(d)) {
                return true;
            }
            d = d.substring(d.indexOf('.') + 1);
        }
        return false;
    }

    int size() {
        return current().size();
    }

    /** The current list, sorted. */
    List<String> domains() {
        return List.copyOf(new TreeSet<>(current()));
    }

    /** The list in use, reloaded from the database at most every {@code reload-interval} (another instance may refresh). */
    private Set<String> current() {
        Instant now = clock.instant();
        if (now.isAfter(loadedAt.plus(config.reloadInterval()))) {
            loadedAt = now;
            try {
                stored().ifPresent(s -> domains = s);
            } catch (RuntimeException e) {
                log.warn("Reading the stored disposable-domain list failed; keeping the one in memory", e);
            }
        }
        return domains;
    }

    private Optional<Set<String>> stored() {
        return jdbc.sql("select domains from disposable_domain_list where id = 1").query(String.class).optional()
                .map(DisposableDomains::parse).filter(s -> !s.isEmpty());
    }

    /**
     * Considers a freshly downloaded list: stores it and uses it, or refuses it and keeps the current one.
     *
     * @param body   the downloaded file
     * @param source where it came from (kept with the copy)
     */
    synchronized Outcome apply(String body, String source, Instant now) {
        Set<String> fresh = parse(body);
        if (fresh.isEmpty()) {
            log.warn("Disposable-domain list refused: the download had no domains");
            return Outcome.REJECTED_EMPTY;
        }
        Set<String> old = current();
        if (fresh.equals(old)) {
            return Outcome.UNCHANGED;
        }
        Set<String> removed = new HashSet<>(old);
        removed.removeAll(fresh);
        Set<String> added = new HashSet<>(fresh);
        added.removeAll(old);
        double change = (double) (removed.size() + added.size()) / Math.max(1, old.size());
        if (change > config.maxChange()) {
            log.warn("Disposable-domain list refused: {} added and {} removed of {} ({}% > {}%)", added.size(),
                    removed.size(), old.size(), Math.round(change * 100), Math.round(config.maxChange() * 100));
            return Outcome.REJECTED_TOO_DIFFERENT;
        }
        String text = String.join("\n", new TreeSet<>(fresh));
        jdbc.sql("""
                insert into disposable_domain_list (id, domains, domain_count, source, fetched_at)
                values (1, :domains, :count, :source, :at)
                on conflict (id) do update set domains = excluded.domains, domain_count = excluded.domain_count,
                    source = excluded.source, fetched_at = excluded.fetched_at
                """).param("domains", text).param("count", fresh.size())
                .param("source", source.length() > 500 ? source.substring(0, 500) : source)
                .param("at", OffsetDateTime.ofInstant(now, ZoneOffset.UTC)).update();
        domains = Set.copyOf(fresh);
        loadedAt = clock.instant();
        log.info("Disposable-domain list updated: {} domains ({} added, {} removed)", fresh.size(), added.size(),
                removed.size());
        return Outcome.UPDATED;
    }
}
