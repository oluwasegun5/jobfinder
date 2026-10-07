import { expect, test, type Page, type Route } from "@playwright/test";

/**
 * Job -> start a mock interview -> answer every question -> feedback after each answer -> summary -> history (P5.2).
 *
 * Like tailor-apply.spec, this needs no backend stack: the interview endpoints call a model, which E2E must not do, so
 * the /api/core/* surface is a small stateful stand-in served through Playwright routes. It plays the part of core-api
 * with ai-service on its fake provider (deterministic feedback, no network): fixed rubric scores, quotes taken from the
 * answer that was sent, and the questions of a scripted interviewer. What it exercises for real is the web app: the
 * production build, routing, the typed client, the answer box and its counter, the scoring state, the feedback cards,
 * the idempotency key on retries, the summary and the history. The stand-in follows the rules core-api enforces and the UI
 * depends on (ADR 0034): one stored result per idempotency key, no answer after the last turn, the daily cap and an
 * unavailable interviewer as typed problems. core-api's own tests cover the server side of those rules.
 */
const JOB = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
const SESSION = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";

const QUESTIONS = [
  { category: "behavioral", content: "Tell me about a time you disagreed with a teammate about a technical decision." },
  { category: "technical", content: "How would you design a retry policy for a flaky downstream service?" },
  { category: "behavioral", content: "Describe a time you had to learn a new tool quickly." },
];

type Turn = Record<string, unknown>;

function feedbackFor(answer: string, behavioral: boolean) {
  const quote = answer.split(/(?<=[.!?])\s+/)[0].slice(0, 120);
  return {
    structure: 4,
    relevance: 5,
    specificity: 4,
    overall: 4,
    star: behavioral ? { score: 4, situation: true, task: true, action: true, result: false } : {},
    strengths: [{ text: "You gave a concrete starting point.", quote }],
    improvements: [{ text: "Finish with the measurable result." }],
    evidence: [quote],
  };
}

function backend(options: { capOnce?: boolean; failOnce?: boolean } = {}) {
  let created: { maxTurns: number } | undefined;
  let turns: Turn[] = [];
  let answered = 0;
  let status = "ACTIVE";
  const stored = new Map<string, { answer: string; result: unknown }>();
  let capLeft = options.capOnce ? 1 : 0;
  let failLeft = options.failOnce ? 1 : 0;
  const modelCalls: string[] = [];

  const session = () => {
    const maxTurns = created?.maxTurns ?? 3;
    const open = status === "ACTIVE" && answered < maxTurns ? turns[turns.length - 1] : undefined;
    return {
      id: SESSION,
      jobId: JOB,
      jobTitle: "Java Engineer",
      jobCompany: "Acme",
      mode: "MOCK",
      persona: { interviewer: "Engineering manager", function: "engineering", seniority: "senior", tone: "direct", questionStyle: "technical_depth" },
      status,
      maxTurns,
      turnsAnswered: answered,
      creditsConsumed: 4 + answered * 3 + (status === "COMPLETED" ? 2 : 0),
      promptVersion: "mock_interview/v1",
      createdAt: "2026-10-02T10:00:00Z",
      ...(status === "COMPLETED" ? { completedAt: "2026-10-02T10:20:00Z", summary: summary() } : {}),
      ...(open ? { openQuestion: open } : {}),
      turns,
    };
  };
  const summary = () => ({
    turnsAnswered: answered,
    averages: { structure: 4, relevance: 5, specificity: 4, starCompleteness: 4, overall: 4 },
    topStrengths: ["You start from a concrete example."],
    topImprovements: ["Say the result in numbers."],
    narrative: "A clear interview. Be more specific about outcomes.",
    nextSteps: ["Write down two stories with a measured result."],
    model: "fake-strong",
    creditsConsumed: 4 + answered * 3 + 2,
  });
  const ask = (position: number) => ({ position, role: "INTERVIEWER", content: QUESTIONS[position / 2].content, category: QUESTIONS[position / 2].category, source: "GENERATED" });

  async function handle(route: Route) {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname.replace(/^\/api\/core/, "");
    const key = `${request.method()} ${path}`;
    const body = request.postDataJSON?.() as Record<string, unknown> | undefined;
    const ok = (json: unknown, status = 200) => route.fulfill({ status, json });
    const problem = (status: number, code: string, extra: Record<string, unknown> = {}) => ok({ status, code, detail: code, ...extra }, status);

    if (key === "POST /auth/refresh") return ok({ accessToken: "e2e-token", expiresIn: 900 });
    if (key === "GET /auth/me") return ok({ id: "u1", email: "e2e@example.test", role: "USER", emailVerified: true, aiConsent: true });
    if (key === "GET /profile") return ok({ onboardingCompleted: true });
    if (key === `GET /jobs/${JOB}`) {
      return ok({ id: JOB, title: "Java Engineer", company: { id: "c1", name: "Acme" }, status: "ACTIVE", description: "Build things.", skills: ["Java"], applyUrl: "https://acme.example/apply" });
    }
    if (key === "POST /interview-sessions") {
      modelCalls.push("opening");
      created = { maxTurns: Number(body?.maxTurns ?? 3) };
      turns = [ask(0)];
      return ok(session(), 201);
    }
    if (key === `GET /interview-sessions/${SESSION}`) return created ? ok(session()) : problem(404, "interview_session_not_found");
    if (key === "GET /interview-sessions") {
      const items = created
        ? [{ id: SESSION, jobId: JOB, jobTitle: "Java Engineer", jobCompany: "Acme", status, maxTurns: created.maxTurns, turnsAnswered: answered, creditsConsumed: session().creditsConsumed, overall: status === "COMPLETED" ? 4 : undefined, createdAt: "2026-10-02T10:00:00Z" }]
        : [];
      return ok({ items, page: 0, size: 10, totalElements: items.length, totalPages: items.length ? 1 : 0 });
    }
    if (key === `POST /interview-sessions/${SESSION}/answers`) {
      const answer = String(body?.answer ?? "");
      const idem = String(body?.idempotencyKey ?? "");
      const prior = stored.get(idem);
      if (prior) {
        if (prior.answer !== answer) return problem(409, "idempotency_key_reused");
        return ok(prior.result);
      }
      if (status !== "ACTIVE") return problem(409, "interview_session_completed");
      if (answer.length > 4000) return problem(400, "answer_too_long");
      if (capLeft > 0) {
        capLeft -= 1;
        return problem(429, "ai_daily_cap_reached", { resetsAt: "2026-10-03T00:00:00Z" });
      }
      if (failLeft > 0) {
        failLeft -= 1;
        return problem(503, "mock_interview_unavailable");
      }
      modelCalls.push("turn");
      const position = turns.length;
      const behavioral = QUESTIONS[(position - 1) / 2].category === "behavioral";
      const candidate = { position, role: "CANDIDATE", content: answer, feedback: feedbackFor(answer, behavioral) };
      turns.push(candidate);
      answered += 1;
      let next: Turn | undefined;
      if (answered < (created?.maxTurns ?? 3)) {
        next = ask(turns.length);
        turns.push(next);
      } else {
        status = "COMPLETED";
        modelCalls.push("summary");
      }
      const result = { turn: candidate, ...(next ? { nextQuestion: next } : { summary: summary() }), session: session() };
      stored.set(idem, { answer, result });
      return ok(result);
    }
    if (key === `POST /interview-sessions/${SESSION}/complete`) {
      if (answered === 0) return problem(409, "nothing_to_summarise");
      if (status !== "COMPLETED") {
        status = "COMPLETED";
        modelCalls.push("summary");
      }
      return ok(session());
    }
    return route.fulfill({ status: 404, json: { status: 404, code: "e2e_unrouted", detail: key } });
  }

  return { handle, modelCalls };
}

