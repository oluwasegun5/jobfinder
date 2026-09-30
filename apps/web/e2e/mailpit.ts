const MAILPIT_URL = process.env.MAILPIT_URL ?? "http://localhost:8025";

type MailpitMessage = { ID: string };

/** Polls Mailpit for the newest message to `email` and returns the first link in its body. */
export async function waitForEmailLink(email: string, linkPath: string, timeoutMs = 15_000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const search = await fetch(`${MAILPIT_URL}/api/v1/search?query=${encodeURIComponent(`to:${email}`)}`);
    const { messages = [] } = (await search.json()) as { messages?: MailpitMessage[] };
    for (const { ID } of messages) {
      const message = (await (await fetch(`${MAILPIT_URL}/api/v1/message/${ID}`)).json()) as {
        Text?: string;
        HTML?: string;
      };
      const match = `${message.Text ?? ""} ${message.HTML ?? ""}`.match(
        new RegExp(`https?://[^\\s"'<>]*${linkPath}[^\\s"'<>]*`),
      );
      if (match) return new URL(match[0].replaceAll("&amp;", "&"));
    }
    await new Promise((resolve) => setTimeout(resolve, 500));
  }
  throw new Error(`No email to ${email} containing a ${linkPath} link within ${timeoutMs} ms`);
}
