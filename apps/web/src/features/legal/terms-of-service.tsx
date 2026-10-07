import Link from "next/link";

import { Fill, LegalPage, Section } from "./legal-page";

export function TermsOfService() {
  return (
    <LegalPage title="Terms of service">
      <Section title="1. The service">
        <p>
          JobFinder, operated by <Fill of="company" />, collects job listings from public sources, ranks them against
          your profile and helps you prepare applications (tailored CVs, cover letters, interview practice) and track
          them. By creating an account you agree to these terms and to the <Link href="/privacy">privacy policy</Link>.
        </p>
      </Section>
      <Section title="2. Your account">
        <p>
          You must be at least <Fill of="minimumAge" />, give a real email address you control, keep your password
          safe and tell us if you think someone else has access. You are responsible for what happens under your
          account.
        </p>
      </Section>
      <Section title="3. Your content and AI features">
        <p>
          You keep ownership of your CV and everything you add. You give us the permission needed to store and process
          it to run the service for you, including by the AI providers in the <Link href="/subprocessors">list</Link>,
          if you consented to AI processing.
        </p>
        <p>
          AI output can be wrong or incomplete. Tailored documents are built from your own CV and checked so they add
          nothing you do not have, but you must read and approve everything before sending it to an employer, and you
          alone are responsible for the accuracy of what you submit. Match scores are guidance, not a prediction or a
          promise of any outcome.
        </p>
      </Section>
      <Section title="4. Job listings">
        <p>
          Listings come from third parties. We do not employ, recommend or vouch for any employer, we do not guarantee a
          listing is accurate, current or still open, and an application is made on the employer&apos;s own site under its
          terms.
        </p>
      </Section>
      <Section title="5. Plans, credits and payment">
        <p>
          AI features use credits. The Free plan includes a monthly amount; paid plans and credit packs are priced on the
          billing page. Payments are processed by Stripe or Paystack; subscriptions renew until cancelled and can be
          cancelled at any time, effective at the end of the paid period. Refunds: <Fill of="refundPolicy" />. We may
          change prices or credit amounts with notice before the change applies to you.
        </p>
      </Section>
      <Section title="6. Acceptable use">
        <p>
          Do not break the law, upload files you may not share or that contain malware, put someone else&apos;s personal data
          in your profile without their permission, try to bypass limits or security, scrape or overload the service, or
          use it to submit false information about yourself. We may limit, suspend or end accounts that do.
        </p>
      </Section>
      <Section title="7. Ending the relationship">
        <p>
          You can delete your account at any time in Settings, Privacy and data; your data is then erased as the privacy
          policy describes. We may suspend or end the service or an account for breach of these terms or to meet a legal
          duty, with notice where we can give it.
        </p>
      </Section>
      <Section title="8. Warranties and liability">
        <p>
          [LAWYER TO COMPLETE: disclaimer of warranties, limitation and exclusion of liability, and consumer rights that
          cannot be excluded in the countries where the service is offered.]
        </p>
      </Section>
      <Section title="9. Changes, law and contact">
        <p>
          We will give notice of material changes before they apply. These terms are governed by{" "}
          <Fill of="governingLaw" />. Contact: <Fill of="contactEmail" />.
        </p>
      </Section>
    </LegalPage>
  );
}
