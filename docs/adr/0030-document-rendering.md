# 0030. Document rendering: PDF and DOCX in core-api

## Status
Accepted

## Context
P4.2 (`PLAN.md` section 7, "ATS-friendly PDF/DOCX rendering from templates (no tables/columns in the ATS template)"). An
approved tailored resume (ADR 0029) and the user's own resumes have to leave the system as a PDF or a DOCX that a person
can send and a recruiting system can read. "Can read" is the hard part: the file must give back its text in the order a
human reads it, with every letter intact. The names this product will see include Yoruba names (`Ṣọlá Ọ̀gúnlẹ́yẹ`) with
dots below and tone marks that exist only as combining characters, so Unicode is a requirement and not a nicety.

## Decision

### Where rendering lives: core-api, in a new `rendering` module
Rendering runs inside core-api on the JVM, not in ai-service.

| | core-api (PDFBox + POI) | ai-service (WeasyPrint + python-docx) |
| --- | --- | --- |
| New moving parts | two jars, no new service | WeasyPrint needs Pango, Cairo, HarfBuzz and fontconfig in a `python:3.12-slim` image (tens of MB, a new class of native-library CVEs); another network hop and failure mode between "approved" and "download" |
| Already present | PDFBox 3 would be needed anyway (tests) | `python-docx` (parsing, reading only), `pdfplumber` (parsing); no PDF writer |
| Who owns the file | the same service that owns the bucket, the user check and the deletion hook | ai-service is internal-only, has no storage and no user model, and "nothing here needs an LLM" |
| Determinism | PDFBox output is reproducible once the clock-derived parts are fixed (see below) | WeasyPrint embeds timestamps and ids; reproducible only with extra flags |

Rendering is CPU work on a few kilobytes of structured JSON, takes tens of milliseconds, and the result is cached, so
there is nothing to gain from a separate process. The cost is one more thing in the core-api jar (see "Image size").

A new `rendering` module (package `com.jobfinder.core.rendering`, everything in `internal`, no public API) owns the
templates, the two renderers, the cache table and the two endpoints. It reads through public APIs only:
`documents.ApprovedDocuments` (an approved document, plus a new `exists` so a draft is a 409 and not a 404) and the new
`profile.ResumeContents` (the latest content of one of the caller's resumes). Neither depends on `rendering`.

### Object storage becomes a module of its own: `storage`
CVs already use an S3 client, a presigner, a private bucket and an `ObjectStorage` port inside `profile.internal`. Rendered
files need exactly that, and `profile.internal` is private to `profile`. Duplicating the client would have meant two
configurations of one bucket. So the port (`ObjectStorage`, `ObjectNotFoundException`) moved to a public package
`com.jobfinder.core.storage`, the S3 implementation, client configuration and `app.storage.*` properties to
`storage.internal`, and `profile` now uses the port like `rendering` does. Behaviour, properties and keys of CVs are
unchanged (their tests pass untouched). Two additions: `exists(key)` (so a cache row whose file went missing is rendered
again), and a stricter `Content-Disposition` builder (below).

### Libraries and licences
| Library | Version | Licence | Used for |
| --- | --- | --- | --- |
| Apache PDFBox (with FontBox, PDFBox-IO) | 3.0.7 | Apache-2.0 | PDF layout and writing; also text extraction in tests |
| Apache POI (poi-ooxml, with poi-ooxml-lite, XMLBeans, commons-compress, commons-io, commons-collections4, commons-math3, commons-codec, SparseBitSet, curvesapi, log4j-api) | 5.5.1 | Apache-2.0 (curvesapi: BSD-3-Clause; SparseBitSet: Apache-2.0) | DOCX |
| Noto Sans Regular, Bold, Italic (hinted TTF, 1.9 MB) | 2022 release | SIL OFL 1.1 (text in `rendering/fonts/OFL.txt`) | PDF text, embedded as subsets |

No AGPL, GPL or LGPL anywhere in the path. Alternatives that were rejected on licence: iText 7/8 and Flying Saucer with iText
(AGPL); OpenPDF and openhtmltopdf (LGPL/MPL, workable but heavier and no better at extraction); docx4j (Apache-2.0, but
a much larger dependency tree than POI for the same output). The OFL permits bundling and embedding in documents. Fonts
are bundled in the jar and loaded from there: nothing is read from the operating system, so the Alpine JRE image (which
has no fonts at all) renders exactly like a laptop.

### Fonts, Unicode and extraction
- Text is Unicode NFC. Every code point is drawn as one glyph (no shaping engine). For Latin this is correct: Noto Sans
  places combining marks over their base letters without GPOS shaping, which was checked by eye at 300 dpi for
  `Ọ̀` (O, dot below, combining grave) and `ẹ́` (e, dot below, combining acute). A script that needs shaping (Arabic,
  Devanagari) is out of scope.
- Fonts are embedded as subsets with a `ToUnicode` map, so a copy-paste or an ATS gets the real characters back.
  Ligatures are never substituted, so `fi` is two letters and not U+FB01.
- A character the font does not have (an emoji, a rare symbol) is replaced by U+FFFD instead of failing the render.
  Control and format characters (zero-width spaces, soft hyphens, bidi controls, tabs, NBSP) are removed or turned into a
  plain space before layout; this is one function used by both renderers.
- The DOCX does not embed fonts (POI cannot). It names Arial (ATS) or Calibri (styled), both of which contain the Latin
  Extended Additional letters of Yoruba; Word positions the marks itself.

### Templates
Both templates are a single column, and both read the same neutral model (`ResumeModel`), so the PDF and the DOCX of one
template say the same things in the same order. Sections appear in a fixed order and only when they have content:
Summary, Experience, Education, Skills, Projects, Certifications. An empty section leaves no heading behind; an
entirely empty resume renders one blank page with the title "Resume".

- **`ats`**: black text on white, one font, no colour, no rules, no tables, no text boxes, no images, no icons, no headers
  or footers (contact details are in the body, directly under the name; a page-number footer would pollute extraction).
  Section headings are the standard words. An entry is a bold title line, then one line `Company, Place | Mar 2021 –
  Present`, then real bullets (`•` followed by text on the same line, the text hanging).
- **`styled`**: restrained and modern: an accent colour (`#1F4E79`) in a two-tone header band behind the name, headline and
  contact lines, thin rules under section headings, accent-coloured headings and bullets, dates right-aligned on the
  title's line, the company in italics. The text is still ordinary selectable text in the same order. (White text on a
  dark band is what a person sees too; nothing is hidden.)

