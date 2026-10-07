/**
 * Data retention (docs/adr/0040-compliance-and-account-data.md): the small public contract every module uses to say what
 * it deletes once data is past its retention period ({@link com.jobfinder.core.compliance.RetentionTask}) and the
 * periods themselves ({@link com.jobfinder.core.compliance.RetentionProperties}, {@code app.retention.*}). The daily
 * runner that calls every task, under a lock and with a metric per task, lives in {@code internal}. Depends on no other
 * module. Account deletion and the data export are the other half of the same policy and live with the modules that
 * hold the data ({@code identity.UserDeletionRequested}, {@code identity.UserDataExporter}).
 */
package com.jobfinder.core.compliance;
