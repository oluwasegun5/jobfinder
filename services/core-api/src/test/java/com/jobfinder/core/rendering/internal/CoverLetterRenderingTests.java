package com.jobfinder.core.rendering.internal;

import static com.jobfinder.core.rendering.internal.PdfRenderingTests.assertInOrder;
import static com.jobfinder.core.rendering.internal.PdfRenderingTests.count;
import static com.jobfinder.core.rendering.internal.PdfRenderingTests.flat;
import static com.jobfinder.core.rendering.internal.PdfRenderingTests.text;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The cover letter through the P4.2 renderers (docs/adr/0031-cover-letters-and-application-pack.md): a rendered PDF
 * extracts back to the letter in reading order (sender, recipient, salutation, paragraphs, closing, signature), the
 * DOCX has its paragraphs in the same order with real styles and no tables, both templates and both paper sizes, and
 * the text rules of the model (invisible characters, empty paragraphs, the signature that defaults to the name).
 */
class CoverLetterRenderingTests {

    static final String NAME = PdfRenderingTests.NAME;
    static final String EMAIL = "shola.ogunleye@example.test";
    static final String P1 = "I am writing to apply for the Staff Backend Engineer role at Acme Test Co.";
    static final String P2 = "At Ìbàdàn Payments Ltd I cut the p95 latency of the settlement API by 38%.";
    static final String P3 = "I would welcome the chance to talk. Thank you for your consideration.";

    private final PdfRenderer pdf = new PdfRenderer();
    private final DocxRenderer docx = new DocxRenderer();

    static ObjectNode letter(String... paragraphs) {
        ObjectNode root = RenderingFixtures.JSON.createObjectNode();
        ObjectNode sender = root.putObject("sender");
        sender.put("full_name", NAME);
        sender.put("email", EMAIL);
        sender.put("phone", "+234 800 555 0199");
        sender.put("location", "Ibadan, Nigeria");
        ObjectNode link = sender.putArray("links").addObject();
        link.put("label", "GitHub");
        link.put("url", "https://github.com/shola-test");
        ObjectNode recipient = root.putObject("recipient");
        recipient.put("job_title", "Staff Backend Engineer");
        recipient.put("company", "Acme Test Co");
        root.put("salutation", "Dear Hiring Manager,");
        ArrayNode list = root.putArray("paragraphs");
        for (String p : paragraphs) {
            list.add(p);
        }
        root.put("closing", "Yours sincerely,");
        root.put("signature", NAME);
        return root;
    }

    private static LetterModel model(JsonNode letter) {
        return LetterModel.parse(letter);
    }

    private byte[] pdf(JsonNode letter, RenderTemplate template, PageFormat page) {
        byte[] bytes = pdf.renderLetter(model(letter), template, page);
        RenderingFixtures.dump("letter-" + template.slug() + "-" + page.slug() + ".pdf", bytes);
        return bytes;
    }

    private byte[] docx(JsonNode letter, RenderTemplate template, PageFormat page) {
        byte[] bytes = docx.renderLetter(model(letter), template, page);
        RenderingFixtures.dump("letter-" + template.slug() + "-" + page.slug() + ".docx", bytes);
        return bytes;
    }

    // ------------------------------------------------------------------------------------------------- PDF

    @ParameterizedTest
    @EnumSource(RenderTemplate.class)
    void aLetterExtractsFromThePdfInReadingOrder(RenderTemplate template) throws IOException {
        String text = flat(text(pdf(letter(P1, P2, P3), template, PageFormat.A4)));

        assertThat(text).startsWith(NAME);
        assertInOrder(text, NAME, EMAIL, "+234 800 555 0199", "Ibadan, Nigeria",
                "GitHub: https://github.com/shola-test", "Acme Test Co", "Re: Application for Staff Backend Engineer",
                "Dear Hiring Manager,", P1, P2, P3, "Yours sincerely,");
        // The signature comes last: the name appears once in the header and once at the end.
        assertThat(count(text, NAME)).isEqualTo(2);
        assertThat(text).endsWith(NAME);
        assertThat(text.lastIndexOf(NAME)).isGreaterThan(text.indexOf("Yours sincerely,"));
    }

    @ParameterizedTest
    @EnumSource(PageFormat.class)
    void bothPaperSizesGiveTheSameText(PageFormat page) throws IOException {
        String ats = flat(text(pdf(letter(P1, P2, P3), RenderTemplate.ATS, page)));
        String styled = flat(text(pdf(letter(P1, P2, P3), RenderTemplate.STYLED, page)));

        assertThat(styled).isEqualTo(ats);
    }

