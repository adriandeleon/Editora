package com.editora.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * Pure: whether a stop passes a breakpoint's {@link com.editora.config.StopFilters} — its thread's name and its caller
 * against glob patterns — and whether a filtered breakpoint's hit count says to stop at this hit, Editora counting
 * them since the adapter cannot.
 */
final class StopFilterCheck {

    private StopFilterCheck() {}

    /**
     * Whether {@code patterns} (comma-separated globs, {@code *} and {@code ?}, case ignored; {@code !} before one
     * excludes) accept any of {@code names}: one of them matches a pattern that includes (or none includes), and none
     * matches one that excludes. Blank patterns accept everything.
     */
    static boolean matches(String patterns, List<String> names) {
        if (patterns == null || patterns.isBlank()) {
            return true;
        }
        boolean anyInclude = false;
        boolean included = false;
        for (String raw : patterns.split(",")) {
            String p = raw.strip();
            if (p.isEmpty()) {
                continue;
            }
            boolean exclude = p.startsWith("!");
            Pattern glob = glob(exclude ? p.substring(1).strip() : p);
            boolean hit = names.stream().anyMatch(n -> glob.matcher(n).matches());
            if (exclude) {
                if (hit) {
                    return false;
                }
            } else {
                anyInclude = true;
                included |= hit;
            }
        }
        return !anyInclude || included;
    }

    private static Pattern glob(String glob) {
        StringBuilder re = new StringBuilder();
        for (char c : glob.toCharArray()) {
            switch (c) {
                case '*' -> re.append(".*");
                case '?' -> re.append('.');
                default -> re.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(re.toString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.DOTALL);
    }

    /**
     * The names a caller filter is matched against for a frame named {@code frame}: as shown ({@code
     * OrderService.save(Order)}), without its arguments ({@code OrderService.save}), and its function alone ({@code
     * save}) — so {@code OrderService.save}, {@code *.save} and {@code save} all name it.
     */
    static List<String> callerNames(@Nullable String frame) {
        List<String> out = new ArrayList<>();
        if (frame == null || frame.isBlank()) {
            out.add("");
            return out;
        }
        String name = DebugPanelText.frameName(frame).strip();
        out.add(name);
        int args = name.indexOf('(', 1); // Go's main.(*T).M has its parenthesis first: not an argument list
        String bare = args > 0 && !name.substring(0, args).endsWith(".") ? name.substring(0, args) : name;
        if (!bare.equals(name)) {
            out.add(bare);
        }
        int dot = Math.max(bare.lastIndexOf('.'), bare.lastIndexOf(':')); // Java's and Go's dots, Rust's ::
        if (dot >= 0 && dot < bare.length() - 1) {
            out.add(bare.substring(dot + 1));
        }
        return out;
    }

    private static final Pattern HIT = Pattern.compile("^\\s*(==|>=|<=|>|<|%)?\\s*(\\d{1,9})\\s*$");

    /**
     * Whether a filtered breakpoint stops at its {@code count}th hit that passed its filters, by its hit count {@code
     * condition}: {@code N} or {@code == N} at the Nth, {@code >= N}, {@code > N}, {@code <= N}, {@code < N}, and
     * {@code % N} at every Nth. Blank, or a form not among these, stops at every hit.
     */
    static boolean hitMatches(@Nullable String condition, int count) {
        if (condition == null || condition.isBlank()) {
            return true;
        }
        Matcher m = HIT.matcher(condition);
        if (!m.matches()) {
            return true;
        }
        int n = Integer.parseInt(m.group(2));
        String op = m.group(1) == null ? "==" : m.group(1);
        return switch (op) {
            case ">=" -> count >= n;
            case ">" -> count > n;
            case "<=" -> count <= n;
            case "<" -> count < n;
            case "%" -> n > 0 && count % n == 0;
            default -> count == n;
        };
    }

    /** What the value whose method stopped is called in {@code language}: {@code self} or {@code this}. */
    static String selfName(@Nullable String language) {
        return switch (language == null ? "" : language.toLowerCase(Locale.ROOT)) {
            case "python", "ruby", "rust" -> "self";
            case "php" -> "$this";
            default -> "this";
        };
    }
}
