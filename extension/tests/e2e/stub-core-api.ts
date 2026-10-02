import { createServer, type IncomingMessage, type Server, type ServerResponse } from "node:http";

/**
 * A small stand-in for core-api, for the end-to-end test only. The extension is built to talk to it (the core-api URL is a
 * build-time setting), because Playwright cannot intercept a service worker's requests. Synthetic data throughout.
 */

export const IDS = {
  jobGreenhouse: "5b9a3c10-6f1e-4a52-9c71-0d2f3a4b5c01",
  jobLever: "5b9a3c10-6f1e-4a52-9c71-0d2f3a4b5c02",
  packGreenhouse: "7c1d4e20-8a2f-4b63-8d82-1e3a4b5c0001",
  packLever: "7c1d4e20-8a2f-4b63-8d82-1e3a4b5c0002",
  docResume: "a1b2c3d4-0000-4000-8000-0000000000a1",
  docLetterGh: "a1b2c3d4-0000-4000-8000-0000000000a2",
  docAnswersGh: "a1b2c3d4-0000-4000-8000-0000000000a3",
  docLetterLever: "a1b2c3d4-0000-4000-8000-0000000000a4",
  docAnswersLever: "a1b2c3d4-0000-4000-8000-0000000000a5",
  fileStyled: "b1b2c3d4-0000-4000-8000-0000000000b1",
  fileAts: "b1b2c3d4-0000-4000-8000-0000000000b2",
  resume: "c1b2c3d4-0000-4000-8000-0000000000c1",
  applicationLever: "d1b2c3d4-0000-4000-8000-0000000000d1",
  applicationNew: "d1b2c3d4-0000-4000-8000-0000000000d2",
} as const;

export const CREDENTIALS = { email: "sam.example@example.test", password: "synthetic-Passw0rd!" };
export const PDF_BYTES = Buffer.from("%PDF-1.4\n% synthetic test CV\n%%EOF\n");

export interface Recorded {
  method: string;
  path: string;
  query: Record<string, string>;
  authorization: string | undefined;
  cookie: string | undefined;
  body: unknown;
}

interface AppRow {
  id: string;
  jobId: string;
  status: string;
  title: string;
}

export class Stub {
  readonly requests: Recorded[] = [];
  readonly applications = new Map<string, AppRow>();
  private tokens = new Set<string>();
  private issued = 0;
  private server: Server | undefined;

  constructor(readonly port: number) {}

  get origin(): string {
    return `http://127.0.0.1:${this.port}`;
  }

  reset(): void {
    this.requests.length = 0;
    this.applications.clear();
    this.tokens.clear();
    this.applications.set(IDS.applicationLever, { id: IDS.applicationLever, jobId: IDS.jobLever, status: "SAVED", title: "Backend Engineer" });
  }

  count(method: string, path: string): number {
    return this.requests.filter((r) => r.method === method && r.path === path).length;
  }

  find(method: string, path: string): Recorded[] {
    return this.requests.filter((r) => r.method === method && r.path === path);
  }

  async start(): Promise<void> {
    this.reset();
    this.server = createServer((req, res) => void this.handle(req, res));
    await new Promise<void>((resolve, reject) => {
      this.server!.once("error", reject);
      this.server!.listen(this.port, "127.0.0.1", resolve);
    });
  }

  async stop(): Promise<void> {
    const server = this.server;
    if (!server) return;
    server.closeAllConnections();
    await new Promise<void>((resolve) => server.close(() => resolve()));
  }

  private newToken(): string {
    this.issued += 1;
    const token = `tok-${this.issued}`;
    this.tokens.add(token);
    return token;
  }

  private send(res: ServerResponse, status: number, body?: unknown, headers: Record<string, string> = {}): void {
    const payload = body === undefined ? undefined : Buffer.isBuffer(body) ? body : JSON.stringify(body);
    res.writeHead(status, { ...(payload && !Buffer.isBuffer(body) ? { "content-type": "application/json" } : {}), ...headers });
    res.end(payload);
  }

