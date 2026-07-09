package com.editora.run;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Pure, unit-tested extractor of runnable targets from a GNU Makefile. Each {@link Target} is a real rule
 * target (its 0-based definition line + name) that {@code make <name>} can build; the editor draws a Run
 * glyph on that line and clicking it runs that target.
 *
 * <p>Deliberately heuristic (no full make grammar): it skips recipe lines (leading tab), comments/blanks,
 * variable assignments ({@code :=} / {@code ::=} / {@code ?=} / {@code =} / {@code +=} / {@code !=}), make
 * directives ({@code include} / {@code ifeq} / {@code define} / ...), pattern rules ({@code %}), special
 * dot targets ({@code .PHONY} / {@code .SUFFIXES} / ...), and target names carrying a variable reference
 * ({@code $}). A target defined on several lines (or a double-colon rule) yields its first line only.
 */
public final class MakefileTargets {

    private MakefileTargets() {}

    /** A runnable make target: its name and 0-based definition line. */
    public record Target(String name, int line) {}

    /** Make control keywords that can precede a colon but never name a runnable target. */
    private static final Set<String> DIRECTIVES = Set.of(
            "include",
            "-include",
            "sinclude",
            "ifeq",
            "ifneq",
            "ifdef",
            "ifndef",
            "else",
            "endif",
            "define",
            "endef",
            "export",
            "unexport",
            "override",
            "vpath",
            "undefine",
            "load");

    /** Parses {@code text} into the ordered, de-duplicated list of runnable targets (first definition wins). */
    public static List<Target> parse(String text) {
        List<Target> out = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return out;
        }
        Set<String> seen = new HashSet<>();
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            for (String name : targetsOn(lines[i])) {
                if (seen.add(name)) {
                    out.add(new Target(name, i));
                }
            }
        }
        return out;
    }

    /** The runnable target names declared on one physical line (empty for a non-rule / non-runnable line). */
    static List<String> targetsOn(String rawLine) {
        String line = stripTrailingCr(rawLine);
        if (line.isEmpty() || line.charAt(0) == '\t' || line.charAt(0) == '#') {
            return List.of(); // recipe / blank / comment
        }
        int colon = indexOfRuleColon(line);
        if (colon < 0) {
            return List.of(); // no rule colon — an assignment, directive, comment, or blank
        }
        String head = line.substring(0, colon).strip();
        if (head.isEmpty()) {
            return List.of();
        }
        String[] tokens = head.split("\\s+");
        if (DIRECTIVES.contains(tokens[0])) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (String token : tokens) {
            if (isRunnableTarget(token)) {
                names.add(token);
            }
        }
        return names;
    }

    /**
     * The index of the colon that makes {@code line} a rule, or -1 when the line is an assignment / has no
     * rule colon. An {@code =} occurring before any colon means the line is a variable assignment; a colon
     * run immediately followed by {@code =} ({@code :=} / {@code ::=}) is likewise an assignment.
     */
    static int indexOfRuleColon(String line) {
        for (int j = 0; j < line.length(); j++) {
            char c = line.charAt(j);
            if (c == '#') {
                return -1; // comment starts before any rule colon
            }
            if (c == '=') {
                return -1; // `=` before a colon ⇒ variable assignment (incl. `?=` / `+=` / `!=`)
            }
            if (c == ':') {
                int k = j;
                while (k < line.length() && line.charAt(k) == ':') {
                    k++;
                }
                if (k < line.length() && line.charAt(k) == '=') {
                    return -1; // `:=` / `::=` / `:::=` — an assignment, not a rule
                }
                return j; // a `:` or `::` rule colon
            }
        }
        return -1;
    }

    /** A token names a runnable target when it isn't a special dot target, a pattern rule, or a variable ref. */
    static boolean isRunnableTarget(String token) {
        return !token.isEmpty() && token.charAt(0) != '.' && token.indexOf('%') < 0 && token.indexOf('$') < 0;
    }

    private static String stripTrailingCr(String s) {
        return (!s.isEmpty() && s.charAt(s.length() - 1) == '\r') ? s.substring(0, s.length() - 1) : s;
    }
}
