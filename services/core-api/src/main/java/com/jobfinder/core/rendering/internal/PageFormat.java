package com.jobfinder.core.rendering.internal;

import java.util.Locale;

/** Paper size: A4 (most of the world) or US Letter. Sizes in PDF points (1/72 inch) and DOCX twips (1/20 point). */
enum PageFormat {
    A4(595.276f, 841.89f, 11906, 16838),
    LETTER(612f, 792f, 12240, 15840);

    private final float width;
    private final float height;
    private final int twipsWidth;
    private final int twipsHeight;

    PageFormat(float width, float height, int twipsWidth, int twipsHeight) {
        this.width = width;
        this.height = height;
        this.twipsWidth = twipsWidth;
        this.twipsHeight = twipsHeight;
    }

    float width() {
        return width;
    }

    float height() {
        return height;
    }

    int twipsWidth() {
        return twipsWidth;
    }

    int twipsHeight() {
        return twipsHeight;
    }

    String slug() {
        return name().toLowerCase(Locale.ROOT);
    }
}
