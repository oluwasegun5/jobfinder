"use client";

import { Loader2 } from "lucide-react";
import Link from "next/link";
import { useEffect, useRef, useState, type FormEvent } from "react";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import { FailureNotice } from "@/features/tailoring/shared";

import { interviewFailure } from "./errors";
import { FeedbackCard } from "./feedback-card";
import {
  ANSWER_MAX_CHARS,
  answerLength,
  canSubmit,
  categoryLabel,
  createKeyKeeper,
  exchanges,
  formatCredits,
  isOver,
  personaLine,
  progressOf,
  statusLabel,
  type Exchange,
  type Session,
} from "./model";
import { useCompleteSession, useSession, useSubmitAnswer } from "./queries";
import { SummaryView } from "./summary-view";

const buttonLink =
  "inline-flex h-9 items-center rounded-lg bg-primary px-3 text-sm font-medium text-primary-foreground outline-none hover:bg-primary/80 focus-visible:ring-3 focus-visible:ring-ring/50";

function Progress({ session }: { session: Session }) {
  const { answered, total, current, percent } = progressOf(session);
  const over = isOver(session);
  return (
    <div className="flex flex-col gap-1">
      <p className="text-sm font-medium" aria-live="polite">
        {over ? `${answered} of ${total} questions answered` : session.openQuestion ? `Question ${current} of ${total}` : `${answered} of ${total} questions answered`}
      </p>
      <div
        role="progressbar"
        aria-label="Interview progress"
        aria-valuemin={0}
        aria-valuemax={total}
        aria-valuenow={answered}
        aria-valuetext={`${answered} of ${total} questions answered`}
        className="h-2 overflow-hidden rounded-full bg-muted"
      >
        <div className="h-full rounded-full bg-primary transition-all" style={{ width: `${percent}%` }} />
      </div>
    </div>
  );
}

function QuestionBlock({ exchange, number }: { exchange: Exchange; number: number }) {
  return (
    <div className="flex flex-col gap-1">
      <p className="text-xs text-muted-foreground">
        Question {number} <Badge variant="outline">{categoryLabel(exchange.question.category)}</Badge>
      </p>
      <p className="text-base font-medium">{exchange.question.content}</p>
    </div>
  );
}

/** A past question with the answer that was given and the feedback on it. The latest one starts open. */
function PastExchange({ exchange, number, open }: { exchange: Exchange; number: number; open: boolean }) {
  const feedback = exchange.answer?.feedback;
  return (
    <li>
      <details open={open} className="rounded-lg border">
        <summary className="cursor-pointer px-3 py-2 text-sm font-medium outline-none focus-visible:ring-3 focus-visible:ring-ring/50">
          Question {number}: {exchange.question.content}
        </summary>
        <div className="flex flex-col gap-3 px-3 pt-1 pb-3">
          <QuestionBlock exchange={exchange} number={number} />
          <div className="flex flex-col gap-1">
            <p className="text-xs text-muted-foreground">Your answer</p>
            <p className="rounded-lg bg-muted px-3 py-2 text-sm break-words whitespace-pre-wrap">{exchange.answer?.content}</p>
          </div>
          {feedback && <FeedbackCard feedback={feedback} position={number} />}
        </div>
      </details>
    </li>
  );
}

function AnswerForm({ session }: { session: Session }) {
  const submit = useSubmitAnswer(session.id as string);
  const complete = useCompleteSession(session.id as string);
  // The text and the key stay in memory only: never in browser storage.
  const [text, setText] = useState("");
  const keys = useRef(createKeyKeeper());
  const pending = submit.isPending || complete.isPending;
  const failure = submit.error ? interviewFailure(submit.error) : complete.error ? interviewFailure(complete.error) : undefined;
  const length = answerLength(text);
  const tooLong = length > ANSWER_MAX_CHARS;
  const question = session.openQuestion;

  function send(event?: FormEvent) {
    event?.preventDefault();
    if (pending || !canSubmit(text)) return;
    const idempotencyKey = keys.current.keyFor(text);
    submit.mutate(
      { answer: text, idempotencyKey },
      {
        onSuccess: () => {
          keys.current.reset();
          setText("");
        },
      },
    );
  }

  if (!question) return null;
  return (
    <Card>
      <CardHeader>
        <CardTitle>
          <h2 className="text-lg font-semibold">Your turn</h2>
        </CardTitle>
      </CardHeader>
      <CardContent>
        <form onSubmit={send} className="flex flex-col gap-3" aria-busy={pending}>
          <div className="flex flex-col gap-1" id="open-question">
            <p className="text-xs text-muted-foreground">
              Question {progressOf(session).current} <Badge variant="outline">{categoryLabel(question.category)}</Badge>
            </p>
            <p className="text-base font-medium" data-testid="open-question">
              {question.content}
            </p>
          </div>
          <div className="flex flex-col gap-1.5">
            <Label htmlFor="answer">Your answer</Label>
            <Textarea
              id="answer"
              value={text}
              onChange={(e) => setText(e.target.value)}
              onKeyDown={(e) => {
                if ((e.metaKey || e.ctrlKey) && e.key === "Enter") send();
              }}
              rows={8}
              disabled={pending}
              aria-invalid={tooLong}
              aria-describedby="answer-count answer-help"
              placeholder="Type your answer as you would say it in the interview."
              autoComplete="off"
              spellCheck
            />
            <div className="flex flex-wrap justify-between gap-2 text-xs text-muted-foreground">
              <span id="answer-help">Press Ctrl or Cmd + Enter to send. Your answer is not saved in your browser.</span>
              <span id="answer-count" className={tooLong ? "font-medium text-destructive" : undefined} aria-live="polite">
                {length} / {ANSWER_MAX_CHARS}
                {tooLong ? ": too long" : ""}
              </span>
            </div>
          </div>

          <div role="status" aria-live="polite" className="min-h-5 text-sm text-muted-foreground">
            {submit.isPending && "Scoring your answer. This can take up to a minute."}
            {complete.isPending && "Making your summary."}
          </div>
          <FailureNotice
            failure={failure}
            onRetry={() => (submit.error ? send() : complete.mutate())}
            retrying={pending}
          />

          <div className="flex flex-wrap gap-2">
            <Button type="submit" disabled={pending || !canSubmit(text)}>
              {submit.isPending ? (
                <>
                  <Loader2 className="animate-spin" aria-hidden /> Scoring your answer…
                </>
              ) : (
                "Send answer"
              )}
            </Button>
            {(session.turnsAnswered ?? 0) > 0 && (
              <Button type="button" variant="outline" disabled={pending} onClick={() => complete.mutate()}>
                End interview and get summary
              </Button>
            )}
          </div>
        </form>
      </CardContent>
    </Card>
  );
}