  private async handle(req: IncomingMessage, res: ServerResponse): Promise<void> {
    const url = new URL(req.url ?? "/", this.origin);
    const chunks: Buffer[] = [];
    for await (const chunk of req) chunks.push(chunk as Buffer);
    const raw = Buffer.concat(chunks).toString("utf8");
    const method = req.method ?? "GET";
    // Like the web app's rewrite, the extension reaches core-api under /api/core (the refresh cookie's path requires it).
    const path = url.pathname.replace(/^\/api\/core(?=\/)/, "");
    this.requests.push({
      method,
      path,
      query: Object.fromEntries(url.searchParams),
      authorization: req.headers.authorization,
      cookie: req.headers.cookie,
      body: raw ? (JSON.parse(raw) as unknown) : undefined,
    });
    const body = raw ? (JSON.parse(raw) as Record<string, unknown>) : {};

    // --- auth: the refresh token is an httpOnly SameSite=Strict cookie, as in core-api ---
    if (method === "POST" && path === "/auth/login") {
      if (body.email === CREDENTIALS.email && body.password === CREDENTIALS.password) {
        return this.send(res, 200, { accessToken: this.newToken(), tokenType: "Bearer", expiresIn: 900 }, {
          "set-cookie": "refresh_token=synthetic-refresh; Path=/api/core/auth; HttpOnly; Secure; SameSite=Strict; Max-Age=3600",
        });
      }
      return this.send(res, 401, { code: "invalid_credentials" });
    }
    if (method === "POST" && path === "/auth/refresh") {
      if ((req.headers.cookie ?? "").includes("refresh_token=synthetic-refresh")) {
        return this.send(res, 200, { accessToken: this.newToken(), tokenType: "Bearer", expiresIn: 900 });
      }
      return this.send(res, 401, { code: "invalid_refresh_token" });
    }
    if (method === "POST" && path === "/auth/logout") {
      return this.send(res, 204, undefined, { "set-cookie": "refresh_token=; Path=/api/core/auth; HttpOnly; Secure; SameSite=Strict; Max-Age=0" });
    }

    // --- pre-signed storage links carry their own signature ---
    if (method === "GET" && path === "/files/cv.pdf") {
      return this.send(res, 200, PDF_BYTES, { "content-type": "application/pdf" });
    }

    const token = (req.headers.authorization ?? "").replace(/^Bearer /, "");
    if (!this.tokens.has(token)) return this.send(res, 401, { code: "unauthorized" });

    if (method === "GET" && path === "/auth/me") return this.send(res, 200, { id: "u1", email: CREDENTIALS.email, role: "USER", emailVerified: true });
    if (method === "GET" && path === "/profile") {
      return this.send(res, 200, {
        fullName: "Sam Example",
        phone: "+1 555 0100",
        location: "Lagos, Nigeria",
        links: [
          { label: "LinkedIn", url: "https://www.linkedin.com/in/sam-example" },
          { label: "Code", url: "https://github.com/sam-example" },
          { label: "Portfolio", url: "https://sam-example.example.test" },
        ],
      });
    }
    if (method === "GET" && path === "/resumes") return this.send(res, 200, [{ id: IDS.resume, label: "Sam Example CV", fileType: "PDF", primary: true, parseStatus: "PARSED" }]);
    if (method === "GET" && path === `/resumes/${IDS.resume}/download-url`) return this.send(res, 200, { url: `${this.origin}/files/cv.pdf?sig=synthetic`, expiresAt: "2099-01-01T00:00:00Z" });

    if (method === "GET" && path === "/extension/apply-context") {
      const page = url.searchParams.get("url") ?? "";
      if (page.includes("/exampleco/jobs/4012345")) {
        return this.send(res, 200, { job: { id: IDS.jobGreenhouse, title: "Backend Engineer", company: "Example Corp" }, packSummary: { id: IDS.packGreenhouse, status: "COMPLETE", version: 1 } });
      }
      if (page.includes("5f2c0a7e-1d3b-4c58-9a7e-0b1c2d3e4f50")) {
        return this.send(res, 200, { job: { id: IDS.jobLever, title: "Backend Engineer", company: "Example Corp" }, applicationId: IDS.applicationLever, applicationStatus: "SAVED", packSummary: { id: IDS.packLever, status: "COMPLETE", version: 1 } });
      }
      return this.send(res, 404, { code: "job_not_found" });
    }

    const approved = (id: string, type: string, content: unknown) => ({ type, state: "READY", document: { id, type, status: "APPROVED", content } });
    const letter = (company: string) => ({ salutation: `Dear ${company} team,`, paragraphs: ["I am applying for the Backend Engineer role.", "I have built reliable services."], closing: "Kind regards,", signature: "Sam Example" });
    const answers = {
      answers: [
        { id: "WHY_COMPANY_ROLE", question: "Why do you want to work at this company and in this role?", answer: "I like building reliable services for people.", status: "GENERATED" },
        { id: "SALARY_EXPECTATION", question: "What are your salary expectations?", answer: "I am looking for 90,000 USD a year.", status: "FROM_PROFILE" },
        { id: "NOTICE_PERIOD", question: "What is your notice period or earliest start date?", answer: "Four weeks.", status: "FROM_PROFILE" },
        { id: "WORK_AUTHORIZATION", question: "Are you authorized to work in this country, and do you need sponsorship?", answer: "Yes, no sponsorship needed.", status: "FROM_PROFILE" },
        { id: "STRENGTHS", question: "What are your key strengths?", answer: "", status: "NEEDS_INPUT" },
      ],
    };
    if (method === "GET" && path === `/application-packs/${IDS.packGreenhouse}`) {
      return this.send(res, 200, {
        id: IDS.packGreenhouse,
        status: "COMPLETE",
        parts: [approved(IDS.docResume, "TAILORED_RESUME", {}), approved(IDS.docLetterGh, "COVER_LETTER", letter("Example Corp")), approved(IDS.docAnswersGh, "SCREENING_ANSWERS", answers)],
      });
    }
    if (method === "GET" && path === `/application-packs/${IDS.packLever}`) {
      return this.send(res, 200, {
        id: IDS.packLever,
        status: "COMPLETE",
        parts: [approved(IDS.docLetterLever, "COVER_LETTER", letter("Example Corp")), approved(IDS.docAnswersLever, "SCREENING_ANSWERS", answers)],
      });
    }
    if (method === "GET" && path === `/documents/${IDS.docResume}/files`) {
      return this.send(res, 200, [
        { id: IDS.fileStyled, template: "STYLED", filename: "styled.pdf", contentType: "application/pdf", createdAt: "2026-02-01T00:00:00Z" },
        { id: IDS.fileAts, template: "ATS", filename: "tailored-cv.pdf", contentType: "application/pdf", createdAt: "2026-01-01T00:00:00Z" },
      ]);
    }
    if (method === "GET" && path === `/documents/${IDS.docResume}/files/${IDS.fileAts}/download`) {
      return this.send(res, 200, { id: IDS.fileAts, template: "ATS", filename: "tailored-cv.pdf", contentType: "application/pdf", downloadUrl: `${this.origin}/files/cv.pdf?sig=synthetic` });
    }

    // --- the tracker ---
    const app = /^\/applications\/([0-9a-f-]{36})(\/status)?$/.exec(path);
    if (method === "GET" && app?.[1] && !app[2]) {
      const row = this.applications.get(app[1]);
      return row ? this.send(res, 200, row) : this.send(res, 404, { code: "application_not_found" });
    }
    if (method === "POST" && path === "/applications") {
      const jobId = String(body.jobId);
      const existing = [...this.applications.values()].find((a) => a.jobId === jobId);
      if (existing) return this.send(res, 200, existing);
      const row = { id: IDS.applicationNew, jobId, status: String(body.status ?? "APPLIED"), title: "Backend Engineer" };
      this.applications.set(row.id, row);
      return this.send(res, 201, row);
    }
    if (method === "POST" && app?.[1] && app[2]) {
      const row = this.applications.get(app[1]);
      if (!row) return this.send(res, 404, { code: "application_not_found" });
      row.status = String(body.status);
      return this.send(res, 200, row);
    }
    return this.send(res, 404, { code: "not_found" });
  }
}
