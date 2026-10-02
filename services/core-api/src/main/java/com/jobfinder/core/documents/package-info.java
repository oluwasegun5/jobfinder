/**
 * AI-generated documents the user reviews and approves (docs/adr/0029-resume-tailoring.md): tailored resumes today
 * (cover letters come with P4.3). A draft is made from the user's primary resume and a job by ai-service, carries a
 * fact check against that resume, and can be edited change by change; an approved document is final and immutable.
 * Other modules (rendering, the application tracker) read approved documents through
 * {@link com.jobfinder.core.documents.ApprovedDocuments} (and the packs they were made in through
 * {@link com.jobfinder.core.documents.ApplicationPacks}); everything else lives in {@code internal}.
 */
package com.jobfinder.core.documents;
