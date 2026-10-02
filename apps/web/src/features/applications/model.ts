import type { components } from "@jobfinder/api-contract";

export const STATUSES = ["SAVED", "APPLIED", "SCREENING", "INTERVIEW", "OFFER", "REJECTED", "WITHDRAWN"] as const;
export type Status = (typeof STATUSES)[number];

export const STATUS_LABELS: Record<Status, string> = {
  SAVED: "Saved",
  APPLIED: "Applied",
  SCREENING: "Screening",
  INTERVIEW: "Interview",
  OFFER: "Offer",
  REJECTED: "Rejected",
  WITHDRAWN: "Withdrawn",
};

export type ApplicationDetail = components["schemas"]["ApplicationDetail"];
export type ApplicationEvent = components["schemas"]["EventView"];
export type Reminder = components["schemas"]["ReminderView"];
export type ReminderKind = NonNullable<Reminder["kind"]>;
export type FollowUpDraft = components["schemas"]["FollowUpDraft"];

/** An application as the board lists it: the detail without its history and reminders. */
export type ApplicationCard = Omit<ApplicationDetail, "events" | "reminders">;

/** The body of GET /applications, as the contract describes it (keys are plain strings, every field optional). */
export type BoardResponse = components["schemas"]["ApplicationListResponse"];

/**
 * The board as the UI works with it: only known statuses as keys, and no field missing. `toBoard` is the one place
 * the wire shape becomes this one.
 */
export type Board = {
  board: Partial<Record<Status, ApplicationCard[]>>;
  counts: Partial<Record<Status, number>>;
  truncated?: boolean;
};

export function isStatus(value: unknown): value is Status {
  return typeof value === "string" && (STATUSES as readonly string[]).includes(value);
}

export function toBoard(body: BoardResponse): Board {
  const board: Board["board"] = {};
  for (const [status, cards] of Object.entries(body.board ?? {})) {
    if (isStatus(status)) board[status] = cards;
  }
  const counts: Board["counts"] = {};
  for (const [status, count] of Object.entries(body.counts ?? {})) {
    if (isStatus(status)) counts[status] = count;
  }
  return { board, counts, truncated: body.truncated };
}

export function cardsIn(board: Board | undefined, status: Status): ApplicationCard[] {
  return board?.board?.[status] ?? [];
}

export function countIn(board: Board | undefined, status: Status): number {
  return board?.counts?.[status] ?? cardsIn(board, status).length;
}

/**
 * The board after moving one card to another column: it leaves its column, lands at the top of the new one (the
 * board is ordered by latest status change) and the two counts follow. Null when there is nothing to do (unknown card,
 * or the card is already there): the caller then sends nothing.
 */
export function moveCard(board: Board, id: string, to: Status, now: Date = new Date()): Board | null {
  let from: Status | undefined;
  let card: ApplicationCard | undefined;
  for (const status of STATUSES) {
    const found = cardsIn(board, status).find((c) => c.id === id);
    if (found) {
      from = status;
      card = found;
      break;
    }
  }
  if (!from || !card || from === to) return null;
  const moved: ApplicationCard = { ...card, status: to, statusChangedAt: now.toISOString() };
  return {
    ...board,
    board: {
      ...board.board,
      [from]: cardsIn(board, from).filter((c) => c.id !== id),
      [to]: [moved, ...cardsIn(board, to)],
    },
    counts: { ...board.counts, [from]: Math.max(0, countIn(board, from) - 1), [to]: countIn(board, to) + 1 },
  };
}

/** The first card of every column, for keyboard users who need to know where they are. */
export function findCard(board: Board | undefined, id: string): ApplicationCard | undefined {
  for (const status of STATUSES) {
    const found = cardsIn(board, status).find((c) => c.id === id);
    if (found) return found;
  }
  return undefined;
}

/** "Due in 3 days", "Due today", "Overdue" for the next reminder shown on a card. */
export function reminderLabel(iso: string | undefined, now: Date = new Date()): string | undefined {
  if (!iso) return undefined;
  const due = new Date(iso);
  if (Number.isNaN(due.getTime())) return undefined;
  const days = Math.ceil((due.getTime() - now.getTime()) / 86_400_000);
  if (due.getTime() < now.getTime()) return "Reminder overdue";
  if (days <= 0) return "Reminder today";
  if (days === 1) return "Reminder tomorrow";
  return `Reminder in ${days} days`;
}

export const CANCEL_REASON_LABELS: Record<NonNullable<Reminder["cancelReason"]>, string> = {
  USER: "cancelled by you",
  APPLICATION_CLOSED: "application closed",
  EMAIL_DISABLED: "email notifications are off",
  NO_RECIPIENT: "your account cannot receive email",
  SEND_FAILED: "the email could not be sent",
};

export const REMINDER_KIND_LABELS: Record<ReminderKind, string> = {
  FOLLOW_UP: "Follow up",
  INTERVIEW: "Interview",
  CUSTOM: "Custom",
};