**PDF specifics.** The layout is hand-written on PDFBox: greedy word wrap, hard break of a word wider than the line, and
pagination in groups that must stay together. A section heading travels with the first lines of its first entry; an
entry's title lines travel with its first bullet; a bullet is never split; a long paragraph (summary, skills) may split but
keeps its heading and first two lines together. Text is drawn top to bottom, one text object per run, so the order of the
content stream is the reading order. A4 (595.28 x 841.89 pt) and US Letter (612 x 792 pt). Metadata: title
(`<name> - Resume`), author, language `en`, "display document title". No timestamps, and a fixed trailer id (PDFBox
derives it from the clock otherwise), so the same input gives the same bytes (tested). There is no structure tree (tagged
PDF); the reading order is carried by the content stream, which is what text extractors and ATS parsers follow.

**DOCX specifics.** Real styles: Title (name), Subtitle (headline), Contact Info, Heading 1 (sections, `w:name` "heading 1",
outline level 0), Heading 2 (entries), Normal, Entry Detail and List Bullet, which is backed by a real numbering definition
(bullet `•`) and referenced from every bullet paragraph. Headings keep with the next paragraph and bullets keep their
lines together. The styled template puts the dates on the entry's line with a right-aligned tab stop, not a table. No
tables, text boxes, pictures, headers or footers (tested by reading the XML). DOCX zips carry write times, so DOCX bytes are
not reproducible; the cache makes that irrelevant.

### What can be rendered
- An **approved** tailored resume (`POST /documents/{id}/render`). A draft, in any state, is `409 document_not_approved`;
  someone else's document or an unknown id is `404 document_not_found` (never a 409, which would say it exists).
- The user's **own resume** (`POST /resumes/{id}/render`): users need CV export without tailoring, so the latest version of
  any of their resumes with content can be exported. No approval is involved because no model touched it; the content is
  what the user saved. `409 resume_content_required` while it has none, `404 resume_not_found` for someone else's.
  Which resume (primary or not) is the caller's choice; the web app will usually pass the primary one.

### Cache and storage
- **Index table `rendered_files` (V26)**: `user_id`, `source_type` (`DOCUMENT` or `RESUME_VERSION`), `source_id`,
  `content_sha256`, `renderer_version`, `template`, `format`, `page_size`, `storage_key` (unique), `file_sha256`,
  `size_bytes`, `created_at`. A unique index on (source, content hash, renderer version, template, format, page size)
  makes the cache exact. `PLAN.md` sketches a `file_key` column on `generated_documents`; that row is immutable once
  approved (V25), and a document has several files (template x format x page size), so the files get their own table. No
  foreign keys, as for `generated_documents`; `user_id` cascades.
- **Key**: `renders/<userId>/<sourceId>/<template>-<pageSize>-<first 16 hex of content hash>-r<renderer version>.<ext>`.
  Only ids, enums and a hash go in it; nothing the client sent.
