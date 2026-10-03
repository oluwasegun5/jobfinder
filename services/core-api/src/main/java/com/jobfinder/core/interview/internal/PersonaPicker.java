package com.jobfinder.core.interview.internal;

import java.util.Locale;

import com.jobfinder.core.interview.MockInterviews.PersonaView;

/**
 * Chooses the interviewer persona of a mock interview from the job (title, seniority, function), once, at session start
 * (docs/adr/0034-mock-interview.md). The result comes from closed vocabularies, not from free text of the posting, so a
 * posting cannot write the persona. It decides the tone of the wording and the style of generated questions; the rubric
 * is the same for every persona, and ai-service's prompt says so.
 */
final class PersonaPicker {

    private PersonaPicker() {
    }

    static final String ENGINEERING = "engineering";
    static final String DATA = "data";
    static final String PRODUCT = "product";
    static final String DESIGN = "design";
    static final String MARKETING = "marketing";
    static final String SALES = "sales";
    static final String OPERATIONS = "operations";
    static final String SUPPORT = "support";
    static final String OTHER = "other";

    static PersonaView pick(String title, String seniorityField) {
        String function = function(title);
        String seniority = seniority(title, seniorityField);
        return new PersonaView(interviewer(function), function, seniority, tone(seniority),
                questionStyle(function, seniority));
    }

    /** The first match wins, so a "product designer" is design and a "data engineer" is data. */
    static String function(String title) {
        String t = " " + normalise(title) + " ";
        if (any(t, " design", " ux ", " ui ", "designer")) {
            return DESIGN;
        }
        if (any(t, "product manager", "product owner", "head of product", "product lead", "product director")) {
            return PRODUCT;
        }
        if (any(t, " data ", "machine learning", " ml ", "analytics", "scientist", " bi ", "analyst")) {
            return DATA;
        }
        if (any(t, "support", "customer success", "customer service", "helpdesk", "help desk")) {
            return SUPPORT;
        }
        if (any(t, " sales", "account executive", "business development", "account manager")) {
            return SALES;
        }
        if (any(t, "marketing", " growth ", " seo ", "content ", "brand ")) {
            return MARKETING;
        }
        if (any(t, "operations", "logistics", "supply chain", "program manager", "project manager",
                "dispatch", "warehouse", "procurement")) {
            return OPERATIONS;
        }
        if (any(t, "engineer", "developer", "software", "devops", " sre ", "backend", "frontend", "full stack",
                "fullstack", "programmer", "architect", " qa ", "security")) {
            return ENGINEERING;
        }
        return OTHER;
    }

    /** From the job's seniority field when it says something we know, else from the title; mid when neither does. */
    static String seniority(String title, String field) {
        String fromField = level(normalise(field));
        if (fromField != null) {
            return fromField;
        }
        String fromTitle = level(normalise(title));
        return fromTitle != null ? fromTitle : "mid";
    }

    private static String level(String text) {
        String t = " " + text + " ";
        if (any(t, " intern ", " interns ", "junior", " jr ", "graduate", " entry ", "trainee", " associate ")) {
            return "junior";
        }
        if (any(t, " lead ", "head of", "director", "manager", " vp ", "principal", "chief", " staff ")) {
            return "lead";
        }
        if (any(t, "senior", " sr ", "expert")) {
            return "senior";
        }
        if (any(t, " mid ", "mid level", "intermediate", " regular ")) {
            return "mid";
        }
        return null;
    }

    static String interviewer(String function) {
        return switch (function) {
            case ENGINEERING -> "Engineering manager";
            case DATA -> "Head of data";
            case PRODUCT -> "Head of product";
            case DESIGN -> "Design lead";
            case MARKETING -> "Marketing manager";
            case SALES -> "Sales director";
            case OPERATIONS -> "Operations manager";
            case SUPPORT -> "Customer support lead";
            default -> "Hiring manager";
        };
    }

    static String tone(String seniority) {
        return switch (seniority) {
            case "junior" -> "warm";
            case "mid" -> "neutral";
            default -> "direct";
        };
    }

    static String questionStyle(String function, String seniority) {
        if ("lead".equals(seniority)) {
            return "behavioral_probing";
        }
        return switch (function) {
            case ENGINEERING, DATA -> "technical_depth";
            case PRODUCT, OPERATIONS -> "scenario_based";
            case SALES, SUPPORT, MARKETING -> "behavioral_probing";
            default -> "conversational";
        };
    }

    private static boolean any(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static String normalise(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9+#]+", " ").strip();
    }
}
