package com.jobfinder.core.rendering.internal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.interactive.viewerpreferences.PDViewerPreferences;
import org.apache.pdfbox.util.Matrix;

import com.jobfinder.core.rendering.internal.PdfFonts.Face;
import com.jobfinder.core.rendering.internal.ResumeModel.Entry;

/**
 * Lays a {@link ResumeModel} out on A4 or Letter pages with PDFBox and writes a PDF.
 *
 * <p>The layout is a single column, drawn top to bottom in the order of the model, one text object per run, so the
 * order of the content stream (which is what text extraction and most ATS parsers follow) is exactly the reading
 * order. There are no tables, text boxes, images or footers. Page breaks never leave a heading, an entry's title
 * lines or the first bullet of an entry alone at the bottom of a page: the heading and the first entry travel
 * together, and an entry's header stays with its first bullet. The output is deterministic: no timestamps, a fixed
 * document id, fonts embedded as subsets.
 */
final class PdfRenderer {

    private static final float[] BLACK = { 0f, 0f, 0f };
    private static final float[] WHITE = { 1f, 1f, 1f };

    /** The visual parameters of a template. */
    private record Look(float margin, float marginTop, float name, float headline, float contact, float body,
            float heading, float lead, float sectionGap, float entryGap, float bulletGap, float indent,
            float[] text, float[] accent, float[] soft, boolean band, boolean rules, boolean datesRight) {
    }

    private static final Look ATS = new Look(54f, 50f, 20f, 11f, 10f, 10.5f, 12f, 1.32f, 13f, 7f, 2f, 14f, BLACK,
            BLACK, BLACK, false, false, false);
    private static final Look STYLED = new Look(52f, 40f, 25f, 12f, 9.5f, 10f, 11.5f, 1.34f, 15f, 8f, 2.5f, 14f,
            new float[] { 0.13f, 0.14f, 0.17f }, new float[] { 0.12f, 0.31f, 0.47f },
            new float[] { 0.86f, 0.91f, 0.96f }, true, true, true);

    private record Seg(Face face, float size, float[] rgb, String text, float x, boolean right) {
    }

    /** One printed line: its height, the segments (placed left to right from {@code x}) and an optional rule. */
    private record Ln(float height, float size, List<Seg> segs, float[] rule) {
    }

    /**
     * Lines that belong together. The first {@code keepFirst} lines are never separated from each other by a page
     * break; later lines of a {@code splittable} group may start a new page (a long paragraph); a group that is not
     * splittable moves to the next page whole if it fits on one.
     */
    private record Group(float before, List<Ln> lines, int keepFirst, float[] band) {
    }

    byte[] render(ResumeModel model, RenderTemplate template, PageFormat page) {
        return write(template, page, model.name(), "Resume", layout -> layout.build(model));
    }

    /** A cover letter: the same fonts, looks and pager, a letter's blocks in reading order. */
    byte[] renderLetter(LetterModel model, RenderTemplate template, PageFormat page) {
        return write(template, page, model.name(), "Cover letter", layout -> layout.letter(model));
    }

