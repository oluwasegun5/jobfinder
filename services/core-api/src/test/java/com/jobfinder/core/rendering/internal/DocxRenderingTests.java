package com.jobfinder.core.rendering.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;

import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import tools.jackson.databind.JsonNode;

/**
 * The DOCX output, opened again with POI: real Title, Heading 1, Heading 2 and List Bullet styles, a real bullet list,
 * no tables, text boxes, pictures, headers or footers, and the same text in the same order as the PDF.
 */
class DocxRenderingTests {

    private static final String NAME = PdfRenderingTests.NAME;

    private final DocxRenderer renderer = new DocxRenderer();

    private record Para(String style, String text, boolean numbered) {
    }

    private byte[] docx(JsonNode resume, RenderTemplate template, PageFormat page, String name) {
        byte[] bytes = renderer.render(RenderingFixtures.model(resume), template, page);
        RenderingFixtures.dump(name + "-" + template.slug() + "-" + page.slug() + ".docx", bytes);
        return bytes;
    }

    private static List<Para> paragraphs(XWPFDocument document) {
        List<Para> out = new ArrayList<>();
        for (IBodyElement element : document.getBodyElements()) {
            assertThat(element).as("only paragraphs in the body, no tables").isInstanceOf(XWPFParagraph.class);
            XWPFParagraph p = (XWPFParagraph) element;
            out.add(new Para(p.getStyleID(), Normalizer.normalize(p.getText(), Normalizer.Form.NFC),
                    p.getNumID() != null));
        }
        return out;
    }

    private static List<String> texts(List<Para> paragraphs, String style) {
        return paragraphs.stream().filter(p -> style.equals(p.style())).map(Para::text).toList();
    }

