package com.jobfinder.core.observability;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Last line of defence for "never log PII or secrets" (CLAUDE.md, PLAN.md section 9): masks what is recognisable by its
 * shape in any text that is about to leave the process in a log line or an error report. The rule itself is kept by never
 * passing such values to a logger (LogRedactionTests); this catches the slip. It cannot recognise free text such as a CV,
 * so it also caps the length of a message: nothing legitimate the application logs comes near the cap.
 */
public final class PiiRedactor {

    /** A log message longer than this is cut; a pasted CV or request body is far longer. */
    public static final int MAX_LENGTH = 4000;

    private static final String TRUNCATED = "...[truncated]";

    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9\\-]+(?:\\.[A-Za-z0-9\\-]+)*\\.[A-Za-z]{2,}");
    private static final Pattern JWT = Pattern
            .compile("eyJ[A-Za-z0-9_\\-]{5,}\\.[A-Za-z0-9_\\-]{5,}\\.[A-Za-z0-9_\\-]*");
    private static final Pattern BEARER = Pattern.compile("(?i)\\b(bearer|basic)\\s+[A-Za-z0-9._~+/\\-]+=*");
    // Provider keys by prefix: Anthropic, Stripe (secret, publishable, restricted, webhook), Paystack, Voyage, AWS, Google.
    private static final Pattern PROVIDER_KEY = Pattern.compile(
            "\\b(?:sk-ant-[A-Za-z0-9_\\-]{8,}|(?:sk|pk|rk)_(?:live|test)_[A-Za-z0-9]{6,}|whsec_[A-Za-z0-9]{6,}"
                    + "|pa-[A-Za-z0-9_\\-]{20,}|AKIA[0-9A-Z]{16}|AIza[0-9A-Za-z_\\-]{20,})");
    // name=value, name: value and "name":"value" for names that hold secrets; the name stays, the value goes.
    private static final Pattern NAMED_SECRET = Pattern.compile(
            "(?i)([\"']?(?:password|passwd|secret|api[_-]?key|access[_-]?token|refresh[_-]?token|id[_-]?token|token|"
                    + "authorization|x-service-token|cookie|set-cookie|signature|stripe-signature|x-paystack-signature)"
                    + "[\"']?\\s*[=:]\\s*)(?:\"[^\"]*\"|'[^']*'|[^\\s,;&\"'}]+)");
    private static final Pattern PHONE = Pattern.compile("(?<![\\w+])\\+\\d[\\d\\s().\\-]{7,}\\d");

    private PiiRedactor() {
    }

    /** The text with emails, tokens, keys, secret-looking assignments and international phone numbers masked. */
    public static String redact(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String result = text;
        result = JWT.matcher(result).replaceAll("[jwt]");
        result = BEARER.matcher(result).replaceAll(m -> m.group(1) + " [redacted]");
        result = PROVIDER_KEY.matcher(result).replaceAll("[key]");
        result = NAMED_SECRET.matcher(result).replaceAll(m -> Matcher.quoteReplacement(m.group(1) + "[redacted]"));
        result = EMAIL.matcher(result).replaceAll("[email]");
        result = PHONE.matcher(result).replaceAll("[phone]");
        return result.length() > MAX_LENGTH ? result.substring(0, MAX_LENGTH) + TRUNCATED : result;
    }
}
