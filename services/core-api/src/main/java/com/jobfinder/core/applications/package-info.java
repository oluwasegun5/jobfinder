/**
 * Application tracking (docs/adr/0032-application-tracker.md): the applications a user has made, saved or entered by
 * hand, their status history (the columns of a Kanban board), follow-up reminders sent by email, and an AI-written
 * follow-up email draft. It owns {@code applications}, {@code application_events} and {@code reminders}, reads other
 * modules through their public APIs only (jobs, documents, profile, billing, identity, notifications), and its public API is
 * {@link com.jobfinder.core.applications.ApplicationLookup} (is this application the user's, and for which job: mock
 * interviews can be tied to one); nothing else needs to know about applications.
 */
package com.jobfinder.core.applications;
