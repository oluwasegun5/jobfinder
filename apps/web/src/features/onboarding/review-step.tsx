"use client";

import { useRouter, useSearchParams } from "next/navigation";

import { ProfileEditor } from "@/features/profile/profile-editor";
import { useResumes } from "@/features/profile/queries";

/** Step 2: review what was read from the CV (or fill it in by hand if it could not be read). */
export function ReviewStep() {
  const router = useRouter();
  const requested = useSearchParams().get("resume") ?? undefined;
  const resumes = useResumes();

  // Without ?resume= (a bookmark, a reload of a bare URL) fall back to the primary CV.
  const resumeId = requested ?? resumes.data?.find((r) => r.primary)?.id ?? resumes.data?.[0]?.id;

  return (
    <div className="flex flex-col gap-6">
      <div className="flex flex-col gap-1">
        <h1 className="text-2xl font-semibold tracking-tight">Review your profile</h1>
        <p className="text-sm text-muted-foreground">Correct anything that&apos;s wrong, then continue.</p>
      </div>
      {!requested && resumes.isPending ? (
        <p role="status" className="text-sm text-muted-foreground">
          Loading…
        </p>
      ) : (
        <ProfileEditor
          // A different CV is a different editor: its content must not be mixed into the previous draft.
          key={resumeId ?? "none"}
          resumeId={resumeId}
          submitLabel="Save and continue"
          onSaved={() => router.push("/onboarding/preferences")}
        />
      )}
    </div>
  );
}
