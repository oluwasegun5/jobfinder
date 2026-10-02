import { describe, expect, it } from "vitest";

import { ANSWERS, answersDraft, blockedCv, cvDraft, fullPack, letterDraft } from "./fixtures";
import {
  answersOf,
  approvalGate,
  changeTitle,
  countStates,
  explainFlag,
  flagsForChange,
  letterOf,
  letterText,
  replaceDocument,
  unattachedFlags,
  unitLines,
} from "./model";

describe("approvalGate", () => {
  it("allows a clean draft", () => {
    expect(approvalGate(cvDraft())).toEqual({ allowed: true, reasons: [] });
  });

  it("allows a draft with warnings only: a person judges those", () => {
    const draft = cvDraft({ factCheck: { passed: true, blocking: 0, warnings: 1, flags: [{ code: "NEW_SKILL", severity: "WARNING" }] } });
    expect(approvalGate(draft).allowed).toBe(true);
  });

  it("blocks on a blocking flag and says why, in terms of what to do", () => {
    const gate = approvalGate(blockedCv());
    expect(gate.allowed).toBe(false);
    expect(gate.reasons.join(" ")).toMatch(/1 blocking fact-check flag: reject or edit the changes/);
  });

  it("blocks when the status says the fact check failed even if the counts are missing", () => {
    expect(approvalGate(cvDraft({ status: "FACT_CHECK_FAILED", factCheck: undefined })).allowed).toBe(false);
  });

  it("blocks screening answers that still need input, counting the questions", () => {
    const gate = approvalGate(answersDraft());
    expect(gate.allowed).toBe(false);
    expect(gate.reasons[0]).toMatch(/1 question needs your answer/);
  });

  it("does not allow approving twice, a superseded draft, or one still generating", () => {
    expect(approvalGate(cvDraft({ status: "APPROVED" })).allowed).toBe(false);
    expect(approvalGate(letterDraft({ status: "SUPERSEDED" })).allowed).toBe(false);
    expect(approvalGate(cvDraft({ status: "GENERATING" })).allowed).toBe(false);
  });
});

describe("flags", () => {
  it("ties flags to the change that caused them and keeps the rest as notes on the whole CV", () => {
    const draft = blockedCv();
    expect(flagsForChange(draft, "c2").map((f) => f.code)).toEqual(["NEW_EMPLOYER"]);
    expect(flagsForChange(draft, "c1")).toEqual([]);
    expect(unattachedFlags(draft).map((f) => f.code)).toEqual(["JOB_DESCRIPTION_INJECTION"]);
  });

  it("explains a job-posting injection in plain words", () => {
    const text = explainFlag({ code: "JOB_DESCRIPTION_INJECTION" });
    expect(text).toMatch(/job posting/i);
    expect(text).toMatch(/removed and ignored/);
  });

  it("falls back to the server's message for an unknown code", () => {
    expect(explainFlag({ code: "SOMETHING_NEW", message: "Look at this." })).toBe("Look at this.");
  });
});

describe("pack and document helpers", () => {
  it("replaces one document in the pack and leaves the rest alone", () => {
    const pack = fullPack();
    const updated = answersDraft({ version: 2 });
    const next = replaceDocument(pack, updated);
    expect(next.parts?.find((p) => p.type === "SCREENING_ANSWERS")?.document?.version).toBe(2);
    expect(next.parts?.find((p) => p.type === "TAILORED_RESUME")?.document).toBe(pack.parts?.[0].document);
    expect(updated.id).toBe(ANSWERS);
  });

  it("counts accepted and rejected changes", () => {
    const draft = cvDraft();
    draft.changes![1].state = "REJECTED";
    expect(countStates(draft.changes)).toEqual({ accepted: 1, rejected: 1, total: 2 });
  });

  it("reads the letter and the answers out of the document content", () => {
    expect(letterText(letterOf(letterDraft()))).toBe("Dear hiring team,\n\nFirst paragraph.\n\nSecond paragraph.\n\nKind regards,\n\nJordan Reyes");
    expect(answersOf(answersDraft()).map((a) => a.status)).toEqual(["NEEDS_INPUT", "FROM_PROFILE"]);
  });

  it("titles a change by its unit", () => {
    expect(changeTitle({ section: "EXPERIENCE", path: "experience[1]", op: "REPLACE" })).toBe("Experience 2");
    expect(changeTitle({ section: "SKILLS", path: "skills", op: "REPLACE" })).toBe("Skills");
    expect(changeTitle({ section: "EXPERIENCE", path: "experience[0]", op: "ADD" })).toBe("Experience 1 (added)");
  });

  it("turns units into readable lines", () => {
    expect(unitLines("Hello")).toEqual(["Hello"]);
    expect(unitLines(["Java", "SQL"])).toEqual(["Java, SQL"]);
    expect(unitLines({ company: "Northwind", title: "Engineer", start_date: "2020-01", is_current: true, bullets: ["Did x."] })).toEqual([
      "Engineer at Northwind",
      "2020-01 to present",
      "• Did x.",
    ]);
    expect(unitLines(null)).toEqual([]);
  });
});
