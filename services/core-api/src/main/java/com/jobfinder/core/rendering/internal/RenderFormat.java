package com.jobfinder.core.rendering.internal;

import java.util.Locale;

enum RenderFormat {
    PDF("application/pdf", "pdf"),
    DOCX("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx");

    private final String contentType;
    private final String extension;

    RenderFormat(String contentType, String extension) {
        this.contentType = contentType;
        this.extension = extension;
    }

    String contentType() {
        return contentType;
    }

    String extension() {
        return extension;
    }

    String slug() {
        return name().toLowerCase(Locale.ROOT);
    }
}
