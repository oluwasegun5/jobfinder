package com.jobfinder.core.rendering.internal;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.EnumMap;
import java.util.Map;

import org.apache.fontbox.ttf.CmapLookup;
import org.apache.fontbox.ttf.TTFParser;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDType0Font;

/**
 * The fonts of a PDF: Noto Sans (SIL OFL 1.1, bundled under {@code rendering/fonts}) in regular, bold and italic,
 * embedded as subsets so the PDF looks the same everywhere and extracts to the right Unicode text. Nothing is read
 * from the operating system, so a container without fonts renders identically.
 *
 * <p>Noto Sans covers Latin Extended Additional (the dot-below and tone-mark letters of Yoruba: {@code ẹ ọ ṣ}) and
 * the combining marks. Text is Unicode NFC and each code point is one glyph (no shaping), which is correct for
 * Latin; a character the font lacks is replaced rather than failing the render.
 */
final class PdfFonts {

    enum Face {
        REGULAR("NotoSans-Regular.ttf"), BOLD("NotoSans-Bold.ttf"), ITALIC("NotoSans-Italic.ttf");

        private final String file;

        Face(String file) {
            this.file = file;
        }
    }

    private static final Map<Face, byte[]> BYTES = new EnumMap<>(Face.class);
    private static final Map<Face, CmapLookup> CMAPS = new EnumMap<>(Face.class);

    private final Map<Face, PDType0Font> fonts = new EnumMap<>(Face.class);

    PdfFonts(PDDocument document) {
        for (Face face : Face.values()) {
            try (InputStream in = new ByteArrayInputStream(bytes(face))) {
                fonts.put(face, PDType0Font.load(document, in, true));
            } catch (IOException e) {
                throw new UncheckedIOException("Could not load font " + face.file, e);
            }
        }
    }

    PDType0Font font(Face face) {
        return fonts.get(face);
    }

    /** Width of {@code text} (already {@link #printable}) in points. */
    float width(Face face, float size, String text) {
        try {
            return fonts.get(face).getStringWidth(text) / 1000f * size;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The text with every code point the font cannot draw replaced by U+FFFD (or '?'), so encoding cannot fail. */
    String printable(Face face, String text) {
        StringBuilder out = new StringBuilder(text.length());
        text.codePoints().forEach(cp -> {
            if (hasGlyph(face, cp)) {
                out.appendCodePoint(cp);
            } else {
                out.appendCodePoint(hasGlyph(face, 0xFFFD) ? 0xFFFD : '?');
            }
        });
        return out.toString();
    }

    private static boolean hasGlyph(Face face, int codePoint) {
        CmapLookup cmap = cmap(face);
        synchronized (cmap) {
            return cmap.getGlyphId(codePoint) > 0;
        }
    }

    private static synchronized byte[] bytes(Face face) {
        return BYTES.computeIfAbsent(face, f -> {
            try (InputStream in = PdfFonts.class.getResourceAsStream("/rendering/fonts/" + f.file)) {
                if (in == null) {
                    throw new IllegalStateException("Missing bundled font " + f.file);
                }
                return in.readAllBytes();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    private static synchronized CmapLookup cmap(Face face) {
        return CMAPS.computeIfAbsent(face, f -> {
            try (TrueTypeFont ttf = new TTFParser().parse(new RandomAccessReadBuffer(bytes(f)))) {
                return ttf.getUnicodeCmapLookup();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }
}
