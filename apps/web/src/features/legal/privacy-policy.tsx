import Link from "next/link";

import { Fill, LegalPage, Section } from "./legal-page";
import { RETENTION_DAYS } from "./placeholders";

export function PrivacyPolicy() {
  return (
    <LegalPage title="Privacy policy">
      <Section title="1. Who we are">
        <p>
          JobFinder is operated by <Fill of="company" />, <Fill of="address" /> (&quot;we&quot;). We decide why and how
          your personal data is used (we are the controller). For anything in this policy, write to{" "}
          <Fill of="privacyEmail" />.
        </p>
        <p>
          This policy is written to be read in any country. Where the Nigeria Data Protection Act 2023, the GDPR or
          the UK GDPR applies to you, the rights in section 8 apply as those laws provide.
        </p>
      </Section>

      <Section title="2. What we collect and why">
        <table>
          <thead>
            <tr>
              <th scope="col">Data</th>
              <th scope="col">What it is</th>
              <th scope="col">Why (legal basis)</th>
            </tr>
          </thead>
          <tbody>
            <tr>
              <td>Account</td>
              <td>
                Email address; a salted password hash (never your password), or your Google account identifier if you
                sign in with Google; whether your email is verified; the time and version of your AI consent.
              </td>
              <td>To run your account (contract); to prove your consent (legal obligation).</td>
            </tr>
            <tr>
              <td>Profile and preferences</td>
              <td>
                Name, headline, location, phone, links, years of experience, seniority; target titles, locations, work
                modes, salary expectations, sponsorship needs, excluded companies and industries.
              </td>
              <td>To find and rank jobs for you (contract).</td>
            </tr>
            <tr>
              <td>CVs</td>
              <td>
                The PDF or DOCX files you upload, the structured content read from them, and numeric search vectors
                derived from that content.
              </td>
              <td>To parse, match and tailor (contract; and your consent for the AI processing in section 4).</td>
            </tr>
            <tr>
              <td>Your work in the product</td>
              <td>
                Jobs you saved, hid, viewed or applied to; match scores and explanations; tailored CVs, cover letters,
                screening answers and application packs; generated PDF and DOCX files; your application tracker, notes
                and reminders; interview preparation and mock-interview answers and feedback; saved searches.
              </td>
              <td>To provide those features (contract; consent for AI processing).</td>
            </tr>
            <tr>
              <td>Email</td>
              <td>
                Your email settings, a log of the emails we sent you (digests, alerts, reminders) and the unsubscribe
                state.
              </td>
              <td>To send what you asked for (contract); you can switch it off at any time.</td>
            </tr>
            <tr>
              <td>Billing</td>
              <td>
                Your plan, subscription status and reference numbers at the payment provider, your credit ledger, and a
                record of each AI call made for you (feature, model, token counts, cost; never its content). We never
                see or store your card details: they go to the payment provider.
              </td>
              <td>To charge and to apply credits (contract); to keep our accounts (legal obligation, legitimate interest).</td>
            </tr>
            <tr>
              <td>Technical</td>
              <td>
                IP address and request counts, held for a short time to limit abuse; server logs and error reports
                with emails, phone numbers and tokens removed, plus a request identifier.
              </td>
              <td>Security and reliability (legitimate interest).</td>
            </tr>
          </tbody>
        </table>
        <p>
          We do not sell your data, and we do not use it for advertising or to build profiles for others. We collect
          job listings from public job boards and employer career pages; those listings are about jobs, not about you.
        </p>
      </Section>

      <Section title="3. Cookies and similar storage">
        <p>
          We use one cookie, to keep you signed in, and nothing else is stored in your browser for tracking. We use no
          analytics or advertising cookies. See the <Link href="/cookies">cookie notice</Link>.
        </p>
      </Section>

      <Section title="4. AI processing and your consent">
        <p>
          Reading a CV, scoring jobs against it, tailoring a CV, writing a cover letter, preparing interview questions
          and similar features send the relevant text (your CV content, your profile and preferences, the job
          description) to AI model providers who process it for us (see the{" "}
          <Link href="/subprocessors">subprocessors</Link>). The AI model only drafts: tailoring is checked so that it
          cannot add employers, titles, dates or qualifications you do not have, and you approve every change.
        </p>
        <p>
          We ask for your consent to this when you create an account. Without it no AI feature runs for you and no CV
          can be uploaded. You can withdraw consent at any time in <strong>Settings, Privacy and data</strong>; the
          AI features stop at once. Withdrawing does not delete what is already stored: delete it, or your account,
          there.
        </p>
        <p>
          We use the providers&apos; business API terms. <Fill of="transferMechanism" /> [CONFIRM, with each provider&apos;s
          current terms, that submitted content is not used to train its models and how long it is retained.]
        </p>
      </Section>

      <Section title="5. Who receives your data">
        <p>
          Only the providers that help us run JobFinder, each bound by a data processing agreement (
          <Link href="/subprocessors">list</Link>): AI model and embedding providers, payment providers, email delivery,
          hosting and storage, and error reporting. Google receives a sign-in request if you use Google sign-in. We
          disclose data to authorities only where the law requires it. We never send your data to the job boards or
          employers: when you apply, you do it on their site.
        </p>
      </Section>

      <Section title="6. International transfers">
        <p>
          Our providers may process data in countries other than yours. Where the law requires a safeguard we rely on
          <Fill of="transferMechanism" />.
        </p>
      </Section>

      <Section title="7. How long we keep data">
        <ul>
          <li>Account, profile, CVs and everything you create: until you delete it or your account.</li>
          <li>
            When you delete your account, everything about you is erased at once from our database, file storage and
            caches, in one step; if a step fails the deletion is refused and you can try again. The exceptions: records of AI
            calls (feature, model, token counts, cost) stay without any link to you; your payment provider keeps the
            records the law requires it to keep. Our database backups are kept for {RETENTION_DAYS.backups} days and then deleted, so
            data you deleted disappears from them at the latest {RETENTION_DAYS.backups} days later; a backup is only ever restored
            to recover from a failure, and nothing in it is used for any other purpose.
          </li>
          <li>
            Accounts whose email was never verified: deleted after {RETENTION_DAYS.unverifiedAccounts} days. Expired sign-in and
            email tokens: deleted {RETENTION_DAYS.expiredTokens} days after they expire.
          </li>
          <li>Log of emails sent: {RETENTION_DAYS.emailLog} days. Generated PDF and DOCX files (they can be made again): {RETENTION_DAYS.renderedFiles} days.</li>
          <li>Job postings collected from job boards: 30 days (they are not about you).</li>
          <li>Database backups: {RETENTION_DAYS.backups} days (encrypted before they leave our server).</li>
        </ul>
      </Section>

      <Section title="8. Your rights">
        <p>You can, free of charge:</p>
        <ul>
          <li>
            <strong>Access and portability:</strong> download everything we hold about you as a zip of JSON files and
            your original files (Settings, Privacy and data, &quot;Download my data&quot;).
          </li>
          <li>
            <strong>Rectification:</strong> correct your profile, preferences and parsed CV in the app, or write to us.
          </li>
          <li>
            <strong>Erasure:</strong> delete your account and data in the app (same page).
          </li>
          <li>
            <strong>Withdraw consent:</strong> switch off AI processing in the app, and email notifications in
            notification settings.
          </li>
          <li>
            <strong>Restriction and objection:</strong> write to <Fill of="privacyEmail" />; switching off AI processing
            also stops the processing based on consent. We will answer within one month.
          </li>
          <li>
            <strong>Complain</strong> to the data protection authority: in Nigeria the Nigeria Data Protection
            Commission; in the EU or UK your national authority.
          </li>
        </ul>
        <p>We do not make decisions about you with legal or similarly significant effect by automated means: scores and drafts help you decide.</p>
      </Section>

      <Section title="9. Security">
        <p>
          Passwords are hashed; CVs are in private storage that is only reachable through short-lived links; access is
          limited to the signed-in owner and checked on every request; logs are scrubbed of personal data; uploads are
          size- and type-checked. [CONFIRM AT LAUNCH: encryption in transit and at rest in the chosen hosting setup.]
          No system is perfectly secure; if a breach affects you we will tell you and the authorities as the law
          requires.
        </p>
      </Section>

      <Section title="10. Children">
        <p>JobFinder is not for anyone under <Fill of="minimumAge" />, and we do not knowingly collect their data.</p>
      </Section>

      <Section title="11. Changes">
        <p>We will tell you about material changes before they apply. The effective date is at the top of this page.</p>
      </Section>
    </LegalPage>
  );
}
