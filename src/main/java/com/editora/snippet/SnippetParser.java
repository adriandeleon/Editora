package com.editora.snippet;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses a TextMate/VS Code snippet body into a {@link ParsedSnippet}: the literal text to insert plus
 * the tab stops and their offset ranges. Pure (no toolkit), so it is unit-tested.
 *
 * <p>Supported syntax:
 * <ul>
 *   <li>tab stops {@code $1 $2 … $0} (0 = final caret);</li>
 *   <li>placeholders {@code ${1:default}} (the default may itself contain nested stops/variables);</li>
 *   <li>mirrors — a number repeated reuses the first occurrence's text and tracks it live;</li>
 *   <li>choices {@code ${1|a,b,c|}} — the first option is used as the default (a dropdown is a later
 *       enhancement);</li>
 *   <li>variables {@code $VAR} / {@code ${VAR:default}} resolved by the supplied resolver;</li>
 *   <li>escapes {@code \$ \} \\}.</li>
 * </ul>
 * Regex transforms {@code ${1/re/fmt/flags}} are parsed and ignored (treated as a plain mirror) so a
 * snippet containing one still expands rather than throwing.
 */
public final class SnippetParser {

    private SnippetParser() {}

    /** Resolves a snippet variable name to its value, or {@code null} when unknown. */
    @FunctionalInterface
    public interface Variables {
        String resolve(String name);
    }

    public static ParsedSnippet parse(String body, Variables variables) {
        StringBuilder out = new StringBuilder();
        Map<Integer, List<int[]>> ranges = new LinkedHashMap<>();
        Map<Integer, String> firstText = new LinkedHashMap<>();
        Map<Integer, List<String>> choices = new LinkedHashMap<>();
        int[] pos = {0};
        parseSeq(body == null ? "" : body, pos, out, ranges, firstText, choices, variables, false);

        List<TabStop> stops = new ArrayList<>();
        ranges.keySet().stream()
                .sorted((a, b) -> Integer.compare(a == 0 ? Integer.MAX_VALUE : a, b == 0 ? Integer.MAX_VALUE : b))
                .forEach(n -> stops.add(new TabStop(
                        n, ranges.get(n), firstText.getOrDefault(n, ""), choices.getOrDefault(n, List.of()))));
        return new ParsedSnippet(out.toString(), stops);
    }

    /** Scans {@code s} from {@code pos[0]}, appending literal text to {@code out}, until end or
     *  (when {@code stopAtBrace}) an unescaped '}' (left unconsumed). */
    private static void parseSeq(
            String s,
            int[] pos,
            StringBuilder out,
            Map<Integer, List<int[]>> ranges,
            Map<Integer, String> firstText,
            Map<Integer, List<String>> choices,
            Variables vars,
            boolean stopAtBrace) {
        while (pos[0] < s.length()) {
            char c = s.charAt(pos[0]);
            if (c == '\\' && pos[0] + 1 < s.length()) {
                char n = s.charAt(pos[0] + 1);
                if (n == '$' || n == '}' || n == '\\') {
                    out.append(n);
                    pos[0] += 2;
                    continue;
                }
                out.append(c);
                pos[0]++;
                continue;
            }
            if (stopAtBrace && c == '}') {
                return;
            }
            if (c == '$' && tryDollar(s, pos, out, ranges, firstText, choices, vars)) {
                continue;
            }
            out.append(c);
            pos[0]++;
        }
    }

    /** Tries to consume a {@code $…} construct at {@code pos[0]}; returns false (and consumes nothing)
     *  if the '$' is just a literal. */
    private static boolean tryDollar(
            String s,
            int[] pos,
            StringBuilder out,
            Map<Integer, List<int[]>> ranges,
            Map<Integer, String> firstText,
            Map<Integer, List<String>> choices,
            Variables vars) {
        int n = pos[0] + 1;
        if (n >= s.length()) {
            return false;
        }
        char c = s.charAt(n);
        if (Character.isDigit(c)) {
            int j = n;
            while (j < s.length() && Character.isDigit(s.charAt(j))) {
                j++;
            }
            int num = Integer.parseInt(s.substring(n, j));
            emitStop(num, null, out, ranges, firstText);
            pos[0] = j;
            return true;
        }
        if (c == '{') {
            return parseBrace(s, pos, out, ranges, firstText, choices, vars);
        }
        if (isVarStart(c)) {
            int j = n;
            while (j < s.length() && isVarPart(s.charAt(j))) {
                j++;
            }
            String val = vars == null ? null : vars.resolve(s.substring(n, j));
            out.append(val == null ? "" : val);
            pos[0] = j;
            return true;
        }
        return false;
    }

