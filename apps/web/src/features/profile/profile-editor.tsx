"use client";

import { Plus, Trash2 } from "lucide-react";
import Link from "next/link";
import { useEffect, useState, type FormEvent, type ReactNode } from "react";

import { Button } from "@/components/ui/button";
import { problemFieldErrors, problemMessage } from "@/features/auth/api-errors";
import { FormError, FormNotice } from "@/features/auth/form-parts";

import {
  SENIORITIES,
  blankCertification,
  blankEducation,
  blankExperience,
  blankLink,
  blankProject,
  parseFailureMessage,
  summariseWarnings,
  toContent,
  toDraft,
  toProfileRequest,
  type Draft,
  type WarningSummary,
} from "./content-draft";
import { Field, Section, SelectField, TextAreaField, CheckboxField } from "./fields";
import {
  ApiProblem,
  PARSE_POLL_MS,
  useProfile,
  useResumeContent,
  useSaveProfile,
  useSaveResumeContent,
  type Profile,
  type ResumeContentState,
} from "./queries";

/** After this long a parse is called slow (it normally takes seconds); the user is told they need not wait. */
const SLOW_PARSE_MS = 45_000;

type Props = {
  /** The CV whose content is reviewed. Without one only the profile fields are shown. */
  resumeId?: string;
  submitLabel: string;
  /** Called after both the profile and the content were saved. */
  onSaved?: () => void;
  /** Overrides for tests. */
  pollMs?: number;
  slowAfterMs?: number;
};

function Loading({ children }: { children: ReactNode }) {
  return (
    <p role="status" className="text-sm text-muted-foreground">
      {children}
    </p>
  );
}

/**
 * Review/edit the profile and one CV's content. Parsing is asynchronous, so the screen is a small state machine
 * over the resume's parse state: PENDING waits (with a way out), PARSED pre-fills the form (flagging anything the
 * parser could not ground in the CV), FAILED explains why and leaves the form empty to fill in by hand.
 */
export function ProfileEditor({ resumeId, submitLabel, onSaved, pollMs = PARSE_POLL_MS, slowAfterMs = SLOW_PARSE_MS }: Props) {
  const profile = useProfile();
  const content = useResumeContent(resumeId, { pollMs });
  const [fillManually, setFillManually] = useState(false);
  const [slow, setSlow] = useState(false);

  const waiting = Boolean(resumeId) && content.data?.parseStatus === "PENDING" && !content.data.content && !fillManually;
  useEffect(() => {
    if (!waiting) return;
    const timer = setTimeout(() => setSlow(true), slowAfterMs);
    return () => clearTimeout(timer);
  }, [waiting, slowAfterMs]);

  if (profile.isPending || (resumeId && content.isPending)) return <Loading>Loading your profile…</Loading>;
  if (profile.isError || content.isError) {
    return (
      <div className="flex flex-col gap-3">
        <FormError>We couldn&apos;t load your profile.</FormError>
        <Button
          variant="outline"
          className="self-start"
          onClick={() => void Promise.all([profile.refetch(), content.refetch()])}
        >
          Try again
        </Button>
      </div>
    );
  }

  if (waiting) {
    return (
      <div className="flex flex-col gap-3 rounded-xl border p-4">
        <Loading>Reading your CV… this usually takes less than a minute.</Loading>
        {slow && (
          <p className="text-sm">
            This is taking longer than usual. You don&apos;t have to wait: you can fill your profile in yourself.
          </p>
        )}
        <Button variant="outline" className="self-start" onClick={() => setFillManually(true)}>
          Fill it in myself
        </Button>
      </div>
    );
  }

  return (
    <EditorForm
      profile={profile.data}
      contentState={resumeId ? content.data : undefined}
      resumeId={resumeId}
      submitLabel={submitLabel}
      onSaved={onSaved}
    />
  );
}

