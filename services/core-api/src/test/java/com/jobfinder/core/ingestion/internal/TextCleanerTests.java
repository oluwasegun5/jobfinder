package com.jobfinder.core.ingestion.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** HTML to text and title cleaning on postings shaped like the ones real boards send. */
class TextCleanerTests {

    @Test
    void greenhouseSendsEscapedHtmlWhichIsUnescapedOnceAndThenRendered() {
        String raw = "&lt;div class=&quot;content-intro&quot;&gt;&lt;p&gt;Join our &amp;amp; grow&lt;/p&gt;&lt;/div&gt;"
                + "&lt;h3&gt;What you&#39;ll do&lt;/h3&gt;&lt;ul&gt;&lt;li&gt;Build APIs&lt;/li&gt;&lt;li&gt;Review code&lt;/li&gt;&lt;/ul&gt;";

        String text = TextCleaner.descriptionText(raw);

        assertThat(text).isEqualTo("Join our & grow\n\nWhat you'll do\n\n- Build APIs\n- Review code");
        assertThat(TextCleaner.sanitizedHtml(raw)).contains("<li>Build APIs</li>").doesNotContain("&lt;");
    }

    @Test
    void listsParagraphsAndLineBreaksBecomeLines() {
        String html = "<p>About us</p><p>We make<br>things.</p><ol><li>One</li><li>Two <b>bold</b></li></ol>";

        assertThat(TextCleaner.descriptionText(html)).isEqualTo("About us\n\nWe make\nthings.\n\n- One\n- Two bold");
    }

    @Test
    void scriptsStylesAndCommentsNeverReachTheText() {
        String html = "<style>.x{color:red}</style><p>Hello</p><script>alert('x')</script><!-- hidden -->"
                + "<noscript>enable js</noscript><p>World</p>";

        assertThat(TextCleaner.descriptionText(html)).isEqualTo("Hello\n\nWorld");
    }

    @Test
    void entitiesAndNonBreakingSpacesAreDecodedAndCollapsed() {
        String html = "<p>R&amp;D&nbsp;&nbsp;team  \n   in   Lagos &mdash; 5&nbsp;days</p>";

        assertThat(TextCleaner.descriptionText(html)).isEqualTo("R&D team in Lagos — 5 days");
    }

    @Test
    void plainTextKeepsItsLinesAndHasNoHtml() {
        String plain = "Great role.\r\n\r\n\r\n\r\nRequirements:\r\n  - Java\r\n  - SQL   ";

        assertThat(TextCleaner.descriptionText(plain)).isEqualTo("Great role.\n\nRequirements:\n- Java\n- SQL");
        assertThat(TextCleaner.sanitizedHtml(plain)).isNull();
    }

    @Test
    void tablesRenderCellsOnOneLineAndRowsOnSeparateLines() {
        String html = "<table><tr><td>Level</td><td>Pay</td></tr><tr><td>Senior</td><td>High</td></tr></table>";

        assertThat(TextCleaner.descriptionText(html)).isEqualTo("Level Pay\nSenior High");
    }

    @Test
    void emptyAndWhitespaceOnlyDescriptionsAreNull() {
        assertThat(TextCleaner.descriptionText(null)).isNull();
        assertThat(TextCleaner.descriptionText("   ")).isNull();
        assertThat(TextCleaner.descriptionText("<p>&nbsp;</p><div></div>")).isNull();
        assertThat(TextCleaner.sanitizedHtml("")).isNull();
    }

    @Test
    void sanitizedHtmlKeepsFormattingAndDropsEverythingActive() {
        String html = "<p onclick=\"steal()\">Hi <b>there</b></p><script>bad()</script>"
                + "<a href=\"javascript:alert(1)\">x</a><a href=\"https://example.com/apply\">apply</a>"
                + "<img src=\"https://tracker.example/p.gif\"><iframe src=\"https://evil.example\"></iframe>";

        String clean = TextCleaner.sanitizedHtml(html);

        assertThat(clean).contains("<b>there</b>").contains("href=\"https://example.com/apply\"")
                .contains("rel=\"nofollow noopener noreferrer\"")
                .doesNotContain("onclick").doesNotContain("<script").doesNotContain("javascript:")
                .doesNotContain("<img").doesNotContain("<iframe");
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @CsvSource(delimiter = '|', nullValues = "-", textBlock = """
            Senior Engineer   |  Senior Engineer
              Software      Engineer   (Backend)  |  Software Engineer (Backend)
            R&amp;D Engineer  |  R&D Engineer
            <b>Data</b> Analyst  |  Data Analyst
            Sales &amp; Marketing Lead  |  Sales & Marketing Lead
            Café Manager  |  Café Manager
            Dev​Ops Engineer  |  DevOps Engineer
            AT&T Network Engineer  |  AT&T Network Engineer
            '   '  |  -
            """)
    void titlesAreOneCleanLine(String raw, String expected) {
        assertThat(TextCleaner.line(raw)).isEqualTo(expected);
    }
}
