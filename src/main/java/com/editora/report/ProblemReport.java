package com.editora.report;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.editora.AppInfo;
import org.jspecify.annotations.Nullable;

/**
 * A problem report: the system details and the latest two session logs, redacted, as one plain-text file to
 * attach to a GitHub issue — plus the link that opens that issue with the details filled in.
 *
 * <p>Nothing here sends anything. The command ({@code help.reportProblem}) shows the text for review first, and
 * the command-line flag ({@code --report}) writes a file; either way the user submits the issue themselves.
 * Pure apart from {@link #readLog}, which reads a file.
 */
public final class ProblemReport {

    /** The issue form in {@code .github/ISSUE_TEMPLATE} whose fields {@link #issueUrl} fills. */
    static final String ISSUE_TEMPLATE = "bug_report.yml";

    /**
     * Longest issue link we build. GitHub turns away a request line much past 8 KB, so a report that would push
     * the link over this goes to the clipboard and a file instead, and the issue's field says to paste it.
     */
    public static final int MAX_URL_LENGTH = 7500;

    /** Only the end of a long log is kept: that is where a failure is, and a report must stay attachable. */
    static final int MAX_LOG_CHARS = 200_000;

    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /** One log in the report: its heading and its text ({@code null} when the file was not there). */
    public record Log(String heading, @Nullable String text) {}

    private ProblemReport() {}

    /**
     * The system block: version, Java, JavaFX, OS, locale, how Editora was installed, and its plugins. Each
     * line is {@code Label: value}, the same shape as About → Copy details.
     *
     * @param plugins one entry per plugin ({@code "id 1.2.0"}, {@code "id 1.2.0 (disabled)"}); empty for none
     */
    public static String systemDetails(List<String> plugins) {
        List<String> lines = new ArrayList<>();
        lines.add(AppInfo.versionLine());
        lines.add("Java: " + System.getProperty("java.version", "?") + " (" + System.getProperty("java.vendor", "?")
                + ")");
        String fx = System.getProperty("javafx.runtime.version");
        if (fx != null && !fx.isBlank()) {
            lines.add("JavaFX: " + fx);
        }
        lines.add("OS: " + System.getProperty("os.name", "?") + " " + System.getProperty("os.version", "?") + " ("
                + System.getProperty("os.arch", "?") + ")");
        lines.add("Locale: " + Locale.getDefault().toLanguageTag());
        lines.add("Install: " + installKind(System.getProperty("jpackage.app-path"), AppInfo.isSnapshot()));
        lines.add("Plugins: " + (plugins.isEmpty() ? "none" : String.join(", ", plugins)));
        return String.join("\n", lines);
    }

    /** How this copy runs: an installed app (jpackage sets the property), a development build, or a jar. */
    static String installKind(@Nullable String jpackageAppPath, boolean snapshot) {
        if (jpackageAppPath != null && !jpackageAppPath.isBlank()) {
            return "installed app";
        }
        return snapshot ? "development build" : "jar";
    }

    /** A report ready to show: the redacted system block, the redacted full text, and how many values went. */
    public record Assembled(String systemDetails, String text, int redacted) {}

    /**
     * The report over two session logs — {@code latestLog} ({@code fallbackLog}, the in-memory capture, when that
     * file cannot be read) and {@code previousLog}, the session before it — redacted by {@code redactor}.
     */
    public static Assembled assemble(
            Path latestLog, Path previousLog, List<String> plugins, @Nullable String fallbackLog, Redactor redactor) {
        String latest = readLog(latestLog);
        List<Log> logs = List.of(
                new Log(latestLog.getFileName() + " (the latest session)", latest != null ? latest : fallbackLog),
                new Log(previousLog.getFileName() + " (the session before it)", readLog(previousLog)));
        Redactor.Result details = redactor.redact(systemDetails(plugins));
        Redactor.Result body = redactor.redact(logSections(logs));
        return new Assembled(
                details.text(), header(details.text()) + body.text(), details.replaced() + body.replaced());
    }

    /** The whole report: a title, the system block, then each log under its heading. */
    static String render(String systemDetails, List<Log> logs) {
        return header(systemDetails) + logSections(logs);
    }

    private static String header(String systemDetails) {
        return "Editora problem report\n======================\n\n" + systemDetails.strip() + "\n";
    }

    private static String logSections(List<Log> logs) {
        StringBuilder sb = new StringBuilder();
        for (Log log : logs) {
            sb.append("\n--- ").append(log.heading()).append(" ---\n");
            String text = log.text();
            if (text == null) {
                sb.append("(not found)\n");
            } else if (text.isBlank()) {
                sb.append("(empty)\n");
            } else {
                sb.append(text.stripTrailing()).append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * The log file's text, its last {@link #MAX_LOG_CHARS} characters when longer (cut at a line start, with a
     * note saying so); {@code null} when the file is missing or unreadable.
     */
    public static @Nullable String readLog(@Nullable Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        try {
            return tail(Files.readString(file, StandardCharsets.UTF_8), MAX_LOG_CHARS);
        } catch (IOException | RuntimeException e) {
            return null; // a malformed byte or a locked file: report without it rather than fail
        }
    }

    static String tail(String text, int max) {
        if (text.length() <= max) {
            return text;
        }
        int from = text.length() - max;
        int nextLine = text.indexOf('\n', from);
        if (nextLine >= 0 && nextLine < text.length() - 1) {
            from = nextLine + 1;
        }
        return "(… " + from + " earlier characters left out …)\n" + text.substring(from);
    }

    /** {@code editora-report-20261026-153012.txt} — sorts by time, and says what it is. */
    public static String fileName(LocalDateTime now) {
        return "editora-report-" + FILE_STAMP.format(now) + ".txt";
    }

    /**
     * The new-issue link for {@code repo}, with the issue form's environment field set to {@code systemDetails}
     * and its report field set to {@code report} when the link stays under {@link #MAX_URL_LENGTH}; otherwise to
     * {@code pasteHint}, which tells the user where the full report is.
     */
    public static String issueUrl(String repo, String systemDetails, String report, String pasteHint) {
        String base = baseIssueUrl(repo, systemDetails);
        return fitsInIssueUrl(repo, systemDetails, report)
                ? base + "&report=" + encode(report)
                : base + "&report=" + encode(pasteHint);
    }

    /** Whether {@link #issueUrl} can carry {@code report} itself (else the user pastes it from the clipboard). */
    public static boolean fitsInIssueUrl(String repo, String systemDetails, String report) {
        return baseIssueUrl(repo, systemDetails).length()
                        + "&report=".length()
                        + encode(report).length()
                <= MAX_URL_LENGTH;
    }

    private static String baseIssueUrl(String repo, String systemDetails) {
        return "https://github.com/" + repo + "/issues/new?template=" + ISSUE_TEMPLATE + "&environment="
                + encode(systemDetails.strip());
    }

    /** Query-string encoding with {@code %20} for a space — a {@code +} is left as-is in some form fields. */
    static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
