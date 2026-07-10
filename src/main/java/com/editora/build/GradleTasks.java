package com.editora.build;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the task names out of {@code gradle tasks --all} output. Gradle's build files are a Groovy/Kotlin DSL
 * that can't be statically parsed, so the full task list is discovered on demand by running the tool; each
 * task is printed flush-left as {@code taskName - description} (subproject tasks as {@code sub:taskName - …})
 * under group headers. We keep the names (deduped, in output order) and ignore everything else — headers,
 * blank lines, {@code > Task :tasks}, the trailing "BUILD SUCCESSFUL" / "run gradle tasks" notes (none match
 * the {@code name - description} shape). Pure.
 */
public final class GradleTasks {

    private GradleTasks() {}

    /** A task line: a flush-left name token, then " - ", then a non-blank description. */
    private static final Pattern TASK = Pattern.compile("^(\\S+) - \\S");

    /** The distinct task names from {@code gradle tasks} output, in the order they appear. */
    public static List<String> parse(String tasksOutput) {
        List<String> out = new ArrayList<>();
        if (tasksOutput == null || tasksOutput.isBlank()) {
            return out;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String line : tasksOutput.split("\\r?\\n", -1)) {
            Matcher m = TASK.matcher(line);
            if (m.find() && seen.add(m.group(1))) {
                out.add(m.group(1));
            }
        }
        return out;
    }
}
