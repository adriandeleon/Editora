package com.editora.snippet;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Parses a TextMate/VS Code snippet body into a {@link ParsedSnippet}: the literal text to insert plus
 * the tab stops and their offset ranges. Pure (no toolkit), so it is unit-tested.
 *
 * <p>Supported syntax:
 * <ul>
 *   <li>tab stops {@code $1 $2 … $0} (0 = final caret);</li>
 *   <li>placeholders {@code ${1:default}} (the default may itself contain nested stops/variables);</li>
 *   <li>mirrors — a number repeated reuses the first occurrence's value and tracks it live;</li>
 *   <li>choices {@code ${1|a,b,c|}} — the first option is the value (a dropdown at edit time);</li>
 *   <li>variables {@code $VAR} / {@code ${VAR:default}} resolved by the supplied resolver;</li>
 *   <li>regex transforms {@code ${1/re/fmt/flags}} — each occurrence's text is derived from the stop's
 *       value by {@link SnippetTransform};</li>
 *   <li>escapes {@code \$ \} \\}.</li>
 * </ul>
 *
 * <h2>Two passes</h2>
 *
 * <p>Emission is a second pass over a first pass that resolves each stop's <em>value</em>. A single
 * forward pass cannot be correct, because an occurrence may need a value defined later in the body: in
 * {@code ${1/(.*)/$1Item/} in ${1:collection}} the transform occurrence is emitted before the
 * placeholder that gives stop&nbsp;1 its value. It was previously the source of two defects — the
 * transform was discarded (rendered as a plain mirror), and, worse, that leading non-value occurrence
 * <em>claimed the value slot</em> so the {@code collection} default was dropped too, yielding an empty
 * mirror (#642).
 *
 * <p>The value of a stop is the rendered text of its first <em>value-defining</em> occurrence — a
 * {@code ${n:default}} or a {@code ${n|choice|}}. Plain mirrors ({@code $n}, {@code ${n}}) and transform
 * occurrences never define a value; they display it. Variables are resolved through a per-parse memoizing
 * wrapper so a side-effecting variable ({@code $RANDOM}, a timestamp) yields the same text in both passes
 * and across a stop's occurrences.
 */
public final class SnippetParser {

    private SnippetParser() {}

    /** Resolves a snippet variable name to its value, or {@code null} when unknown. */
    @FunctionalInterface
    public interface Variables {
        String resolve(String name);
    }

    public static ParsedSnippet parse(String body, Variables variables) {
        String s = body == null ? "" : body;
        Variables memo = memoize(variables);

        // Pass 1: resolve each stop's value (rendered into a throwaway buffer).
        Ctx pass1 = new Ctx(s, memo, true, new HashMap<>());
        parseSeq(pass1, false);

        // Pass 2: emit for real, seeded with the resolved values.
        Ctx c = new Ctx(s, memo, false, pass1.values);
        parseSeq(c, false);

        List<TabStop> stops = new ArrayList<>();
        c.ranges.keySet().stream()
                .sorted((a, b) -> Integer.compare(a == 0 ? Integer.MAX_VALUE : a, b == 0 ? Integer.MAX_VALUE : b))
                .forEach(n -> {
                    List<int[]> rs = c.ranges.get(n);
                    List<SnippetTransform> ts = c.transforms.get(n);
                    int primary = c.primaryIndex.getOrDefault(n, firstNonTransform(ts));
                    stops.add(new TabStop(
                            n, rs, c.values.getOrDefault(n, ""), c.choices.getOrDefault(n, List.of()), ts, primary));
                });
        return new ParsedSnippet(c.out.toString(), stops);
    }

    /** Everything one pass of the walk carries. Pass 1 renders into a throwaway {@code out} and only cares
     *  about {@link #values}; pass 2 records ranges/transforms/choices. */
    private static final class Ctx {
        final String s;
        final Variables vars;
        final boolean pass1;
        final Map<Integer, String> values; // stop number → its resolved value (shared pass1 → pass2)
        StringBuilder out = new StringBuilder();
        final Map<Integer, List<int[]>> ranges = new LinkedHashMap<>();
        final Map<Integer, List<SnippetTransform>> transforms = new LinkedHashMap<>();
        final Map<Integer, List<String>> choices = new LinkedHashMap<>();
        final Map<Integer, Integer> primaryIndex = new HashMap<>();
        final Set<Integer> definerSeen = new HashSet<>(); // stops whose value-defining occurrence was handled
        int pos;

        Ctx(String s, Variables vars, boolean pass1, Map<Integer, String> values) {
            this.s = s;
            this.vars = vars;
            this.pass1 = pass1;
            this.values = values;
        }

        String value(int num) {
            return values.getOrDefault(num, "");
        }
    }

    /** Resolves each variable name at most once, so a side-effecting resolver is stable across passes. */
    private static Variables memoize(Variables inner) {
        Map<String, String> cache = new HashMap<>();
        return name -> {
            if (cache.containsKey(name)) {
                return cache.get(name);
            }
            String v = inner == null ? null : inner.resolve(name);
            cache.put(name, v);
            return v;
        };
    }

    /** Scans from {@code c.pos}, appending literal text to {@code c.out}, until end or (when
     *  {@code stopAtBrace}) an unescaped '}' (left unconsumed). */
    private static void parseSeq(Ctx c, boolean stopAtBrace) {
        String s = c.s;
        while (c.pos < s.length()) {
            char ch = s.charAt(c.pos);
            if (ch == '\\' && c.pos + 1 < s.length()) {
                char n = s.charAt(c.pos + 1);
                if (n == '$' || n == '}' || n == '\\') {
                    c.out.append(n);
                    c.pos += 2;
                    continue;
                }
                c.out.append(ch);
                c.pos++;
                continue;
            }
            if (stopAtBrace && ch == '}') {
                return;
            }
            if (ch == '$' && tryDollar(c)) {
                continue;
            }
            c.out.append(ch);
            c.pos++;
        }
    }

    /** Tries to consume a {@code $…} construct at {@code c.pos}; returns false (consuming nothing) if the
     *  '$' is just a literal. */
    private static boolean tryDollar(Ctx c) {
        String s = c.s;
        int n = c.pos + 1;
        if (n >= s.length()) {
            return false;
        }
        char ch = s.charAt(n);
        if (Character.isDigit(ch)) {
            int j = n;
            while (j < s.length() && Character.isDigit(s.charAt(j))) {
                j++;
            }
            Integer num = parseStopNumber(s, n, j);
            if (num == null) {
                return false; // an over-long number (e.g. a literal "$12345678901") → treat "$…" as text
            }
            emitOccurrence(c, num, null); // bare $1 → a plain mirror of the value
            c.pos = j;
            return true;
        }
        if (ch == '{') {
            return parseBrace(c);
        }
        if (isVarStart(ch)) {
            int j = n;
            while (j < s.length() && isVarPart(s.charAt(j))) {
                j++;
            }
            String val = c.vars.resolve(s.substring(n, j));
            c.out.append(val == null ? "" : val);
            c.pos = j;
            return true;
        }
        return false;
    }

    /** A tab-stop number {@code s[from,to)}, or {@code null} when the digit run overflows an int (so the
     *  caller can treat the {@code $…} as literal text rather than throwing). */
    private static Integer parseStopNumber(String s, int from, int to) {
        try {
            return Integer.parseInt(s.substring(from, to));
        } catch (NumberFormatException overflow) {
            return null;
        }
    }

    /** Parses a {@code ${…}} construct beginning at {@code c.pos} (the '$'). */
    private static boolean parseBrace(Ctx c) {
        String s = c.s;
        int i = c.pos + 2; // past "${"
        if (i >= s.length()) {
            return false; // a bare "${" at end of body → treat it as literal text (don't crash)
        }
        if (Character.isDigit(s.charAt(i))) {
            int j = i;
            while (j < s.length() && Character.isDigit(s.charAt(j))) {
                j++;
            }
            Integer boxed = parseStopNumber(s, i, j);
            if (boxed == null) {
                return false; // over-long number → treat the "${…" as literal text
            }
            int num = boxed;
            char sep = j < s.length() ? s.charAt(j) : '}';
            return switch (sep) {
                case '}' -> { // ${1}
                    emitOccurrence(c, num, null);
                    c.pos = j + 1;
                    yield true;
                }
                case ':' -> { // ${1:default}
                    parsePlaceholder(c, num, j);
                    yield true;
                }
                case '|' -> { // ${1|a,b,c|}
                    parseChoice(c, num, j);
                    yield true;
                }
                case '/' -> { // ${1/re/fmt/flags}
                    parseTransform(c, num, j);
                    yield true;
                }
                default -> { // unknown form: consume to closing brace, emit nothing
                    int close = findBraceClose(s, i);
                    c.pos = close < 0 ? s.length() : close + 1;
                    yield true;
                }
            };
        }
        // ${VAR} or ${VAR:default}
        if (isVarStart(s.charAt(i))) {
            return parseVariable(c, i);
        }
        // Not a recognized construct: emit nothing, skip to the closing brace.
        int close = findBraceClose(s, i);
        c.pos = close < 0 ? s.length() : close + 1;
        return true;
    }

    /** {@code ${n:default}} — the first such occurrence defines the value; a later one mirrors it. */
    private static void parsePlaceholder(Ctx c, int num, int colon) {
        boolean definer = c.definerSeen.add(num);
        if (definer) {
            c.pos = colon + 1; // past ':'
            int start = c.out.length();
            parseSeq(c, true); // render the default (registers nested definers + values)
            c.values.putIfAbsent(num, c.out.substring(start));
            if (!c.pass1) {
                record(c, num, start, c.out.length(), null);
                markPrimary(c, num);
            }
            if (c.pos < c.s.length() && c.s.charAt(c.pos) == '}') {
                c.pos++;
            }
        } else {
            // Mirror: skip the whole default and emit the value. Nested stops inside a repeated placeholder's
            // default are intentionally dropped (they belong to the value-defining occurrence).
            int close = findBraceClose(c.s, c.pos + 2);
            emitOccurrence(c, num, null);
            c.pos = close < 0 ? c.s.length() : close + 1;
        }
    }

    /** {@code ${n|a,b,c|}} — captures the options; the first is the value. */
    private static void parseChoice(Ctx c, int num, int bar) {
        int close = c.s.indexOf("|}", bar + 1);
        List<String> opts = splitChoices(close < 0 ? c.s.substring(bar + 1) : c.s.substring(bar + 1, close));
        boolean definer = c.definerSeen.add(num);
        if (definer) {
            c.values.putIfAbsent(num, opts.isEmpty() ? "" : opts.get(0));
            if (!c.pass1) {
                c.choices.put(num, opts);
            }
        }
        emitOccurrence(c, num, null);
        if (!c.pass1 && definer) {
            markPrimary(c, num);
        }
        c.pos = close < 0 ? c.s.length() : close + 2;
    }

    /** {@code ${n/re/fmt/flags}} — a transform occurrence, derived from the stop's value. */
    private static void parseTransform(Ctx c, int num, int slash) {
        SnippetTransform.Parsed p = SnippetTransform.parseAt(c.s, slash + 1);
        if (p == null) {
            // Malformed transform → fall back to the old behaviour: a plain mirror, so the snippet still
            // expands rather than throwing.
            int close = findBraceClose(c.s, c.pos + 2);
            emitOccurrence(c, num, null);
            c.pos = close < 0 ? c.s.length() : close + 1;
            return;
        }
        emitOccurrence(c, num, p.transform());
        c.pos = p.end();
    }

    /** {@code ${VAR}} / {@code ${VAR:default}} (unchanged behaviour; resolver is memoized). */
    private static boolean parseVariable(Ctx c, int i) {
        String s = c.s;
        int j = i;
        while (j < s.length() && isVarPart(s.charAt(j))) {
            j++;
        }
        String name = s.substring(i, j);
        String value = c.vars.resolve(name);
        if (j < s.length() && s.charAt(j) == ':') {
            c.pos = j + 1;
            if (value != null) {
                c.out.append(value);
                int close = findBraceClose(s, j); // skip the default
                c.pos = close < 0 ? s.length() : close + 1;
            } else {
                parseSeq(c, true); // emit the default
                if (c.pos < s.length() && s.charAt(c.pos) == '}') {
                    c.pos++;
                }
            }
            return true;
        }
        c.out.append(value == null ? "" : value);
        c.pos = (j < s.length() && s.charAt(j) == '}') ? j + 1 : j;
        return true;
    }

    /** Appends one leaf occurrence's text (value, transformed when {@code transform != null}) and records
     *  its range in pass 2. */
    private static void emitOccurrence(Ctx c, int num, SnippetTransform transform) {
        String value = c.value(num);
        String text = transform == null ? value : transform.apply(value);
        int start = c.out.length();
        c.out.append(text);
        if (!c.pass1) {
            record(c, num, start, c.out.length(), transform);
        }
    }

    private static void record(Ctx c, int num, int start, int end, SnippetTransform t) {
        c.ranges.computeIfAbsent(num, k -> new ArrayList<>()).add(new int[] {start, end});
        c.transforms.computeIfAbsent(num, k -> new ArrayList<>()).add(t);
    }

    /** Marks the range just recorded for {@code num} as the editable field (the value-defining one). */
    private static void markPrimary(Ctx c, int num) {
        c.primaryIndex.putIfAbsent(num, c.ranges.get(num).size() - 1);
    }

    /** First occurrence that is not a transform (the fallback editable field for a stop with no
     *  value-defining occurrence, e.g. all-bare {@code $1}); 0 if somehow all are transforms. */
    private static int firstNonTransform(List<SnippetTransform> ts) {
        for (int k = 0; k < ts.size(); k++) {
            if (ts.get(k) == null) {
                return k;
            }
        }
        return 0;
    }

    /** Index of the '}' that closes the brace whose '{' is at-or-after {@code from}, honoring nesting and
     *  escapes; -1 if unbalanced. */
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
