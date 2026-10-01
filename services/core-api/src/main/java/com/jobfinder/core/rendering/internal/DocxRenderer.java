package com.jobfinder.core.rendering.internal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigInteger;

import org.apache.poi.xwpf.usermodel.XWPFAbstractNum;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFNumbering;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFStyle;
import org.apache.poi.xwpf.usermodel.XWPFStyles;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTAbstractNum;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTLvl;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPBdr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPPrGeneral;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTRPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSectPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTStyle;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTabStop;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STBorder;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STNumberFormat;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STStyleType;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STTabJc;

import com.jobfinder.core.rendering.internal.ResumeModel.Entry;

/**
 * Writes a {@link ResumeModel} as a DOCX with Apache POI: real paragraph styles (Title, Heading 1, List Bullet,
 * Normal), a real bullet list definition, no tables, text boxes, images, headers or footers, and every paragraph in
 * reading order. ATS parsers that read Word files look for exactly these styles.
 *
 * <p>Fonts are not embedded (POI cannot): the document names Arial (ATS) or Calibri (styled), both of which have the
 * Latin Extended Additional letters of Yoruba, and Word positions combining marks itself.
 */
final class DocxRenderer {

    private static final String BODY_FONT_ATS = "Arial";
    private static final String BODY_FONT_STYLED = "Calibri";
    private static final String ACCENT = "1F4E79";

