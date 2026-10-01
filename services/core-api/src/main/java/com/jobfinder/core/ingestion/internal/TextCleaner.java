package com.jobfinder.core.ingestion.internal;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.parser.Parser;
import org.jsoup.safety.Safelist;
import org.jsoup.select.NodeTraversor;
import org.jsoup.select.NodeVisitor;

/**
 * Turns the text a source sends into text we can store, search and show. Posting content is untrusted
 * (PLAN.md section 9): HTML is parsed, never pattern-matched, and the HTML we keep is sanitized.
 */
final class TextCleaner {

    private static final Pattern TAG = Pattern.compile("<\\s*/?\\s*[a-zA-Z][^>]*>");
    private static final Pattern ESCAPED_TAG = Pattern.compile("&lt;\\s*/?\\s*[a-zA-Z][^&]*&gt;");
    private static final Pattern INVISIBLE = Pattern.compile("[\\u200B-\\u200F\\u2060\\uFEFF\\u00AD]");
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}&&[^\\n\\t]]");
    private static final Pattern SPACES = Pattern.compile("[\\s\\u00A0]+");
    private static final Pattern LINE_SPACES = Pattern.compile("[ \\t\\u00A0]+");
    private static final Pattern BLANK_LINES = Pattern.compile("\\n{3,}");

    private static final Safelist SAFELIST = Safelist.relaxed()
            .removeTags("img")
            .addEnforcedAttribute("a", "rel", "nofollow noopener noreferrer");

    private TextCleaner() {
    }

    /** One line: entities decoded, tags and invisible characters gone, whitespace collapsed; null if nothing is left. */
    static String line(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw;
        if (text.indexOf('<') >= 0 || text.indexOf('&') >= 0) {
            text = Jsoup.parse(text).text();
        }
        text = INVISIBLE.matcher(text).replaceAll("");
        text = CONTROL.matcher(text).replaceAll(" ");
        text = SPACES.matcher(text).replaceAll(" ").trim();
        return text.isEmpty() ? null : text;
    }

    static String truncate(String text, int max) {
        if (text == null || text.length() <= max) {
            return text;
        }
        return text.substring(0, max).trim();
    }

    /** Lower case, accents removed: the form in which names are compared. */
    static String fold(String text) {
        String decomposed = Normalizer.normalize(text, Normalizer.Form.NFKD);
        return decomposed.replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT);
    }

    static boolean looksLikeHtml(String raw) {
        return raw != null && (TAG.matcher(raw).find() || ESCAPED_TAG.matcher(raw).find());
    }

    /**
     * The HTML of a description as received, with escaped markup (Greenhouse sends {@code &lt;p&gt;})
     * unescaped once. Null for plain text.
     */
    private static String asHtml(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String html = raw;
        if (!TAG.matcher(html).find() && ESCAPED_TAG.matcher(html).find()) {
            html = Parser.unescapeEntities(html, false);
        }
        return TAG.matcher(html).find() ? html : null;
    }

    /** Sanitized HTML safe to render: formatting kept; scripts, handlers, images and unsafe links gone. Null for plain text. */
    static String sanitizedHtml(String raw) {
        String html = asHtml(raw);
        if (html == null) {
            return null;
        }
        String clean = Jsoup.clean(html, "", SAFELIST).trim();
        return clean.isEmpty() ? null : clean;
    }

    /** Readable plain text: paragraphs and list items kept as lines, everything else collapsed. Null if empty. */
    static String descriptionText(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String html = asHtml(raw);
        String text;
        if (html == null) {
            text = raw.replace("\r\n", "\n").replace('\r', '\n');
        } else {
            text = render(Jsoup.parseBodyFragment(html));
        }
        text = INVISIBLE.matcher(text).replaceAll("");
        text = CONTROL.matcher(text).replaceAll(" ");
        StringBuilder out = new StringBuilder();
        for (String lineText : text.split("\n", -1)) {
            out.append(LINE_SPACES.matcher(lineText).replaceAll(" ").trim()).append('\n');
        }
        String result = BLANK_LINES.matcher(out).replaceAll("\n\n").trim();
        return result.isEmpty() ? null : result;
    }

    private static String render(Document document) {
        document.select("script, style, head, noscript, template, iframe, object, embed").remove();
        StringBuilder out = new StringBuilder();
        NodeTraversor.traverse(new NodeVisitor() {
            @Override
            public void head(Node node, int depth) {
                if (node instanceof TextNode text) {
                    out.append(SPACES.matcher(text.getWholeText()).replaceAll(" "));
                } else if (node instanceof Element element) {
                    String name = element.normalName();
                    switch (name) {
                        case "br" -> out.append('\n');
                        case "li" -> out.append("\n- ");
                        case "td", "th" -> out.append(' ');
                        default -> {
                            // A row ends a line; it need not also start one, or tables gain blank lines.
                            if (isBlock(name) && !name.equals("tr")) {
                                out.append('\n');
                            }
                        }
                    }
                }
            }

            @Override
            public void tail(Node node, int depth) {
                if (node instanceof Element element && isBlock(element.normalName())) {
                    out.append('\n');
                }
            }
        }, document.body());
        return out.toString();
    }

    private static boolean isBlock(String name) {
        return switch (name) {
            case "p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "ul", "ol", "tr", "table", "blockquote", "section",
                    "article", "pre", "hr", "dl", "dt", "dd", "header", "footer" -> true;
            default -> false;
        };
    }
}
