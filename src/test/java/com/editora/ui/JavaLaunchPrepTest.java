package com.editora.ui;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.editora.editor.LspDiagnostic;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The decisions made before a Java debug launch (review A2, A16, B3). */
class JavaLaunchPrepTest {

    private static final Path P = Path.of("/w/proj").toAbsolutePath();

    private static LspDiagnostic diag(int line, LspDiagnostic.Severity severity, String message) {
        return new LspDiagnostic(line, 0, line, 1, severity, message, null, "Java", null, false, false);
    }

    @Test
    void aPlainFolderProjectIsTheWindowsWhenTheFileIsInIt() {
        Path file = P.resolve("app/Main.java");
        assertEquals(P.resolve("maven"), JavaLaunchPrep.projectOf(file, P.resolve("maven"), P));
        assertEquals(P, JavaLaunchPrep.projectOf(file, null, P));
        assertNull(JavaLaunchPrep.projectOf(Path.of("/elsewhere/Main.java").toAbsolutePath(), null, P));
        assertNull(JavaLaunchPrep.projectOf(file, null, null));
    }

    @Test
    void theProjectsUnsavedJavaFilesAreSavedAndNothingElse() {
        Path launched = P.resolve("src/demo/Main.java");
        assertTrue(JavaLaunchPrep.saveBeforeLaunch(P.resolve("src/demo/Helper.java"), "java", launched, P));
        assertFalse(JavaLaunchPrep.saveBeforeLaunch(P.resolve("README.md"), "markdown", launched, P));
        assertFalse(JavaLaunchPrep.saveBeforeLaunch(Path.of("/other/X.java").toAbsolutePath(), "java", launched, P));
        assertTrue(
                JavaLaunchPrep.saveBeforeLaunch(P.resolve("src/demo/sub/Y.java"), "java", launched, null),
                "no project: the launched file's folder tree");
        assertFalse(JavaLaunchPrep.saveBeforeLaunch(P.resolve("src/Z.java"), "java", launched, null));
    }

    @Test
    void compileErrorsInTheProjectAreConsoleLinesByFile() {
        Map<Path, List<LspDiagnostic>> errors = Map.of(
                P.resolve("src/B.java"),
                List.of(diag(4, LspDiagnostic.Severity.ERROR, "b"), diag(5, LspDiagnostic.Severity.WARNING, "w")),
                P.resolve("src/A.java"),
                List.of(diag(0, LspDiagnostic.Severity.ERROR, "a1"), diag(9, LspDiagnostic.Severity.ERROR, "a2")),
                Path.of("/other/C.java").toAbsolutePath(),
                List.of(diag(1, LspDiagnostic.Severity.ERROR, "not ours")));
        assertEquals(
                List.of(P.resolve("src/A.java") + ":1: error: a1", P.resolve("src/A.java") + ":10: error: a2"),
                JavaLaunchPrep.errorLines(errors, P, 2));
        assertEquals(3, JavaLaunchPrep.errorCount(errors, P), "warnings and other projects do not count");
    }
}
