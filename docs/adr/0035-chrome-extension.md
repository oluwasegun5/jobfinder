# 0035. Chrome extension: user-triggered autofill that never submits

## Status
Accepted

## Context
P5.3 (`PLAN.md` section 7). Filling the same contact details and screening answers into every ATS form is the most repetitive
part of applying, and JobFinder already holds the data (profile, resumes, the application pack of ADR 0031). A browser
extension can fill a form on a page the user is looking at. It also runs inside pages we do not control, next to the user's
session with JobFinder, so the questions are what it may touch, what it may send, and where the credential lives.

## Decision

### 1. User-triggered only
Nothing runs against a form until the user presses "Fill from JobFinder", in the on-page panel or in the toolbar popup. The
content script, on page load, only detects the ATS and adds the panel. There is no background fill, no observer that fills
fields as they appear, and no fill on navigation.

### 2. The extension never submits
No code path calls `submit`, `requestSubmit` or `click`, or dispatches a key event. A unit test (`source-safety`, mutation
checked) scans the source for those tokens, and the end-to-end test asserts that the fixture's submit handler and the submit
button's click handler were never invoked (`window.__submitCount` and `__submitClicks` are 0). The user reviews the form and
submits it themselves. Only afterwards can they press "I submitted this application" (decision 8).

### 3. What is never filled
Demographic and EEO questions, work authorization, sponsorship and other legal attestations, government ID, passwords,
captchas, hidden fields, honeypots and anything unrecognised are not filled; they are listed under "Needs your input".
Selects, checkboxes and radios are never filled either (consent and attestation boxes live there). A value the user typed is
kept unless they click "Overwrite"; "Undo fill" restores every field the extension changed (and removes an attached CV).
The classification order is: never (password, captcha, choice controls), then the sensitive categories, then data keys, then
screening questions, then unknown. A sensitive match always wins over a data match.

### 4. Deterministic matching; page text is untrusted
Fields are recognised by normalised labels, names, ids, autocomplete tokens and placeholders against fixed patterns. Page text
is never sent to a server or a model, never evaluated, never used as a selector, and appears in the panel only via
`textContent`. A screening answer fills a field only when the normalised question closely matches (Dice similarity of at least
0.8, or an anchored pattern for a fixed answer id), is never the work-authorization answer, and is skipped when two answers
match equally.

### 5. What it fills, and from where
Names, email (`GET /auth/me`), phone, location and links (`GET /profile`), the cover letter and screening answers from the
approved parts of the job's application pack (`GET /application-packs/{id}`), and the CV. Only approved documents are used.
The CV is the approved tailored resume's rendered ATS file if the pack has one, else the primary resume; the service worker
asks for the existing pre-signed download URL (`/documents/{id}/files/{fileId}/download` or `/resumes/{id}/download-url`),
fetches the bytes (6 MB cap, no credentials, no redirects), and hands them to the content script, which attaches them with a
`DataTransfer`. No endpoint other than the one in decision 7 was added.

### 6. The access token, and the refresh cookie
The access token lives only in `chrome.storage.session` with its access level set to `TRUSTED_CONTEXTS`, so content scripts
cannot read it. Every core-api call is made from the service worker; the content script and popup only message it, and the
router refuses a sign-in that comes from a content script. The refresh token is an httpOnly `SameSite=Strict` cookie, so the
extension never holds it: the service worker calls `POST /auth/refresh` with `credentials: "include"` (concurrent callers share
one in-flight refresh) and falls back to asking the user to sign in. Logout clears the session storage and calls
`POST /auth/logout`.

**Result of testing this against a real core-api (the P5.3 test-run build, Playwright's Chromium, the extension's service worker):**
- Signing in from the popup stores the cookie `refresh_token`, `HttpOnly`, `Secure`, `SameSite=Strict`, **`Path=/api/core/auth`**.
- A service-worker `POST http://localhost:18080/auth/refresh` with `credentials: "include"` returns **401**: the cookie is not
  sent, because its path is `/api/core/auth` (the path the web app proxies under, ADR 0011), not `/auth`. The same call from an
  extension page also returns 401.
- The same extension built with `CORE_API_URL=http://localhost:18081/api/core`, a plain node proxy standing in for the Next
  rewrite `/api/core/:path*` to core-api, signs in, and the service worker's `POST /auth/refresh` with `credentials:
  "include"` returns **200 with a new access token**. `SameSite=Strict` did not stop it: requests from the service worker to a
  host it has host permission for carry the cookie.
- So silent refresh works when, and only when, the extension is pointed at the web origin's `/api/core`. The build-time
  `CORE_API_URL` may therefore include that path (only the origin goes into `host_permissions`), and the README says to use
  `https://<web origin>/api/core`. With the default of `http://localhost:8080` everything else works and the user signs in again
  when the session ends. We did not change the cookie's path: that is a core-api auth change outside this task. The end-to-end
  stub reproduces the real cookie (name, `Secure`, `SameSite`, path) so the test exercises the same behaviour. Not tested: a
  production HTTPS origin (the real run was `http://localhost`, which Chrome treats as secure for `Secure` cookies), and the
  Next rewrite itself (a stand-in proxy was used).