async function openApp(page: Page, options?: Parameters<typeof backend>[0]) {
  const api = backend(options);
  await page.route("**/api/core/**", (route) => api.handle(route));
  return api;
}

/** The failure notice of the form; Next's own route announcer is also a role=alert, so it is told apart by its text. */
const alert = (page: Page) => page.locator("form [role=alert]");

async function startFromTheJob(page: Page, questions = "3 questions") {
  await page.goto(`/jobs/${JOB}`);
  await expect(page.getByRole("heading", { name: "Java Engineer" })).toBeVisible();
  await page.getByRole("link", { name: "Practise the interview" }).click();
  await expect(page.getByRole("heading", { level: 1, name: "Practise the interview for Java Engineer" })).toBeVisible();
  await page.getByLabel("Number of questions").selectOption({ label: questions });
  await page.getByRole("button", { name: "Start interview" }).click();
  await expect(page.getByRole("heading", { level: 1, name: /Mock interview: Java Engineer/ })).toBeVisible();
}

test("job -> start -> answer every question -> feedback after each -> summary -> history", async ({ page }) => {
  const api = await openApp(page);
  await startFromTheJob(page);

  // The interviewer, the progress and the first question.
  await expect(page.getByText("Interviewer: Engineering manager, direct tone")).toBeVisible();
  await expect(page.getByText("Question 1 of 3")).toBeVisible();
  await expect(page.getByTestId("open-question")).toHaveText(QUESTIONS[0].content);
  await expect(page.getByRole("button", { name: "Send answer" })).toBeDisabled();

  const answers = [
    "Last year my team disagreed about retries. I built a prototype of both options. We chose bounded retries.",
    "I would use exponential backoff with jitter and a retry budget. I would also add a circuit breaker.",
    "At Northwind I had to learn Kafka in two weeks. I read the docs and shipped a consumer. It cut lag by half.",
  ];

  for (let i = 0; i < answers.length; i++) {
    const box = page.getByLabel("Your answer", { exact: true });
    await box.fill(answers[i]);
    await expect(page.getByText(`${answers[i].length} / 4000`)).toBeVisible();
    await page.getByRole("button", { name: "Send answer" }).click();

    // Feedback on this answer: four rubric scores and a quote from the answer.
    const card = page.getByLabel(`Feedback on answer ${i + 1}`);
    await expect(card).toBeVisible();
    for (const label of ["Structure", "Relevance", "Specificity", "Overall"]) {
      await expect(card.getByText(label, { exact: true })).toBeVisible();
    }
    await expect(card.getByLabel("Structure: 4 out of 5")).toBeVisible();
    await expect(card.getByLabel("Relevance: 5 out of 5")).toBeVisible();
    await expect(card.getByText("You gave a concrete starting point.")).toBeVisible();
    await expect(card.locator("blockquote").first()).toContainText(answers[i].split(".")[0]);

    if (i < answers.length - 1) {
      // The next question opens, the box is empty again and the progress moved.
      await expect(page.getByTestId("open-question")).toHaveText(QUESTIONS[i + 1].content);
      await expect(page.getByText(`Question ${i + 2} of 3`)).toBeVisible();
      await expect(page.getByLabel("Your answer", { exact: true })).toHaveValue("");
    }
  }

  // The summary at the end, and no answer box.
  const summary = page.getByLabel("Interview summary");
  await expect(summary).toBeVisible();
  await expect(summary.getByText("A clear interview. Be more specific about outcomes.")).toBeVisible();
  await expect(summary.getByText("You start from a concrete example.")).toBeVisible();
  await expect(summary.getByText("Write down two stories with a measured result.")).toBeVisible();
  await expect(summary.getByText(/used 15 credits in total/)).toBeVisible();
  await expect(page.getByLabel("Your answer", { exact: true })).toHaveCount(0);
  await expect(page.getByText("3 of 3 questions answered").first()).toBeVisible();
  expect(api.modelCalls).toEqual(["opening", "turn", "turn", "turn", "summary"]);

  // Nothing the person wrote was kept in the browser.
  const stored = await page.evaluate(() => JSON.stringify({ ...localStorage }) + JSON.stringify({ ...sessionStorage }));
  expect(stored).not.toContain("Northwind");
  expect(stored).not.toContain("bounded retries");

  // The history lists the interview.
  await page.getByRole("link", { name: "All interviews" }).click();
  await expect(page.getByRole("heading", { name: "Mock interviews" })).toBeVisible();
  const row = page.getByRole("list", { name: "Your interviews" }).getByRole("link").first();
  await expect(row).toContainText("Java Engineer");
  await expect(row).toContainText("Completed");
  await expect(row).toContainText("3 of 3 answered");
  await expect(row).toContainText("Overall 4 / 5");
});

