package com.editora.build;

import com.editora.logviewer.LogLevel;
import com.editora.logviewer.LogPatterns;

/**
 * Chooses the {@code .text.<class>} CSS class to color one line of a build tool's console output, or {@code
 * null} for the default foreground. Per tool: Maven reclassifies its plain {@code [INFO]}/{@code [WARNING]}/
 * {@code [ERROR]} prefixes + {@code BUILD SUCCESS}/{@code FAILURE} (Maven emits no ANSI over a plain pipe);
 * npm/Cargo/Go get {@link #passthrough()} (their output formats differ, so mis-applying Maven's rules would
 * mis-color them — honest no-op until a tool gets its own styler). Pure.
 */
@FunctionalInterface
public interface OutputStyle {

    /** The CSS class to color {@code line}, or {@code null} for no special coloring. */
    String styleClassFor(String line);

    /** No per-line coloring (default console foreground). */
    static OutputStyle passthrough() {
        return line -> null;
    }

    /** Generic process/log output: color a leading severity token without assuming a build-tool format. */
    static OutputStyle console() {
        return line -> {
            LogLevel level = LogPatterns.levelOf(line);
            if (level == null) {
                return null;
            }
            return switch (level) {
                case ERROR, FATAL -> "log-error";
                case WARN -> "log-warn";
                case INFO -> "log-info";
                case DEBUG -> "log-debug";
                case TRACE -> "log-trace";
            };
        };
    }

    /** Maven's classifier: warnings/errors + the build result get a color; plain {@code [INFO]} noise doesn't. */
    static OutputStyle maven() {
        return OutputStyle::mavenStyle;
    }

    /**
     * A GitHub Actions log classifier: an Actions workflow-command error/warning ({@code ##[error]} /
     * {@code ##[warning]}) or a level token anywhere in the line. Unlike Maven's, this searches the whole line
     * because gh prefixes every log line with {@code job<TAB>step<TAB>timestamp}.
     */
    static OutputStyle ci() {
        return OutputStyle::ciStyle;
    }

    /**
     * Git's human-readable status and unified-diff output.  This deliberately recognizes only stable Git
     * markers: command output is otherwise free-form, and coloring ordinary prose would make the transcript
     * harder to read rather than more useful.
     */
    static OutputStyle git() {
        return OutputStyle::gitStyle;
    }

    private static String ciStyle(String line) {
        if (line == null) {
            return null;
        }
        if (line.contains("##[error]")) {
            return "log-error";
        }
        if (line.contains("##[warning]")) {
            return "log-warn";
        }
        LogLevel level = LogPatterns.levelOf(line);
        if (level == null) {
            return null;
        }
        return switch (level) {
            case ERROR, FATAL -> "log-error";
            case WARN -> "log-warn";
            case DEBUG, TRACE, INFO -> null; // CI logs are mostly INFO noise — don't paint the whole console
        };
    }

    private static String mavenStyle(String line) {
        if (line == null) {
            return null;
        }
        String trimmed = line.strip();
        if (trimmed.endsWith("BUILD SUCCESS")) {
            return "maven-build-success";
        }
        if (trimmed.endsWith("BUILD FAILURE")) {
            return "maven-build-failure";
        }
        LogLevel level = LogPatterns.levelOf(line);
        if (level == null) {
            return null;
        }
        return switch (level) {
            case ERROR, FATAL -> "log-error";
            case WARN -> "log-warn";
            case DEBUG -> "log-debug";
            case TRACE -> "log-trace";
            case INFO -> null;
        };
    }

    private static String gitStyle(String line) {
        if (line == null) {
            return null;
        }
        String trimmed = line.stripLeading();
        if (trimmed.startsWith("diff --git ")
                || trimmed.startsWith("index ")
                || trimmed.startsWith("--- ")
                || trimmed.startsWith("+++ ")) {
            return "diff-header";
        }
        if (trimmed.startsWith("@@")) {
            return "diff-range";
        }
        if (trimmed.matches(".+\\s+\\|\\s+(?:\\d+(?:\\s+[+\\-=]*)?|Bin\\b.*)")) {
            return "git-output-stat";
        }
        if (trimmed.startsWith("+ ") && trimmed.endsWith("(forced update)")) {
            return "git-output-forced";
        }
        if (trimmed.startsWith("* [new branch]") || trimmed.startsWith("* [new tag]")) {
            return "git-output-added";
        }
        if (trimmed.startsWith("+")) {
            return "diff-inserted";
        }
        if (trimmed.startsWith("-")) {
            return "diff-deleted";
        }
        if (trimmed.matches("(?:modified|typechange):\\s+.+")) {
            return "git-output-modified";
        }
        if (trimmed.matches("new file:\\s+.+") || trimmed.matches("create mode \\d+ .+")) {
            return "git-output-added";
        }
        if (trimmed.matches("deleted:\\s+.+") || trimmed.matches("delete mode \\d+ .+")) {
            return "git-output-deleted";
        }
        if (trimmed.matches("renamed(?: from| to)?:\\s+.+")) {
            return "git-output-renamed";
        }
        return console().styleClassFor(line);
    }
}
