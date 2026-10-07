import { Fill, LegalPage, Section } from "./legal-page";

type Row = { who: React.ReactNode; what: React.ReactNode; data: React.ReactNode };

const rows: Row[] = [
  {
    who: "Anthropic (Claude models)",
    what: "AI language model: reads CVs into a profile, scores jobs, tailors CVs, writes letters and interview material.",
    data: "CV content, profile and preferences, job descriptions, the text you type into those features. Only with your AI consent.",
  },
  {
    who: "Voyage AI",
    what: "Embeddings: turns CV and job text into numeric vectors for matching.",
    data: "CV content and job descriptions. Only with your AI consent.",
  },
  {
    who: "Stripe",
    what: "Card payments and subscriptions (checkout page hosted by Stripe).",
    data: "Email address, plan, payment details entered on Stripe's page (not visible to us), reference numbers.",
  },
  {
    who: "Paystack",
    what: "Payments and subscriptions in supported local currencies (checkout page hosted by Paystack).",
    data: "Email address, plan, payment details entered on Paystack's page (not visible to us), reference numbers.",
  },
  {
    who: <Fill of="emailProvider" />,
    what: "Delivers our emails: verification, password reset, digests, alerts and reminders.",
    data: "Your email address and the content of the email.",
  },
  {
    who: <Fill of="hostingProvider" />,
    what: "Hosting of the application, database, cache, message queue and private file storage.",
    data: "All data described in the privacy policy, stored encrypted where the provider supports it.",
  },
  {
    who: "Sentry (only if enabled for this deployment)",
    what: "Error reporting for the web app and the servers.",
    data: "Error details and request identifiers, with emails, phone numbers, tokens and CV text removed before sending.",
  },
  {
    who: "Google (only if you choose Google sign-in)",
    what: "Sign-in. We verify the sign-in token Google gives your browser.",
    data: "Google tells us your verified email address and a stable account identifier.",
  },
];

export function Subprocessors() {
  return (
    <LegalPage title="Subprocessors">
      <Section title="Companies that process your data for us">
        <p>
          <Fill of="company" /> uses the companies below to run JobFinder. Each is bound by a data processing agreement
          (<Fill of="transferMechanism" /> where data leaves your country), and we list a new one here before it
          starts receiving data. Questions or objections: <Fill of="privacyEmail" />.
        </p>
        <table>
          <thead>
            <tr>
              <th scope="col">Company</th>
              <th scope="col">What it does</th>
              <th scope="col">Data it receives</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((row, index) => (
              <tr key={index}>
                <td>{row.who}</td>
                <td>{row.what}</td>
                <td>{row.data}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </Section>
      <Section title="Not subprocessors">
        <p>
          The job boards and employer career pages we read listings from receive no data about you. When you apply, you
          do it on their site and their own privacy policy applies. The JobFinder browser extension, if you install it,
          talks only to JobFinder.
        </p>
      </Section>
      <Section title="Data processing agreement">
        <p>
          Business customers who need a data processing agreement with <Fill of="company" /> should write to{" "}
          <Fill of="privacyEmail" />. [A DPA TEMPLATE FOR THE LAWYER TO COMPLETE IS IN docs/compliance/dpa-template.md.]
        </p>
      </Section>
    </LegalPage>
  );
}
