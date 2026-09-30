"use client";

import { useRouter } from "next/navigation";

import { Button } from "@/components/ui/button";
import { useResumes } from "@/features/profile/queries";
import { UploadCv } from "@/features/profile/upload-cv";

/** Step 1. A CV uploaded earlier (say, before a reload) can be reused instead of uploading it again. */
export function CvStep() {
  const router = useRouter();
  const resumes = useResumes();
  const existing = resumes.data?.find((resume) => resume.primary) ?? resumes.data?.[0];

  return (
    <div className="flex flex-col gap-6">
      <div className="flex flex-col gap-1">
        <h1 className="text-2xl font-semibold tracking-tight">Upload your CV</h1>
        <p className="text-sm text-muted-foreground">
          We&apos;ll read it to fill in your profile. You&apos;ll check everything before it is saved.
        </p>
      </div>

      {existing && (
        <div className="flex flex-col gap-3 rounded-xl border p-4">
          <p className="text-sm">
            You&apos;ve already uploaded <span className="font-medium">{existing.label}</span>.
          </p>
          <Button className="self-start" onClick={() => router.push(`/onboarding/review?resume=${existing.id}`)}>
            Continue with this CV
          </Button>
        </div>
      )}

      <div className="flex flex-col gap-3">
        {existing && <h2 className="text-base font-medium">Or upload a different one</h2>}
        <UploadCv onUploaded={(resume) => router.push(`/onboarding/review?resume=${resume.id}`)} />
      </div>
    </div>
  );
}