    private byte[] write(RenderTemplate template, PageFormat page, String name, String kind,
            java.util.function.Function<Layout, List<Group>> build) {
        Look look = template == RenderTemplate.ATS ? ATS : STYLED;
        try (PDDocument document = new PDDocument()) {
            PdfFonts fonts = new PdfFonts(document);
            List<Group> groups = build.apply(new Layout(fonts, look, page));
            new Pager(document, fonts, look, page).draw(groups);
            metadata(document, name, kind);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void metadata(PDDocument document, String name, String kind) throws IOException {
        PDDocumentInformation info = document.getDocumentInformation();
        info.setTitle(name.isEmpty() ? kind : name + " - " + kind);
        if (!name.isEmpty()) {
            info.setAuthor(name);
        }
        info.setSubject(kind);
        info.setCreator("JobFinder");
        info.setProducer("JobFinder (Apache PDFBox)");
        document.getDocumentCatalog().setLanguage("en");
        PDViewerPreferences preferences = new PDViewerPreferences(new org.apache.pdfbox.cos.COSDictionary());
        preferences.setDisplayDocTitle(true);
        document.getDocumentCatalog().setViewerPreferences(preferences);
        // PDFBox derives the trailer /ID from the clock when none is set; a fixed one keeps the bytes reproducible.
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((name + "|" + document.getNumberOfPages()).getBytes(StandardCharsets.UTF_8));
            COSArray id = new COSArray();
            id.add(new COSString(java.util.Arrays.copyOf(digest, 16)));
            id.add(new COSString(java.util.Arrays.copyOf(digest, 16)));
            document.getDocument().getTrailer().setItem(COSName.ID, id);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------------------------------------------ layout

    private static final class Layout {

        private final PdfFonts fonts;
        private final Look look;
        private final float width;
        private final float left;

        Layout(PdfFonts fonts, Look look, PageFormat page) {
            this.fonts = fonts;
            this.look = look;
            this.left = look.margin();
            this.width = page.width() - 2 * look.margin();
        }

        List<Group> build(ResumeModel m) {
            List<Group> groups = new ArrayList<>();
            groups.add(header(m));
            if (!m.summary().isEmpty()) {
                List<Ln> lines = new ArrayList<>();
                for (String paragraph : m.summary().split("\n")) {
                    lines.addAll(paragraph(paragraph, look.body(), Face.REGULAR, look.text(), 0f));
                }
                section("Summary", List.of(new Group(look.entryGap(), lines, 2, null)), groups);
            }
            section("Experience", entries(m.experience(), true), groups);
            section("Education", entries(m.education(), false), groups);
            if (!m.skills().isEmpty()) {
                List<Ln> lines = paragraph(String.join(", ", m.skills()), look.body(), Face.REGULAR, look.text(), 0f);
                section("Skills", List.of(new Group(look.entryGap(), lines, 2, null)), groups);
            }
            section("Projects", entries(m.projects(), false), groups);
            section("Certifications", entries(m.certifications(), false), groups);
            return groups;
        }

        /** The letter: sender block, recipient, salutation, paragraphs, closing and signature, top to bottom. */
        List<Group> letter(LetterModel m) {
            List<Group> groups = new ArrayList<>();
            groups.add(header(m.name(), "", m.contactLines()));
            if (!m.recipientLines().isEmpty()) {
                List<Ln> lines = new ArrayList<>();
                for (String to : m.recipientLines()) {
                    lines.addAll(paragraph(to, look.body(), to.startsWith("Re: ") ? Face.BOLD : Face.REGULAR,
                            look.text(), 0f));
                }
                groups.add(new Group(look.sectionGap(), lines, lines.size(), null));
            }
            if (!m.salutation().isEmpty()) {
                List<Ln> lines = paragraph(m.salutation(), look.body(), Face.REGULAR, look.text(), 0f);
                groups.add(new Group(look.sectionGap(), lines, lines.size(), null));
            }
            for (String text : m.paragraphs()) {
                List<Ln> lines = paragraph(text, look.body(), Face.REGULAR, look.text(), 0f);
                groups.add(new Group(look.entryGap() + 4f, lines, Math.min(2, lines.size()), null));
            }
            List<Ln> close = new ArrayList<>();
            if (!m.closing().isEmpty()) {
                close.addAll(paragraph(m.closing(), look.body(), Face.REGULAR, look.text(), 0f));
            }
            if (!m.signature().isEmpty()) {
                close.add(new Ln(look.body() * look.lead() * 1.6f, look.body(), List.of(), null));
                close.addAll(paragraph(m.signature(), look.body(), Face.BOLD, look.text(), 0f));
            }
            if (!close.isEmpty()) {
                groups.add(new Group(look.sectionGap(), close, close.size(), null));
            }
            return groups;
        }

        private Group header(ResumeModel m) {
            return header(m.name(), m.headline(), m.contactLines());
        }

        private Group header(String name, String headline, List<String> contactLines) {
            float[] main = look.band() ? WHITE : look.text();
            float[] soft = look.band() ? look.soft() : look.text();
            List<Ln> lines = new ArrayList<>();
            if (!name.isEmpty()) {
                lines.addAll(paragraph(name, look.name(), Face.BOLD, main, 0f));
            }
            if (!headline.isEmpty()) {
                lines.addAll(paragraph(headline, look.headline(), Face.REGULAR, soft, 0f));
            }
            for (String contact : contactLines) {
                lines.addAll(paragraph(contact, look.contact(), Face.REGULAR, soft, 0f));
            }
            return new Group(0f, lines, lines.size(), look.band() && !lines.isEmpty() ? look.accent() : null);
        }

        /** Puts the heading in front of the first group so a heading is never alone at the bottom of a page. */
        private void section(String title, List<Group> body, List<Group> out) {
            if (body.isEmpty()) {
                return;
            }
            Ln heading = new Ln(look.heading() * look.lead() + (look.rules() ? 4f : 0f), look.heading(),
                    List.of(new Seg(Face.BOLD, look.heading(), look.rules() ? look.accent() : look.text(),
                            fonts.printable(Face.BOLD, title), left, false)),
                    look.rules() ? new float[] { 0.62f, 0.72f, 0.82f } : null);
            Group first = body.get(0);
            List<Ln> lines = new ArrayList<>();
            lines.add(heading);
            lines.addAll(first.lines());
            out.add(new Group(look.sectionGap(), lines, Math.min(lines.size(), 1 + first.keepFirst()), null));
            out.addAll(body.subList(1, body.size()));
        }

        private List<Group> entries(List<Entry> entries, boolean bullets) {
            List<Group> groups = new ArrayList<>();
            for (int i = 0; i < entries.size(); i++) {
                Entry entry = entries.get(i);
                List<Ln> header = entryHeader(entry);
                if (!entry.text().isEmpty()) {
                    header.addAll(paragraph(entry.text(), look.body(), Face.REGULAR, look.text(), 0f));
                }
                List<List<Ln>> bulletLines = new ArrayList<>();
                for (String bullet : entry.bullets()) {
                    bulletLines.add(bullet(bullet));
                }
                int keep = header.size();
                if (!bulletLines.isEmpty()) {
                    header.addAll(bulletLines.get(0));
                    keep = header.size();
                }
                groups.add(new Group(i == 0 ? 0f : look.entryGap(), header, keep, null));
                for (int b = 1; b < bulletLines.size(); b++) {
                    List<Ln> lines = bulletLines.get(b);
                    groups.add(new Group(look.bulletGap(), lines, lines.size(), null));
                }
            }
            return groups;
        }

        private List<Ln> entryHeader(Entry e) {
            List<Ln> lines = new ArrayList<>();
            float size = look.body();
            if (look.datesRight() && !e.dates().isEmpty()) {
                String dates = fonts.printable(Face.REGULAR, e.dates());
                float datesWidth = fonts.width(Face.REGULAR, size - 0.5f, dates);
                List<String> titleLines = wrap(Face.BOLD, size + 0.5f, e.title(), width - datesWidth - 12f);
                for (int i = 0; i < titleLines.size(); i++) {
                    List<Seg> segs = new ArrayList<>();
                    segs.add(new Seg(Face.BOLD, size + 0.5f, look.text(), titleLines.get(i), left, false));
                    if (i == 0) {
                        segs.add(new Seg(Face.REGULAR, size - 0.5f, look.accent(), dates, 0f, true));
                    }
                    lines.add(new Ln(size * look.lead(), size, segs, null));
                }
                if (!e.detail().isEmpty()) {
                    lines.addAll(paragraph(e.detail(), size, Face.ITALIC, look.text(), 0f));
                }
            } else {
                lines.addAll(paragraph(e.title(), size + 0.5f, Face.BOLD, look.text(), 0f));
                String second = ResumeModel.join(" | ", e.detail(), e.dates());
                if (!second.isEmpty()) {
                    lines.addAll(paragraph(second, size, Face.REGULAR, look.text(), 0f));
                }
            }
            return lines;
        }

        /** A bullet: the marker in the margin column, the text hanging after it, wrapped lines aligned with it. */
        private List<Ln> bullet(String text) {
            float size = look.body();
            float indent = look.indent();
            List<String> wrapped = wrap(Face.REGULAR, size, text, width - indent);
            List<Ln> lines = new ArrayList<>();
            for (int i = 0; i < wrapped.size(); i++) {
                List<Seg> segs = new ArrayList<>();
                if (i == 0) {
                    segs.add(new Seg(Face.REGULAR, size, look.accent(), fonts.printable(Face.REGULAR, "•"),
                            left + 2f, false));
                }
                segs.add(new Seg(Face.REGULAR, size, look.text(), wrapped.get(i), left + indent, false));
                lines.add(new Ln(size * look.lead(), size, segs, null));
            }
            return lines;
        }

        private List<Ln> paragraph(String text, float size, Face face, float[] rgb, float indent) {
            List<Ln> lines = new ArrayList<>();
            for (String line : wrap(face, size, text, width - indent)) {
                lines.add(new Ln(size * look.lead(), size, List.of(new Seg(face, size, rgb, line, left + indent, false)),
                        null));
            }
            return lines;
        }

        /** Greedy word wrap; a word wider than the line is split by characters. Every line is printable text. */
        private List<String> wrap(Face face, float size, String text, float maxWidth) {
            List<String> lines = new ArrayList<>();
            StringBuilder current = new StringBuilder();
            for (String word : fonts.printable(face, text).split(" ")) {
                if (word.isEmpty()) {
                    continue;
                }
                String candidate = current.isEmpty() ? word : current + " " + word;
                if (fonts.width(face, size, candidate) <= maxWidth) {
                    current = new StringBuilder(candidate);
                    continue;
                }
                if (!current.isEmpty()) {
                    lines.add(current.toString());
                    current = new StringBuilder();
                }
                String remaining = word;
                while (fonts.width(face, size, remaining) > maxWidth) {
                    int cut = fit(face, size, remaining, maxWidth);
                    lines.add(remaining.substring(0, cut));
                    remaining = remaining.substring(cut);
                }
                current = new StringBuilder(remaining);
            }
            if (!current.isEmpty()) {
                lines.add(current.toString());
            }
            return lines;
        }

        /** The longest prefix (in chars, never splitting a surrogate pair or a base from its marks) that fits. */
        private int fit(Face face, float size, String word, float maxWidth) {
            int end = 0;
            int i = 0;
            while (i < word.length()) {
                int cp = word.codePointAt(i);
                int next = i + Character.charCount(cp);
                while (next < word.length() && Character.getType(word.codePointAt(next)) == Character.NON_SPACING_MARK) {
                    next += Character.charCount(word.codePointAt(next));
                }
                if (fonts.width(face, size, word.substring(0, next)) > maxWidth) {
                    break;
                }
                end = next;
                i = next;
            }
            return Math.max(end, Math.min(word.length(), Character.charCount(word.codePointAt(0))));
        }
    }

    // ------------------------------------------------------------------------------------------------ pagination

    private static final class Pager {

        private final PDDocument document;
        private final Look look;
        private final PageFormat format;
        private final float bottom;
        private PDPageContentStream stream;
        private float y;
        private boolean atTop = true;
        private final PdfFonts fonts;

        Pager(PDDocument document, PdfFonts fonts, Look look, PageFormat format) {
            this.document = document;
            this.fonts = fonts;
            this.look = look;
            this.format = format;
            this.bottom = format.height() - look.marginTop();
        }

        void draw(List<Group> groups) throws IOException {
            newPage();
            try {
                for (Group group : groups) {
                    place(group);
                }
            } finally {
                stream.close();
            }
        }

        private void newPage() throws IOException {
            if (stream != null) {
                stream.close();
            }
            PDPage page = new PDPage(new PDRectangle(format.width(), format.height()));
            document.addPage(page);
            stream = new PDPageContentStream(document, page);
            y = look.marginTop();
            atTop = true;
        }

        private void place(Group group) throws IOException {
            if (group.lines().isEmpty()) {
                return;
            }
            float contentHeight = bottom - look.marginTop();
            float before = atTop ? 0f : group.before();
            float keepHeight = height(group.lines(), group.keepFirst());
            if (!atTop && y + before + keepHeight > bottom && keepHeight <= contentHeight) {
                newPage();
                before = 0f;
            }
            y += before;
            if (group.band() != null) {
                float h = height(group.lines(), group.lines().size()) + 18f;
                stream.setNonStrokingColor(group.band()[0], group.band()[1], group.band()[2]);
                stream.addRect(0, format.height() - (y + h), format.width(), y + h);
                stream.fill();
                stream.setNonStrokingColor(0f);
            }
            for (Ln line : group.lines()) {
                if (!atTop && y + line.height() > bottom) {
                    newPage();
                }
                draw(line);
                y += line.height();
                atTop = false;
            }
            if (group.band() != null) {
                y += 18f;
            }
        }

        private void draw(Ln line) throws IOException {
            float baseline = format.height() - (y + line.size() * 1.0f + (line.height() - line.size() * 1.36f) / 2f);
            for (Seg seg : line.segs()) {
                float x = seg.x();
                if (seg.right()) {
                    x = format.width() - look.margin() - fonts.width(seg.face(), seg.size(), seg.text());
                }
                stream.beginText();
                stream.setFont(fonts.font(seg.face()), seg.size());
                stream.setNonStrokingColor(seg.rgb()[0], seg.rgb()[1], seg.rgb()[2]);
                stream.setTextMatrix(Matrix.getTranslateInstance(x, baseline));
                stream.showText(seg.text());
                stream.endText();
            }
            if (line.rule() != null) {
                float ruleY = format.height() - (y + line.height() - 1f);
                stream.setStrokingColor(line.rule()[0], line.rule()[1], line.rule()[2]);
                stream.setLineWidth(0.7f);
                stream.moveTo(look.margin(), ruleY);
                stream.lineTo(format.width() - look.margin(), ruleY);
                stream.stroke();
            }
        }

        private static float height(List<Ln> lines, int count) {
            float h = 0f;
            for (int i = 0; i < Math.min(count, lines.size()); i++) {
                h += lines.get(i).height();
            }
            return h;
        }
    }
}
