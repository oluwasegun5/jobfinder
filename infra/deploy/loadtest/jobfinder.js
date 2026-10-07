// k6 load test for a deployed JobFinder (docs/load-test-report.md). Run through infra/deploy/loadtest/run.sh locally or
// `k6 run` against staging. No LLM-backed endpoint is called: the test must not spend money or hit rate-limit class AI.
//
// What a virtual user does, per iteration (mix chosen to resemble browsing a signed-in app):
//   60 %  signed-in reads: /auth/me, /billing/allowance, /profile, /jobs search, /feed
//   25 %  the public pages: / and /privacy
//   15 %  /api/core/actuator/health (what the status badge and the uptime monitor call)
//
// Environment: BASE_URL (https://...), ORIGIN (the application's public URL when it differs from BASE_URL), USERS_FILE (JSON list of {email, password}), VUS (peak, default 10),
// RAMP (default 30s), HOLD (default 90s), P95_MS (default 800), P99_MS (default 2000).
import http from "k6/http";
import { check, group, sleep } from "k6";
import exec from "k6/execution";
import { SharedArray } from "k6/data";
import { Rate } from "k6/metrics";

const BASE = (__ENV.BASE_URL || "https://localhost").replace(/\/$/, "");
const users = new SharedArray("users", () => JSON.parse(open(__ENV.USERS_FILE || "/work/users.json")));
const apiErrors = new Rate("api_errors");

export const options = {
  scenarios: {
    browse: {
      executor: "ramping-vus",
      startVUs: 1,
      stages: [
        { duration: __ENV.RAMP || "30s", target: Number(__ENV.VUS || 10) },
        { duration: __ENV.HOLD || "90s", target: Number(__ENV.VUS || 10) },
        { duration: "10s", target: 0 },
      ],
      gracefulRampDown: "10s",
    },
  },
  thresholds: {
    http_req_failed: ["rate<0.01"],
    api_errors: ["rate<0.01"],
    http_req_duration: [`p(95)<${__ENV.P95_MS || 800}`, `p(99)<${__ENV.P99_MS || 2000}`],
    checks: ["rate>0.99"],
  },
  summaryTrendStats: ["avg", "min", "med", "p(90)", "p(95)", "p(99)", "max"],
};

// The browser origin the application expects (WEB_BASE_URL); the cross-site request filter compares it.
const ORIGIN = (__ENV.ORIGIN || BASE).replace(/\/$/, "");
const jsonHeaders = { "Content-Type": "application/json", Origin: ORIGIN };

export function setup() {
  // One login per account; tokens last 15 minutes, longer than the test.
  return users.map((u) => {
    const r = http.post(`${BASE}/api/core/auth/login`, JSON.stringify(u), { headers: jsonHeaders, tags: { name: "login" } });
    const ok = check(r, { "login 200": (x) => x.status === 200 });
    if (!ok) exec.test.abort(`login failed for a load-test account (HTTP ${r.status}); nothing meaningful can be measured`);
    return { email: u.email, token: r.status === 200 ? r.json("accessToken") : null };
  });
}

function authed(token) {
  return { headers: { Authorization: `Bearer ${token}`, Origin: ORIGIN } };
}

function read(name, url, params) {
  const r = http.get(url, { ...params, tags: { name } });
  const ok = check(r, { [`${name} 200`]: (x) => x.status === 200 });
  apiErrors.add(!ok);
  return r;
}

export default function (sessions) {
  const me = sessions[(__VU - 1) % sessions.length];
  const roll = Math.random();
  if (roll < 0.6 && me.token) {
    group("signed-in reads", () => {
      read("auth_me", `${BASE}/api/core/auth/me`, authed(me.token));
      read("allowance", `${BASE}/api/core/billing/allowance`, authed(me.token));
      read("profile", `${BASE}/api/core/profile`, authed(me.token));
      read("jobs_search", `${BASE}/api/core/jobs?q=engineer&size=20`, authed(me.token));
      read("feed", `${BASE}/api/core/feed`, authed(me.token));
    });
  } else if (roll < 0.85) {
    group("public pages", () => {
      read("home", `${BASE}/`);
      read("privacy", `${BASE}/privacy`);
    });
  } else {
    read("health", `${BASE}/api/core/actuator/health`);
  }
  sleep(1 + Math.random() * 2);
}

export function teardown(sessions) {
  // The accounts were created for this run: delete them (which also proves deletion under a warm stack).
  sessions.forEach((s) => {
    if (s.token) http.del(`${BASE}/api/core/me`, null, authed(s.token));
  });
}
