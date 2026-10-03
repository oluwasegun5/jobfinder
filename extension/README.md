# JobFinder autofill (Chrome extension)

A Manifest V3 extension that fills a job application form from your JobFinder profile and application pack. You press the
button, it fills what it can recognise, highlights what it filled, lists what it left for you, and stops. **It never submits
a form.** Design decisions and their reasons are in [`docs/adr/0035-chrome-extension.md`](../docs/adr/0035-chrome-extension.md).

## What it does

On a Greenhouse, Lever, Ashby or Workday application page, a small "JobFinder" pill appears at the bottom right. The toolbar
popup has the same Fill button. Nothing happens until you press it.

| Site | What is filled |
| --- | --- |
| Greenhouse (`boards` / `job-boards` hosted forms) | Name, email, phone, location, LinkedIn / GitHub / portfolio links, cover letter text, screening answers that closely match an approved answer, and the CV upload |
| Lever (`jobs.lever.co`) | The same: full name, email, phone, location, links, cover letter, screening answers, CV upload |
| Ashby | Detection plus the contact fields and links of the first step (name, email, phone, location, links). No CV, cover letter or screening answers |
| Workday | Detection plus first name, last name, email, phone and city of the "My Information" step. Nothing else |

It does **not** fill, on any site: demographic or EEO questions, work authorization, sponsorship or other legal attestations,
government ID numbers, passwords, captchas, hidden fields, selects, checkboxes, radios, or any field it does not recognise.
Those are listed under "Needs your input". Values you already typed are kept unless you click "Overwrite", and "Undo fill"
puts the form back. Greenhouse forms embedded in another site's iframe are not supported.

After you have submitted the application yourself, "I submitted this application" logs it in your tracker (it creates the
application if there is none and moves it to Applied; pressing it again changes nothing).

## Build and load it

```bash
npm ci                          # from the repository root
npm run extension:build         # writes extension/dist
```

Build settings (environment variables):

| Variable | Default | Meaning |
| --- | --- | --- |
| `CORE_API_URL` | `http://localhost:8080` | Base URL the extension calls. See the note on the refresh cookie below |
| `STORAGE_ORIGIN` | `http://localhost:9000` | Origin of the pre-signed CV download links (S3Mock locally, R2 in production) |
| `OUT_DIR` | `dist` | Output directory |

These become the only `host_permissions`; they are read at build time and never from a page.

Then open `chrome://extensions`, switch on Developer mode, choose "Load unpacked" and select `extension/dist`. Click the
toolbar icon and sign in with your JobFinder email and password.

**Silent re-login needs the web app's path.** core-api sets the refresh token as an httpOnly, `SameSite=Strict` cookie whose
path is `/api/core/auth` (the path the web app proxies under). The cookie is only sent to that path, so for the extension to
renew its session without asking for your password, build it against the web origin:
`CORE_API_URL=https://<your web origin>/api/core` (locally `http://localhost:3000/api/core`). Built against core-api directly
(`http://localhost:8080`), everything works except the silent refresh: when the short-lived access token or the browser session
ends you sign in again from the popup. Verified results are in the ADR.

## Security model

- **Page text is untrusted data.** It is only read to decide which field is which, by deterministic string matching. It is
  never sent to a server or a model, never evaluated, and never used as a selector. The only thing sent to core-api about the
  page is its URL (to `GET /extension/apply-context`).
- **No submit.** The code contains no `submit`, `requestSubmit`, `click` or key-event call; a unit test scans the source for
  them, and the end-to-end test asserts the form's submit handler and the submit button's click handler were never invoked.
- **The access token** is kept only in `chrome.storage.session` (memory, cleared when the browser closes), with its access
  level set to trusted contexts, so content scripts cannot read it (the end-to-end test checks this from the content script's
  world). Every core-api call is made from the service worker; the content script and popup only send it messages. The
  password is typed in the popup, passed to the service worker, and never stored.
- **Manifest:** permission `storage` only. No `tabs`, no `activeTab`, no `<all_urls>`. Content scripts run only on the four
  ATS hosts. No `web_accessible_resources`, no `externally_connectable`, default CSP (no remote code, no eval, no inline
  script).
- **Privacy:** no profile, CV, answer or job text is written to the console. The test fixtures are synthetic.
- The panel is a closed shadow root, so the page can neither read nor restyle it. (The test build opens it so Playwright can
  drive it.)

## Limits

- Matching is by field labels and attributes in English. A form with unusual or non-English labels will have more fields
  under "Needs your input".
- Screening answers fill only on a close match of the normalised question to an approved answer; ambiguous matches are left.
- The apply page is matched to a job in your tracker by its URL. A job you have not saved gives "no matching job": the form can
  still be filled from your profile, but "I submitted this application" is unavailable.
- Only approved pack documents are used. The CV is the approved tailored resume's ATS file when there is one, otherwise your
  primary resume. Files over 6 MB are not uploaded.
- No extension icons yet.

## Checks

```bash
npm run extension:lint
npm run typecheck -w @jobfinder/extension
npm run extension:test          # vitest (jsdom, saved fixtures)
npm run extension:e2e           # Playwright, real Chromium with the built extension
```

The end-to-end tests build the extension against a local stub core-api (`tests/e2e/stub-core-api.ts`) and serve the saved
fixtures at the real ATS addresses by page-level routing, because Playwright cannot intercept a service worker's requests.
The first run needs `npx playwright install chromium`.
