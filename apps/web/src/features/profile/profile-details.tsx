"use client";

import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";

import { ProfileEditor } from "./profile-editor";
import { useResumes } from "./queries";

/** The profile page: edits the profile and the content of the primary CV (or the one named by ?resume=). */
export function ProfileDetails() {
  const router = useRouter();
  const search = useSearchParams();
  const requested = search.get("resume") ?? undefined;
  const resumes = useResumes();

  if (resumes.isPending) {
    return (
      <p role="status" className="text-sm text-muted-foreground">
        Loading…
      </p>
    );
  }
  const list = resumes.data ?? [];
  const resumeId = requested ?? list.find((r) => r.primary)?.id ?? list[0]?.id;

  return (
    <div className="flex flex-col gap-6">
      {list.length > 1 && (
        <div className="flex flex-col gap-1.5">
          <label htmlFor="editing-resume" className="text-sm font-medium">
            CV you are editing
          </label>
          <select
            id="editing-resume"
            value={resumeId}
            onChange={(e) => router.replace(`/profile?resume=${e.target.value}`)}
            className="h-9 max-w-sm rounded-lg border border-input bg-transparent px-2 text-sm dark:bg-input/30"
          >
            {list.map((resume) => (
              <option key={resume.id} value={resume.id}>
                {resume.label}
                {resume.primary ? " (primary)" : ""}
              </option>
            ))}
          </select>
        </div>
      )}
      {list.length === 0 && (
        <p className="text-sm text-muted-foreground">
          <Link href="/profile/resumes" className="underline underline-offset-4">
            Upload a CV
          </Link>{" "}
          to add your experience, education and skills. You can edit your personal details here meanwhile.
        </p>
      )}
      <ProfileEditor key={resumeId ?? "none"} resumeId={resumeId} submitLabel="Save changes" />
    </div>
  );
}
