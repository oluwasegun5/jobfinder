package com.jobfinder.core.rendering.internal;

/** The two looks of a rendered resume. Both are a single column; see docs/adr/0030-document-rendering.md. */
enum RenderTemplate {
    /** Plain, parser-first: no colour, rules, tables, images or icons; the safest choice for an online application. */
    ATS,
    /** Restrained and modern: an accent colour, a two-tone header band and thin rules, same reading order. */
    STYLED;

    String slug() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
