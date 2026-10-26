package com.editora.report;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import com.editora.AppInfo;
import com.editora.plugin.PluginInstaller;
import com.editora.process.DesktopActions;
import org.jspecify.annotations.Nullable;

/**
 * {@code editora --report}: writes a redacted problem report to a file in the current folder, then opens a new
 * GitHub issue with it filled in and shows the file — for when something went wrong and, above all, when Editora
 * will not start far enough to use Help → Report a Problem. Sends nothing itself: the user reads the issue and
 * submits it.
 */
public final class ReportCommand {

    private ReportCommand() {}

    /**
     * Writes the report for {@code configDir}'s session logs into {@code outDir} (the temp folder when that is
     * not writable), prints where, and — when {@code open} — opens the issue page and the file's folder.
     * Returns the process exit status.
     */
    public static int run(
            Path configDir, Path latestLog, Path previousLog, Path outDir, boolean open, PrintStream out) {
        ProblemReport.Assembled report = ProblemReport.assemble(
                latestLog, previousLog, pluginFolders(configDir.resolve("plugins")), null, Redactor.forThisMachine());
        Path file = write(report.text(), outDir, ProblemReport.fileName(LocalDateTime.now()));
        if (file == null) {
            out.println("Could not write the problem report to " + outDir + " or the temp folder.");
            return 1;
        }
        String url = ProblemReport.issueUrl(
                AppInfo.GITHUB_REPO,
                report.systemDetails(),
                report.text(),
                "Attach " + file.getFileName() + " here (drag it into this box), or paste its contents.");
        boolean inline = ProblemReport.fitsInIssueUrl(AppInfo.GITHUB_REPO, report.systemDetails(), report.text());
        out.println("Wrote the problem report to " + file);
        out.println(redactedLine(report.redacted()));
        out.println();
        out.println("Read it before you share it. Then open a new issue"
                + (inline ? " (the report is already filled in)" : " and attach the file") + ":");
        out.println("  " + url);
        if (open) {
            DesktopActions.openUrlNow(url);
            DesktopActions.revealNow(file);
        }
        return 0;
    }

    static String redactedLine(int redacted) {
        return redacted == 0
                ? "Nothing in it looked private, but check it for anything you would rather not post."
                : redacted + (redacted == 1 ? " value" : " values")
                        + " (paths in your home folder, e-mail addresses, tokens) were replaced; check the rest.";
    }

    /** Writes {@code text} to {@code dir/name}, else to the temp folder; the file written, or null. */
    static @Nullable Path write(String text, Path dir, String name) {
        for (Path candidate : List.of(dir, Path.of(System.getProperty("java.io.tmpdir", ".")))) {
            try {
                return Files.writeString(candidate.toAbsolutePath().resolve(name), text);
            } catch (IOException | RuntimeException e) {
                // try the next folder
            }
        }
        return null;
    }

    /** The installed plugins, by folder name: those holding a {@code plugin.json} that are not tool folders. */
    static List<String> pluginFolders(Path pluginsDir) {
        List<String> names = new ArrayList<>();
        if (!Files.isDirectory(pluginsDir)) {
            return names;
        }
        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(pluginsDir, Files::isDirectory)) {
            for (Path dir : dirs) {
                String name = String.valueOf(dir.getFileName());
                if (!PluginInstaller.isReservedId(name) && Files.isRegularFile(dir.resolve("plugin.json"))) {
                    names.add(name);
                }
            }
        } catch (IOException | RuntimeException e) {
            return names;
        }
        names.sort(String::compareTo);
        return names;
    }
}
