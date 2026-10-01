package com.jobfinder.core.rendering.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import tools.jackson.databind.JsonNode;

/**
 * The "done when" of P4.2: a rendered PDF extracts back to clean text in the right order. Extraction is PDFBox's own
 * {@link PDFTextStripper}; it reads the content stream in order, which is what ATS parsers do too. Both templates, on
 * short, long (multi-page), sparse and awkward resumes, with Yoruba names whose letters carry dots below and
 * combining tone marks.
 */
class PdfRenderingTests {

    /** "Ṣọlá Ọ̀gúnlẹ́yẹ": S with dot below, o with dot below, O with dot below plus a combining grave, e with dot below plus a combining acute. */
    static final String NAME = "Ṣọlá Ọ̀gúnlẹ́yẹ";
    static final String COMPANY = "Ìbàdàn Payments Ltd";
    static final Set<String> HEADINGS = Set.of("Summary", "Experience", "Education", "Skills", "Projects",
            "Certifications");

    private final PdfRenderer renderer = new PdfRenderer();

    private byte[] pdf(JsonNode resume, RenderTemplate template, PageFormat page, String name) {
        byte[] bytes = renderer.render(RenderingFixtures.model(resume), template, page);
        RenderingFixtures.dump(name + "-" + template.slug() + "-" + page.slug() + ".pdf", bytes);
        return bytes;
    }

