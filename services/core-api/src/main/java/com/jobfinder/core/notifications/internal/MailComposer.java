package com.jobfinder.core.notifications.internal;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Builds the text and HTML of a digest or alert (docs/adr/0028-notifications.md). Pure functions of their input, so
 * they are tested without a mail server.
 *
 * <p><b>Everything that came from outside is untrusted</b>: job titles, company names, places, the model's strengths and
 * saved-search names can hold markup or control characters. Every such value goes through {@link #esc} before it enters
 * the HTML, and through {@link #oneLine} before it enters the plain text or the subject. Links are built from our own
 * base URL and ids; a source's attribution link is only used when it is an http(s) URL.
 */
final class MailComposer {

    /** One job in the email. Strings are raw (not escaped yet). */
    record Item(String title, String company, String place, String salary, String posted, String url,
            String score, String reason, String source) {
    }

    /** A credit a source's terms require ("Jobs by Adzuna"). */
    record Credit(String text, String url) {
    }

    /** A link in the footer: where it goes and what it says. */
    record Link(String label, String url) {
    }

    /**
     * What the email says.
     *
     * @param more     jobs found beyond the ones listed
     * @param moreUrl  where to see them all, or null
     * @param oneClick the RFC 8058 endpoint of the list-unsubscribe header
     * @param footer   the unsubscribe and settings links, in the order shown
     */
    record Layout(String subject, String heading, String intro, List<Item> items, int more, String moreUrl,
            List<Credit> credits, List<Link> footer, String oneClick, String sentLine) {
    }

    /** What is sent: the subject, both bodies and the one-click URL for the header. */
    record Mail(String subject, String text, String html, String oneClickUrl) {
    }

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH);
    private static final DateTimeFormatter SENT = DateTimeFormatter.ofPattern("EEE d MMM yyyy, HH:mm", Locale.ENGLISH);

    private MailComposer() {
    }

    static Mail compose(Layout layout) {
        return new Mail(oneLine(layout.subject()), text(layout), html(layout), layout.oneClick());
    }

    /** "Posted Mon 29 Sep" in the user's zone, or null when the job has no date. */
    static String posted(Instant at, ZoneId zone) {
        return at == null ? null : "Posted " + DAY.format(at.atZone(zone));
    }

    /** "Sent Wed 1 Oct 2026, 08:05 (Africa/Lagos)". */
    static String sentLine(Instant now, ZoneId zone) {
        ZonedDateTime local = now.atZone(zone);
        return "Sent " + SENT.format(local) + " (" + (zone.equals(java.time.ZoneOffset.UTC) ? "UTC" : zone.getId()) + ")";
    }

    /** "USD 100,000-120,000 per year", or null without a salary. */
    static String salary(BigDecimal min, BigDecimal max, String currency, String period) {
        if (min == null && max == null) {
            return null;
        }
        String amount = min != null && max != null && min.compareTo(max) != 0 ? number(min) + "-" + number(max)
                : number(min != null ? min : max);
        String unit = period == null ? "" : " per " + period.toLowerCase(Locale.ROOT);
        return (currency == null ? "" : currency + " ") + amount + unit;
    }

    private static String number(BigDecimal value) {
        return String.format(Locale.ENGLISH, "%,d", value.setScale(0, java.math.RoundingMode.HALF_UP).longValue());
    }

    // --- plain text ---

    private static String text(Layout l) {
        StringBuilder out = new StringBuilder();
        out.append(oneLine(l.heading())).append("\n\n");
        if (l.intro() != null) {
            out.append(oneLine(l.intro())).append("\n\n");
        }
        int n = 1;
        for (Item item : l.items()) {
            out.append(n++).append(". ").append(oneLine(item.title()));
            if (item.company() != null) {
                out.append(" - ").append(oneLine(item.company()));
            }
            out.append('\n');
            List<String> facts = facts(item);
            if (!facts.isEmpty()) {
                out.append("   ").append(String.join(" | ", facts)).append('\n');
            }
            if (item.reason() != null) {
                out.append("   ").append(oneLine(item.reason())).append('\n');
            }
            out.append("   ").append(item.url()).append("\n\n");
        }
        if (l.more() > 0) {
            out.append("... and ").append(l.more()).append(" more");
            out.append(l.moreUrl() != null ? ": " + l.moreUrl() : "").append("\n\n");
        }
        for (Credit credit : l.credits()) {
            out.append(oneLine(credit.text()));
            if (safe(credit.url())) {
                out.append(" (").append(credit.url()).append(')');
            }
            out.append('\n');
        }
        if (!l.credits().isEmpty()) {
            out.append('\n');
        }
        out.append("--\n").append(l.sentLine()).append('\n');
        for (Link link : l.footer()) {
            out.append(oneLine(link.label())).append(": ").append(link.url()).append('\n');
        }
        return out.toString();
    }

    private static List<String> facts(Item item) {
        List<String> facts = new java.util.ArrayList<>();
        for (String fact : new String[] { item.place(), item.salary(), item.posted(), item.score(), item.source() }) {
            if (fact != null && !fact.isBlank()) {
                facts.add(oneLine(fact));
            }
        }
        return facts;
    }

    // --- HTML ---

    private static String html(Layout l) {
        StringBuilder out = new StringBuilder(2048);
        out.append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
                .append("<title>").append(esc(l.subject())).append("</title></head>")
                .append("<body style=\"margin:0;padding:0;background:#f4f4f5;\">")
                .append("<div style=\"max-width:600px;margin:0 auto;padding:24px 16px;font-family:-apple-system,Segoe UI,")
                .append("Helvetica,Arial,sans-serif;color:#18181b;line-height:1.45;\">")
                .append("<h1 style=\"font-size:20px;margin:0 0 8px;\">").append(esc(l.heading())).append("</h1>");
        if (l.intro() != null) {
            out.append("<p style=\"margin:0 0 16px;color:#52525b;\">").append(esc(l.intro())).append("</p>");
        }
        for (Item item : l.items()) {
            out.append("<div style=\"background:#ffffff;border:1px solid #e4e4e7;border-radius:8px;padding:12px 14px;")
                    .append("margin:0 0 10px;\">")
                    .append("<a href=\"").append(esc(item.url())).append("\" style=\"font-size:16px;font-weight:600;")
                    .append("color:#1d4ed8;text-decoration:none;\">").append(esc(item.title())).append("</a>");
            if (item.company() != null) {
                out.append("<div style=\"font-size:14px;\">").append(esc(item.company())).append("</div>");
            }
            List<String> facts = facts(item);
            if (!facts.isEmpty()) {
                out.append("<div style=\"font-size:13px;color:#52525b;\">")
                        .append(String.join(" &middot; ", facts.stream().map(MailComposer::esc).toList()))
                        .append("</div>");
            }
            if (item.reason() != null) {
                out.append("<div style=\"font-size:13px;margin-top:4px;\">").append(esc(item.reason())).append("</div>");
            }
            out.append("</div>");
        }
        if (l.more() > 0) {
            out.append("<p style=\"margin:8px 0 16px;\">");
            if (l.moreUrl() != null) {
                out.append("<a href=\"").append(esc(l.moreUrl())).append("\" style=\"color:#1d4ed8;\">");
            }
            out.append("and ").append(l.more()).append(" more");
            if (l.moreUrl() != null) {
                out.append("</a>");
            }
            out.append("</p>");
        }
        for (Credit credit : l.credits()) {
            out.append("<p style=\"font-size:12px;color:#71717a;margin:0 0 4px;\">");
            if (safe(credit.url())) {
                out.append("<a href=\"").append(esc(credit.url())).append("\" style=\"color:#71717a;\">")
                        .append(esc(credit.text())).append("</a>");
            } else {
                out.append(esc(credit.text()));
            }
            out.append("</p>");
        }
        out.append("<hr style=\"border:0;border-top:1px solid #e4e4e7;margin:16px 0;\">")
                .append("<p style=\"font-size:12px;color:#71717a;margin:0 0 6px;\">").append(esc(l.sentLine()))
                .append("</p><p style=\"font-size:12px;color:#71717a;margin:0;\">");
        for (int i = 0; i < l.footer().size(); i++) {
            Link link = l.footer().get(i);
            out.append(i > 0 ? " &middot; " : "").append("<a href=\"").append(esc(link.url()))
                    .append("\" style=\"color:#71717a;\">").append(esc(link.label())).append("</a>");
        }
        return out.append("</p></div></body></html>").toString();
    }

    private static boolean safe(String url) {
        return url != null && (url.startsWith("https://") || url.startsWith("http://"));
    }

    /** Escapes text for HTML content and double-quoted attribute values. */
    static String esc(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(Character.isISOControl(c) && c != '\n' ? ' ' : c);
            }
        }
        return out.toString();
    }

    /** One line of plain text: control characters (line breaks above all) become spaces, runs of space collapse. */
    static String oneLine(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            out.append(Character.isISOControl(c) || c == ' ' || c == ' ' ? ' ' : c);
        }
        return out.toString().replaceAll(" {2,}", " ").strip();
    }
}
