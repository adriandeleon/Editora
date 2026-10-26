package com.editora.report;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The problem report's text, its files and the new-issue link it opens. */
class ProblemReportTest {

    private static final String REPO = "owner/repo";

    @Test
    void theReportIsTheSystemBlockThenEachLogUnderItsHeading() {
        String text = ProblemReport.render(
                "Editora 1.0\nOS: Linux",
                List.of(
                        new ProblemReport.Log("latest", "line 1\nline 2\n\n"),
                        new ProblemReport.Log("before", ""),
                        new ProblemReport.Log("missing", null)));
        assertEquals("""
                Editora problem report
                ======================

                Editora 1.0
                OS: Linux

                --- latest ---
                line 1
                line 2

                --- before ---
                (empty)

                --- missing ---
                (not found)
                """, text);
    }

    @Test
    void theSystemDetailsNameTheVersionJavaOsAndPlugins() {
        String details = ProblemReport.systemDetails(List.of("a 1.0", "b 2.0 (disabled)"));
        assertTrue(details.startsWith("Editora "), details);
        assertTrue(details.contains("\nJava: " + System.getProperty("java.version")), details);
        assertTrue(details.contains("\nOS: " + System.getProperty("os.name")), details);
        assertTrue(details.endsWith("\nPlugins: a 1.0, b 2.0 (disabled)"), details);
        assertTrue(ProblemReport.systemDetails(List.of()).endsWith("\nPlugins: none"));
    }

    @Test
    void installKind() {
        assertEquals("installed app", ProblemReport.installKind("/opt/editora/bin/Editora", false));
        assertEquals("development build", ProblemReport.installKind(null, true));
        assertEquals("jar", ProblemReport.installKind(" ", false));
    }

    @Test
    void aLongLogKeepsItsEndFromALineStart() {
        assertEquals("short", ProblemReport.tail("short", 10));
        String tail = ProblemReport.tail("aaaa\nbbbb\ncccc\n", 8);
        assertEquals("(… 10 earlier characters left out …)\ncccc\n", tail);
    }

    @Test
    void readingALogThatIsNotThereIsNull(@TempDir Path dir) throws Exception {
        assertNull(ProblemReport.readLog(dir.resolve("absent.log")));
        assertNull(ProblemReport.readLog(null));
        assertNull(ProblemReport.readLog(dir), "a folder is not a log");
        Files.writeString(dir.resolve("a.log"), "x");
        assertEquals("x", ProblemReport.readLog(dir.resolve("a.log")));
    }

    @Test
    void assembleRedactsBothLogsAndFallsBackToTheCaptureWhenThisSessionsFileIsMissing(@TempDir Path dir)
            throws Exception {
        Path latest = dir.resolve("editora-session.log");
        Path previous = dir.resolve("editora-session.prev.log");
        Files.writeString(previous, "crash in /home/jdoe/proj/App.java\n");
        Redactor redactor = new Redactor("/home/jdoe", "jdoe");

        ProblemReport.Assembled report =
                ProblemReport.assemble(latest, previous, List.of(), "captured in memory", redactor);

        assertTrue(report.text().contains("--- editora-session.log (the latest session) ---\ncaptured in memory\n"));
        assertTrue(report.text()
                .contains("--- editora-session.prev.log (the session before it) ---\ncrash in ~/proj/App.java\n"));
        assertEquals(1, report.redacted());
        assertTrue(report.text().startsWith("Editora problem report\n"));
        assertTrue(report.text().contains(report.systemDetails()));

        Files.writeString(latest, "from the file");
        assertTrue(ProblemReport.assemble(latest, previous, List.of(), "captured", redactor)
                .text()
                .contains("(the latest session) ---\nfrom the file\n"));
    }

    @Test
    void aShortReportGoesIntoTheIssueLinkItself() {
        String url = ProblemReport.issueUrl(REPO, "Editora 1.0", "a short log & more", "paste it");
        assertTrue(url.startsWith("https://github.com/owner/repo/issues/new?template=bug_report.yml&environment="));
        assertEquals("a short log & more", queryValue(url, "report"));
        assertEquals("Editora 1.0", queryValue(url, "environment"));
        assertFalse(url.contains("+"), "spaces are %20");
        assertTrue(ProblemReport.fitsInIssueUrl(REPO, "Editora 1.0", "a short log & more"));
    }

    @Test
    void aLongReportLeavesThePasteHintInstead() {
        String big = "x".repeat(ProblemReport.MAX_URL_LENGTH);
        String url = ProblemReport.issueUrl(REPO, "Editora 1.0", big, "paste it");
        assertEquals("paste it", queryValue(url, "report"));
        assertTrue(url.length() <= ProblemReport.MAX_URL_LENGTH);
        assertFalse(ProblemReport.fitsInIssueUrl(REPO, "Editora 1.0", big));
    }

    @Test
    void theFileNameSortsByTime() {
        assertEquals(
                "editora-report-20261026-090507.txt", ProblemReport.fileName(LocalDateTime.of(2026, 10, 26, 9, 5, 7)));
    }

    private static String queryValue(String url, String name) {
        for (String pair : url.substring(url.indexOf('?') + 1).split("&")) {
            if (pair.startsWith(name + "=")) {
                return URLDecoder.decode(pair.substring(name.length() + 1), StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError("no " + name + " in " + url);
    }
}
