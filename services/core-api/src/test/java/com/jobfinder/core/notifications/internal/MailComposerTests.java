package com.jobfinder.core.notifications.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.jobfinder.core.notifications.internal.MailComposer.Credit;
import com.jobfinder.core.notifications.internal.MailComposer.Item;
import com.jobfinder.core.notifications.internal.MailComposer.Layout;
import com.jobfinder.core.notifications.internal.MailComposer.Link;
import com.jobfinder.core.notifications.internal.MailComposer.Mail;

/** The text and HTML an email is made of, with hostile input. */
class MailComposerTests {

    private static Item item(String title, String company, String url, String source) {
        return new Item(title, company, "Lagos", null, "Posted Mon 29 Sep", url, "92", "Strong Java overlap", source);
    }

    private static Layout layout(String subject, List<Item> items, List<Credit> credits) {
        return new Layout(subject, "Your matches", "Intro", items, 0, null, credits,
                List.of(new Link("Unsubscribe", "http://localhost:3000/unsubscribe?token=t"),
                        new Link("Settings", "http://localhost:3000/settings/notifications")),
                "http://localhost:3000/api/core/notifications/unsubscribe/t", "Sent Thu 1 Oct 2026, 08:05 (UTC)");
    }

    @Test
    void hostileTextIsEscapedInHtmlAndHarmlessInText() {
        String hostile = "<script>alert(1)</script><img src=x onerror=alert(2)>\"'&";
        Mail mail = MailComposer.compose(layout("Hi", List.of(item(hostile, hostile, "http://localhost:3000/jobs/1", hostile)),
                List.of()));

        assertThat(mail.html()).doesNotContain("<script", "<img").contains("&lt;script&gt;", "&lt;img");
        assertThat(mail.html()).contains("&quot;", "&#39;", "&amp;");
        assertThat(mail.text()).contains(hostile); // plain text is shown as text, not parsed
    }

    @Test
    void aSubjectCannotCarryALineBreak() {
        Mail mail = MailComposer.compose(layout("Hello\r\nBcc: attacker@example.test\n\tx", List.of(), List.of()));

        assertThat(mail.subject()).doesNotContain("\r", "\n", "\t").isEqualTo("Hello Bcc: attacker@example.test x");
    }

    @Test
    void creditLinksAreOnlyUsedWhenTheyAreHttp() {
        Mail mail = MailComposer.compose(layout("Hi", List.of(item("Job", "Co", "http://localhost:3000/jobs/1", "Adzuna")),
                List.of(new Credit("Jobs by Adzuna", "javascript:alert(1)"), new Credit("Jobs by Other", "https://other.example/x"))));

        assertThat(mail.html()).doesNotContain("javascript:").contains("https://other.example/x");
        assertThat(mail.html()).contains("Jobs by Adzuna").contains("Jobs by Other");
        assertThat(mail.text()).contains("Jobs by Adzuna", "Jobs by Other");
    }

    @Test
    void bothPartsCarryTheLinksAndTheFooter() {
        Mail mail = MailComposer.compose(layout("Hi", List.of(item("Job", "Co", "http://localhost:3000/jobs/1", "Adzuna")),
                List.of()));

        for (String part : List.of(mail.text(), mail.html())) {
            assertThat(part).contains("http://localhost:3000/jobs/1", "http://localhost:3000/unsubscribe?token=t",
                    "http://localhost:3000/settings/notifications", "Sent Thu 1 Oct 2026, 08:05 (UTC)", "Adzuna");
        }
        assertThat(mail.oneClickUrl()).isEqualTo("http://localhost:3000/api/core/notifications/unsubscribe/t");
    }

    @Test
    void datesSalariesAndZones() {
        Instant at = Instant.parse("2026-10-01T23:30:00Z");

        assertThat(MailComposer.posted(at, ZoneId.of("UTC"))).isEqualTo("Posted Thu 1 Oct");
        assertThat(MailComposer.posted(at, ZoneId.of("Africa/Lagos"))).isEqualTo("Posted Fri 2 Oct"); // next day there
        assertThat(MailComposer.posted(null, ZoneId.of("UTC"))).isNull();
        assertThat(MailComposer.sentLine(at, ZoneId.of("Europe/Zurich"))).isEqualTo("Sent Fri 2 Oct 2026, 01:30 (Europe/Zurich)");
        assertThat(MailComposer.sentLine(at, ZoneId.of("UTC"))).isEqualTo("Sent Thu 1 Oct 2026, 23:30 (UTC)");
        assertThat(MailComposer.salary(new BigDecimal("100000"), new BigDecimal("120000"), "USD", "YEAR"))
                .isEqualTo("USD 100,000-120,000 per year");
        assertThat(MailComposer.salary(new BigDecimal("5000"), null, null, null)).isEqualTo("5,000");
        assertThat(MailComposer.salary(null, null, "USD", "YEAR")).isNull();
    }
}
