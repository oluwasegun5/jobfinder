import { beforeEach, describe, expect, it, vi } from "vitest";

import { saveAuth } from "../../src/background/session";
import { fetchCv, safeFileName, toBase64 } from "../../src/background/cv";
import { loadFillData } from "../../src/background/fill-data";
import { logApplied } from "../../src/background/log-applied";
import { installFakeChrome, installFetch, json } from "./fake-chrome";

const JOB = "5b9a3c10-6f1e-4a52-9c71-0d2f3a4b5c6d";
const APP = "6c0b4d21-7a2f-4b63-8d82-1e3a4b5c6d7e";
const PACK = "7c1d4e20-8a2f-4b63-8d82-1e3a4b5c6d7f";
const DOC_LETTER = "a1b2c3d4-0000-4000-8000-000000000001";
const DOC_ANSWERS = "a1b2c3d4-0000-4000-8000-000000000002";
const DOC_CV = "a1b2c3d4-0000-4000-8000-000000000003";
const FILE = "b1b2c3d4-0000-4000-8000-000000000004";
const RESUME = "c1b2c3d4-0000-4000-8000-000000000005";
const PAGE = "https://boards.greenhouse.io/exampleco/jobs/4012345?gh_src=x";

beforeEach(async () => {
  vi.unstubAllGlobals();
  installFakeChrome();
  await saveAuth({ accessToken: "t", expiresAt: Date.now() + 600_000 });
});

const application = (status: string) => ({ id: APP, jobId: JOB, status, title: "Backend Engineer" });

describe("logging an application the user says they submitted", () => {
  it("creates the tracker entry as applied when there is none, recording the documents used", async () => {
    const calls = installFetch({ "POST /applications": () => json(201, application("APPLIED")) });

    const result = await logApplied({
      jobId: JOB,
      applicationId: null,
      packId: PACK,
      used: { coverLetterDocumentId: DOC_LETTER, screeningAnswersDocumentId: "not-a-uuid" },
    });

    expect(result).toEqual({ applicationId: APP, status: "APPLIED" });
    expect(calls).toHaveLength(1);
    expect(calls[0]!.body).toEqual({ jobId: JOB, status: "APPLIED", packId: PACK, coverLetterDocumentId: DOC_LETTER });
  });

  it("moves an existing saved application to applied, once", async () => {
    let status = "SAVED";
    const calls = installFetch({
      [`GET /applications/${APP}`]: () => json(200, application(status)),
      [`POST /applications/${APP}/status`]: () => {
        status = "APPLIED";
        return json(200, application(status));
      },
    });

    const first = await logApplied({ jobId: JOB, applicationId: APP, packId: null, used: {} });
    const second = await logApplied({ jobId: JOB, applicationId: APP, packId: null, used: {} });

    expect(first).toEqual({ applicationId: APP, status: "APPLIED" });
    expect(second).toEqual({ applicationId: APP, status: "APPLIED" });
    expect(calls.filter((c) => c.method === "POST")).toHaveLength(1);
    expect(calls.find((c) => c.method === "POST")!.body).toEqual({ status: "APPLIED" });
  });

  it("leaves an application that is already further along alone", async () => {
    const calls = installFetch({ [`GET /applications/${APP}`]: () => json(200, application("INTERVIEW")) });

    const result = await logApplied({ jobId: JOB, applicationId: APP, packId: null, used: {} });

    expect(result).toEqual({ applicationId: APP, status: "INTERVIEW" });
    expect(calls.every((c) => c.method === "GET")).toBe(true);
  });

  it("creates the entry (and tolerates the tracker already having one) when the remembered application is gone", async () => {
    const calls = installFetch({
      [`GET /applications/${APP}`]: () => json(404, { code: "application_not_found" }),
      "POST /applications": () => json(200, application("APPLIED")),
    });

    const result = await logApplied({ jobId: JOB, applicationId: APP, packId: null, used: {} });

    expect(result.status).toBe("APPLIED");
    expect(calls.map((c) => `${c.method} ${c.url.pathname}`)).toEqual([`GET /applications/${APP}`, "POST /applications"]);
  });

  it("is idempotent from the extension's side: repeating the create changes nothing new", async () => {
    installFetch({ "POST /applications": () => json(200, application("APPLIED")) });

    const a = await logApplied({ jobId: JOB, applicationId: null, packId: null, used: {} });
    const b = await logApplied({ jobId: JOB, applicationId: null, packId: null, used: {} });

    expect(a).toEqual(b);
  });

  it("refuses a malformed job id without calling the server", async () => {
    const calls = installFetch({});
    await expect(logApplied({ jobId: "../../etc", applicationId: null, packId: null, used: {} })).rejects.toThrow();
    expect(calls).toHaveLength(0);
  });
});

