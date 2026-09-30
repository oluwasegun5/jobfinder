package com.jobfinder.core.profile.internal;

/** The CV formats we accept. The format is decided by sniffing the bytes, never by the client. */
enum ResumeFormat {

    PDF("application/pdf", "pdf"),
    DOCX("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx");

    private final String contentType;
    private final String extension;

    ResumeFormat(String contentType, String extension) {
        this.contentType = contentType;
        this.extension = extension;
    }

    String contentType() {
        return contentType;
    }

    String extension() {
        return extension;
    }
}
