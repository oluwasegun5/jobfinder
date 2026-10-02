/**
 * Renders a resume (an approved tailored resume, or one of the user's own resumes) to PDF or DOCX, keeps the files in
 * object storage and hands out short-lived download links (docs/adr/0030-document-rendering.md). The module has no
 * public API: it is reached through its REST endpoints, and everything lives in {@code internal}.
 */
package com.jobfinder.core.rendering;
