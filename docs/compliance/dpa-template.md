# Data processing agreement (TEMPLATE)

> **Template, not legal advice.** A skeleton for the lawyer to complete. Nothing here has been reviewed. Replace every
> bracketed item. It assumes JobFinder is the controller and the customer relationship, if any, is covered by a separate
> master agreement. Where a customer is itself a controller of its own users' data, adapt the roles.

Between **[COMPANY LEGAL NAME]**, [REGISTERED ADDRESS] ("Processor") and **[CUSTOMER NAME]**, [ADDRESS] ("Controller").

## 1. Subject matter, duration, nature and purpose
- Subject: processing of personal data to provide the JobFinder service (job search, matching, document tailoring,
  application tracking).
- Duration: the term of the master agreement, then deletion under section 8.
- Nature and purpose: storage, analysis by AI models, generation of documents, email notifications.
- Data subjects: [job seekers who are the Controller's users / employees / candidates].
- Categories of data: see `docs/compliance/data-inventory.md` (identity and contact data, CVs and employment history,
  preferences, usage records). Special categories: none intended; users should not upload them.

## 2. Controller's instructions
The Processor processes personal data only on documented instructions from the Controller, including by use of the
product's features, unless law requires otherwise (then it informs the Controller first where permitted).

## 3. Confidentiality
Persons authorised to process the data are bound by confidentiality. [Describe access control.]

## 4. Security
Measures as described in `docs/security-review.md` (OWASP ASVS L2 review) and ADR 0037, including: per-user access
control on every request, hashed credentials, private object storage with short-lived links, scrubbed logs, upload checks.
Public traffic is served over TLS with automatically renewed certificates. Database backups are taken daily, encrypted
with a passphrase before they leave the server and kept for 30 days (`docs/runbooks/backup-restore.md`). The incident
process is `docs/runbooks/incident.md`.
[Add the encryption at rest of the production environment as the hosting provider offers it (disk or volume encryption,
object storage encryption): confirm with the provider, do not assume. Do not claim certifications the company does not hold.]

## 5. Subprocessors
The Controller gives general authorisation for the subprocessors on `/subprocessors`. The Processor informs the Controller
[30] days before adding or replacing one, and the Controller may object on reasonable grounds. The Processor imposes
equivalent data protection obligations on each and remains liable for them.

## 6. International transfers
Transfers outside [Nigeria / the EEA / the UK] rely on [TRANSFER MECHANISM, e.g. standard contractual clauses, adequacy].

## 7. Assistance
The Processor helps the Controller answer data subject requests (the product provides self-service export and deletion),
carry out impact assessments and prior consultations, and meet breach duties.

## 8. Deletion and return
On termination, at the Controller's choice, the Processor returns (export) and deletes the data within [30] days, and
backups within 30 days (the backup retention period, ADR 0041). Records of AI calls (feature, model, token counts, cost) are kept without any
link to a person.

## 9. Personal data breach
The Processor notifies the Controller without undue delay and within [48] hours of becoming aware, with the information
needed to meet the Controller's duty to notify [the Nigeria Data Protection Commission / the supervisory authority].

## 10. Audits
The Processor makes available information needed to show compliance and allows audits [no more than once a year, on
[30] days' notice].

## 11. Liability, governing law
[LAWYER TO COMPLETE. Governing law and courts: [GOVERNING LAW AND COURTS].]

Signed: ______________________ (Processor)   ______________________ (Controller)   Date: __________