describe("loading what the form can use", () => {
  const profile = { fullName: "Sam Example", phone: "+1 555 0100", location: "Lagos, Nigeria", links: [{ url: "https://github.com/sam-example" }] };
  const pack = {
    id: PACK,
    status: "COMPLETE",
    parts: [
      { type: "TAILORED_RESUME", state: "READY", document: { id: DOC_CV, type: "TAILORED_RESUME", status: "APPROVED", content: {} } },
      { type: "COVER_LETTER", state: "READY", document: { id: DOC_LETTER, type: "COVER_LETTER", status: "APPROVED", content: { salutation: "Dear team,", paragraphs: ["Hello."], closing: "Regards,", signature: "Sam" } } },
      { type: "SCREENING_ANSWERS", state: "READY", document: { id: DOC_ANSWERS, type: "SCREENING_ANSWERS", status: "DRAFT", content: { answers: [{ id: "STRENGTHS", question: "Q", answer: "A", status: "GENERATED" }] } } },
    ],
  };

  it("sends only the page URL, and returns contact data, the approved parts and a CV reference", async () => {
    const calls = installFetch({
      "GET /extension/apply-context": () => json(200, { job: { id: JOB, title: "Backend Engineer", company: "Example Corp" }, applicationId: APP, applicationStatus: "SAVED", packSummary: { id: PACK, status: "COMPLETE", version: 1 } }),
      "GET /profile": () => json(200, profile),
      "GET /auth/me": () => json(200, { id: "x", email: "sam.example@example.test" }),
      "GET /resumes": () => json(200, [{ id: RESUME, primary: true, label: "CV", fileType: "PDF" }]),
      [`GET /application-packs/${PACK}`]: () => json(200, pack),
      [`GET /documents/${DOC_CV}/files`]: () => json(200, [{ id: FILE, template: "ATS", filename: "cv.pdf", contentType: "application/pdf", createdAt: "2026-01-01T00:00:00Z" }]),
    });

    const data = await loadFillData(PAGE);

    expect(data).toMatchObject({
      job: { id: JOB, title: "Backend Engineer", company: "Example Corp" },
      applicationId: APP,
      applicationStatus: "SAVED",
      packId: PACK,
      contact: { fullName: "Sam Example", firstName: "Sam", lastName: "Example", email: "sam.example@example.test", github: "https://github.com/sam-example" },
      cv: { kind: "document", documentId: DOC_CV, fileId: FILE },
    });
    expect(data.coverLetter).toBe("Dear team,\n\nHello.\n\nRegards,\n\nSam");
    // The answers document is a draft: not used.
    expect(data.screening).toEqual([]);
    expect(data.used).toEqual({ coverLetterDocumentId: DOC_LETTER, resumeDocumentId: DOC_CV });

    const context = calls.find((c) => c.url.pathname === "/extension/apply-context")!;
    expect([...context.url.searchParams.keys()]).toEqual(["url"]);
    expect(context.url.searchParams.get("url")).toBe(PAGE);
    // Nothing else about the page, and no profile data, is in any URL or body sent.
    for (const call of calls) {
      expect(call.method).toBe("GET");
      expect(call.body).toBeUndefined();
      expect(call.authorization).toBe("Bearer t");
    }
  });

  it("still gives contact details and the primary CV for a page core-api does not know", async () => {
    installFetch({
      "GET /extension/apply-context": () => json(404, { code: "job_not_found" }),
      "GET /profile": () => json(200, profile),
      "GET /auth/me": () => json(200, { email: "sam.example@example.test" }),
      "GET /resumes": () => json(200, [{ id: RESUME, primary: true, label: "CV", fileType: "PDF" }]),
    });

    const data = await loadFillData(PAGE);

    expect(data.job).toBeNull();
    expect(data.packId).toBeNull();
    expect(data.coverLetter).toBeNull();
    expect(data.contact.email).toBe("sam.example@example.test");
    expect(data.cv).toEqual({ kind: "resume", resumeId: RESUME });
  });

  it("fails on an unexpected answer from the apply-context endpoint", async () => {
    installFetch({
      "GET /extension/apply-context": () => json(500),
      "GET /profile": () => json(200, profile),
      "GET /auth/me": () => json(200, { email: "e@example.test" }),
      "GET /resumes": () => json(200, []),
    });
    await expect(loadFillData(PAGE)).rejects.toThrow(/500/);
  });
});

