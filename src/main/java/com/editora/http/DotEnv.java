package com.editora.http;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pure parser for a {@code .env} file: {@code KEY=VALUE} lines, optional surrounding quotes (escapes expanded
 * inside double quotes), {@code #} comments — whole-line or after an unquoted value — and blank lines skipped,
 * an optional leading {@code export }. Backs the {@code {{$dotenv NAME}}} dynamic
 * variable. Takes the file content as a string, so it is unit-tested.
 */
public final class DotEnv {

    private DotEnv() {}

    /** Parses {@code content} into {@code KEY → VALUE}, in declaration order. */
    public static Map<String, String> parse(String content) {
        Map<String, String> out = new LinkedHashMap<>();
        if (content == null || content.isBlank()) {
            return out;
        }
        for (String raw : content.split("\n", -1)) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("export ")) {
                line = line.substring("export ".length()).strip();
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            out.put(
                    line.substring(0, eq).strip(),
                    unquote(line.substring(eq + 1).strip()));
        }
        return out;
    }

    /**
     * The value part of a line: a double-quoted value with its backslash escapes ({@code \n}, {@code \t},
     * {@code \r}, an escaped quote or backslash) expanded, a single-quoted value verbatim, an unquoted value up to an inline {@code  # comment}.
     */
    private static String unquote(String v) {
        if (v.startsWith("\"")) {
            int end = closingQuote(v);
            if (end > 0) {
                return unescape(v.substring(1, end));
            }
        }
        if (v.startsWith("'")) {
            int end = v.indexOf('\'', 1);
            if (end > 0) {
                return v.substring(1, end);
            }
        }
        int hash = v.indexOf(" #");
        int tab = v.indexOf("\t#");
        int cut = hash < 0 ? tab : tab < 0 ? hash : Math.min(hash, tab);
        return cut >= 0 ? v.substring(0, cut).strip() : v;
    }

    /** The index of the double quote that closes {@code v} (which starts with one), skipping escaped quotes. */
    private static int closingQuote(String v) {
        for (int i = 1; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == '"') {
                return i;
            }
        }
        return -1;
    }

    private static String unescape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char next = s.charAt(++i);
                switch (next) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case '"', '\\' -> sb.append(next);
                    default -> sb.append('\\').append(next);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