    @ParameterizedTest
    @EnumSource(RenderTemplate.class)
    void theDocumentHasRealStylesInReadingOrder(RenderTemplate template) throws IOException {
        byte[] bytes = docx(RenderingFixtures.shortResume(), template, PageFormat.A4, "short");
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            List<Para> paragraphs = paragraphs(document);

            assertThat(paragraphs.get(0)).isEqualTo(new Para("Title", NAME, false));
            assertThat(paragraphs.get(1)).isEqualTo(new Para("Subtitle", "Backend engineer for payments and logistics", false));
            assertThat(texts(paragraphs, "ContactInfo")).containsExactly(
                    "shola.ogunleye@example.test | +234 800 555 0199 | Ibadan, Nigeria",
                    "Portfolio: https://shola-ogunleye.example.test | GitHub: https://github.com/shola-test");
            assertThat(texts(paragraphs, "Heading1")).containsExactly("Summary", "Experience", "Education", "Skills",
                    "Projects", "Certifications");

            // Every entry title is a Heading 2; in the styled template its dates follow after a tab on the same line.
            List<String> entryTitles = texts(paragraphs, "Heading2").stream().map(t -> t.split("\t")[0]).toList();
            assertThat(entryTitles).containsExactly("Senior Backend Engineer", "Software Engineer", "Web Developer",
                    "BSc, Computer Science", "Roster Tool", "Certified Kubernetes Application Developer");

            // The bullets, in the order of the jobs, each a list paragraph with a real numbering reference.
            List<Para> bullets = paragraphs.stream().filter(p -> "ListBullet".equals(p.style())).toList();
            assertThat(bullets).extracting(Para::text).containsExactly(
                    "Cut p95 latency of the settlement API by 38% across 14 services.",
                    "Led four engineers delivering a ledger service in Java 21 and Spring Boot.",
                    "Introduced contract tests that caught 11 breaking changes before release.",
                    "Moved nightly reconciliation to Kafka consumers, removing a 3 hour batch window.",
                    "Built REST APIs on PostgreSQL for dispatch and proof of delivery.",
                    "Containerised twelve services with Docker and wrote their runbooks.",
                    "Mentored three graduate engineers through their first production releases.",
                    "Delivered small business sites and a booking tool for a clinic.");
            assertThat(bullets).allMatch(Para::numbered);

            // Document order: name, summary, then jobs with their own bullets directly under them, then the rest.
            List<String> flow = paragraphs.stream().map(Para::text).toList();
            int senior = indexStarting(flow, "Senior Backend Engineer");
            int software = indexStarting(flow, "Software Engineer");
            int web = indexStarting(flow, "Web Developer");
            int education = flow.indexOf("Education");
            assertThat(flow.indexOf("Cut p95 latency of the settlement API by 38% across 14 services."))
                    .isBetween(senior, software);
            assertThat(flow.indexOf("Moved nightly reconciliation to Kafka consumers, removing a 3 hour batch window."))
                    .isBetween(senior, software);
            assertThat(flow.indexOf("Built REST APIs on PostgreSQL for dispatch and proof of delivery."))
                    .isBetween(software, web);
            assertThat(flow.indexOf("Delivered small business sites and a booking tool for a clinic."))
                    .isBetween(web, education);
            assertThat(flow.indexOf("Skills") + 1).isEqualTo(flow.indexOf(
                    "Java, Spring Boot, PostgreSQL, Kafka, Docker, REST APIs, Testcontainers, Git"));
            assertThat(String.join(" ", flow)).contains("Ìbàdàn Payments Ltd").contains("Akòkó Logistics");
        }
    }

    private static int indexStarting(List<String> list, String prefix) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).startsWith(prefix)) {
                return i;
            }
        }
        throw new AssertionError("no paragraph starts with " + prefix);
    }

    @ParameterizedTest
    @EnumSource(RenderTemplate.class)
    void theHeadingStylesAreWordsOwnHeadingStylesAndKeepWithTheNextParagraph(RenderTemplate template) throws IOException {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(
                docx(RenderingFixtures.shortResume(), template, PageFormat.A4, "short")))) {
            var heading1 = document.getStyles().getStyle("Heading1");
            var heading2 = document.getStyles().getStyle("Heading2");
            var bullet = document.getStyles().getStyle("ListBullet");

            assertThat(heading1.getName()).isEqualTo("heading 1");
            assertThat(heading1.getCTStyle().getPPr().isSetKeepNext()).isTrue();
            assertThat(heading1.getCTStyle().getPPr().getOutlineLvl().getVal()).isEqualTo(java.math.BigInteger.ZERO);
            assertThat(heading2.getName()).isEqualTo("heading 2");
            assertThat(heading2.getCTStyle().getPPr().getOutlineLvl().getVal()).isEqualTo(java.math.BigInteger.ONE);
            assertThat(bullet.getName()).isEqualTo("List Bullet");
            assertThat(document.getStyles().getStyle("Title").getName()).isEqualTo("Title");
            assertThat(document.getStyles().getStyle("Normal")).isNotNull();
            // A real bullet list definition backs the bullet paragraphs.
            assertThat(document.getNumbering().getNums()).isNotEmpty();
        }
    }

    @ParameterizedTest
    @EnumSource(RenderTemplate.class)
    void noTablesTextBoxesPicturesHeadersOrFooters(RenderTemplate template) throws IOException {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(
                docx(RenderingFixtures.shortResume(), template, PageFormat.A4, "short")))) {
            assertThat(document.getTables()).isEmpty();
            assertThat(document.getAllPictures()).isEmpty();
            assertThat(document.getHeaderList()).isEmpty();
            assertThat(document.getFooterList()).isEmpty();
            String xml = document.getDocument().xmlText();
            assertThat(xml).doesNotContain("<w:tbl>").doesNotContain("txbxContent").doesNotContain("<w:drawing")
                    .doesNotContain("<w:pict");
            assertThat(document.getProperties().getCoreProperties().getTitle()).isEqualTo(NAME + " - Resume");
            assertThat(document.getProperties().getCoreProperties().getCreator()).isEqualTo(NAME);
        }
    }

    @Test
    void theStyledTemplateRightAlignsDatesWithATabStopNotATable() throws IOException {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(
                docx(RenderingFixtures.shortResume(), RenderTemplate.STYLED, PageFormat.A4, "short")))) {
            XWPFParagraph title = document.getParagraphs().stream()
                    .filter(p -> p.getText().startsWith("Senior Backend Engineer")).findFirst().orElseThrow();
            assertThat(title.getText()).isEqualTo("Senior Backend Engineer\tMar 2021 – Present");
            assertThat(title.getCTP().getPPr().getTabs().getTabArray(0).getVal().toString()).isEqualTo("right");
        }
    }

    @ParameterizedTest
    @EnumSource(PageFormat.class)
    void thePageSizeIsA4OrLetter(PageFormat page) throws IOException {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(
                docx(RenderingFixtures.shortResume(), RenderTemplate.ATS, page, "short")))) {
            var size = document.getDocument().getBody().getSectPr().getPgSz();
            assertThat(size.getW()).isEqualTo(java.math.BigInteger.valueOf(page.twipsWidth()));
            assertThat(size.getH()).isEqualTo(java.math.BigInteger.valueOf(page.twipsHeight()));
        }
    }

    @ParameterizedTest
    @EnumSource(RenderTemplate.class)
    void sparseAndLongResumesKeepTheirOrder(RenderTemplate template) throws IOException {
        try (XWPFDocument sparse = new XWPFDocument(new ByteArrayInputStream(
                docx(RenderingFixtures.sparseResume(), template, PageFormat.A4, "sparse")))) {
            List<Para> paragraphs = paragraphs(sparse);
            assertThat(texts(paragraphs, "Heading1")).containsExactly("Education", "Skills");
            assertThat(paragraphs.get(0).text()).isEqualTo("Chi Àyọdélé");
        }
        try (XWPFDocument empty = new XWPFDocument(new ByteArrayInputStream(
                docx(RenderingFixtures.JSON.readTree("{}"), template, PageFormat.A4, "empty")))) {
            assertThat(empty.getParagraphs()).isEmpty();
        }
        try (XWPFDocument big = new XWPFDocument(new ByteArrayInputStream(
                docx(RenderingFixtures.longResume(12, 8, 4), template, PageFormat.A4, "long")))) {
            List<String> bullets = texts(paragraphs(big), "ListBullet");
            assertThat(bullets).hasSize(96);
            assertThat(bullets.get(0)).startsWith("Job 1 bullet 1:");
            assertThat(bullets.get(95)).startsWith("Job 12 bullet 8:");
            for (int i = 1; i < bullets.size(); i++) {
                // Bullets of job n are all before those of job n+1: numbers never go backwards.
                int jobNow = Integer.parseInt(bullets.get(i).split(" ")[1]);
                int jobBefore = Integer.parseInt(bullets.get(i - 1).split(" ")[1]);
                assertThat(jobNow).isGreaterThanOrEqualTo(jobBefore);
            }
        }
    }

    @ParameterizedTest
    @EnumSource(RenderTemplate.class)
    void awkwardTextIsKeptAndCleaned(RenderTemplate template) throws IOException {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(
                docx(RenderingFixtures.awkwardResume(), template, PageFormat.A4, "awkward")))) {
            String all = String.join("\n", paragraphs(document).stream().map(Para::text).toList());
            assertThat(all).contains("C++/C# & \"quoted\" <b>engineer</b>");
            assertThat(all).contains("Bullet with an embedded newline and a URL https://example.test/");
            assertThat(all).doesNotContain("​").doesNotContain("\t" + "and a");
            assertThat(all).contains("fi fl ffi ffl ligature candidates");
        }
    }
}