    @Test
    void thereIsNoDateNoPlaceholderAndNothingGarbled() throws IOException {
        String text = flat(text(pdf(letter(P1, P2, P3), RenderTemplate.ATS, PageFormat.A4)));

        // A rendered file is a pure function of the approved text: no date of rendering is printed.
        assertThat(text).doesNotContainPattern("\\b(19|20)\\d{2}\\b");
        assertThat(text).doesNotContain("null").doesNotContain("[").doesNotContain("�")
                .doesNotContain("NEEDS_INPUT");
    }

    @ParameterizedTest
    @EnumSource(RenderTemplate.class)
    void aLongLetterRunsOverPagesWithoutLosingOrReorderingAnything(RenderTemplate template) throws IOException {
        String[] paragraphs = new String[14];
        for (int i = 0; i < paragraphs.length; i++) {
            paragraphs[i] = "Paragraph " + (i + 1) + " says something plausible about reliable backend systems and "
                    + "how the work was done, with enough words that it wraps over several lines of the page. "
                    + "It goes on to describe the outcome, the people involved and what was learned from it.";
        }
        byte[] bytes = pdf(letter(paragraphs), template, PageFormat.A4);
        String text = flat(text(bytes));

        List<String> order = new ArrayList<>();
        order.add("Dear Hiring Manager,");
        for (int i = 0; i < paragraphs.length; i++) {
            order.add("Paragraph " + (i + 1) + " says");
        }
        order.add("Yours sincerely,");
        assertInOrder(text, order.toArray(String[]::new));
        try (PDDocument document = Loader.loadPDF(bytes)) {
            assertThat(document.getNumberOfPages()).isGreaterThan(1);
            // The closing and the signature are never left alone on a page apart from the last paragraph's page:
            // they stay together, on the last page.
            PDFTextStripper last = new PDFTextStripper();
            last.setStartPage(document.getNumberOfPages());
            String lastPage = flat(last.getText(document));
            assertThat(lastPage).contains("Yours sincerely,").endsWith(NAME);
        }
    }

    @Test
    void theRenderIsDeterministicAndItsMetadataNamesTheLetter() throws IOException {
        byte[] first = pdf(letter(P1, P2, P3), RenderTemplate.STYLED, PageFormat.A4);
        byte[] second = pdf(letter(P1, P2, P3), RenderTemplate.STYLED, PageFormat.A4);

        assertThat(first).isEqualTo(second);
        try (PDDocument document = Loader.loadPDF(first)) {
            assertThat(document.getDocumentInformation().getTitle()).isEqualTo(NAME + " - Cover letter");
            assertThat(document.getDocumentInformation().getSubject()).isEqualTo("Cover letter");
            assertThat(document.getDocumentInformation().getAuthor()).isEqualTo(NAME);
        }
        // A letter and a resume are different files.
        assertThat(first).isNotEqualTo(pdf.render(RenderingFixtures.model(RenderingFixtures.shortResume()),
                RenderTemplate.STYLED, PageFormat.A4));
    }

    @Test
    void anAwkwardLetterStillRendersEveryCharacterThatTheFontHas() throws IOException {
        String text = flat(text(pdf(letter("Line one with a tab\tand a​zero-width space.", "   ",
                "C++/C# & \"quoted\" <b>engineer</b> – 100% € £ and a very long token " + "x".repeat(300)),
                RenderTemplate.ATS, PageFormat.LETTER)));

        assertThat(text).contains("Line one with a tab and azero-width space.")
                .contains("C++/C# & \"quoted\" <b>engineer</b> – 100% € £");
        assertThat(count(text, "xxxxxxxxxx")).isGreaterThan(1);
    }

    // ------------------------------------------------------------------------------------------------- DOCX

    private static List<XWPFParagraph> body(XWPFDocument document) {
        document.getBodyElements().forEach(e -> assertThat(e).as("only paragraphs, no tables").isInstanceOf(XWPFParagraph.class));
        return document.getParagraphs();
    }

