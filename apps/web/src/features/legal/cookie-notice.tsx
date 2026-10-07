import { Fill, LegalPage, Section } from "./legal-page";

export function CookieNotice() {
  return (
    <LegalPage title="Cookie notice">
      <Section title="What we use">
        <table>
          <thead>
            <tr>
              <th scope="col">Name</th>
              <th scope="col">Purpose</th>
              <th scope="col">Lifetime</th>
              <th scope="col">Type</th>
            </tr>
          </thead>
          <tbody>
            <tr>
              <td>
                <code>refresh_token</code>
              </td>
              <td>
                Keeps you signed in. It is httpOnly (scripts cannot read it), Secure and SameSite=Strict (other sites
                cannot use it) and holds an opaque random value, not your data.
              </td>
              <td>Until you sign out, or it expires; it is renewed when you use the app.</td>
              <td>Strictly necessary</td>
            </tr>
          </tbody>
        </table>
      </Section>
      <Section title="What we do not use">
        <p>
          No analytics, advertising or tracking cookies, no third-party scripts that set cookies, and nothing sensitive
          in local storage: your short-lived access token lives in the page&apos;s memory only and is gone when you close
          the tab. Because the only cookie is strictly necessary to provide the service you ask for, no cookie banner
          is needed [LAWYER TO CONFIRM for the countries you serve]. If this ever changes, we will ask for your consent before setting anything else.
        </p>
      </Section>
      <Section title="Contact">
        <p>
          Questions: <Fill of="privacyEmail" />.
        </p>
      </Section>
    </LegalPage>
  );
}
