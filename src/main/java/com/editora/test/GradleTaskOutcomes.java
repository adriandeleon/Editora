package com.editora.test;

import java.nio.file.Path;
import java.util.Collection;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads Gradle's plain-console task lines ({@code > Task :app:test UP-TO-DATE}) so a test run can tell which
 * test tasks Gradle <em>reused</em> rather than ran — the only tasks whose reports on disk stand for this
 * run. Pure.
 */
public final class GradleTaskOutcomes {

    private static final Pattern TASK = Pattern.compile("^> Task (:\\S+)(?:\\s+(\\S+))?\\s*$");

    private GradleTaskOutcomes() {}

    /** Whether {@code line} is a task line at all (Gradle prints none under {@code -q}). */
    public static boolean isTaskLine(String line) {
        return line != null && TASK.matcher(line.strip()).matches();
    }

    /** The path of a task Gradle skipped because its previous result still holds, else {@code null}. */
    public static String reusedTask(String line) {
        if (line == null) {
            return null;
        }
        Matcher m = TASK.matcher(line.strip());
        if (!m.matches() || m.group(2) == null) {
            return null;
        }
        return switch (m.group(2)) {
            case "UP-TO-DATE", "FROM-CACHE" -> m.group(1);
            default -> null;
        };
    }

    /**
     * Whether {@code report} is in the result directory of one of {@code taskPaths}: {@code :app:test} writes
     * {@code app/build/test-results/test/}, the root project's {@code :test} writes
     * {@code build/test-results/test/} (Gradle's default layout).
     */
    public static boolean reportBelongsTo(Path root, Path report, Collection<String> taskPaths) {
        Path absoluteRoot = root.toAbsolutePath().normalize();
        Path absoluteReport = report.toAbsolutePath().normalize();
        for (String task : taskPaths) {
            String[] parts = task.substring(1).split(":");
            Path dir = absoluteRoot;
            for (int i = 0; i < parts.length - 1; i++) {
                dir = dir.resolve(parts[i]);
            }
            dir = dir.resolve("build").resolve("test-results").resolve(parts[parts.length - 1]);
            if (absoluteReport.startsWith(dir)) {
                return true;
            }
        }
        return false;
    }
}
