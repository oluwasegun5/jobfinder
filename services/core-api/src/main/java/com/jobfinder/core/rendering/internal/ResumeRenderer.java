package com.jobfinder.core.rendering.internal;

import org.springframework.stereotype.Component;

/**
 * Turns a resume into the bytes of a PDF or DOCX. The one place that actually renders, so a test can count renders
 * (the cache must make a repeated request render nothing).
 */
@Component
class ResumeRenderer {

    private final PdfRenderer pdf = new PdfRenderer();
    private final DocxRenderer docx = new DocxRenderer();

    byte[] render(ResumeModel model, RenderTemplate template, RenderFormat format, PageFormat page) {
        return switch (format) {
            case PDF -> pdf.render(model, template, page);
            case DOCX -> docx.render(model, template, page);
        };
    }
}
