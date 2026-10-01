package com.jobfinder.core.matching.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import org.springframework.stereotype.Component;

/**
 * The stage-2 blend (PLAN.md section 7, docs/adr/0026-matching-engine.md): how well a job fits the resume without
 * asking a model. Pure and deterministic: the same inputs and weights always give the same score.
 *
 * <p>Three components, each in 0 to 1:
 * <ul>
 * <li><b>vector</b>: cosine similarity of the resume and job embeddings, clamped to 0 to 1 (a negative similarity is
 * no similarity). Unknown when either embedding is missing.</li>
 * <li><b>skills</b>: the share of the job's listed skills that the resume lists, compared case-insensitively
 * after trimming and collapsing spaces. Unknown when either side lists none, since "lists none" says the
 * extraction found nothing, not that nothing matches.</li>
 * <li><b>recency</b>: {@code 0.5 ^ (age / half-life)} of the job's posting time; a job from the future counts as
 * brand new. Always known.</li>
 * </ul>
 * The score is {@code 100 * sum(weight * component) / sum(weight)} over the components that are known, so the
 * configured weights (which sum to 1) are rescaled when one drops out.
 */
@Component
class Stage2Scorer {

    /** The components and their blend; a null component was unknown. {@code score} is on a 0 to 100 scale. */
    record Stage2(Double vector, Double skills, double recency, double score, Double cosine) {
    }

    private final MatchingProperties.Weights weights;
    private final Duration halfLife;

    Stage2Scorer(MatchingProperties properties) {
        this.weights = properties.weights();
        this.halfLife = properties.recencyHalfLife();
    }

    /**
     * @param cosine         the cosine similarity, or null if unknown
     * @param resumeSkills   the resume's skills, already normalized by {@link #normalize}
     * @param jobSkills      the job's listed skills, as stored
     */
    Stage2 score(Double cosine, Set<String> resumeSkills, Collection<String> jobSkills, Instant postedAt,
            Instant now) {
        Double vector = cosine == null ? null : Math.min(1.0, Math.max(0.0, cosine));
        Double skills = overlap(resumeSkills, jobSkills);
        double recency = recency(postedAt, now);
        double sum = weights.recency();
        double weighted = weights.recency() * recency;
        if (vector != null) {
            sum += weights.vector();
            weighted += weights.vector() * vector;
        }
        if (skills != null) {
            sum += weights.skills();
            weighted += weights.skills() * skills;
        }
        // Only recency is left and it has no weight: nothing to say, which is the lowest score.
        double score = sum <= 0 ? 0.0 : 100.0 * weighted / sum;
        return new Stage2(vector, skills, recency, score, cosine);
    }

    static Double overlap(Set<String> resumeSkills, Collection<String> jobSkills) {
        Set<String> wanted = new HashSet<>();
        for (String skill : jobSkills) {
            String key = normalize(skill);
            if (!key.isEmpty()) {
                wanted.add(key);
            }
        }
        if (resumeSkills.isEmpty() || wanted.isEmpty()) {
            return null;
        }
        long hits = wanted.stream().filter(resumeSkills::contains).count();
        return (double) hits / wanted.size();
    }

    double recency(Instant postedAt, Instant now) {
        if (postedAt == null) {
            return 0.0;
        }
        double ageMillis = Math.max(0, Duration.between(postedAt, now).toMillis());
        return Math.pow(0.5, ageMillis / halfLife.toMillis());
    }

    /** Lower-case, trimmed, runs of whitespace collapsed to one space. */
    static String normalize(String skill) {
        return skill == null ? "" : skill.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
