package com.esp.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Parses and evaluates ESP pattern strings.
 *
 * <h3>Syntax</h3>
 * <p>Patterns are comma-separated tokens. Tokens are OR'd — any match = the whole
 * pattern matches.</p>
 *
 * <ul>
 *   <li><b>Plain token</b> — {@code magmatic} — matches if the label text contains
 *       {@code "magmatic"} (case-insensitive).</li>
 *   <li><b>Quoted token</b> — {@code "one two three"} — matches if ALL space-separated
 *       words inside the quotes appear anywhere in the label text.</li>
 *   <li><b>Unicode escape</b> — {@code \\uXXXX} (4 hex) is replaced by that character
 *       anywhere in the pattern. Use it to match custom server glyphs you can't type,
 *       such as Hypixel's mob-type icons (run {@code /esp types} for the list).</li>
 * </ul>
 *
 * <h3>Examples</h3>
 * <pre>
 *   one, two, three          → true if text contains "one" OR "two" OR "three"
 *   "one two three", two     → true if (text has all of one+two+three) OR (text has "two")
 *   \\uE072                   → matches the Aquatic mob-type icon (U+E072)
 *   \\uE072, \\uE07D           → matches the Aquatic OR Magmatic icon
 * </pre>
 */
public class PatternMatcher {

    private final List<Token> tokens;

    public PatternMatcher(String patternString) {
        this.tokens = parse(patternString);
    }

    /** Returns true if {@code text} satisfies at least one token in this pattern. */
    public boolean matches(String text) {
        if (tokens.isEmpty()) return false;
        String lower = text.toLowerCase();
        for (Token t : tokens) {
            if (t.matches(lower)) return true;
        }
        return false;
    }

    public boolean isEmpty() {
        return tokens.isEmpty();
    }

    // ── Parsing ───────────────────────────────────────────────────────────────

    private static List<Token> parse(String raw) {
        List<Token> result = new ArrayList<>();
        if (raw == null || raw.isBlank()) return result;

        // Turn unicode escapes into the actual character before any other parsing, so
        // users can enter glyphs they can't type (e.g. Hypixel's mob-type PUA icons).
        raw = unescapeUnicode(raw);

        for (String part : splitByComma(raw)) {
            String trimmed = part.strip();
            if (trimmed.isEmpty()) continue;

            if (trimmed.startsWith("\"") && trimmed.endsWith("\"") && trimmed.length() > 1) {
                // Quoted: all words inside must appear
                String inner = trimmed.substring(1, trimmed.length() - 1).strip();
                List<String> words = Arrays.stream(inner.split("\\s+"))
                        .map(String::toLowerCase)
                        .filter(w -> !w.isEmpty())
                        .collect(Collectors.toList());
                if (!words.isEmpty()) result.add(new AndToken(words));
            } else {
                // Plain: text just needs to contain this substring
                result.add(new PlainToken(trimmed.toLowerCase()));
            }
        }
        return result;
    }

    /**
     * Replaces every {@code \\uXXXX} (backslash, 'u'/'U', exactly 4 hex digits) with the
     * corresponding character. Sequences that aren't a valid escape are left untouched.
     */
    private static String unescapeUnicode(String s) {
        if (s.indexOf('\\') < 0) return s; // fast path — no escapes present
        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\\' && i + 6 <= s.length()
                    && (s.charAt(i + 1) == 'u' || s.charAt(i + 1) == 'U')
                    && isHex(s, i + 2, 4)) {
                out.append((char) Integer.parseInt(s.substring(i + 2, i + 6), 16));
                i += 6;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static boolean isHex(String s, int from, int count) {
        for (int k = 0; k < count; k++) {
            char h = s.charAt(from + k);
            boolean hex = (h >= '0' && h <= '9') || (h >= 'a' && h <= 'f') || (h >= 'A' && h <= 'F');
            if (!hex) return false;
        }
        return true;
    }

    /** Splits on commas that are not inside double-quoted sections. */
    private static List<String> splitByComma(String s) {
        List<String> parts = new ArrayList<>();
        boolean inQuote = false;
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"')            inQuote = !inQuote;
            else if (c == ',' && !inQuote) {
                parts.add(s.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(s.substring(start));
        return parts;
    }

    // ── Token types ───────────────────────────────────────────────────────────

    private interface Token {
        boolean matches(String lowerText);
    }

    /** Matches if the text contains the given substring. */
    private record PlainToken(String term) implements Token {
        public boolean matches(String lowerText) {
            return lowerText.contains(term);
        }
    }

    /** Matches if the text contains ALL of the given words. */
    private record AndToken(List<String> words) implements Token {
        public boolean matches(String lowerText) {
            for (String w : words) {
                if (!lowerText.contains(w)) return false;
            }
            return true;
        }
    }
}
