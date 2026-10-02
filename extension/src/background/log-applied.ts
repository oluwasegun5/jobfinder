import type { UsedDocuments } from "../shared/types";
import { ApiError, authedOk } from "./api";
import { isUuid } from "./fill-data";

export interface LogRequest {
  jobId: string;
  applicationId: string | null;
  packId: string | null;
  used: UsedDocuments;
}

/**
 * The user said they submitted the application (the extension cannot and must not tell by itself). Creates the tracker
 * entry when there is none and moves it to APPLIED when it is still SAVED. Every step is idempotent, so a second click, or
 * a retry after a dropped connection, changes nothing.
 */
export async function logApplied(request: LogRequest): Promise<{ applicationId: string; status: string }> {
  if (!isUuid(request.jobId)) throw new Error("Invalid job.");
  let detail;
  if (request.applicationId && isUuid(request.applicationId)) {
    const id = request.applicationId;
    detail = await authedOk((api) => api.GET("/applications/{id}", { params: { path: { id } } }), [404]);
  }
  if (!detail?.data) {
    const body = {
      jobId: request.jobId,
      status: "APPLIED" as const,
      ...(request.packId && isUuid(request.packId) ? { packId: request.packId } : {}),
      ...(isUuid(request.used.resumeDocumentId) ? { resumeDocumentId: request.used.resumeDocumentId } : {}),
      ...(isUuid(request.used.coverLetterDocumentId) ? { coverLetterDocumentId: request.used.coverLetterDocumentId } : {}),
      ...(isUuid(request.used.screeningAnswersDocumentId)
        ? { screeningAnswersDocumentId: request.used.screeningAnswersDocumentId }
        : {}),
    };
    // 201 when created, 200 with the existing one when the user already tracks this job.
    detail = await authedOk((api) => api.POST("/applications", { body }));
  }
  const application = detail.data;
  if (!application?.id) throw new ApiError(detail.status);
  const id = application.id;
  if (application.status === "SAVED") {
    const moved = await authedOk((api) =>
      api.POST("/applications/{id}/status", { params: { path: { id } }, body: { status: "APPLIED" } }),
    );
    return { applicationId: id, status: moved.data?.status ?? "APPLIED" };
  }
  return { applicationId: id, status: application.status ?? "APPLIED" };
}
