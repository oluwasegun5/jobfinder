package com.jobfinder.core.rendering.internal;

import org.springframework.stereotype.Component;

/**
 * Turns a resume or a cover letter into the bytes of a PDF or DOCX. The one place that actually renders, so a test can count renders
 * (the cache must make a repeated request render nothing).
 */
@Component
class ResumeRenderer {

    private final PdfRenderer pdf = new PdfRenderer();
    private final DocxRenderer docx = new DocxRenderer();

    byte[] render(Printable model, RenderTemplate template, RenderFormat format, PageFormat page) {
        if (model instanceof LetterModel letter) {
            return switch (format) {
                case PDF -> pdf.renderLetter(letter, template, page);
                case DOCX -> docx.renderLetter(letter, template, page);
            };
        }
        ResumeModel resume = (ResumeModel) model;
        return switch (format) {
            case PDF -> pdf.render(resume, template, page);
            case DOCX -> docx.render(resume, template, page);
        };
    }
}