- **Idempotent and never stale.** A request computes the SHA-256 of the exact structured JSON, looks the variant up and, if
  the row exists and the object is in storage, renders nothing (a test counts calls to the renderer: one for any number of
  identical requests). Approved content is immutable (V25 trigger), so a `DOCUMENT` entry can never go stale. For a
  `RESUME_VERSION` the user can still edit the latest version in place (ADR 0017), so the content hash is part of the key:
  an edit produces a new hash, a new row and a new object, and the old file stays until the account is deleted. When a
  template changes what it prints, `RENDERER_VERSION` is bumped and old files are never served for the new look.
- **Write order** follows ADR 0015: put the object, then insert the row. Two concurrent identical requests may both render
  (the bytes are the same, so is the key) and one insert is a no-op; the loser serves the winner's row. A failed insert
  removes the object; an index row whose file is gone is deleted and rendered again.
- **Status**: `201` with the file when this request rendered it, `200` when it already existed.

### Download
The response carries the file's metadata and a pre-signed link:
`{id, template, format, pageSize, filename, contentType, sizeBytes, sha256, downloadUrl, expiresAt, cached}`. The POST
returns the link directly, and two GET endpoints serve a later visit without rendering: `GET /documents/{id}/files` lists the
files rendered so far (metadata only, no link, no storage key) and `GET /documents/{id}/files/{fileId}/download` returns the
same body as the POST with a freshly signed link (a JSON body is easier for the client than a redirect). Both need the
document to be the caller's and approved (404/409 as for rendering); a file id of another document or user is
`404 file_not_found`. A resume export has no list: the POST is itself idempotent and re-signs at no render cost. The link is a pre-signed S3 GET, valid `app.rendering.download-url-ttl`
(default `5m`, as for CVs), with `response-content-disposition: attachment` and the stored content type
(`application/pdf`, or the DOCX type). `app.storage.public-endpoint` applies as for CVs. The bucket stays private. S3Mock
does not enforce expiry, so the tests check that the link is signed for the configured lifetime (`X-Amz-Expires`) and that
`expiresAt` matches, and that the link serves the identical bytes (same SHA-256 as the response and as the object).

**File name**: `Firstname_Lastname_Resume_<Company>.pdf` (the company only for a tailored document; the job's company is
copied into the document when the draft is made). The name and the company are untrusted text, so the file name is built only from
ASCII letters, digits, `_` and `-`: accents are folded (`Ọ̀gúnlẹ́yẹ` becomes `Ogunleye`), everything else becomes a
separator, each part is at most 30 characters, a name with no Latin letters is left out (`Resume.pdf`). `../`, quotes, CR/LF,
NUL, bidi controls, percent sequences and shell syntax cannot survive (tested with hostile inputs). Independently, the
storage layer builds the header defensively: quotes, backslashes, `;`, `/`, `%` and control characters become `_` in the
ASCII `filename`, and a non-ASCII name additionally gets an RFC 6266 `filename*=UTF-8''...`.

### Authorization and deletion
Every method takes the user from the token and scopes by it; a request for someone else's document or resume is a 404 and
creates nothing. `RenderingDeletionHandler` listens to `UserDeletionRequested` (like `ProfileDeletionHandler`): inside the
deleting transaction it deletes the user's `rendered_files` rows and every object under `renders/<userId>/`, so a storage
failure rolls the account deletion back and a file that lost its row is removed too.

### API and contract
Two render endpoints, same body and response (`RenderRequest`: `template` `ATS|STYLED`, `format` `PDF|DOCX`, `pageSize`
`A4|LETTER` (the field is called `pageSize`), all optional, defaulting to ATS, PDF, A4). An unknown value is a 400. `openapi.json` and the TypeScript
client were regenerated as described in `packages/api-contract/README.md`.

## Consequences
- core-api grows by two libraries (Image size below) and 1.9 MB of fonts. No new service, no new infrastructure, no new
  secret.
- The layout engine is ours. It is about 450 lines and handles what a resume needs (wrap, bullets, page groups, one column).
  It will not grow into a general typesetter; a new template is a new `Look` and, if it needs a new element, a change here.
- Right-to-left scripts and scripts that need shaping are not supported in the PDF (the glyphs are drawn one code point at
  a time). Names in Latin script with any diacritics are.
- Files are buffered in memory (a resume PDF is 30 to 100 KB), consistent with ADR 0015.
- Rendered files of a user are never deleted before the account is, even when superseded by an edit. They are small and
  private; a retention sweep can be added later without a schema change.
- Not part of this change: cover letters (P4.3), any UI (P4.4), the application tracker (P4.5), and attaching a rendered
  file to an application.
