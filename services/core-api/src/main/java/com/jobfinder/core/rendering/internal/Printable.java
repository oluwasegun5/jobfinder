package com.jobfinder.core.rendering.internal;

/** A document the renderers can print: it has the name of the person it is from, and a kind for the file name. */
interface Printable {

    /** The full name of the person the document is from; may be empty. */
    String name();

    /** The kind as it appears in a file name: {@code Resume} or {@code Cover_Letter}. */
    String fileKind();
}
