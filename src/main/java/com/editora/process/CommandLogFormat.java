package com.editora.process;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Renders a {@link CommandLog.Entry} as console lines: the echoed command, its output, and an exit footer.
 * Pure and toolkit-free — the panel only appends what this returns, so the whole format is unit-testable.
 *
 * <p>Two judgement calls worth keeping:
 *
 * <ul>
 *   <li><b>stderr is only reddened when the command failed.</b> {@code git push}/{@code clone} write their
 *       normal progress to stderr, so colouring by stream would paint a successful push alarming red — the
 *       exit code, not the stream, is what says something went wrong.
 *   <li><b>Truncation keeps the head.</b> Entries are bounded at {@link #MAX_OUTPUT_LINES} and the drop is
 *       stated in the transcript rather than silently applied — a console that hides output without saying
 *       so is worse than one that shows less.
 * </ul>
 */
public final class CommandLogFormat {

    private CommandLogFormat() {}

    /** Beyond this many output lines an entry is truncated (with a line saying how many were dropped). */
    public static final int MAX_OUTPUT_LINES = 200;

    /** The echoed command line. */
    public static final String ECHO_STYLE = "cmd-echo";

    /** The trailing {@code exit N · 120 ms} line of a successful command. */
    public static final String FOOTER_STYLE = "cmd-footer";

    /** A failed command's stderr and footer (shared with the build consoles' error colour). */
    public static final String ERROR_STYLE = "log-error";

    /** One console line plus the {@code .text.<class>} style to paint it with ({@code null} = default). */
    public record Line(String text, String styleClass) {}

    /** The full transcript entry: {@code $ <command>}, the output, then the exit footer. */
    public static List<Line> format(CommandLog.Entry entry) {
        List<Line> lines = new ArrayList<>();
        boolean failed = entry.exitCode() != 0;
        lines.add(new Line(commandLine(entry.argv()), ECHO_STYLE));

        List<String> out = splitLines(entry.out());
        List<String> err = splitLines(entry.err());
        int budget = MAX_OUTPUT_LINES;
        for (String l : out) {
            if (budget-- <= 0) {
                break;
            }
            lines.add(new Line(l, null));
        }
        for (String l : err) {
            if (budget-- <= 0) {
                break;
            }
            lines.add(new Line(l, failed ? ERROR_STYLE : null));
        }
        int dropped = out.size() + err.size() - MAX_OUTPUT_LINES;
        if (dropped > 0) {
            lines.add(new Line("… " + dropped + " more line" + (dropped == 1 ? "" : "s"), FOOTER_STYLE));
        }

        lines.add(new Line(footer(entry.exitCode(), entry.millis()), failed ? ERROR_STYLE : FOOTER_STYLE));
        return lines;
    }

    /**
     * {@code $ git commit -m "fix: a thing"} — arguments carrying whitespace or a quote are double-quoted so
     * the echoed line reads as the shell command it stands for rather than an ambiguous token soup.
     */
    public static String commandLine(List<String> argv) {
        StringBuilder sb = new StringBuilder("$");
        for (String a : argv == null ? List.<String>of() : argv) {
            sb.append(' ').append(quote(a));
        }
        return sb.toString();
    }

    /** {@code exit 0 · 120 ms}. */
    public static String footer(int exitCode, long millis) {
        return "exit " + exitCode + " · " + duration(millis);
    }

    /** Sub-second durations in ms, longer ones in seconds to one decimal. */
    public static String duration(long millis) {
        if (millis < 1000) {
            return millis + " ms";
        }
        return String.format(Locale.ROOT, "%.1f s", millis / 1000.0);
    }

    private static String quote(String arg) {
        if (arg == null || arg.isEmpty()) {
            return "\"\"";
        }
        boolean needs = false;
        for (int i = 0; i < arg.length(); i++) {
            char c = arg.charAt(i);
            if (Character.isWhitespace(c) || c == '"') {
                needs = true;
                break;
            }
        }
        return needs ? '"' + arg.replace("\"", "\\\"") + '"' : arg;
    }

    /** Splits captured output into lines, dropping a single trailing newline and any trailing {@code \r}. */
    private static List<String> splitLines(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        for (String l : text.split("\n", -1)) {
            lines.add(l.endsWith("\r") ? l.substring(0, l.length() - 1) : l);
        }
        // split(-1) keeps the empty tail a trailing newline produces; it isn't a line of output.
        if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        return lines;
    }
}
