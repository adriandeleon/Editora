package com.editora.format;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Format Document for Python: which formatter, how it is called, what comes back (adl's decision 4). */
class PythonFormatterTest {

    @TempDir
    Path dir;

    @BeforeAll
    static void english() {
        com.editora.i18n.Messages.init("en");
    }

    private static Optional<String> none(String name) {
        return Optional.empty();
    }

    @Test
    void theProjectsEnvironmentWinsThenPathRuffBeforeBlack() {
        Path venv = Path.of("/p/.venv");
        Set<Path> exists = Set.of(Path.of("/p/.venv/bin/black"));
        Map<String, String> path = Map.of("ruff", "/usr/bin/ruff", "black", "/usr/bin/black");
        var found = PythonFormatter.find(venv, false, exists::contains, n -> Optional.ofNullable(path.get(n)));
        assertEquals(
                new PythonFormatter.Found("black", Path.of("/p/.venv/bin/black").toString(), true),
                found.orElseThrow(),
                "the project pinned Black in its environment: that beats a Ruff on PATH");
        found = PythonFormatter.find(null, false, p -> false, n -> Optional.ofNullable(path.get(n)));
        assertEquals("ruff", found.orElseThrow().name());
        assertTrue(PythonFormatter.find(venv, false, p -> false, PythonFormatterTest::none)
                .isEmpty());
        var windows = PythonFormatter.find(
                Path.of("C:\\p\\.venv"), true, p -> p.toString().endsWith("ruff.exe"), PythonFormatterTest::none);
        assertTrue(windows.orElseThrow().executable().endsWith("ruff.exe"));
    }

    /** stdin in, stdout out, and the file named so the formatter reads the project's pyproject settings. */
    @Test
    void theArgvFormatsStdinAsTheNamedFile() {
        Path file = Path.of("/p/app.py");
        assertEquals(
                List.of("/bin/ruff", "format", "--stdin-filename", file.toString(), "-"),
                new PythonFormatter.Found("ruff", "/bin/ruff", false).argv(file));
        assertEquals(
                List.of("/bin/black", "-q", "--stdin-filename", file.toString(), "-"),
                new PythonFormatter.Found("black", "/bin/black", false).argv(file));
        assertEquals(
                List.of("/bin/black", "-q", "-"), new PythonFormatter.Found("black", "/bin/black", false).argv(null));
    }

    @Test
    void aFailureIsReportedWithTheFormattersOwnWords() {
        var ruff = new PythonFormatter.Found("ruff", "/bin/ruff", false);
        var bad = PythonFormatter.outcome(
                ruff, 2, "", "\nerror: Failed to parse app.py:3:5: Expected an expression\n", "x = (\n", false);
        assertFalse(bad.ok());
        assertEquals("ruff: error: Failed to parse app.py:3:5: Expected an expression", bad.error());
        assertEquals(
                "x = 1\n",
                PythonFormatter.outcome(ruff, 0, "x = 1\n", "", "x=1\n", false).text());
    }

    /**
     * Python review 2, D102: exit 0 is not enough. Black prints nothing for a file its force-exclude skips, and a
     * result past the 10 MB capture limit is cut short; either used to replace the buffer (with nothing, or with
     * its head). An empty file formatted to nothing is still fine.
     */
    @Test
    void anEmptyOrTruncatedResultNeverReplacesTheText() {
        var black = new PythonFormatter.Found("black", "/bin/black", false);
        var skipped = PythonFormatter.outcome(black, 0, "", "", "x = 1\nprint(x)\n", false);
        assertFalse(skipped.ok());
        assertNull(skipped.text());
        assertTrue(skipped.error().startsWith("black printed nothing"), skipped.error());
        var cut = PythonFormatter.outcome(black, 0, "x = 1\n", "", "x=1\n", true);
        assertFalse(cut.ok());
        assertTrue(cut.error().contains("10 MB"), cut.error());
        assertEquals(
                "", PythonFormatter.outcome(black, 0, "", "", "\n\n", false).text());
    }

    /**
     * Python review 2, A103: a Ruff set to {@code line-ending = "cr-lf"} prints CRLF. The buffer holds LF text, so the
     * result is read as LF; diffed as it came, a lone {@code \r} became an extra blank line on every run.
     */
    @Test
    void aCrlfResultIsReadAsLfSoTheSpanStaysTight() {
        var ruff = new PythonFormatter.Found("ruff", "/bin/ruff", false);
        String text = PythonFormatter.outcome(ruff, 0, "import os\r\nx = 1\r\n", "", "import os\nx=1\n", false)
                .text();
        assertEquals("import os\nx = 1\n", text);
        assertArrayEquals(new int[] {11, 12, 14}, PythonFormatter.changedSpan("import os\nx=1\n", text));
        assertNull(
                PythonFormatter.changedSpan(
                        "x = 1\n",
                        PythonFormatter.outcome(ruff, 0, "x = 1\r\n", "", "x = 1\n", false)
                                .text()),
                "already formatted: no edit, so Format Document says so");
        assertEquals("a\nb\nc", FormatterOutput.lf("a\r\nb\rc"));
    }

    @Test
    void onlyTheChangedSpanIsReplaced() {
        assertNull(PythonFormatter.changedSpan("a\n", "a\n"));
        assertArrayEquals(new int[] {1, 2, 4}, PythonFormatter.changedSpan("x=1\n", "x = 1\n"));
        assertArrayEquals(new int[] {0, 0, 1}, PythonFormatter.changedSpan("", "\n"));
    }

    /** A real run: the buffer's text goes in on stdin, the formatter's stdout is the result. */
    @Test
    void aRealRunPipesTheTextThrough() throws Exception {
        assumeTrue(!com.editora.run.PythonInterpreters.isWindows(), "POSIX stand-in formatter");
        Path ruff = dir.resolve(".venv/bin/ruff");
        Files.createDirectories(ruff.getParent());
        // Stands in for `ruff format`: checks its arguments, then spaces the one assignment it is given.
        Files.writeString(ruff, "#!/bin/sh\n[ \"$1\" = format ] && [ \"$4\" = - ] || exit 9\nsed 's/x=1/x = 1/'\n");
        Files.setPosixFilePermissions(ruff, PosixFilePermissions.fromString("rwxr-xr-x"));
        var found = PythonFormatter.findLocal(dir.resolve(".venv")).orElseThrow();
        var outcome = PythonFormatter.run(found, dir.resolve("app.py"), "x=1\nprint(x)\n");
        assertTrue(outcome.ok(), outcome.error());
        assertEquals("x = 1\nprint(x)\n", outcome.text());
    }

    /** A real run of a stand-in Black that skips the file (force-exclude): nothing printed, exit 0, no result. */
    @Test
    void aRealRunThatPrintsNothingIsNotASuccess() throws Exception {
        assumeTrue(!com.editora.run.PythonInterpreters.isWindows(), "POSIX stand-in formatter");
        Path black = dir.resolve(".venv/bin/black");
        Files.createDirectories(black.getParent());
        Files.writeString(black, "#!/bin/sh\ncat >/dev/null\nexit 0\n");
        Files.setPosixFilePermissions(black, PosixFilePermissions.fromString("rwxr-xr-x"));
        var found = PythonFormatter.findLocal(dir.resolve(".venv")).orElseThrow();
        var outcome = PythonFormatter.run(found, dir.resolve("migrations_0001.py"), "x = 1\n");
        assertFalse(outcome.ok());
        assertNull(outcome.text());
    }
}
