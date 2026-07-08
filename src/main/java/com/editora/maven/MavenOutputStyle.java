package com.editora.maven;

import com.editora.logviewer.LogLevel;
import com.editora.logviewer.LogPatterns;

/**
 * Pure classifier for coloring a single line of Maven console output. IntelliJ and VS Code don't invent
 * their own highlighting here — they render Maven's own ANSI-colored output (jansi, on by default since
 * Maven 3.5) directly in a terminal-aware console. Since {@code MavenService} spawns the process over a
 * plain pipe (no pty), Maven emits no ANSI escapes, so this reclassifies its plain {@code [LEVEL]}
 * prefixes instead — reusing {@link LogPatterns#levelOf(String)}, which already recognizes Maven's
 * bracketed {@code [INFO]}/{@code [WARNING]}/{@code [ERROR]} format.
 *
 * <p>Deliberately narrower than coloring every {@code [INFO]} line: real ANSI-colored Maven output
 * leaves INFO at the default terminal foreground and only calls out warnings, errors, and the build
 * result — painting the (usually dominant) INFO noise green would be both unfaithful to the real tool
 * and less readable, not more.
 */
public final class MavenOutputStyle {

    private MavenOutputStyle() {}

    /**
     * The {@code .text.<class>} CSS class to color {@code line} with, or {@code null} for no special
     * coloring (the console's default foreground — used for INFO and unrecognized lines).
     *
     * <p>{@code BUILD SUCCESS}/{@code BUILD FAILURE} are checked by suffix, not exact match: Maven always
     * prints them under a plain {@code [INFO]} prefix (e.g. {@code "[INFO] BUILD SUCCESS"}), so the level
     * alone can't tell success from failure.
     */
    public static String styleClassFor(String line) {
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
}
