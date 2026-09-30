import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { fakeApi, json } from "@/test/fake-api";
import { renderWithQueryClient } from "@/test/render";

import { ProfileEditor } from "./profile-editor";

const hoisted = vi.hoisted(() => ({ client: undefined as unknown }));
vi.mock("@/lib/api", () => ({
  get api() {
    return hoisted.client;
  },
}));

const RESUME = "11111111-1111-4111-8111-111111111111";
const CONTENT_PATH = `/resumes/${RESUME}/content`;

const parsedContent = {
  schema_version: 1,
  contact: { full_name: "Jordan Reyes", email: "jordan@example.test", location: "London, UK", links: [] },
  headline: "Senior Backend Engineer",
  summary: "Eight years in payments.",
  experience: [
    { company: "Northwind Payments", title: "Senior Backend Engineer", start_date: "2021-03", is_current: true, bullets: ["Led a migration."] },
    { company: "Contoso", title: "Engineer", start_date: "2017-06", end_date: "2021-02", is_current: false, bullets: [] },
  ],
  education: [{ institution: "University of Leeds", degree: "BSc" }],
  skills: ["Java", "Python"],
  projects: [],
  certifications: [],
};

const contentState = (overrides: Record<string, unknown>) =>
  json({ resumeId: RESUME, versionNumber: 1, source: "UPLOAD", warnings: [], ...overrides });

const emptyProfile = () => json({ links: [], onboardingCompleted: false });

function setup(routes: Parameters<typeof fakeApi>[0], props: Partial<Parameters<typeof ProfileEditor>[0]> = {}) {
  const api = fakeApi({ "GET /profile": emptyProfile, ...routes });
  hoisted.client = api.client;
  const onSaved = vi.fn();
  renderWithQueryClient(
    <ProfileEditor resumeId={RESUME} submitLabel="Save and continue" onSaved={onSaved} pollMs={10} slowAfterMs={60} {...props} />,
  );
  return { ...api, onSaved };
}

beforeEach(() => {
  hoisted.client = undefined;
});

describe("ProfileEditor while the CV is still being parsed", () => {
  it("shows a clear in-progress state instead of a blank form, and lets the user skip the wait", async () => {
    const user = userEvent.setup();
    setup({ [`GET ${CONTENT_PATH}`]: () => contentState({ parseStatus: "PENDING" }) });

    expect(await screen.findByText(/Reading your CV/)).toBeInTheDocument();
    expect(screen.queryByLabelText("Full name")).not.toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Fill it in myself" }));

    expect(await screen.findByLabelText("Full name")).toBeInTheDocument();
    expect(screen.getByText(/still reading your CV/)).toBeInTheDocument();
  });

  it("says when parsing is slow", async () => {
    setup({ [`GET ${CONTENT_PATH}`]: () => contentState({ parseStatus: "PENDING" }) });

    expect(await screen.findByText(/taking longer than usual/)).toBeInTheDocument();
  });

  it("polls and fills the form in as soon as parsing finishes", async () => {
    let polls = 0;
    const api = setup({
      [`GET ${CONTENT_PATH}`]: () =>
        ++polls < 3
          ? contentState({ parseStatus: "PENDING" })
          : contentState({ parseStatus: "PARSED", content: parsedContent }),
    });

    expect(await screen.findByText(/Reading your CV/)).toBeInTheDocument();
    expect(await screen.findByDisplayValue("Jordan Reyes")).toBeInTheDocument();
    expect(screen.queryByText(/Reading your CV/)).not.toBeInTheDocument();
    expect(api.callsTo("GET", CONTENT_PATH).length).toBeGreaterThanOrEqual(3);
  });
});

