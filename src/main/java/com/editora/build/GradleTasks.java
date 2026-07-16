package com.editora.build;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the task names out of {@code gradle tasks --all} output. Gradle's build files are a Groovy/Kotlin DSL
 * that can't be statically parsed, so the full task list is discovered on demand by running the tool.
 *
 * <p>The report prints each task flush-left, either as {@code taskName - description} or — when the task has
 * no description, which is the norm in the "Other tasks" section and for any {@code tasks.register} that
 * doesn't set one — as a <b>bare name</b>. Requiring the {@code " - description"} therefore dropped real
 * tasks silently, while the status line still reported the undercount as a success.
 *
 * <p>Section headers ({@code Build tasks}, {@code Other tasks}, {@code Rules}) are bare words too, so on their
 * own they'd be indistinguishable from a description-less task — but the report always underlines a header
 * with a {@code -----} rule, and that is what separates them here. Everything else (the {@code > Task :tasks}
 * progress lines, {@code Pattern: clean<TaskName>: …} rules, {@code BUILD SUCCESSFUL in 1s}, the trailing
 * notes) contains a space and so fails the whole-line name match. Pure.
 */
public final class GradleTasks {

    private GradleTasks() {}

    /** A task line: a flush-left task name alone, or followed by {@code " - "} and a non-blank description. */
    private static final Pattern TASK = Pattern.compile("^([A-Za-z0-9_][A-Za-z0-9_:.\\-]*)(?: - \\S.*)?$");

    /** The rule Gradle underlines every section header with (and the banner around the report title). */
    private static final Pattern SEPARATOR = Pattern.compile("^-{3,}$");

    /** The distinct task names from {@code gradle tasks} output, in the order they appear. */
    public static List<String> parse(String tasksOutput) {
        List<String> out = new ArrayList<>();
        if (tasksOutput == null || tasksOutput.isBlank()) {
            return out;
        }
        Set<String> seen = new LinkedHashSet<>();
        String[] lines = tasksOutput.split("\\r?\\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (SEPARATOR.matcher(line).matches()) {
                continue; // the rule itself
            }
            if (i + 1 < lines.length && SEPARATOR.matcher(lines[i + 1]).matches()) {
                continue; // underlined ⇒ a section header, not a task ("Rules", "Other tasks", the title)
            }
            Matcher m = TASK.matcher(line);
            if (m.matches() && seen.add(m.group(1))) {
                out.add(m.group(1));
            }
        }
        return out;
    }
}
