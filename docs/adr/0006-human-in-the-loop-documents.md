# 0006. Human-in-the-loop for every AI-generated document

## Status
Accepted

## Context
Tailored CVs and cover letters are sent to employers under the user's name. LLMs can fabricate
experience, skills, dates or employers. A fabricated claim in a CV harms the user and the
product's credibility, and is hard to spot once the document has been sent.

## Decision
- The AI may rephrase, reorder, emphasize and trim the user's real experience. It must never
  invent employers, titles, dates, degrees, certifications, skills or metrics.
- Every tailored CV goes through a fact-check against the stored profile. Anything not supported
  by the profile is flagged prominently.
- Tailored CVs are shown as a diff against the source version; the user accepts or rejects each
  change and must approve the document before it can be exported or used in an application pack.
  Cover letters are editable and also require approval.
- Job descriptions and CV text are untrusted input to the model: wrapped in delimited blocks,
  outputs validated against schemas, and no tool access.
- This guardrail is never weakened to make a feature work.

## Consequences
- Extra UI and a fact-check step on every tailoring request, adding latency and cost.
- Tests must prove the fact-check catches injected fake experience (Phase 4 acceptance).
- Users stay accountable for what they send, and the product can make a clear claim that it
  does not fabricate.