    byte[] render(ResumeModel m, RenderTemplate template, PageFormat page) {
        boolean styled = template == RenderTemplate.STYLED;
        try (XWPFDocument doc = new XWPFDocument()) {
            BigInteger numId = numbering(doc);
            styles(doc, styled, numId);
            page(doc, page);
            int rightTab = page.twipsWidth() - 2 * 1080;

            if (!m.name().isEmpty()) {
                paragraph(doc, "Title").createRun().setText(m.name());
            }
            if (!m.headline().isEmpty()) {
                paragraph(doc, "Subtitle").createRun().setText(m.headline());
            }
            for (String contact : m.contactLines()) {
                paragraph(doc, "ContactInfo").createRun().setText(contact);
            }
            if (!m.summary().isEmpty()) {
                heading(doc, "Summary");
                for (String line : m.summary().split("\n")) {
                    paragraph(doc, "Normal").createRun().setText(line);
                }
            }
            if (!m.experience().isEmpty()) {
                heading(doc, "Experience");
                entries(doc, m.experience(), styled, numId, rightTab);
            }
            if (!m.education().isEmpty()) {
                heading(doc, "Education");
                entries(doc, m.education(), styled, numId, rightTab);
            }
            if (!m.skills().isEmpty()) {
                heading(doc, "Skills");
                paragraph(doc, "Normal").createRun().setText(String.join(", ", m.skills()));
            }
            if (!m.projects().isEmpty()) {
                heading(doc, "Projects");
                entries(doc, m.projects(), styled, numId, rightTab);
            }
            if (!m.certifications().isEmpty()) {
                heading(doc, "Certifications");
                entries(doc, m.certifications(), styled, numId, rightTab);
            }

            var core = doc.getProperties().getCoreProperties();
            core.setTitle(m.name().isEmpty() ? "Resume" : m.name() + " - Resume");
            if (!m.name().isEmpty()) {
                core.setCreator(m.name());
            }
            core.setSubjectProperty("Resume");
            doc.getProperties().getExtendedProperties().setApplication("JobFinder");

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // --------------------------------------------------------------------------------------------------- content

    private void entries(XWPFDocument doc, java.util.List<Entry> entries, boolean styled, BigInteger numId,
            int rightTab) {
        for (Entry e : entries) {
            XWPFParagraph title = paragraph(doc, "Heading2");
            title.createRun().setText(e.title());
            String second = e.detail();
            if (styled && !e.dates().isEmpty()) {
                // A right-aligned tab stop, not a table: the date sits on the title's line for the eye, and is
                // plain text after the title for a parser.
                CTTabStop tab = title.getCTP().getPPr().addNewTabs().addNewTab();
                tab.setVal(STTabJc.RIGHT);
                tab.setPos(BigInteger.valueOf(rightTab));
                XWPFRun run = title.createRun();
                run.addTab();
                run.setText(e.dates());
                run.setBold(false);
                run.setFontSize(9);
            } else {
                second = ResumeModel.join(" | ", e.detail(), e.dates());
            }
            if (!second.isEmpty()) {
                XWPFParagraph detail = paragraph(doc, "EntryDetail");
                detail.createRun().setText(second);
            }
            if (!e.text().isEmpty()) {
                paragraph(doc, "Normal").createRun().setText(e.text());
            }
            for (String bullet : e.bullets()) {
                XWPFParagraph p = paragraph(doc, "ListBullet");
                p.setNumID(numId);
                p.createRun().setText(bullet);
            }
        }
    }

    private static XWPFParagraph paragraph(XWPFDocument doc, String style) {
        XWPFParagraph p = doc.createParagraph();
        p.setStyle(style);
        if (p.getCTP().getPPr() == null) {
            p.getCTP().addNewPPr();
        }
        return p;
    }

    private static void heading(XWPFDocument doc, String text) {
        paragraph(doc, "Heading1").createRun().setText(text);
    }

    // ----------------------------------------------------------------------------------------- page and styles

    private void page(XWPFDocument doc, PageFormat page) {
        CTSectPr sect = doc.getDocument().getBody().addNewSectPr();
        sect.addNewPgSz().setW(BigInteger.valueOf(page.twipsWidth()));
        sect.getPgSz().setH(BigInteger.valueOf(page.twipsHeight()));
        var margin = sect.addNewPgMar();
        BigInteger twips = BigInteger.valueOf(1080);
        margin.setTop(twips);
        margin.setBottom(twips);
        margin.setLeft(twips);
        margin.setRight(twips);
        margin.setHeader(BigInteger.valueOf(720));
        margin.setFooter(BigInteger.valueOf(720));
    }

    private static BigInteger numbering(XWPFDocument doc) {
        XWPFNumbering numbering = doc.createNumbering();
        CTAbstractNum abstractNum = CTAbstractNum.Factory.newInstance();
        abstractNum.setAbstractNumId(BigInteger.ZERO);
        CTLvl level = abstractNum.addNewLvl();
        level.setIlvl(BigInteger.ZERO);
        level.addNewStart().setVal(BigInteger.ONE);
        level.addNewNumFmt().setVal(STNumberFormat.BULLET);
        level.addNewLvlText().setVal("•");
        level.addNewPPr().addNewInd();
        level.getPPr().getInd().setLeft(BigInteger.valueOf(360));
        level.getPPr().getInd().setHanging(BigInteger.valueOf(240));
        BigInteger abstractId = numbering.addAbstractNum(new XWPFAbstractNum(abstractNum));
        return numbering.addNum(abstractId);
    }

    private static void styles(XWPFDocument doc, boolean styled, BigInteger numId) {
        XWPFStyles styles = doc.createStyles();
        String font = styled ? BODY_FONT_STYLED : BODY_FONT_ATS;
        String accent = styled ? ACCENT : "000000";
        int body = styled ? 20 : 21;

        CTStyle normal = style("Normal", "Normal", STStyleType.PARAGRAPH, null);
        normal.setDefault(org.openxmlformats.schemas.officeDocument.x2006.sharedTypes.STOnOff1.ON);
        fonts(normal.addNewRPr(), font).addNewSz().setVal(BigInteger.valueOf(body));
        spacing(normal.addNewPPr(), 0, 40);
        styles.addStyle(new XWPFStyle(normal, styles));

        CTStyle title = style("Title", "Title", STStyleType.PARAGRAPH, "Normal");
        CTRPr titleRun = fonts(title.addNewRPr(), font);
        titleRun.addNewB();
        titleRun.addNewColor().setVal(accent);
        titleRun.addNewSz().setVal(BigInteger.valueOf(styled ? 52 : 40));
        spacing(title.addNewPPr(), 0, 40);
        styles.addStyle(new XWPFStyle(title, styles));

        CTStyle subtitle = style("Subtitle", "Subtitle", STStyleType.PARAGRAPH, "Normal");
        fonts(subtitle.addNewRPr(), font).addNewSz().setVal(BigInteger.valueOf(styled ? 24 : 22));
        spacing(subtitle.addNewPPr(), 0, 40);
        styles.addStyle(new XWPFStyle(subtitle, styles));

        CTStyle contact = style("ContactInfo", "Contact Info", STStyleType.PARAGRAPH, "Normal");
        fonts(contact.addNewRPr(), font).addNewSz().setVal(BigInteger.valueOf(19));
        spacing(contact.addNewPPr(), 0, 20);
        styles.addStyle(new XWPFStyle(contact, styles));

        CTStyle heading1 = style("Heading1", "heading 1", STStyleType.PARAGRAPH, "Normal");
        CTPPrGeneral h1 = heading1.addNewPPr();
        h1.addNewKeepNext();
        h1.addNewKeepLines();
        spacing(h1, 280, 80);
        h1.addNewOutlineLvl().setVal(BigInteger.ZERO);
        if (styled) {
            CTPBdr border = h1.addNewPBdr();
            border.addNewBottom().setVal(STBorder.SINGLE);
            border.getBottom().setSz(BigInteger.valueOf(6));
            border.getBottom().setSpace(BigInteger.valueOf(1));
            border.getBottom().setColor("9DB8D2");
        }
        CTRPr h1Run = fonts(heading1.addNewRPr(), font);
        h1Run.addNewB();
        h1Run.addNewColor().setVal(accent);
        h1Run.addNewSz().setVal(BigInteger.valueOf(styled ? 23 : 24));
        styles.addStyle(new XWPFStyle(heading1, styles));

        CTStyle heading2 = style("Heading2", "heading 2", STStyleType.PARAGRAPH, "Normal");
        CTPPrGeneral h2 = heading2.addNewPPr();
        h2.addNewKeepNext();
        h2.addNewKeepLines();
        spacing(h2, 140, 0);
        h2.addNewOutlineLvl().setVal(BigInteger.ONE);
        CTRPr h2Run = fonts(heading2.addNewRPr(), font);
        h2Run.addNewB();
        h2Run.addNewSz().setVal(BigInteger.valueOf(body + 1));
        styles.addStyle(new XWPFStyle(heading2, styles));

        CTStyle detail = style("EntryDetail", "Entry Detail", STStyleType.PARAGRAPH, "Normal");
        detail.addNewPPr().addNewKeepNext();
        spacing(detail.getPPr(), 0, 40);
        if (styled) {
            fonts(detail.addNewRPr(), font).addNewI();
        }
        styles.addStyle(new XWPFStyle(detail, styles));

        CTStyle bullet = style("ListBullet", "List Bullet", STStyleType.PARAGRAPH, "Normal");
        CTPPrGeneral bulletPPr = bullet.addNewPPr();
        bulletPPr.addNewNumPr().addNewNumId().setVal(numId);
        bulletPPr.getNumPr().addNewIlvl().setVal(BigInteger.ZERO);
        bulletPPr.addNewKeepLines();
        spacing(bulletPPr, 0, 30);
        bulletPPr.addNewInd().setLeft(BigInteger.valueOf(360));
        bulletPPr.getInd().setHanging(BigInteger.valueOf(240));
        styles.addStyle(new XWPFStyle(bullet, styles));
    }

    private static CTStyle style(String id, String name, STStyleType.Enum type, String basedOn) {
        CTStyle style = CTStyle.Factory.newInstance();
        style.setStyleId(id);
        style.setType(type);
        style.addNewName().setVal(name);
        if (basedOn != null) {
            style.addNewBasedOn().setVal(basedOn);
            style.addNewNext().setVal("Normal");
        }
        style.addNewQFormat();
        return style;
    }

    private static CTRPr fonts(CTRPr run, String font) {
        var fonts = run.addNewRFonts();
        fonts.setAscii(font);
        fonts.setHAnsi(font);
        fonts.setCs(font);
        fonts.setEastAsia(font);
        return run;
    }

    private static void spacing(CTPPrGeneral ppr, int beforeTwips, int afterTwips) {
        var spacing = ppr.isSetSpacing() ? ppr.getSpacing() : ppr.addNewSpacing();
        spacing.setBefore(BigInteger.valueOf(beforeTwips));
        spacing.setAfter(BigInteger.valueOf(afterTwips));
    }
}
