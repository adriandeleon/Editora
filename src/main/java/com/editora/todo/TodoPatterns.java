package com.editora.todo;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Compiles the user's {@link TodoPattern}s into ready-to-match {@link Compiled} forms and supplies the
 * built-in defaults (TODO / FIXME). Disabled, blank, or invalid-regex patterns are skipped so one bad
 * entry never breaks the rest. Pure + unit-tested.
 */
public final class TodoPatterns {

    /** Fallback highlight color (amber) when a pattern has none. */
    public static final String DEFAULT_COLOR = "#E5C07B";

    /** A pattern ready to scan with: its display name, highlight color, and compiled regex. */
    public record Compiled(String name, String color, Pattern pattern) {}

    private TodoPatterns() {}

    /** Compiles the enabled, valid patterns; skips disabled/blank/invalid entries (never throws). */
    public static List<Compiled> compile(List<TodoPattern> patterns) {
        List<Compiled> out = new ArrayList<>();
        if (patterns == null) {
            return out;
        }
        for (TodoPattern p : patterns) {
            if (p == null || !p.isEnabled()) {
                continue;
            }
            String regex = p.getPattern();
            if (regex == null || regex.isBlank()) {
                continue;
            }
            try {
                Pattern compiled = Pattern.compile(regex, p.isCaseSensitive() ? 0 : Pattern.CASE_INSENSITIVE);
                String color = (p.getColor() == null || p.getColor().isBlank()) ? DEFAULT_COLOR : p.getColor();
                out.add(new Compiled(p.getName() == null ? "" : p.getName(), color, compiled));
            } catch (PatternSyntaxException ignored) {
                // a malformed regex is silently skipped — the others still apply
            }
        }
        return out;
    }

    /**
     * The built-in default keywords — TODO, FIXME, HACK, NOTE, XXX (plus DONE, the "marked done" state) —
     * each whole-word and case-sensitive, mirroring the IntelliJ "Comment Manager" defaults. The order and
     * names are also the source of truth for {@link #DEFAULT_KEYWORDS} (the migration that grows an existing
     * user's list) and {@link #DONE_KEYWORD}.
     */
    public static List<TodoPattern> defaults() {
        List<TodoPattern> list = new ArrayList<>();
        list.add(new TodoPattern("TODO", "\\bTODO\\b", "#E5C07B", true, true)); // amber
        list.add(new TodoPattern("FIXME", "\\bFIXME\\b", "#E06C75", true, true)); // red
        list.add(new TodoPattern("HACK", "\\bHACK\\b", "#D19A66", true, true)); // orange
        list.add(new TodoPattern("NOTE", "\\bNOTE\\b", "#61AFEF", true, true)); // blue
        list.add(new TodoPattern("XXX", "\\bXXX\\b", "#C678DD", true, true)); // magenta
        list.add(new TodoPattern(DONE_KEYWORD, "\\bDONE\\b", "#98C379", true, true)); // green (done state)
        return list;
    }

    /** The keyword an item's keyword is rewritten to when it is "marked done" from the tool window. */
    public static final String DONE_KEYWORD = "DONE";
}
