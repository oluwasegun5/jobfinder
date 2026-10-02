import { describe, expect, it } from "vitest";

import { cardsIn, countIn, findCard, moveCard, reminderLabel, toBoard, type Board } from "./model";

const board = (): Board => ({
  board: {
    SAVED: [{ id: "a", title: "A", status: "SAVED" }],
    APPLIED: [
      { id: "b", title: "B", status: "APPLIED" },
      { id: "c", title: "C", status: "APPLIED" },
    ],
  },
  counts: { SAVED: 1, APPLIED: 2, INTERVIEW: 0 },
});

describe("moveCard", () => {
  it("moves a card to the top of the new column and updates both counts", () => {
    const next = moveCard(board(), "c", "INTERVIEW", new Date("2026-10-02T10:00:00Z"));
    expect(next).not.toBeNull();
    expect(cardsIn(next!, "APPLIED").map((c) => c.id)).toEqual(["b"]);
    expect(cardsIn(next!, "INTERVIEW")[0]).toMatchObject({ id: "c", status: "INTERVIEW", statusChangedAt: "2026-10-02T10:00:00.000Z" });
    expect(countIn(next!, "APPLIED")).toBe(1);
    expect(countIn(next!, "INTERVIEW")).toBe(1);
  });

  it("does not change the board it was given (so a rollback can use it)", () => {
    const original = board();
    moveCard(original, "a", "APPLIED");
    expect(cardsIn(original, "SAVED")).toHaveLength(1);
    expect(countIn(original, "APPLIED")).toBe(2);
  });

  it("returns null for an unknown card or the same column", () => {
    expect(moveCard(board(), "zzz", "OFFER")).toBeNull();
    expect(moveCard(board(), "b", "APPLIED")).toBeNull();
  });

  it("never lets a count go below zero", () => {
    const b = board();
    b.counts = { SAVED: 0 };
    expect(countIn(moveCard(b, "a", "APPLIED")!, "SAVED")).toBe(0);
  });
});

describe("board helpers", () => {
  it("finds a card in any column", () => {
    expect(findCard(board(), "c")?.title).toBe("C");
    expect(findCard(board(), "nope")).toBeUndefined();
  });

  it("labels the next reminder", () => {
    const now = new Date("2026-10-02T10:00:00Z");
    expect(reminderLabel(undefined, now)).toBeUndefined();
    expect(reminderLabel("2026-10-01T10:00:00Z", now)).toBe("Reminder overdue");
    expect(reminderLabel("2026-10-02T18:00:00Z", now)).toBe("Reminder tomorrow");
    expect(reminderLabel("2026-10-07T10:00:00Z", now)).toBe("Reminder in 5 days");
  });
});

describe("toBoard", () => {
  it("keeps known statuses, drops unknown keys and fills in what the wire shape may leave out", () => {
    const board = toBoard({
      board: { APPLIED: [{ id: "a1", title: "Engineer", status: "APPLIED" }], BOGUS: [{ id: "x", title: "x" }] },
      counts: { APPLIED: 1, BOGUS: 4 },
    });
    expect(Object.keys(board.board)).toEqual(["APPLIED"]);
    expect(board.counts).toEqual({ APPLIED: 1 });
    expect(toBoard({})).toEqual({ board: {}, counts: {}, truncated: undefined });
  });
});
