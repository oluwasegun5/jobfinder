export const JOB = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
export const SESSION = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
export const PREP = "cccccccc-cccc-4ccc-8ccc-cccccccccccc";

export const PERSONA = { interviewer: "Engineering manager", function: "engineering", seniority: "senior", tone: "direct", questionStyle: "technical_depth" };

export const FEEDBACK = {
  structure: 4,
  relevance: 5,
  specificity: 3,
  overall: 4,
  star: { score: 4, situation: true, task: true, action: true, result: false },
  strengths: [{ text: "You tested both options.", quote: "I built a small prototype of both options" }],
  improvements: [{ text: "Say what the result was." }],
  evidence: ["I built a small prototype of both options"],
};

export const question = (position: number, content: string, category = "behavioral") => ({
  position,
  role: "INTERVIEWER",
  content,
  category,
  source: "GENERATED",
});

export const answerTurn = (position: number, content: string, feedback: unknown = FEEDBACK) => ({ position, role: "CANDIDATE", content, feedback });

export const SUMMARY = {
  turnsAnswered: 2,
  averages: { structure: 4, relevance: 4.5, specificity: 3.5, starCompleteness: 4, overall: 4 },
  topStrengths: ["You back claims with a measurement."],
  topImprovements: ["Finish with the result."],
  narrative: "A clear, structured interview with room to be more concrete.",
  nextSteps: ["Prepare two stories with numbers.", "Practise saying the result first."],
  model: "fake-strong",
  creditsConsumed: 11.5,
};

export function session(overrides: Record<string, unknown> = {}) {
  return {
    id: SESSION,
    jobId: JOB,
    jobTitle: "Backend Engineer",
    jobCompany: "Harbor Freight Tech",
    mode: "MOCK",
    persona: PERSONA,
    status: "ACTIVE",
    maxTurns: 3,
    turnsAnswered: 0,
    creditsConsumed: 4,
    promptVersion: "mock_interview/v1",
    createdAt: "2026-10-02T10:00:00Z",
    openQuestion: question(0, "Tell me about a time you led a project."),
    turns: [question(0, "Tell me about a time you led a project.")],
    ...overrides,
  };
}