function ParseBanner({ state, manual }: { state: ResumeContentState; manual: boolean }) {
  if (state.parseStatus === "FAILED" && !state.content) {
    return (
      <FormNotice>
        {parseFailureMessage(state.parseError)} You can fill in your details yourself below, or{" "}
        <Link href="/profile/resumes" className="underline underline-offset-4">
          upload a different CV
        </Link>
        .
      </FormNotice>
    );
  }
  if (state.parseStatus === "PENDING" && !state.content) {
    return manual ? (
      <FormNotice>
        We&apos;re still reading your CV. Anything you save here is kept, and nothing you type will be overwritten.
      </FormNotice>
    ) : null;
  }
  if (state.source === "UPLOAD") {
    return (
      <FormNotice>
        We filled this in from your CV. Please check it and correct anything that&apos;s wrong before you continue.
      </FormNotice>
    );
  }
  return null;
}

function EditorForm({
  profile,
  contentState,
  resumeId,
  submitLabel,
  onSaved,
}: {
  profile: Profile | undefined;
  contentState: ResumeContentState | undefined;
  resumeId: string | undefined;
  submitLabel: string;
  onSaved?: () => void;
}) {
  // Initialised once: later refetches (polling, window focus) must never overwrite what the user is typing.
  const [draft, setDraft] = useState<Draft>(() => toDraft(profile, contentState?.content));
  const [warnings] = useState<WarningSummary>(() => summariseWarnings(contentState?.warnings));
  const [errors, setErrors] = useState<string[]>([]);
  const [saved, setSaved] = useState(false);
  const saveProfile = useSaveProfile();
  const saveContent = useSaveResumeContent(resumeId);
  const pending = saveProfile.isPending || saveContent.isPending;

  const edit = (change: (current: Draft) => Draft) => {
    setSaved(false);
    setDraft(change);
  };
  type ListSection = "experience" | "education" | "projects" | "certifications";
  const patchItem = <K extends ListSection>(section: K, key: string, change: Partial<Draft[K][number]>) =>
    edit((d) => ({ ...d, [section]: (d[section] as { key: string }[]).map((x) => (x.key === key ? { ...x, ...change } : x)) }));
  const removeItem = (section: ListSection, key: string) =>
    edit((d) => ({ ...d, [section]: (d[section] as { key: string }[]).filter((x) => x.key !== key) }));
  const editProfile = (field: keyof Draft["profile"], value: string) =>
    edit((d) => ({ ...d, profile: { ...d.profile, [field]: value } }));

  async function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setErrors([]);
    setSaved(false);
    try {
      await saveProfile.mutateAsync(toProfileRequest(draft));
      if (resumeId) await saveContent.mutateAsync(toContent(draft, contentState?.content));
    } catch (error) {
      const problem = error instanceof ApiProblem ? error.problem : undefined;
      const fields = problemFieldErrors(problem);
      setErrors(fields.length > 0 ? fields : [problemMessage(problem, "We couldn't save your changes. Please try again.")]);
      return;
    }
    setSaved(true);
    onSaved?.();
  }

  return (
    <form onSubmit={onSubmit} className="flex flex-col gap-6">
      {contentState && <ParseBanner state={contentState} manual={contentState.parseStatus === "PENDING"} />}

      <Section title="About you">
        <div className="grid gap-4 sm:grid-cols-2">
          <Field id="fullName" label="Full name" value={draft.profile.fullName} maxLength={200} autoComplete="name"
            onChange={(e) => editProfile("fullName", e.target.value)} />
          <Field id="headline" label="Headline" value={draft.profile.headline} maxLength={200}
            hint="For example: Senior backend engineer"
            onChange={(e) => editProfile("headline", e.target.value)} />
          <Field id="location" label="Location" value={draft.profile.location} maxLength={200}
            autoComplete="address-level2" onChange={(e) => editProfile("location", e.target.value)} />
          <Field id="phone" label="Phone" type="tel" value={draft.profile.phone} maxLength={50} autoComplete="tel"
            pattern="[0-9+()./\-\s]*" title="Digits and + ( ) . / - only"
            onChange={(e) => editProfile("phone", e.target.value)} />
          <Field id="yearsExperience" label="Years of experience" type="number" min={0} max={80}
            value={draft.profile.yearsExperience}
            hint={profile?.updatedAt ? undefined : "An estimate from your CV. Please correct it."}
            onChange={(e) => editProfile("yearsExperience", e.target.value)} />
          <SelectField id="seniority" label="Seniority" value={draft.profile.seniority}
            onChange={(e) => editProfile("seniority", e.target.value)}>
            <option value="">Not specified</option>
            {SENIORITIES.map((level) => (
              <option key={level} value={level}>
                {level.charAt(0) + level.slice(1).toLowerCase()}
              </option>
            ))}
          </SelectField>
        </div>
        <div className="flex flex-col gap-3">
          <p className="text-sm font-medium">Links</p>
          {draft.profile.links.map((link, index) => (
            <div key={link.key} className="grid grid-cols-[1fr_2fr_auto] items-end gap-2">
              <Field id={`link-${link.key}-label`} label={`Link ${index + 1} label`} value={link.label} maxLength={200}
                onChange={(e) => edit((d) => ({ ...d, profile: { ...d.profile,
                  links: d.profile.links.map((l) => (l.key === link.key ? { ...l, label: e.target.value } : l)) } }))} />
              <Field id={`link-${link.key}-url`} label={`Link ${index + 1} URL`} type="url" value={link.url}
                maxLength={500} placeholder="https://"
                onChange={(e) => edit((d) => ({ ...d, profile: { ...d.profile,
                  links: d.profile.links.map((l) => (l.key === link.key ? { ...l, url: e.target.value } : l)) } }))} />
              <Button type="button" variant="ghost" size="icon" aria-label={`Remove link ${index + 1}`}
                onClick={() => edit((d) => ({ ...d, profile: { ...d.profile,
                  links: d.profile.links.filter((l) => l.key !== link.key) } }))}>
                <Trash2 />
              </Button>
            </div>
          ))}
          {draft.profile.links.length < 10 && (
            <Button type="button" variant="outline" size="sm" className="self-start"
              onClick={() => edit((d) => ({ ...d, profile: { ...d.profile, links: [...d.profile.links, blankLink()] } }))}>
              <Plus /> Add link
            </Button>
          )}
        </div>
      </Section>

      {resumeId && (
        <>
          <Section title="Summary">
            <TextAreaField id="summary" label="Professional summary" value={draft.summary} maxLength={2000} rows={4}
              onChange={(e) => edit((d) => ({ ...d, summary: e.target.value }))} />
          </Section>

          <Section title="Experience">
            {draft.experience.map((job, index) => (
              <fieldset key={job.key} className="flex flex-col gap-3 rounded-lg border p-3">
                <legend className="px-1 text-sm font-medium">Experience {index + 1}</legend>
                {job.origIndex !== null && warnings.experience.has(job.origIndex) && (
                  <FormNotice>We couldn&apos;t find this employer in your CV. Please check it.</FormNotice>
                )}
                <div className="grid gap-3 sm:grid-cols-2">
                  <Field id={`exp-${job.key}-company`} label="Company" required maxLength={200} value={job.company}
                    onChange={(e) => patchItem("experience", job.key, { company: e.target.value })} />
                  <Field id={`exp-${job.key}-title`} label="Job title" required maxLength={200} value={job.title}
                    onChange={(e) => patchItem("experience", job.key, { title: e.target.value })} />
                  <Field id={`exp-${job.key}-location`} label="Location" maxLength={200} value={job.location}
                    onChange={(e) => patchItem("experience", job.key, { location: e.target.value })} />
                  <div className="grid grid-cols-2 gap-3">
                    <Field id={`exp-${job.key}-start`} label="Start" placeholder="YYYY-MM" pattern="\d{4}(-(0[1-9]|1[0-2]))?"
                      title="YYYY or YYYY-MM" value={job.startDate}
                      onChange={(e) => patchItem("experience", job.key, { startDate: e.target.value })} />
                    <Field id={`exp-${job.key}-end`} label="End" placeholder="YYYY-MM" pattern="\d{4}(-(0[1-9]|1[0-2]))?"
                      title="YYYY or YYYY-MM" value={job.isCurrent ? "" : job.endDate} disabled={job.isCurrent}
                      onChange={(e) => patchItem("experience", job.key, { endDate: e.target.value })} />
                  </div>
                </div>
                <CheckboxField id={`exp-${job.key}-current`} label="I currently work here" checked={job.isCurrent}
                  onChange={(e) => patchItem("experience", job.key, { isCurrent: e.target.checked })} />
                <TextAreaField id={`exp-${job.key}-bullets`} label="Highlights" rows={4} value={job.bullets}
                  hint="One per line"
                  onChange={(e) => patchItem("experience", job.key, { bullets: e.target.value })} />
                <Button type="button" variant="ghost" size="sm" className="self-start"
                  onClick={() => removeItem("experience", job.key)}>
                  <Trash2 /> Remove experience {index + 1}
                </Button>
              </fieldset>
            ))}
            {draft.experience.length < 30 && (
              <Button type="button" variant="outline" size="sm" className="self-start"
                onClick={() => edit((d) => ({ ...d, experience: [...d.experience, blankExperience()] }))}>
                <Plus /> Add experience
              </Button>
            )}
          </Section>

          <Section title="Education">
            {draft.education.map((school, index) => (
              <fieldset key={school.key} className="flex flex-col gap-3 rounded-lg border p-3">
                <legend className="px-1 text-sm font-medium">Education {index + 1}</legend>
                {school.origIndex !== null && warnings.education.has(school.origIndex) && (
                  <FormNotice>We couldn&apos;t find this school in your CV. Please check it.</FormNotice>
                )}
                <div className="grid gap-3 sm:grid-cols-2">
                  <Field id={`edu-${school.key}-institution`} label="School" required maxLength={200} value={school.institution}
                    onChange={(e) => patchItem("education", school.key, { institution: e.target.value })} />
                  <Field id={`edu-${school.key}-degree`} label="Degree" maxLength={200} value={school.degree}
                    onChange={(e) => patchItem("education", school.key, { degree: e.target.value })} />
                  <Field id={`edu-${school.key}-field`} label="Field of study" maxLength={200} value={school.fieldOfStudy}
                    onChange={(e) => patchItem("education", school.key, { fieldOfStudy: e.target.value })} />
                  <div className="grid grid-cols-2 gap-3">
                    <Field id={`edu-${school.key}-start`} label="Start" placeholder="YYYY" pattern="\d{4}(-(0[1-9]|1[0-2]))?"
                      title="YYYY or YYYY-MM" value={school.startDate}
                      onChange={(e) => patchItem("education", school.key, { startDate: e.target.value })} />
                    <Field id={`edu-${school.key}-end`} label="End" placeholder="YYYY" pattern="\d{4}(-(0[1-9]|1[0-2]))?"
                      title="YYYY or YYYY-MM" value={school.endDate}
                      onChange={(e) => patchItem("education", school.key, { endDate: e.target.value })} />
                  </div>
                </div>
                <Button type="button" variant="ghost" size="sm" className="self-start"
                  onClick={() => removeItem("education", school.key)}>
                  <Trash2 /> Remove education {index + 1}
                </Button>
              </fieldset>
            ))}
            {draft.education.length < 15 && (
              <Button type="button" variant="outline" size="sm" className="self-start"
                onClick={() => edit((d) => ({ ...d, education: [...d.education, blankEducation()] }))}>
                <Plus /> Add education
              </Button>
            )}
          </Section>

          <Section title="Skills">
            {warnings.skills > 0 && (
              <FormNotice>
                {warnings.skills === 1
                  ? "1 skill the parser found wasn't mentioned in your CV, so it was left out."
                  : `${warnings.skills} skills the parser found weren't mentioned in your CV, so they were left out.`}{" "}
                Add any you want back.
              </FormNotice>
            )}
            <TextAreaField id="skills" label="Skills" rows={3} value={draft.skills}
              hint="Separate with commas or new lines"
              onChange={(e) => edit((d) => ({ ...d, skills: e.target.value }))} />
          </Section>

          <Section title="Projects">
            {draft.projects.map((project, index) => (
              <fieldset key={project.key} className="flex flex-col gap-3 rounded-lg border p-3">
                <legend className="px-1 text-sm font-medium">Project {index + 1}</legend>
                {project.origIndex !== null && warnings.projects.has(project.origIndex) && (
                  <FormNotice>We couldn&apos;t find this project in your CV. Please check it.</FormNotice>
                )}
                <div className="grid gap-3 sm:grid-cols-2">
                  <Field id={`proj-${project.key}-name`} label="Project name" required maxLength={200} value={project.name}
                    onChange={(e) => patchItem("projects", project.key, { name: e.target.value })} />
                  <Field id={`proj-${project.key}-url`} label="Project link" type="url" maxLength={500} placeholder="https://"
                    value={project.url}
                    onChange={(e) => patchItem("projects", project.key, { url: e.target.value })} />
                </div>
                <TextAreaField id={`proj-${project.key}-description`} label="Description" rows={3} maxLength={2000}
                  value={project.description}
                  onChange={(e) => patchItem("projects", project.key, { description: e.target.value })} />
                <Field id={`proj-${project.key}-tech`} label="Technologies" hint="Separate with commas"
                  value={project.technologies}
                  onChange={(e) => patchItem("projects", project.key, { technologies: e.target.value })} />
                <Button type="button" variant="ghost" size="sm" className="self-start"
                  onClick={() => removeItem("projects", project.key)}>
                  <Trash2 /> Remove project {index + 1}
                </Button>
              </fieldset>
            ))}
            {draft.projects.length < 20 && (
              <Button type="button" variant="outline" size="sm" className="self-start"
                onClick={() => edit((d) => ({ ...d, projects: [...d.projects, blankProject()] }))}>
                <Plus /> Add project
              </Button>
            )}
          </Section>

          <Section title="Certifications">
            {draft.certifications.map((cert, index) => (
              <fieldset key={cert.key} className="flex flex-col gap-3 rounded-lg border p-3">
                <legend className="px-1 text-sm font-medium">Certification {index + 1}</legend>
                <div className="grid gap-3 sm:grid-cols-3">
                  <Field id={`cert-${cert.key}-name`} label="Name" required maxLength={200} value={cert.name}
                    onChange={(e) => patchItem("certifications", cert.key, { name: e.target.value })} />
                  <Field id={`cert-${cert.key}-issuer`} label="Issuer" maxLength={200} value={cert.issuer}
                    onChange={(e) => patchItem("certifications", cert.key, { issuer: e.target.value })} />
                  <Field id={`cert-${cert.key}-date`} label="Date" placeholder="YYYY-MM" pattern="\d{4}(-(0[1-9]|1[0-2]))?"
                    title="YYYY or YYYY-MM" value={cert.date}
                    onChange={(e) => patchItem("certifications", cert.key, { date: e.target.value })} />
                </div>
                <Button type="button" variant="ghost" size="sm" className="self-start"
                  onClick={() => removeItem("certifications", cert.key)}>
                  <Trash2 /> Remove certification {index + 1}
                </Button>
              </fieldset>
            ))}
            {draft.certifications.length < 20 && (
              <Button type="button" variant="outline" size="sm" className="self-start"
                onClick={() => edit((d) => ({ ...d, certifications: [...d.certifications, blankCertification()] }))}>
                <Plus /> Add certification
              </Button>
            )}
          </Section>
        </>
      )}

      {errors.length > 0 && (
        <div role="alert" className="flex flex-col gap-1 rounded-lg bg-destructive/10 px-3 py-2 text-sm text-destructive">
          <p>We couldn&apos;t save your changes:</p>
          <ul className="list-disc pl-5">
            {errors.slice(0, 6).map((message) => (
              <li key={message}>{message}</li>
            ))}
          </ul>
        </div>
      )}
      {saved && !onSaved && <FormNotice>Saved.</FormNotice>}
      <Button type="submit" size="lg" disabled={pending} className="self-start">
        {pending ? "Saving…" : submitLabel}
      </Button>
    </form>
  );
}
