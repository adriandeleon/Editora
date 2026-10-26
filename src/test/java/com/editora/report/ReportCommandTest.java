package com.editora.report;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code editora --report}: the file it writes and what it prints. */
class ReportCommandTest {

    @Test
    void writesTheReportToTheFolderAndPrintsItsPathAndTheIssueLink(@TempDir Path dir) throws Exception {
        Path config = Files.createDirectories(dir.resolve("config"));
        Path out = Files.createDirectories(dir.resolve("out"));
        Files.writeString(config.resolve("editora-session.log"), "latest session line\n");
        Files.writeString(config.resolve("editora-session.prev.log"), "SEVERE crash\n");
        ByteArrayOutputStream printed = new ByteArrayOutputStream();

        int status = ReportCommand.run(
                config,
                config.resolve("editora-session.log"),
                config.resolve("editora-session.prev.log"),
                out,
                false,
                new PrintStream(printed, true, StandardCharsets.UTF_8));

        assertEquals(0, status);
        List<Path> written;
        try (Stream<Path> files = Files.list(out)) {
            written = files.toList();
        }
        assertEquals(1, written.size());
        Path report = written.get(0);
        assertTrue(report.getFileName().toString().matches("editora-report-\\d{8}-\\d{6}\\.txt"), report.toString());
        String text = Files.readString(report);
        assertTrue(text.contains("latest session line"));
        assertTrue(text.contains("SEVERE crash"));
        String console = printed.toString(StandardCharsets.UTF_8);
        assertTrue(console.contains("Wrote the problem report to " + report), console);
        assertTrue(console.contains("https://github.com/adriandeleon/Editora/issues/new?template=bug_report.yml"));
        assertTrue(console.contains("(the report is already filled in)"), "a short report fits in the link");
    }

    @Test
    void anUnwritableFolderFallsBackToTheTempFolder(@TempDir Path dir) throws Exception {
        Path notAFolder = Files.writeString(dir.resolve("file"), "x");
        Path written = ReportCommand.write("report", notAFolder, "editora-report-test-" + System.nanoTime() + ".txt");
        try {
            assertEquals(
                    Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath(),
                    written.getParent().toAbsolutePath());
            assertEquals("report", Files.readString(written));
        } finally {
            Files.deleteIfExists(written);
        }
    }

    @Test
    void pluginsAreTheFoldersWithAManifestThatAreNotToolFolders(@TempDir Path plugins) throws Exception {
        Files.writeString(Files.createDirectories(plugins.resolve("zeta")).resolve("plugin.json"), "{}");
        Files.writeString(Files.createDirectories(plugins.resolve("alpha")).resolve("plugin.json"), "{}");
        Files.createDirectories(plugins.resolve("no-manifest"));
        Files.writeString(Files.createDirectories(plugins.resolve("lsp")).resolve("plugin.json"), "{}");

        assertEquals(List.of("alpha", "zeta"), ReportCommand.pluginFolders(plugins));
        assertEquals(List.of(), ReportCommand.pluginFolders(plugins.resolve("absent")));
    }

    @Test
    void theRedactionLineCountsWhatWasReplaced() {
        assertTrue(ReportCommand.redactedLine(0).startsWith("Nothing in it looked private"));
        assertTrue(ReportCommand.redactedLine(1).startsWith("1 value ("));
        assertTrue(ReportCommand.redactedLine(4).startsWith("4 values ("));
    }
}
