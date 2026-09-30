import { describe, expect, it } from "vitest";

import { estimateYears, summariseWarnings, toContent, toDraft, toProfileRequest } from "./content-draft";
import type { ResumeContent } from "./queries";

const parsed: ResumeContent = {
  contact: {
    full_name: "Jordan Reyes",
    email: "jordan@example.test",
    phone: "+44 7700 900123",
    location: "London, UK",
    links: [{ url: "https://github.com/jordan-example" }],
  },
  headline: "Senior Backend Engineer",
  summary: "Eight years building payments platforms.",
  experience: [
    {
      company: "Northwind Payments",
      title: "Senior Backend Engineer",
      start_date: "2021-03",
      is_current: true,
      bullets: ["Led the settlement migration.", "Cut p99 latency."],
    },
    { company: "Contoso", title: "Engineer", start_date: "2017-06", end_date: "2021-02", is_current: false },
  ],
  education: [{ institution: "University of Leeds", degree: "BSc", start_date: "2012", end_date: "2015" }],
  skills: ["Java", "Python"],
  projects: [{ name: "Router", technologies: ["Go", "Rust"] }],
  certifications: [{ name: "CKA", issuer: "CNCF", date: "2022-05" }],
};

const now = new Date("2026-09-30T00:00:00Z");

describe("toDraft", () => {
  it("pre-fills the profile from the parsed CV when nothing has been saved yet", () => {
    const draft = toDraft({ links: [], onboardingCompleted: false }, parsed, now);

    expect(draft.profile).toMatchObject({
      fullName: "Jordan Reyes",
      headline: "Senior Backend Engineer",
      location: "London, UK",
      phone: "+44 7700 900123",
      yearsExperience: "9",
      seniority: "",
    });
    expect(draft.profile.links.map((l) => l.url)).toEqual(["https://github.com/jordan-example"]);
    expect(draft.experience[0]).toMatchObject({ company: "Northwind Payments", isCurrent: true, origIndex: 0 });
    expect(draft.experience[0].bullets).toBe("Led the settlement migration.\nCut p99 latency.");
    expect(draft.skills).toBe("Java, Python");
  });

  it("prefers what the user already saved over the parsed CV", () => {
    const draft = toDraft(
      { fullName: "J. Reyes", links: [], yearsExperience: 3, seniority: "MID", updatedAt: "2026-09-01T00:00:00Z" },
      parsed,
      now,
    );

    expect(draft.profile).toMatchObject({ fullName: "J. Reyes", yearsExperience: "3", seniority: "MID", headline: "" });
  });

  it("treats a null from the server like a missing value, never as the text 'null'", () => {
    const draft = toDraft(
      { links: [], yearsExperience: null, updatedAt: "2026-09-01T00:00:00Z" } as unknown as Parameters<typeof toDraft>[0],
      null as unknown as undefined,
      now,
    );

    expect(draft.profile.yearsExperience).toBe("");
    expect(draft.profile.fullName).toBe("");
  });

  it("starts empty when there is no content at all (a failed parse)", () => {
    const draft = toDraft(undefined, undefined, now);

    expect(draft.profile.fullName).toBe("");
    expect(draft.experience).toEqual([]);
    expect(draft.skills).toBe("");
  });
});

describe("toProfileRequest / toContent", () => {
  it("round-trips an untouched draft without losing or inventing data", () => {
    const draft = toDraft({ links: [], onboardingCompleted: false }, parsed, now);

    expect(toContent(draft, parsed)).toEqual({
      ...parsed,
      experience: [
        { ...parsed.experience![0], location: undefined, end_date: undefined },
        { ...parsed.experience![1], location: undefined, bullets: [] },
      ],
      education: [{ ...parsed.education![0], field_of_study: undefined }],
      projects: [{ name: "Router", description: undefined, url: undefined, technologies: ["Go", "Rust"] }],
      contact: { ...parsed.contact, links: [{ label: undefined, url: "https://github.com/jordan-example" }] },
    });
  });

  it("drops blank rows' optional text, splits lists and ignores an end date on a current role", () => {
    const draft = toDraft(undefined, undefined, now);
    draft.profile.fullName = "  Ada  ";
    draft.profile.yearsExperience = "7";
    draft.profile.seniority = "SENIOR";
    draft.profile.links = [
      { key: "a", label: "", url: " https://ada.example.test " },
      { key: "b", label: "x", url: "   " },
    ];
    draft.skills = "Math, Poetry\nLogic,, ";
    draft.experience = [
      {
        key: "e",
        origIndex: null,
        company: " Engines ",
        title: "Programmer",
        location: "",
        startDate: "2020-01",
        endDate: "2021-01",
        isCurrent: true,
        bullets: "One\n\n  Two  ",
      },
    ];

    expect(toProfileRequest(draft)).toEqual({
      fullName: "Ada",
      headline: undefined,
      location: undefined,
      phone: undefined,
      links: [{ label: undefined, url: "https://ada.example.test" }],
      yearsExperience: 7,
      seniority: "SENIOR",
    });
    const content = toContent(draft, undefined);
    expect(content.skills).toEqual(["Math", "Poetry", "Logic"]);
    expect(content.experience?.[0]).toMatchObject({
      company: "Engines",
      end_date: undefined,
      is_current: true,
      bullets: ["One", "Two"],
    });
    expect(content.contact?.links).toEqual([{ label: undefined, url: "https://ada.example.test" }]);
  });
});

describe("estimateYears", () => {
  it("uses the earliest start year and gives up on nonsense", () => {
    expect(estimateYears(parsed, now)).toBe("9");
    expect(estimateYears({ experience: [{ company: "x", title: "y", start_date: "1900" }] }, now)).toBe("");
    expect(estimateYears({ experience: [{ company: "x", title: "y", start_date: "2031" }] }, now)).toBe("");
    expect(estimateYears(undefined, now)).toBe("");
  });
});

describe("summariseWarnings", () => {
  it("groups parser warnings by section and item", () => {
    const summary = summariseWarnings([
      { path: "experience[1].company", code: "not_in_source" },
      { path: "education[0].institution", code: "not_in_source" },
      { path: "projects[2].name", code: "not_in_source" },
      { path: "skills[0]", code: "skill_not_in_source" },
      { path: "skills[4]", code: "skill_not_in_source" },
      { path: "something-else", code: "x" },
    ]);

    expect([...summary.experience]).toEqual([1]);
    expect([...summary.education]).toEqual([0]);
    expect([...summary.projects]).toEqual([2]);
    expect(summary.skills).toBe(2);
  });

  it("copes with no warnings", () => {
    expect(summariseWarnings(undefined).skills).toBe(0);
  });
});