### 7. One read-only endpoint
`GET /extension/apply-context?url=` in the applications module returns the job matching the page URL, the user's application
for it if any, and a summary of the latest pack, so the extension knows which job and pack to use. It carries no profile data
and is scoped to the caller: an application or pack of another user is never revealed, and an unknown URL is a 404
(`job_not_found`) whatever the reason. The URL is canonicalised by `ApplyUrls` (Greenhouse board and job id with the
`job-boards` and embed forms mapped to `boards`; Lever and Ashby company and posting id; Workday without locale and `/apply`;
anything else host, path and sorted non-tracking query) and compared with the canonical form of each stored `apply_url`. Cross-module reads go through new public interfaces of the jobs
and documents modules (`JobApplyLinks`, `ApplicationPacks.latestFor`), so Spring Modulith still verifies. The candidate
lookup is a literal substring match on `jobs.apply_url` (LIKE wildcards escaped) followed by the exact comparison; it is a
sequential scan, which is fine at this size and should become an indexed canonical-URL column if the jobs table grows large.
OpenAPI and the TypeScript client are regenerated; the new records are named `ApplyContext*` because springdoc names schemas by
simple class name and a second `PackSummary` silently replaced an existing schema.

### 8. "I submitted this application"
After a fill the user can log the application. The service worker reads the application (when `apply-context` returned one),
otherwise creates it (`POST /applications`, with the pack and the documents used), and moves it to `APPLIED` through the status
endpoint only when it is still `SAVED`. Pressing the button again, or after the user already moved it, changes nothing. When
the page matched no job the button is not offered.

### 9. Manifest
`permissions: ["storage"]`. No `tabs` (the popup messages the active tab by the id from `chrome.tabs.query`, which needs no
permission), no `<all_urls>`, and no `activeTab`: the content script is declared statically for the four ATS hosts, so nothing
needs a click-time grant. `host_permissions` are the core-api origin and the object-storage origin (the CV link is
pre-signed on that host and fetched from the service worker; without it the fetch would need CORS on the bucket), both injected
at build time (core-api defaults to `http://localhost:8080`). Content scripts: `boards`/`job-boards` Greenhouse (and `.eu`),
`jobs.lever.co` (and `.eu`), `jobs.ashbyhq.com`, `*.myworkdayjobs.com`, top frame only. No remote code, no `eval`, no inline
script, no `externally_connectable`, no `web_accessible_resources`, default CSP. A unit test pins all of this.

### 10. Privacy
Only the page URL goes to core-api, in the `apply-context` query, over the user's own authenticated session. Nothing from the
form is sent anywhere. No profile, CV, answer or job text is logged. All fixtures and test data are synthetic.

## Fill depth, stated plainly
- **Greenhouse and Lever:** filled end to end (contact, links, cover letter, screening answers, CV), covered by unit tests on
  saved fixtures and by the Playwright test.
- **Ashby:** detection plus the contact fields and links of the first step. Unit tests on a saved fixture only.
- **Workday:** detection plus first name, last name, email, phone and city of "My Information". Unit tests on a saved fixture
  only. Workday's later steps, account creation and per-tenant variations are not handled.
- None of this has been run against the live sites; the fixtures are synthetic forms shaped like them, so a layout change on a
  real ATS can break matching until the fixture and patterns are updated.

## Deviations and details
- `host_permissions` include the storage origin (decision 9), beyond "core-api only".
- `apply-context` does not return profile data; the extension reads `/profile` and `/auth/me` itself.
- The pack detail endpoint is used instead of the list.
- An unmatched job (404) can still be filled from the profile; logging is then disabled.
- The end-to-end build sets `PANEL_OPEN=true` so Playwright can reach into the panel's shadow root; releases use a closed root.
- Greenhouse forms embedded in an iframe on a company's own site are not covered (no `all_frames`).
- No extension icons; store packaging and publishing are out of scope.

## Consequences
- The extension adds a fifth package (`extension/`, an npm workspace) with its own CI workflow.
- Because matching is deterministic and conservative, it will leave some fields to the user that a model could have filled;
  that is the intended trade for not sending page text anywhere.
- The refresh-cookie path ties silent re-login to the web origin. If that is not wanted, the options are a dedicated
  extension auth route or a cookie path of `/`; neither is done here.
