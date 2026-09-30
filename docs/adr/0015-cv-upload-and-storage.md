# 0015. CV upload and storage

## Status
Accepted

## Context
P1.4 adds CV upload to the `profile` module: PDF/DOCX up to 5 MB, private object storage, pre-signed
downloads, and erasure on account deletion (ADR 0013). `PLAN.md` §5 defines the four tables; several details
were left open.

## Decision
- **Sniff, don't trust.** The declared content type and file name are ignored. A PDF must start with
  `%PDF-`. A DOCX must be a ZIP whose central directory lists `[Content_Types].xml` and `word/document.xml`
  (and not `word/vbaProject.bin`, i.e. no macro-enabled `.docm`). The central directory is parsed directly
  instead of unzipping, so a decompression bomb costs nothing. The stored object gets the sniffed content type.
- **Keys are ours.** `resumes/<userId>/<resumeId>.<ext>`; nothing from the client goes into a key. The bucket
  is private and only pre-signed GET URLs (5 minutes, `Content-Disposition: attachment`) are ever handed out.
  `app.storage.public-endpoint` lets those URLs point at a host browsers can reach when it differs from the
  one core-api uses (compose network name vs. published port).
- **Storage client**: AWS SDK v2 `S3Client`/`S3Presigner` against any S3-compatible endpoint (S3Mock locally,
  R2 in production). No default credentials: startup fails without `OBJECT_STORAGE_ACCESS_KEY/SECRET_KEY`.
- **Write ordering.** Upload: put object, then insert rows; if the insert fails the object is removed. Delete:
  remove rows, then the object; a failed object delete is logged and leaves an unreferenced private file rather
  than a row pointing at nothing. Account deletion runs inside the deleting transaction and deletes files by
  the `resumes/<userId>/` prefix, so a storage failure rolls the whole deletion back and orphans are swept too.
- **Primary resume.** The first upload is primary; a partial unique index guarantees at most one per user.
  Deleting the primary promotes the newest remaining resume.
- **Limits** beyond the brief: at most 10 CVs per user (409), configurable (`app.resumes.max-per-user`), to bound
  storage per account. Multipart limits are set slightly above 5 MB so oversize files get our 413 problem detail.
- **Schema.** `profiles` and `preferences` are created now (tables only; their endpoints belong to P1.6).
  `resume_versions` gets version 1 (`source = UPLOAD`, `structured` NULL) on upload and omits the `embedding`
  column: its dimension depends on the embedding model chosen in Phase 2, and adding a column later is
  backward compatible. `resumes.parse_status` starts `PENDING`; P1.5 owns the transitions.
- **Tests** run against S3Mock through Testcontainers (`S3MockContainer`), pinned to the same version as compose.

## Consequences
- Files are buffered in memory (≤ 5 MB) for sniffing and upload; fine at this size, revisit if limits grow.
- DOCX detection is structural: a ZIP crafted to list those two names passes the sniffer. That is acceptable
  because files are never served inline or opened by core-api; the parser in P1.5 must still treat contents as
  untrusted.
- Legacy `.doc`, `.odt` and `.rtf` are rejected.