/** After the last answer, when the summary could not be made: ask for it again. */
function SummaryPending({ session }: { session: Session }) {
  const complete = useCompleteSession(session.id as string);
  return (
    <Card>
      <CardContent className="flex flex-col gap-3 pt-4">
        <p className="text-sm">You have answered every question. Your summary is not ready yet.</p>
        <div role="status" aria-live="polite" className="min-h-5 text-sm text-muted-foreground">
          {complete.isPending && "Making your summary."}
        </div>
        <FailureNotice failure={complete.error ? interviewFailure(complete.error) : undefined} onRetry={() => complete.mutate()} retrying={complete.isPending} />
        <div>
          <Button onClick={() => complete.mutate()} disabled={complete.isPending}>
            {complete.isPending ? (
              <>
                <Loader2 className="animate-spin" aria-hidden /> Making your summary…
              </>
            ) : (
              "Get my summary"
            )}
          </Button>
        </div>
      </CardContent>
    </Card>
  );
}

export function InterviewSession({ id }: { id: string }) {
  const query = useSession(id);
  const session = query.data;
  const headingRef = useRef<HTMLHeadingElement>(null);
  const answered = session?.turnsAnswered ?? 0;
  const previous = useRef<number | undefined>(undefined);

  // After an answer, move focus to the new feedback so keyboard and screen reader users land on it.
  useEffect(() => {
    if (previous.current !== undefined && answered > previous.current) headingRef.current?.focus();
    previous.current = answered;
  }, [answered]);

  if (query.isPending) return <p role="status">Loading your interview…</p>;
  if (query.isError || !session) {
    return (
      <div className="flex flex-col gap-3">
        <FailureNotice failure={interviewFailure(query.error)} onRetry={() => void query.refetch()} retrying={query.isFetching} />
        <Link href="/interviews" className="text-sm underline">
          Back to your interviews
        </Link>
      </div>
    );
  }

  const pairs = exchanges(session.turns).filter((e) => e.answer);
  const personaText = personaLine(session.persona);

  return (
    <div className="flex flex-col gap-5">
      <header className="flex flex-col gap-2">
        <h1 ref={headingRef} tabIndex={-1} className="text-2xl font-semibold tracking-tight outline-none">
          Mock interview: {session.jobTitle ?? "this job"}
        </h1>
        <p className="flex flex-wrap items-center gap-2 text-sm text-muted-foreground">
          {session.jobCompany && <span>{session.jobCompany}</span>}
          {personaText && <span>Interviewer: {personaText}</span>}
          <Badge variant={session.status === "ABANDONED" ? "destructive" : "secondary"}>{statusLabel(session.status)}</Badge>
          <span>Credits used: {formatCredits(session.creditsConsumed)}</span>
        </p>
        <Progress session={session} />
      </header>

      {session.status === "ABANDONED" && (
        <div role="status" className="flex flex-col gap-2 rounded-lg border px-3 py-3 text-sm">
          <p>This interview was left idle for too long and was closed. What you answered is kept below.</p>
          {session.jobId && (
            <div>
              <Link href={`/jobs/${session.jobId}/interview`} className={buttonLink}>
                Start a new interview
              </Link>
            </div>
          )}
        </div>
      )}

      {session.status === "COMPLETED" && <SummaryView session={session} />}

      {pairs.length > 0 && (
        <section aria-label="Your answers and feedback" className="flex flex-col gap-2">
          <h2 className="text-lg font-semibold">Your answers and feedback</h2>
          <ol className="flex flex-col gap-2">
            {pairs.map((pair, index) => (
              <PastExchange key={pair.question.position} exchange={pair} number={index + 1} open={index === pairs.length - 1} />
            ))}
          </ol>
        </section>
      )}

      {session.status === "ACTIVE" && session.openQuestion && <AnswerForm session={session} />}
      {session.status === "ACTIVE" && !session.openQuestion && answered > 0 && <SummaryPending session={session} />}
    </div>
  );
}
