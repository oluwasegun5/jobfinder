package com.jobfinder.core.embeddings.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class EmbeddingTextBuilderTests {

    private final EmbeddingTextBuilder builder = new EmbeddingTextBuilder(
            new EmbeddingProperties("voyage-4", 1024, 500, "j", "jd", "r", "rd", 10, true));

    @Test
    void aJobIsTitleCompanyAndDescription() {
        assertThat(builder.job("Backend Engineer", "Acme", "  Builds APIs.  "))
                .isEqualTo("Title: Backend Engineer\nCompany: Acme\n\nBuilds APIs.");
        assertThat(builder.job("Backend Engineer", "Acme", null)).isEqualTo("Title: Backend Engineer\nCompany: Acme");
    }

    @Test
    void aLongDescriptionIsCutAtTheBudgetOnAWordBoundary() {
        String text = builder.job("T", "C", "word ".repeat(1000));
        assertThat(text.length()).isLessThanOrEqualTo(500);
        assertThat(text).endsWith("word");
    }

    @Test
    void aCutNeverSplitsASurrogatePair() {
        String text = builder.job("T", "C", "😀".repeat(400));
        assertThat(text.length()).isLessThanOrEqualTo(500);
        assertThat(Character.isHighSurrogate(text.charAt(text.length() - 1))).isFalse();
    }

    @Test
    void aResumeLeavesOutTheContactBlock() {
        Map<String, Object> resume = Map.of(
                "contact", Map.of("full_name", "Ada Lovelace", "email", "ada@example.test", "phone", "+44 20 7946 0000"),
                "headline", "Engineer",
                "summary", "Writes programs.",
                "experience", List.of(Map.of("company", "Analytical Engines", "title", "Programmer",
                        "bullets", List.of("Wrote the first program."))),
                "skills", List.of("Math", "Poetry"),
                "education", List.of(Map.of("institution", "Home", "degree", "BSc", "field_of_study", "Maths")));

        String text = builder.resume(resume);

        assertThat(text).contains("Headline: Engineer", "Experience: Programmer at Analytical Engines",
                "- Wrote the first program.", "Skills: Math, Poetry", "Education: BSc in Maths, Home");
        assertThat(text).doesNotContain("Ada", "example.test", "7946");
    }

    @Test
    void anUnknownShapeGivesEmptyText() {
        assertThat(builder.resume(Map.of("experience", "not a list", "skills", 5))).isEmpty();
    }

    @Test
    void theHashDependsOnTheTextAndIsStable() {
        String a = EmbeddingTextBuilder.hash("same");
        assertThat(a).hasSize(64).isEqualTo(EmbeddingTextBuilder.hash("same")).isNotEqualTo(EmbeddingTextBuilder.hash("other"));
    }
}