describe("ProfileEditor with a parsed CV", () => {
  it("pre-fills every section and flags what the parser could not ground in the CV", async () => {
    setup({
      [`GET ${CONTENT_PATH}`]: () =>
        contentState({
          parseStatus: "PARSED",
          content: parsedContent,
          warnings: [
            { path: "experience[1].company", code: "not_in_source" },
            { path: "education[0].institution", code: "not_in_source" },
            { path: "skills[0]", code: "skill_not_in_source" },
            { path: "skills[3]", code: "skill_not_in_source" },
          ],
        }),
    });

    expect(await screen.findByDisplayValue("Jordan Reyes")).toBeInTheDocument();
    expect(screen.getByLabelText("Headline")).toHaveValue("Senior Backend Engineer");
    expect(screen.getByLabelText("Skills")).toHaveValue("Java, Python");
    expect(screen.getByText(/We filled this in from your CV/)).toBeInTheDocument();

    const first = screen.getByRole("group", { name: "Experience 1" });
    const second = screen.getByRole("group", { name: "Experience 2" });
    expect(within(first).getByLabelText("Company")).toHaveValue("Northwind Payments");
    expect(within(first).queryByText(/couldn't find this employer/)).not.toBeInTheDocument();
    expect(within(second).getByText(/couldn't find this employer/)).toBeInTheDocument();
    expect(within(screen.getByRole("group", { name: "Education 1" })).getByText(/couldn't find this school/)).toBeInTheDocument();
    expect(screen.getByText(/2 skills the parser found/)).toBeInTheDocument();
  });

  it("keeps a warning on the right item after another one is removed", async () => {
    const user = userEvent.setup();
    setup({
      [`GET ${CONTENT_PATH}`]: () =>
        contentState({
          parseStatus: "PARSED",
          content: parsedContent,
          warnings: [{ path: "experience[1].company", code: "not_in_source" }],
        }),
    });
    await screen.findByDisplayValue("Jordan Reyes");

    await user.click(screen.getByRole("button", { name: "Remove experience 1" }));

    const remaining = screen.getByRole("group", { name: "Experience 1" });
    expect(within(remaining).getByLabelText("Company")).toHaveValue("Contoso");
    expect(within(remaining).getByText(/couldn't find this employer/)).toBeInTheDocument();
  });

  it("saves the profile and the edited content, then reports success", async () => {
    const user = userEvent.setup();
    const api = setup({
      [`GET ${CONTENT_PATH}`]: () => contentState({ parseStatus: "PARSED", content: parsedContent }),
      "PUT /profile": () => json({ links: [], onboardingCompleted: false, updatedAt: "2026-09-30T10:00:00Z" }),
      [`PUT ${CONTENT_PATH}`]: () => contentState({ parseStatus: "PARSED", source: "EDIT", versionNumber: 2, content: parsedContent }),
    });
    const name = await screen.findByLabelText("Full name");

    await user.clear(name);
    await user.type(name, "J. Reyes");
    await user.selectOptions(screen.getByLabelText("Seniority"), "SENIOR");
    await user.click(screen.getByRole("button", { name: "Save and continue" }));

    await waitFor(() => expect(api.onSaved).toHaveBeenCalledOnce());
    const [profileCall] = api.callsTo("PUT", "/profile");
    expect(profileCall.body).toMatchObject({
      fullName: "J. Reyes",
      headline: "Senior Backend Engineer",
      location: "London, UK",
      yearsExperience: new Date().getFullYear() - 2017,
      seniority: "SENIOR",
    });
    const [contentCall] = api.callsTo("PUT", CONTENT_PATH);
    expect(contentCall.body).toMatchObject({
      contact: { full_name: "J. Reyes", email: "jordan@example.test" },
      skills: ["Java", "Python"],
      experience: [
        { company: "Northwind Payments", is_current: true, bullets: ["Led a migration."] },
        { company: "Contoso", end_date: "2021-02", is_current: false },
      ],
    });
  });

  it("shows the server's per-field validation errors and does not continue", async () => {
    const user = userEvent.setup();
    const api = setup({
      [`GET ${CONTENT_PATH}`]: () => contentState({ parseStatus: "PARSED", content: parsedContent }),
      "PUT /profile": () =>
        json(
          {
            status: 400,
            code: "validation_failed",
            errors: [{ field: "links[0].url", message: "must be an http(s) URL" }],
          },
          400,
        ),
    });
    await screen.findByLabelText("Full name");

    await user.click(screen.getByRole("button", { name: "Save and continue" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("Links 1 → url: must be an http(s) URL");
    expect(api.onSaved).not.toHaveBeenCalled();
    expect(api.callsTo("PUT", CONTENT_PATH)).toHaveLength(0);
  });
});

describe("ProfileEditor when the CV could not be read", () => {
  it("explains why and lets the user fill the profile in by hand", async () => {
    const user = userEvent.setup();
    const api = setup({
      [`GET ${CONTENT_PATH}`]: () => contentState({ parseStatus: "FAILED", parseError: "no_extractable_text" }),
      "PUT /profile": () => json({ links: [], onboardingCompleted: false, updatedAt: "2026-09-30T10:00:00Z" }),
      [`PUT ${CONTENT_PATH}`]: () => contentState({ parseStatus: "FAILED", source: "EDIT", versionNumber: 2, content: {} }),
    });

    expect(await screen.findByText(/couldn't find any text in that file/)).toBeInTheDocument();
    expect(screen.getByLabelText("Full name")).toHaveValue("");
    expect(screen.queryByRole("group", { name: "Experience 1" })).not.toBeInTheDocument();

    await user.type(screen.getByLabelText("Full name"), "Ada Lovelace");
    await user.click(screen.getByRole("button", { name: "Add experience" }));
    const job = screen.getByRole("group", { name: "Experience 1" });
    await user.type(within(job).getByLabelText("Company"), "Analytical Engines");
    await user.type(within(job).getByLabelText("Job title"), "Programmer");
    await user.type(within(job).getByLabelText("Highlights"), "Wrote the first program{Enter}Annotated the engine");
    await user.type(screen.getByLabelText("Skills"), "Maths, Poetry");
    await user.click(screen.getByRole("button", { name: "Save and continue" }));

    await waitFor(() => expect(api.onSaved).toHaveBeenCalledOnce());
    expect(api.callsTo("PUT", CONTENT_PATH)[0].body).toMatchObject({
      contact: { full_name: "Ada Lovelace" },
      experience: [
        { company: "Analytical Engines", title: "Programmer", bullets: ["Wrote the first program", "Annotated the engine"] },
      ],
      skills: ["Maths", "Poetry"],
    });
  });

  it("does not block on a failed parse when the user already saved content once", async () => {
    setup({
      [`GET ${CONTENT_PATH}`]: () =>
        contentState({ parseStatus: "FAILED", parseError: "parser_unavailable", source: "EDIT", versionNumber: 2, content: parsedContent }),
    });

    expect(await screen.findByDisplayValue("Jordan Reyes")).toBeInTheDocument();
    expect(screen.queryByText(/CV reader is unavailable/)).not.toBeInTheDocument();
  });
});

describe("ProfileEditor without a CV", () => {
  it("shows only the personal details and saves just the profile", async () => {
    const user = userEvent.setup();
    const api = setup(
      { "PUT /profile": () => json({ links: [], onboardingCompleted: false, updatedAt: "2026-09-30T10:00:00Z" }) },
      { resumeId: undefined },
    );

    await user.type(await screen.findByLabelText("Full name"), "Ada");
    expect(screen.queryByRole("group", { name: "Experience" })).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Save and continue" }));

    await waitFor(() => expect(api.onSaved).toHaveBeenCalledOnce());
    expect(api.callsTo("PUT", "/profile")[0].body).toMatchObject({ fullName: "Ada" });
    expect(api.calls.some((c) => c.path.includes("/resumes/"))).toBe(false);
  });
});

describe("ProfileEditor loading failures", () => {
  it("offers a retry when the profile cannot be loaded", async () => {
    const user = userEvent.setup();
    let failing = true;
    setup({
      "GET /profile": () => (failing ? json({ status: 500 }, 500) : emptyProfile()),
      [`GET ${CONTENT_PATH}`]: () => contentState({ parseStatus: "PARSED", content: parsedContent }),
    });

    expect(await screen.findByRole("alert")).toHaveTextContent("couldn't load your profile");
    failing = false;
    await user.click(screen.getByRole("button", { name: "Try again" }));

    expect(await screen.findByDisplayValue("Jordan Reyes")).toBeInTheDocument();
  });
});
