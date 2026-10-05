package dev.jetoptimizer;

import java.util.Set;

/**
 * Replacements for the two regular-expression operations that JEI runs once per tooltip line of
 * every registered ingredient while building its search index.
 *
 * <p>JEI's {@code ListElementInfo.getStrings} turns each tooltip line into search words with
 * {@code ChatFormatting.stripFormatting} (regex {@code (?i)\u00A7[0-9A-FK-OR]}) and
 * {@code WHITESPACE_PATTERN.split} (regex {@code \s+}). Both regexes are pure, both are applied to
 * every tooltip line of every ingredient, and neither has any dependency on the client level, the
 * player or the connection. The regex engine allocates a {@code Matcher} per call, which is the
 * dominant allocation in that loop.
 *
 * <p>Both replacements reproduce the original semantics exactly, including the cases that are easy
 * to get wrong:
 * <ul>
 *   <li>{@code String.trim()} removes every character {@code <= ' '}, while {@code \s} only matches
 *       the six whitespace characters. Trimming therefore has to stay a separate step.</li>
 *   <li>{@code Pattern.split} drops trailing empty strings and {@code addSplitStrings} skips empty
 *       tokens anyway, so only non-empty tokens may be added.</li>
 *   <li>{@code (?i)} in Java folds ASCII only for these ranges, so {@code [0-9A-FK-OR]} matches
 *       exactly {@code 0-9}, {@code a-f}/{@code A-F}, {@code k}/{@code K} through
 *       {@code o}/{@code O}, and {@code r}/{@code R}.</li>
 *   <li>The originals return the same {@code String} instance when nothing matches; the fast path
 *       does the same instead of allocating.</li>
 * </ul>
 *
 * <p>There is no cache here and nothing is retained between calls: the work is already repeated for
 * the same text by JEI, and the replacements are individually cheaper than the regexes they remove,
 * so a memo would only add lookup and retention cost.
 */
public final class SearchTextOptimization {
    private static final char SECTION_SIGN = '\u00A7';

    private static volatile Boolean cachedEnabled;

    private SearchTextOptimization() {
    }

    /**
     * Cached per process so the mixin hot path never touches the config spec.
     *
     * <p>A config read that throws before the config is registered must not be cached: that would
     * disable the optimization for the whole session instead of only for the current call.
     */
    public static boolean enabled() {
        Boolean enabled = cachedEnabled;
        if (enabled != null) {
            return enabled;
        }
        try {
            boolean value = JETOptimizerConfig.FAST_SEARCH_TEXT.get();
            cachedEnabled = value;
            return value;
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    /**
     * Equivalent to {@code Pattern.compile("(?i)\u00A7[0-9A-FK-OR]").matcher(text).replaceAll("")},
     * which is what {@code ChatFormatting.stripFormatting} runs.
     */
    public static String stripFormatting(String text) {
        int length = text.length();
        int firstMatch = -1;
        for (int i = 0; i < length; i++) {
            if (isFormattingSequence(text, i)) {
                firstMatch = i;
                break;
            }
        }
        if (firstMatch < 0) {
            return text;
        }

        StringBuilder builder = new StringBuilder(length - 2);
        builder.append(text, 0, firstMatch);
        int i = firstMatch;
        while (i < length) {
            if (isFormattingSequence(text, i)) {
                i += 2;
            } else {
                builder.append(text.charAt(i));
                i++;
            }
        }
        return builder.toString();
    }

    /**
     * Equivalent to trimming {@code text}, splitting the result on runs of {@code \s} and adding
     * every non-empty token to {@code result}.
     */
    public static void splitOnWhitespace(Set<String> result, String text) {
        int length = text.length();
        int start = 0;
        int end = length;
        while (start < end && text.charAt(start) <= ' ') {
            start++;
        }
        while (end > start && text.charAt(end - 1) <= ' ') {
            end--;
        }
        if (start >= end) {
            return;
        }

        int tokenStart = start;
        int i = start;
        while (i < end) {
            if (isRegexWhitespace(text.charAt(i))) {
                if (i > tokenStart) {
                    result.add(text.substring(tokenStart, i));
                }
                i++;
                while (i < end && isRegexWhitespace(text.charAt(i))) {
                    i++;
                }
                tokenStart = i;
            } else {
                i++;
            }
        }
        if (tokenStart < end) {
            result.add(text.substring(tokenStart, end));
        }
    }

    private static boolean isFormattingSequence(String text, int index) {
        if (text.charAt(index) != SECTION_SIGN) {
            return false;
        }
        int next = index + 1;
        return next < text.length() && isFormattingCode(text.charAt(next));
    }

    private static boolean isFormattingCode(char code) {
        return (code >= '0' && code <= '9')
            || (code >= 'a' && code <= 'f')
            || (code >= 'A' && code <= 'F')
            || (code >= 'k' && code <= 'o')
            || (code >= 'K' && code <= 'O')
            || code == 'r' || code == 'R';
    }

    /** The characters {@code \s} matches in Java: ASCII whitespace, not every {@code <= ' '}. */
    private static boolean isRegexWhitespace(char character) {
        return character == ' ' || character == '\t' || character == '\n'
            || character == '\u000B' || character == '\f' || character == '\r';
    }
}