    /** Parses a {@code ${…}} construct beginning at {@code pos[0]} (the '$'). */
    private static boolean parseBrace(
            String s,
            int[] pos,
            StringBuilder out,
            Map<Integer, List<int[]>> ranges,
            Map<Integer, String> firstText,
            Map<Integer, List<String>> choices,
            Variables vars) {
        int i = pos[0] + 2; // past "${"
        if (Character.isDigit(s.charAt(i))) {
            int j = i;
            while (j < s.length() && Character.isDigit(s.charAt(j))) {
                j++;
            }
            int num = Integer.parseInt(s.substring(i, j));
            char sep = j < s.length() ? s.charAt(j) : '}';
            if (sep == '}') { // ${1}
                emitStop(num, null, out, ranges, firstText);
                pos[0] = j + 1;
                return true;
            }
            if (sep == ':') { // ${1:default}  (default may contain nested constructs)
                pos[0] = j + 1;
                emitStopWithDefault(num, s, pos, out, ranges, firstText, choices, vars);
                if (pos[0] < s.length() && s.charAt(pos[0]) == '}') {
                    pos[0]++;
                }
                return true;
            }
            if (sep == '|') { // ${1|a,b,c|}  -> capture options; first is the default text
                int close = s.indexOf("|}", j + 1);
                String options = close < 0 ? s.substring(j + 1) : s.substring(j + 1, close);
                List<String> opts = splitChoices(options);
                choices.put(num, opts);
                emitStop(num, opts.isEmpty() ? "" : opts.get(0), out, ranges, firstText);
                pos[0] = close < 0 ? s.length() : close + 2;
                return true;
            }
            if (sep == '/') { // ${1/re/fmt/flags} -> ignore transform, treat as mirror
                int close = findBraceClose(s, j);
                emitStop(num, null, out, ranges, firstText);
                pos[0] = close < 0 ? s.length() : close + 1;
                return true;
            }
            // Unknown form: consume to closing brace and emit nothing.
            int close = findBraceClose(s, i);
            pos[0] = close < 0 ? s.length() : close + 1;
            return true;
        }
        // ${VAR} or ${VAR:default}
        if (isVarStart(s.charAt(i))) {
            int j = i;
            while (j < s.length() && isVarPart(s.charAt(j))) {
                j++;
            }
            String name = s.substring(i, j);
            String value = vars == null ? null : vars.resolve(name);
            if (j < s.length() && s.charAt(j) == ':') {
                pos[0] = j + 1;
                if (value != null) {
                    out.append(value);
                    int close = findBraceClose(s, j); // skip the default
                    pos[0] = close < 0 ? s.length() : close + 1;
                } else {
                    parseSeq(s, pos, out, ranges, firstText, choices, vars, true); // emit the default
                    if (pos[0] < s.length() && s.charAt(pos[0]) == '}') {
                        pos[0]++;
                    }
                }
                return true;
            }
            out.append(value == null ? "" : value);
            pos[0] = (j < s.length() && s.charAt(j) == '}') ? j + 1 : j;
            return true;
        }
        // Not a recognized construct: emit nothing, skip to the closing brace.
        int close = findBraceClose(s, i);
        pos[0] = close < 0 ? s.length() : close + 1;
        return true;
    }

    /** Emits a stop's primary or mirror text and records its range. {@code defaultText} is the literal
     *  default for a first plain occurrence ({@code null} = empty); later occurrences mirror the first. */
    private static void emitStop(
            int num,
            String defaultText,
            StringBuilder out,
            Map<Integer, List<int[]>> ranges,
            Map<Integer, String> firstText) {
        int start = out.length();
        if (firstText.containsKey(num)) {
            out.append(firstText.get(num)); // mirror the first occurrence
        } else {
            String t = defaultText == null ? "" : defaultText;
            out.append(t);
            firstText.put(num, t);
        }
        record(num, start, out.length(), ranges);
    }

    /** Emits a {@code ${n:default}} where the default can contain nested constructs. */
    private static void emitStopWithDefault(
            int num,
            String s,
            int[] pos,
            StringBuilder out,
            Map<Integer, List<int[]>> ranges,
            Map<Integer, String> firstText,
            Map<Integer, List<String>> choices,
            Variables vars) {
        int start = out.length();
        if (firstText.containsKey(num)) {
            // Mirror: discard this default's text (into throwaway buffers) and emit the remembered text.
            parseSeq(
                    s,
                    pos,
                    new StringBuilder(),
                    new LinkedHashMap<>(),
                    new LinkedHashMap<>(),
                    new LinkedHashMap<>(),
                    vars,
                    true);
            out.append(firstText.get(num));
        } else {
            firstText.put(num, ""); // reserve so nested same-number mirrors resolve
            parseSeq(s, pos, out, ranges, firstText, choices, vars, true);
            firstText.put(num, out.substring(start));
        }
        record(num, start, out.length(), ranges);
    }

    private static void record(int num, int start, int end, Map<Integer, List<int[]>> ranges) {
        ranges.computeIfAbsent(num, k -> new ArrayList<>()).add(new int[] {start, end});
    }

    /** Index of the '}' that closes the brace whose '{' is at-or-after {@code from}, honoring nesting
     *  and escapes; -1 if unbalanced. {@code from} points at the char after "${"-ish; we scan forward. */
    private static int findBraceClose(String s, int from) {
        int depth = 1;
        for (int k = from; k < s.length(); k++) {
            char c = s.charAt(k);
            if (c == '\\') {
                k++;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                if (--depth == 0) {
                    return k;
                }
            }
        }
        return -1;
    }

    /** Splits a choice list on commas, honoring {@code \,} (and {@code \|}, {@code \\}) escapes. */
    private static List<String> splitChoices(String s) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            if (c == '\\' && k + 1 < s.length()) {
                cur.append(s.charAt(++k));
            } else if (c == ',') {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        return out;
    }

    private static boolean isVarStart(char c) {
        return Character.isLetter(c) || c == '_';
    }

    private static boolean isVarPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }
}
