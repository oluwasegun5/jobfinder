import type {
  BackgroundRequest,
  ContentRequest,
  ContentResponse,
  LoginResponse,
  StatusResponse,
} from "../shared/messages";

/**
 * The toolbar popup: sign in and out, and a Fill button for the page in the active tab. This is the only place a password
 * is typed; it goes to the service worker and is never kept. No `tabs` permission is needed to message a tab by id.
 */

function send<R>(request: BackgroundRequest): Promise<R> {
  return chrome.runtime.sendMessage(request) as Promise<R>;
}

function byId<T extends HTMLElement>(id: string): T {
  const node = document.getElementById(id);
  if (!node) throw new Error(`Missing #${id}`);
  return node as T;
}

const statusLine = byId<HTMLParagraphElement>("status");
const loginForm = byId<HTMLFormElement>("login");
const signedIn = byId<HTMLElement>("signed-in");
const who = byId<HTMLElement>("who");
const emailInput = byId<HTMLInputElement>("email");
const passwordInput = byId<HTMLInputElement>("password");
const signInButton = byId<HTMLButtonElement>("sign-in");
const fillButton = byId<HTMLButtonElement>("fill");
const signOutButton = byId<HTMLButtonElement>("sign-out");

function say(text: string, error = false): void {
  statusLine.textContent = text;
  statusLine.className = error ? "error" : "";
}

function view(status: StatusResponse): void {
  loginForm.hidden = status.signedIn;
  signedIn.hidden = !status.signedIn;
  who.textContent = status.email ?? "your account";
}

async function refresh(): Promise<void> {
  view(await send<StatusResponse>({ type: "status" }));
}

loginForm.addEventListener("submit", (event) => {
  event.preventDefault();
  signInButton.disabled = true;
  say("Signing in...");
  void send<LoginResponse>({ type: "login", email: emailInput.value, password: passwordInput.value }).then(
    async (response) => {
      passwordInput.value = "";
      signInButton.disabled = false;
      if (response.ok) {
        say("");
        await refresh();
      } else {
        say(response.message, true);
      }
    },
    () => {
      signInButton.disabled = false;
      say("Could not reach JobFinder. Try again.", true);
    },
  );
});

signOutButton.addEventListener("click", () => {
  void send<{ ok: true }>({ type: "logout" }).then(async () => {
    say("Signed out.");
    await refresh();
  });
});

fillButton.addEventListener("click", () => {
  fillButton.disabled = true;
  say("Filling...");
  const done = (text: string, error = false): void => {
    fillButton.disabled = false;
    say(text, error);
  };
  void chrome.tabs.query({ active: true, currentWindow: true }).then(
    ([tab]) => {
      if (tab?.id === undefined) return done("Open an application page first.", true);
      const request: ContentRequest = { type: "fill-request" };
      chrome.tabs.sendMessage<ContentRequest, ContentResponse>(tab.id, request).then(
        (response) => {
          if (response.ok) {
            const n = response.report.filled.length;
            const m = response.report.skipped.length;
            done(`Filled ${n} field${n === 1 ? "" : "s"}; ${m} left for you. Details are in the panel on the page.`);
          } else {
            done(response.message, response.reason !== "no_form");
          }
        },
        () => done("Open a Greenhouse, Lever, Ashby or Workday application page first, then try again.", true),
      );
    },
    () => done("Could not find the active tab.", true),
  );
});

void refresh();