    /** All the text of the PDF in extraction order, NFC (extractors differ in how they compose letters and marks). */
    static String text(byte[] pdf) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return Normalizer.normalize(new PDFTextStripper().getText(document), Normalizer.Form.NFC);
        }
    }

    /** The text with every run of whitespace (line breaks included) as one space: wrapped lines read as one. */
    static String flat(String text) {
        return text.replaceAll("\\s+", " ").strip();
    }

    static void assertInOrder(String text, String... needles) {
        int from = 0;
        for (String needle : needles) {
            int at = text.indexOf(needle, from);
            assertThat(at).as("'%s' should come after position %d in:%n%s", needle, from, text).isGreaterThanOrEqualTo(0);
            from = at + needle.length();
        }
    }

    static int count(String text, String needle) {
        int count = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + 1)) {
            count++;
        }
        return count;
    }

    // --------------------------------------------------------------------------------------------- short resume

    @ParameterizedTest
    @EnumSource(RenderTemplate.class)
    void aShortResumeExtractsInReadingOrder(RenderTemplate template) throws IOException {
        String text = flat(text(pdf(RenderingFixtures.shortResume(), template, PageFormat.A4, "short")));

        assertThat(text).startsWith(NAME);
        // The ATS template prints "company | dates" under the title; the styled one puts the dates on the title's line.
        boolean ats = template == RenderTemplate.ATS;
        assertInOrder(text,
                NAME, "Backend engineer for payments and logistics",
                "shola.ogunleye@example.test", "+234 800 555 0199", "Ibadan, Nigeria",
                "Portfolio: https://shola-ogunleye.example.test", "GitHub: https://github.com/shola-test",
                "Summary", "Backend engineer with seven years",
                "Experience",
                "Senior Backend Engineer", ats ? COMPANY : "Mar 2021 – Present", ats ? "Mar 2021 – Present" : COMPANY,
                "• Cut p95 latency of the settlement API by 38% across 14 services.",
                "• Led four engineers delivering a ledger service in Java 21 and Spring Boot.",
                "• Introduced contract tests that caught 11 breaking changes before release.",
                "• Moved nightly reconciliation to Kafka consumers, removing a 3 hour batch window.",
                "Software Engineer", ats ? "Akòkó Logistics" : "Jun 2017 – Feb 2021",
                ats ? "Jun 2017 – Feb 2021" : "Akòkó Logistics",
                "• Built REST APIs on PostgreSQL for dispatch and proof of delivery.",
                "• Containerised twelve services with Docker and wrote their runbooks.",
                "• Mentored three graduate engineers through their first production releases.",
                "Web Developer", ats ? "Freelance" : "2015 – May 2017", ats ? "2015 – May 2017" : "Freelance",
                "• Delivered small business sites and a booking tool for a clinic.",
                "Education", "BSc, Computer Science", ats ? "University of Ìbàdàn" : "2011 – 2015",
                ats ? "2011 – 2015" : "University of Ìbàdàn",
                "Skills", "Java, Spring Boot, PostgreSQL, Kafka, Docker, REST APIs, Testcontainers, Git",
                "Projects", "Roster Tool", "Technologies: Java, Thymeleaf", "A small scheduling tool",
                "Certifications", "Certified Kubernetes Application Developer",
                ats ? "Example Training Foundation" : "Sep 2022", ats ? "Sep 2022" : "Example Training Foundation");
    }

    @ParameterizedTest
    @EnumSource(RenderTemplate.class)
    void nothingIsDuplicatedGarbledOrMissingItsGlyph(RenderTemplate template) throws IOException {
        String text = flat(text(pdf(RenderingFixtures.shortResume(), template, PageFormat.A4, "short")));

        assertThat(count(text, NAME)).isEqualTo(1);
        assertThat(count(text, "Senior Backend Engineer")).isEqualTo(1);
        assertThat(count(text, "Cut p95 latency")).isEqualTo(1);
        for (String heading : List.of("Summary", "Experience", "Education", "Skills", "Projects", "Certifications")) {
            assertThat(count(text, " " + heading + " ") + (text.startsWith(heading + " ") ? 1 : 0)).as(heading)
                    .isEqualTo(1);
        }
        // No replacement characters, private-use or control characters, i.e. no missing glyphs.
        assertThat(text).doesNotContain("�");
        assertThat(Pattern.compile("[\\p{Co}\\p{Cc}\\p{Cn}]").matcher(text).find()).isFalse();
        // Ligature-capable pairs come out as plain letters, not as presentation forms.
        assertThat(text).doesNotContain("ﬁ").doesNotContain("ﬂ");
        // The letters of the Yoruba name are intact, each mark on its own base.
        assertThat(text).contains("Ṣọlá").contains("Ọ̀gúnlẹ́yẹ")
                .contains("Ìbàdàn").contains("Akòkó");
    }

    @ParameterizedTest
    @EnumSource(RenderTemplate.class)
    void theBulletsOfOneJobAreContiguousAndNeverInterleaved(RenderTemplate template) throws IOException {
        String text = flat(text(pdf(RenderingFixtures.shortResume(), template, PageFormat.A4, "short")));
        int first = text.indexOf("Senior Backend Engineer");
        int second = text.indexOf("Software Engineer");
        int third = text.indexOf("Web Developer");
        int education = text.indexOf("Education");

        for (String bullet : List.of("Cut p95", "Led four engineers", "Introduced contract tests",
                "Moved nightly reconciliation")) {
            assertThat(text.indexOf(bullet)).isBetween(first, second);
        }
        for (String bullet : List.of("Built REST APIs", "Containerised twelve", "Mentored three")) {
            assertThat(text.indexOf(bullet)).isBetween(second, third);
        }
        assertThat(text.indexOf("Delivered small business sites")).isBetween(third, education);
    }

    /**
     * A single column reads the same whether the extractor follows the content stream or sorts by position: with a
     * second column (or a table, or a floating box) the two would disagree.
     */
    @ParameterizedTest
    @EnumSource(RenderTemplate.class)
    void streamOrderAndPositionOrderAgreeSoThereIsOneColumn(RenderTemplate template) throws IOException {
        byte[] bytes = pdf(RenderingFixtures.longResume(5, 6, 3), template, PageFormat.A4, "columns");
        try (PDDocument document = Loader.loadPDF(bytes)) {
            String stream = flat(new PDFTextStripper().getText(document));
            PDFTextStripper sorted = new PDFTextStripper();
            sorted.setSortByPosition(true);
            assertThat(flat(sorted.getText(document))).isEqualTo(stream);
        }
    }

    @ParameterizedTest
    @EnumSource(RenderTemplate.class)
    void theFileHasNoImagesAnnotationsOrUnembeddedFontsAndCarriesMetadata(RenderTemplate template) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf(RenderingFixtures.shortResume(), template, PageFormat.A4, "short"))) {
            assertThat(document.getDocumentInformation().getTitle()).isEqualTo(NAME + " - Resume");
            assertThat(document.getDocumentInformation().getAuthor()).isEqualTo(NAME);
            assertThat(document.getDocumentCatalog().getLanguage()).isEqualTo("en");
            assertThat(document.getDocumentCatalog().getViewerPreferences().displayDocTitle()).isTrue();
            for (PDPage page : document.getPages()) {
                assertThat(page.getResources().getXObjectNames()).isEmpty();
                assertThat(page.getAnnotations()).isEmpty();
                for (var name : page.getResources().getFontNames()) {
                    PDFont font = page.getResources().getFont(name);
                    assertThat(font.isEmbedded()).as("font %s embedded", font.getName()).isTrue();
                }
            }
        }
    }

    @Test
    void theOutputIsDeterministic() {
        for (RenderTemplate template : RenderTemplate.values()) {
            assertThat(pdf(RenderingFixtures.shortResume(), template, PageFormat.A4, "short"))
                    .isEqualTo(pdf(RenderingFixtures.shortResume(), template, PageFormat.A4, "short"));
        }
    }

    // ---------------------------------------------------------------------------------------------- page sizes

    @ParameterizedTest
    @EnumSource(RenderTemplate.class)
    void a4AndLetterHaveTheirOwnPageSize(RenderTemplate template) throws IOException {
        try (PDDocument a4 = Loader.loadPDF(pdf(RenderingFixtures.shortResume(), template, PageFormat.A4, "short"));
                PDDocument letter = Loader.loadPDF(pdf(RenderingFixtures.shortResume(), template, PageFormat.LETTER, "short"))) {
            assertThat(a4.getPage(0).getMediaBox().getWidth()).isCloseTo(595.28f, org.assertj.core.data.Offset.offset(0.1f));
            assertThat(a4.getPage(0).getMediaBox().getHeight()).isCloseTo(841.89f, org.assertj.core.data.Offset.offset(0.1f));
            assertThat(letter.getPage(0).getMediaBox().getWidth()).isEqualTo(612f);
            assertThat(letter.getPage(0).getMediaBox().getHeight()).isEqualTo(792f);
            assertThat(flat(new PDFTextStripper().getText(letter))).isEqualTo(flat(new PDFTextStripper().getText(a4)));
        }
    }

    // ------------------------------------------------------------------------------------------------- long

    @ParameterizedTest
    @EnumSource(RenderTemplate.class)
    void aLongResumeFlowsAcrossPagesInOrderWithEveryJobsBulletsTogether(RenderTemplate template) throws IOException {
        byte[] bytes = pdf(RenderingFixtures.longResume(12, 8, 4), template, PageFormat.A4, "long");
        String text = flat(text(bytes));
        try (PDDocument document = Loader.loadPDF(bytes)) {
            assertThat(document.getNumberOfPages()).isGreaterThanOrEqualTo(3);
        }
        assertThat(text).startsWith(NAME);
        List<String> needles = new ArrayList<>(List.of(NAME, "Summary", "Experience"));
        for (int job = 1; job <= 12; job++) {
            needles.add("Engineer Level " + job);
            needles.add("Company " + job + " Ltd");
            for (int bullet = 1; bullet <= 8; bullet++) {
                needles.add("Job " + job + " bullet " + bullet + ":");
            }
        }
        needles.addAll(List.of("Education", "Skills", "Projects", "Certifications"));
        assertInOrder(text, needles.toArray(String[]::new));
        for (int job = 1; job <= 12; job++) {
            assertThat(count(text, "Job " + job + " bullet 1:")).isEqualTo(1);
            assertThat(count(text, "Job " + job + " bullet 8:")).isEqualTo(1);
        }
    }

    /**
     * Moving the summary's length moves every page break. At no break may a section heading, or the lines that head
     * an entry, be the last thing on a page.
     */
    @ParameterizedTest
    @EnumSource(RenderTemplate.class)
    void noPageEndsOnAHeadingOrAnEntrysTitleLines(RenderTemplate template) throws IOException {
        for (int summaryLines = 1; summaryLines <= 14; summaryLines++) {
            byte[] bytes = pdf(RenderingFixtures.longResume(9, 5, summaryLines), template, PageFormat.A4,
                    "orphans-" + summaryLines);
            try (PDDocument document = Loader.loadPDF(bytes)) {
                for (int page = 1; page <= document.getNumberOfPages(); page++) {
                    PDFTextStripper stripper = new PDFTextStripper();
                    stripper.setStartPage(page);
                    stripper.setEndPage(page);
                    List<String> lines = Arrays.stream(stripper.getText(document).split("\\R")).map(String::strip)
                            .filter(l -> !l.isEmpty()).toList();
                    String last = lines.get(lines.size() - 1);
                    assertThat(HEADINGS).as("page %d of variant %d ends with a heading", page, summaryLines)
                            .doesNotContain(last);
                    assertThat(last).as("page %d of variant %d ends with an entry title or company line", page,
                            summaryLines).doesNotStartWith("Engineer Level").doesNotStartWith("Company ");
                    if (page > 1) {
                        // A continued list starts with a bullet or a heading-led block, never with a stray fragment.
                        assertThat(lines.get(0)).doesNotStartWith("Company ");
                    }
                }
            }
        }
    }

    // ----------------------------------------------------------------------------------------- sparse and odd

    @ParameterizedTest
    @EnumSource(RenderTemplate.class)
    void emptySectionsLeaveNoHeadingBehind(RenderTemplate template) throws IOException {
        String text = flat(text(pdf(RenderingFixtures.sparseResume(), template, PageFormat.A4, "sparse")));

        assertThat(text).startsWith("Chi Àyọdélé");
        assertInOrder(text, "Chi Àyọdélé", "Abuja, Nigeria", "Education",
                "Federal Polytechnic Example", "Skills", "Python, SQL");
        assertThat(text).doesNotContain("Summary").doesNotContain("Experience").doesNotContain("Projects")
                .doesNotContain("Certifications");
    }

    @ParameterizedTest
    @EnumSource(RenderTemplate.class)
    void anEntirelyEmptyResumeStillRendersOnePage(RenderTemplate template) throws IOException {
        JsonNode empty = RenderingFixtures.JSON.readTree("{}");
        byte[] bytes = pdf(empty, template, PageFormat.LETTER, "empty");
        try (PDDocument document = Loader.loadPDF(bytes)) {
            assertThat(document.getNumberOfPages()).isEqualTo(1);
            assertThat(document.getDocumentInformation().getTitle()).isEqualTo("Resume");
        }
        assertThat(flat(text(bytes))).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(RenderTemplate.class)
    void awkwardCharactersAndVeryLongTokensRenderAndExtractCleanly(RenderTemplate template) throws IOException {
        String text = flat(text(pdf(RenderingFixtures.awkwardResume(), template, PageFormat.A4, "awkward")));

        assertThat(text).contains("C++/C# & \"quoted\" <b>engineer</b> – 100% € £ ¥ “smart” quotes");
        // The tab, the zero-width space and the line breaks are gone; paragraphs stay apart.
        assertThat(text).contains("Line one with a tab and azero-width space.").contains("Paragraph two after a blank line.");
        // An emoji the font cannot draw is replaced, not allowed to break the render.
        assertThat(text).doesNotContain("🚀").contains("Rocket � emoji");
        // A 300 character token is broken across lines, all of it present.
        assertThat(text.replace(" ", "")).contains("x".repeat(300));
        assertThat(text).contains("Bullet with an embedded newline and a URL https://example.test/");
        assertThat(text).contains("fi fl ffi ffl ligature candidates: official, waffle, fluffy, finally");
        assertThat(text).contains("Skill number 59");
    }
}
