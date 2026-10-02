package com.jobfinder.core.rendering.internal;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Fictional resumes for the rendering tests (no real person is in any of them): a short one with Yoruba names and
 * combining marks, one with almost nothing in it, a long one that runs over several pages, and one full of awkward
 * characters. Set {@code -Drender.dump=/some/dir} to also write every rendered file there, for looking at.
 */
final class RenderingFixtures {

    static final JsonMapper JSON = JsonMapper.builder().build();

    private RenderingFixtures() {
    }

    static JsonNode resource(String name) {
        try (InputStream in = RenderingFixtures.class.getResourceAsStream("/rendering/" + name)) {
            return JSON.readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static JsonNode shortResume() {
        return resource("short.json");
    }

    static JsonNode sparseResume() {
        return resource("sparse.json");
    }

    /**
     * A career of {@code jobs} roles with {@code bullets} bullets each, behind a summary of {@code summaryLines}
     * sentences; varying the summary moves every page break, which is how the tests look for an orphaned heading.
     */
    static JsonNode longResume(int jobs, int bullets, int summaryLines) {
        ObjectNode root = (ObjectNode) shortResume().deepCopy();
        StringBuilder summary = new StringBuilder();
        for (int i = 0; i < summaryLines; i++) {
            summary.append("Sentence ").append(i + 1)
                    .append(" of the summary says something plausible about reliable backend systems. ");
        }
        root.put("summary", summary.toString().strip());
        ArrayNode experience = root.putArray("experience");
        for (int j = 0; j < jobs; j++) {
            ObjectNode job = experience.addObject();
            job.put("company", "Company " + (j + 1) + " Ltd");
            job.put("title", "Engineer Level " + (j + 1));
            job.put("location", "Lagos, Nigeria");
            job.put("start_date", (2024 - 2 * j) + "-01");
            job.put("end_date", (2023 - 2 * j + 1) + "-12");
            job.put("is_current", false);
            ArrayNode list = job.putArray("bullets");
            for (int b = 0; b < bullets; b++) {
                list.add("Job " + (j + 1) + " bullet " + (b + 1) + ": delivered a measurable improvement to the "
                        + "platform and wrote down how it works so the next person on call could change it safely"
                        + (b % 3 == 0 ? ", including the awkward parts nobody liked to talk about." : "."));
            }
        }
        return root;
    }

    /** Characters that break naive renderers and parsers. */
    static JsonNode awkwardResume() {
        ObjectNode root = (ObjectNode) shortResume().deepCopy();
        root.put("headline", "C++/C# & \"quoted\" <b>engineer</b> – 100% € £ ¥ “smart” quotes");
        root.put("summary", "Line one with a tab\tand a​zero-width space.\n\nParagraph two after a blank line.\r\n"
                + "Rocket 🚀 emoji and a very long token: " + "x".repeat(300));
        ObjectNode job = (ObjectNode) root.get("experience").get(0);
        ArrayNode bullets = (ArrayNode) job.get("bullets");
        bullets.add("Bullet with\nan embedded newline and a URL https://example.test/" + "path/".repeat(40));
        bullets.add("fi fl ffi ffl ligature candidates: official, waffle, fluffy, finally");
        ArrayNode skills = (ArrayNode) root.get("skills");
        for (int i = 0; i < 60; i++) {
            skills.add("Skill number " + i);
        }
        return root;
    }

    static ResumeModel model(JsonNode resume) {
        return ResumeModel.parse(resume);
    }

    /** Writes the bytes to {@code $render.dump/<name>} when that property is set. */
    static void dump(String name, byte[] bytes) {
        String dir = System.getProperty("render.dump");
        if (dir == null || dir.isBlank()) {
            return;
        }
        try {
            Files.createDirectories(Path.of(dir));
            Files.write(Path.of(dir, name), bytes);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
