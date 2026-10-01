package com.jobfinder.core.feed.internal;

import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Job titles as sets of comparable words. A title is lower-cased, split on anything that is not a letter or digit,
 * stripped of filler words, seniority words (a hidden "Senior Backend Engineer" says something about backend work, not
 * about seniority) and one-letter fragments (the "m/f/d" of German postings), and a few synonyms are folded together
 * ("developer" is "engineer"). Two titles are as similar as the Jaccard index of their word sets.
 */
final class TitleTokens {

    private static final Set<String> IGNORED = Set.of("a", "an", "and", "or", "of", "the", "for", "in", "at", "to",
            "with", "on", "ii", "iii", "iv", "senior", "sr", "junior", "jr", "lead", "staff", "principal", "mid",
            "midlevel", "entry", "level", "associate", "head", "remote", "hybrid", "fulltime", "parttime", "contract",
            "intern", "internship");

    private static final Map<String, String> SYNONYMS = Map.of("developer", "engineer", "programmer", "engineer",
            "dev", "engineer", "swe", "engineer", "sde", "engineer");

    private TitleTokens() {
    }

    static Set<String> of(String title) {
        if (title == null) {
            return Set.of();
        }
        String text = title.toLowerCase(Locale.ROOT).replace("c++", "cpp").replace("c#", "csharp")
                .replace(".net", "dotnet").replace("node.js", "nodejs");
        Set<String> tokens = new HashSet<>();
        for (String word : text.split("[^\\p{L}\\p{N}]+")) {
            if (word.length() < 2 || IGNORED.contains(word)) {
                continue;
            }
            tokens.add(SYNONYMS.getOrDefault(word, word));
        }
        return Set.copyOf(tokens);
    }

    /** Intersection over union of the two word sets; 0 when either is empty. */
    static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        int shared = 0;
        for (String token : a) {
            if (b.contains(token)) {
                shared++;
            }
        }
        return (double) shared / (a.size() + b.size() - shared);
    }
}