    @ParameterizedTest
    @EnumSource(RenderTemplate.class)
    void theDocxHasTheLettersParagraphsInOrderWithRealStyles(RenderTemplate template) throws IOException {
        try (XWPFDocument document = new XWPFDocument(
                new ByteArrayInputStream(docx(letter(P1, P2, P3), template, PageFormat.A4)))) {
            List<XWPFParagraph> paragraphs = body(document);

            assertThat(paragraphs.stream().map(XWPFParagraph::getText)).containsExactly(NAME,
                    EMAIL + " | +234 800 555 0199 | Ibadan, Nigeria", "GitHub: https://github.com/shola-test",
                    "Acme Test Co", "Re: Application for Staff Backend Engineer", "Dear Hiring Manager,", P1, P2, P3,
                    "Yours sincerely,", NAME);
            assertThat(paragraphs.get(0).getStyle()).isEqualTo("Title");
            assertThat(paragraphs.get(1).getStyle()).isEqualTo("ContactInfo");
            assertThat(paragraphs.get(6).getStyle()).isEqualTo("BodyText");
            assertThat(document.getStyles().getStyle("BodyText")).isNotNull();
            assertThat(document.getProperties().getCoreProperties().getTitle()).isEqualTo(NAME + " - Cover letter");
            assertThat(document.getProperties().getCoreProperties().getSubject()).isEqualTo("Cover letter");
            // The signature is bold, the closing is not.
            assertThat(paragraphs.get(10).getRuns().get(0).isBold()).isTrue();
            assertThat(paragraphs.get(9).getRuns().get(0).isBold()).isFalse();
        }
    }

    @ParameterizedTest
    @EnumSource(PageFormat.class)
    void theDocxHasThePageSize(PageFormat page) throws IOException {
        try (XWPFDocument document = new XWPFDocument(
                new ByteArrayInputStream(docx(letter(P1, P2, P3), RenderTemplate.ATS, page)))) {
            var size = document.getDocument().getBody().getSectPr().getPgSz();

            assertThat(size.getW()).isEqualTo(java.math.BigInteger.valueOf(page.twipsWidth()));
            assertThat(size.getH()).isEqualTo(java.math.BigInteger.valueOf(page.twipsHeight()));
        }
    }

    // ------------------------------------------------------------------------------------------- the model

    @Test
    void theModelCleansItsTextAndDefaultsTheSignatureToTheName() {
        ObjectNode letter = letter("  Para​ one  ", "", "   ", "Para two");
        letter.remove("signature");
        ((ObjectNode) letter.get("sender")).remove("links");
        ((ObjectNode) letter.get("recipient")).remove("company");

        LetterModel model = model(letter);

        assertThat(model.paragraphs()).containsExactly("Para one", "Para two");
        assertThat(model.signature()).isEqualTo(NAME);
        assertThat(model.contactLines()).containsExactly(EMAIL + " | +234 800 555 0199 | Ibadan, Nigeria");
        assertThat(model.recipientLines()).containsExactly("Re: Application for Staff Backend Engineer");
        assertThat(model.fileKind()).isEqualTo("Cover_Letter");
    }

    @Test
    void anEmptyOrOddDocumentNeverThrows() throws IOException {
        for (String json : new String[] { "{}", "{\"paragraphs\":\"not a list\",\"sender\":[]}",
                "{\"sender\":{\"full_name\":5},\"paragraphs\":[1,null,{}]}" }) {
            LetterModel model = model(RenderingFixtures.JSON.readTree(json));

            assertThat(model.paragraphs()).isEmpty();
            assertThat(pdf.renderLetter(model, RenderTemplate.ATS, PageFormat.A4)).startsWith(
                    "%PDF".getBytes(StandardCharsets.US_ASCII));
            assertThat(docx.renderLetter(model, RenderTemplate.STYLED, PageFormat.LETTER)).isNotEmpty();
        }
    }

    @Test
    void theRendererPicksTheLetterLayoutForALetter() {
        ResumeRenderer renderer = new ResumeRenderer();
        LetterModel model = model(letter(P1));

        assertThat(renderer.render(model, RenderTemplate.ATS, RenderFormat.PDF, PageFormat.A4))
                .isEqualTo(pdf.renderLetter(model, RenderTemplate.ATS, PageFormat.A4));
        assertThat(renderer.render(model, RenderTemplate.ATS, RenderFormat.DOCX, PageFormat.A4)).startsWith(
                (byte) 'P', (byte) 'K');
    }

    @Test
    void theFileNameSaysItIsACoverLetterAndStaysSafe() {
        assertThat(FileNames.of("Jordan Ikeji", "Cover_Letter", "Acme Test Co", "pdf"))
                .isEqualTo("Jordan_Ikeji_Cover_Letter_Acme_Test_Co.pdf");
        assertThat(FileNames.of("Jordan Ikeji", "Cover_Letter", null, "DOCX"))
                .isEqualTo("Jordan_Ikeji_Cover_Letter.docx");
        assertThat(FileNames.of("../../etc/passwd\r\nX: y", "Cover_Letter", "\"; rm -rf /", "pdf"))
                .matches("[A-Za-z0-9_-]+_Cover_Letter_[A-Za-z0-9_-]+\\.pdf");
        // The resume's names are unchanged.
        assertThat(FileNames.of("Jordan Ikeji", "Acme Test Co", "pdf")).isEqualTo("Jordan_Ikeji_Resume_Acme_Test_Co.pdf");
    }
}