test("a reached daily cap and an unavailable interviewer are explained, the text is kept and a retry does not double up", async ({ page }) => {
  const api = await openApp(page, { capOnce: true, failOnce: true });
  await startFromTheJob(page);

  const answer = "Last year my team disagreed about retries. I built a prototype of both options.";
  await page.getByLabel("Your answer", { exact: true }).fill(answer);

  // 1. The daily allowance is used up: said in words, nothing lost, no retry button.
  await page.getByRole("button", { name: "Send answer" }).click();
  await expect(alert(page)).toContainText("You have used today's AI allowance");
  await expect(page.getByRole("button", { name: "Try again" })).toHaveCount(0);
  await expect(page.getByLabel("Your answer", { exact: true })).toHaveValue(answer);

  // 2. The interviewer is unavailable: said in words, the text is kept, Try again works.
  await page.getByRole("button", { name: "Send answer" }).click();
  await expect(alert(page)).toContainText("The interviewer is unavailable right now");
  await expect(page.getByLabel("Your answer", { exact: true })).toHaveValue(answer);
  await page.getByRole("button", { name: "Try again" }).click();

  await expect(page.getByLabel("Feedback on answer 1")).toBeVisible();
  await expect(page.getByText("Question 2 of 3")).toBeVisible();
  // Only the one successful answer reached the model.
  expect(api.modelCalls).toEqual(["opening", "turn"]);
});

test("the interview can be ended early and still gives a summary of what was answered", async ({ page }) => {
  const api = await openApp(page);
  await startFromTheJob(page);

  // Nothing to summarise before the first answer: the button is not offered.
  await expect(page.getByRole("button", { name: "End interview and get summary" })).toHaveCount(0);
  await page.getByLabel("Your answer", { exact: true }).fill("Last year my team disagreed about retries. I built a prototype.");
  await page.getByRole("button", { name: "Send answer" }).click();
  await expect(page.getByLabel("Feedback on answer 1")).toBeVisible();

  await page.getByRole("button", { name: "End interview and get summary" }).click();

  const summary = page.getByLabel("Interview summary");
  await expect(summary).toBeVisible();
  await expect(summary.getByText("1 question answered.")).toBeVisible();
  await expect(page.getByLabel("Your answer", { exact: true })).toHaveCount(0);
  expect(api.modelCalls).toEqual(["opening", "turn", "summary"]);
});
