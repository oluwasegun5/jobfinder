/** The ATS the content script knows how to read. */
export type AtsName = "greenhouse" | "lever" | "ashby" | "workday";

/** Where the CV file comes from; ids only, so a page can never steer a request by it. */
export type CvRef =
  | { kind: "document"; documentId: string; fileId: string }
  | { kind: "resume"; resumeId: string };

export interface ContactData {
  fullName?: string;
  firstName?: string;
  lastName?: string;
  email?: string;
  phone?: string;
  /** As written in the profile, e.g. "Lagos, Nigeria". */
  location?: string;
  linkedin?: string;
  github?: string;
  portfolio?: string;
}

export interface ScreeningAnswer {
  /** The catalogue id (WHY_COMPANY_ROLE, NOTICE_PERIOD...). */
  id: string;
  question: string;
  answer: string;
}

/** The approved documents the fill used, so that logging the application can record them. */
export interface UsedDocuments {
  resumeDocumentId?: string;
  coverLetterDocumentId?: string;
  screeningAnswersDocumentId?: string;
}

/**
 * Just the values the current form can use, as the service worker hands them to the content script. No token, no
 * file bytes (the CV is fetched separately and only when the form has a resume field).
 */
export interface FillData {
  job: { id: string; title: string; company: string } | null;
  applicationId: string | null;
  applicationStatus: string | null;
  packId: string | null;
  contact: ContactData;
  coverLetter: string | null;
  screening: ScreeningAnswer[];
  cv: CvRef | null;
  used: UsedDocuments;
}

/** One line of the panel's report. `label` is page text and is only ever shown, never sent anywhere. */
export interface ReportItem {
  label: string;
  detail: string;
}

export interface FillReport {
  filled: ReportItem[];
  skipped: ReportItem[];
  /** How many skipped fields already had a value the user typed (they can be overwritten from the panel). */
  overwritable: number;
  ats: AtsName;
}