describe("fetching the CV", () => {
  const BYTES = new Uint8Array([37, 80, 68, 70, 45, 49, 46, 52]);

  it("downloads the uploaded resume through its pre-signed link, without credentials, and returns base64", async () => {
    const calls = installFetch({
      "GET /resumes": () => json(200, [{ id: RESUME, primary: true, label: "Sam Example CV", fileType: "PDF" }]),
      [`GET /resumes/${RESUME}/download-url`]: () => json(200, { url: "http://localhost:9000/jobfinder/cv.pdf?sig=abc" }),
      "GET /jobfinder/cv.pdf": () => new Response(BYTES, { status: 200 }),
    });

    const file = await fetchCv({ kind: "resume", resumeId: RESUME });

    expect(file).toMatchObject({ name: "Sam Example CV.pdf", contentType: "application/pdf" });
    expect(Buffer.from(file.base64, "base64")).toEqual(Buffer.from(BYTES));
    const download = calls.find((c) => c.url.pathname === "/jobfinder/cv.pdf")!;
    expect(download).toMatchObject({ credentials: "omit", authorization: null });
  });

  it("downloads a rendered file of the approved document", async () => {
    installFetch({
      [`GET /documents/${DOC_CV}/files/${FILE}/download`]: () =>
        json(200, { downloadUrl: "http://localhost:9000/jobfinder/r.docx?sig=abc", filename: "tailored", contentType: "application/vnd.openxmlformats-officedocument.wordprocessingml.document" }),
      "GET /jobfinder/r.docx": () => new Response(BYTES, { status: 200 }),
    });

    const file = await fetchCv({ kind: "document", documentId: DOC_CV, fileId: FILE });

    expect(file.name).toBe("tailored.docx");
  });

  it("refuses ids that are not UUIDs and files that are too large", async () => {
    const calls = installFetch({});
    await expect(fetchCv({ kind: "resume", resumeId: "../x" })).rejects.toThrow();
    await expect(fetchCv({ kind: "document", documentId: DOC_CV, fileId: "x" })).rejects.toThrow();
    expect(calls).toHaveLength(0);

    installFetch({
      "GET /resumes": () => json(200, []),
      [`GET /resumes/${RESUME}/download-url`]: () => json(200, { url: "http://localhost:9000/big.pdf" }),
      "GET /big.pdf": () => new Response(new Uint8Array(6 * 1024 * 1024 + 1), { status: 200 }),
    });
    await expect(fetchCv({ kind: "resume", resumeId: RESUME })).rejects.toThrow(/larger/);
  });

  it("builds safe names and base64", () => {
    expect(safeFileName("../../My CV (final).PDF", "pdf")).toBe(".._.._My CV _final_.PDF");
    expect(safeFileName("", "docx")).toBe("resume.docx");
    expect(toBase64(new Uint8Array([104, 105]))).toBe("aGk=");
  });
});
